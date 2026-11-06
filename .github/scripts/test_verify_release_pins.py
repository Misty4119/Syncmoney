import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("verify_release_pins.py")
SPEC = importlib.util.spec_from_file_location("verify_release_pins", SCRIPT)
verify_release_pins = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(verify_release_pins)


VERSION = "1.4.0-Beta.1"
ENDPOINT_BUILDS = {
    "paper-26.2": 135,
    "folia-26.2": 7,
    "canvas-26.2": 994,
    "paper-26.3": 244,
    "folia-26.3": 6,
    "canvas-26.3": 1010,
}


def sample_pins():
    artifacts = {}
    for key, (path, public) in verify_release_pins.expected_artifacts(VERSION).items():
        artifacts[key] = {"path": path, "public": public, "bytes": 1, "sha256": "a" * 64}
    return {
        "schemaVersion": 1,
        "version": VERSION,
        "candidateGatePassed": True,
        "artifacts": artifacts,
        "candidateEvidence": {
            "version": "1.4.0-Beta.3",
            "disposition": "STATIC_CANDIDATE_NOT_RELEASE_APPROVED",
            "jarPath": "build/libs/Syncmoney-1.4.0-Beta.3.jar",
            "bytes": 2,
            "jarSha256": "b" * 64,
            "sourceManifestPath": "docs/release/beta3-candidate.json",
            "sourceManifestSha256": "c" * 64,
        },
        "runtimeEvidence": {
            "version": VERSION,
            "status": "PASS_BOUNDED_SUBCASE",
            "candidateJarSha256": "a" * 64,
            "matrixPath": "docs/release/beta1-runtime.json",
            "matrixSha256": "e" * 64,
            "endpoints": {
                endpoint: {
                    "status": "PASS_BOUNDED_SUBCASE",
                    "build": build,
                    "serverSha256": "d" * 64,
                }
                for endpoint, build in ENDPOINT_BUILDS.items()
            },
        },
        "waivers": {
            "T21": "WAIVED / NOT VERIFIED",
            "CMI": "WAIVED / NOT VERIFIED",
            "PAPI cross-pair": "WAIVED / NOT VERIFIED",
        },
        "securityResiduals": {
            "deepmerge": {"version": "4.3.1", "accepted": True},
            "malwareAlerts": "UNKNOWN",
        },
    }


def write_evidence(root: Path, pins: dict, *, mutate_matrix=None):
    endpoints = pins["runtimeEvidence"]["endpoints"]
    cells = []
    for name, endpoint in endpoints.items():
        software, minecraft_version = name.rsplit("-", 1)
        cells.append(
            {
                "software": software,
                "minecraftVersion": minecraft_version,
                "build": endpoint["build"],
                "serverSha256": endpoint["serverSha256"],
                "status": "PASS_BOUNDED_SUBCASE",
            }
        )
    matrix = {
        "candidate": {
            "path": pins["artifacts"]["core"]["path"],
            "sha256": pins["artifacts"]["core"]["sha256"],
        },
        "status": "PASS_BOUNDED_SUBCASE",
        "platformEndpointCount": 6,
        "cells": cells,
    }
    if mutate_matrix is not None:
        mutate_matrix(matrix)
    matrix_bytes = (json.dumps(matrix, indent=2, sort_keys=True) + "\n").encode("utf-8")
    matrix_path = root / pins["runtimeEvidence"]["matrixPath"]
    matrix_path.parent.mkdir(parents=True, exist_ok=True)
    matrix_path.write_bytes(matrix_bytes)
    pins["runtimeEvidence"]["matrixSha256"] = hashlib.sha256(matrix_bytes).hexdigest()

    candidate_manifest = {
        "schemaVersion": 1,
        "candidate": {
            "version": pins["candidateEvidence"]["version"],
            "path": pins["candidateEvidence"]["jarPath"],
            "bytes": pins["candidateEvidence"]["bytes"],
            "sha256": pins["candidateEvidence"]["jarSha256"],
            "disposition": pins["candidateEvidence"]["disposition"],
        },
        "source": {"commit": "f" * 40, "tree": "a" * 40, "clean": True},
        "runtimeBinding": {
            "version": pins["runtimeEvidence"]["version"],
            "jarSha256": pins["runtimeEvidence"]["candidateJarSha256"],
            "matrixPath": pins["runtimeEvidence"]["matrixPath"],
            "matrixSha256": pins["runtimeEvidence"]["matrixSha256"],
        },
    }
    manifest_bytes = (json.dumps(candidate_manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
    manifest_path = root / pins["candidateEvidence"]["sourceManifestPath"]
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_bytes(manifest_bytes)
    pins["candidateEvidence"]["sourceManifestSha256"] = hashlib.sha256(manifest_bytes).hexdigest()
    return matrix, candidate_manifest


class ReleasePinsTests(unittest.TestCase):
    def test_valid_pin_document_passes(self):
        pins = sample_pins()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(pins), encoding="utf-8")
            self.assertEqual(verify_release_pins.load_pins(path, VERSION), pins)

    def test_version_mismatch_fails_closed(self):
        pins = sample_pins()
        pins["version"] = "1.4.0-Beta.3"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(pins), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "does not match"):
                verify_release_pins.load_pins(path, VERSION)

    def test_candidate_gate_must_be_passed(self):
        pins = sample_pins()
        pins["candidateGatePassed"] = False
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(pins), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "candidateGatePassed"):
                verify_release_pins.load_pins(path, VERSION)

    def test_all_six_endpoint_cells_are_required(self):
        pins = sample_pins()
        del pins["runtimeEvidence"]["endpoints"]["canvas-26.3"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(pins), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "all six"):
                verify_release_pins.load_pins(path, VERSION)

    def test_runtime_evidence_must_match_public_core_artifact_hash(self):
        pins = sample_pins()
        pins["runtimeEvidence"]["candidateJarSha256"] = "e" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(pins), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "exact public core artifact"):
                verify_release_pins.load_pins(path, VERSION)

    def test_internal_beta3_candidate_is_distinct_from_public_runtime_jar(self):
        pins = sample_pins()
        self.assertNotEqual(
            pins["candidateEvidence"]["jarSha256"],
            pins["runtimeEvidence"]["candidateJarSha256"],
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(pins), encoding="utf-8")
            self.assertEqual(verify_release_pins.load_pins(path, VERSION), pins)

    def test_runtime_evidence_version_must_match_public_release(self):
        pins = sample_pins()
        pins["runtimeEvidence"]["version"] = "1.4.0-Beta.3"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(pins), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "exact public release version"):
                verify_release_pins.load_pins(path, VERSION)

    def test_security_unknown_and_waivers_are_preserved(self):
        pins = sample_pins()
        pins["securityResiduals"]["malwareAlerts"] = "0"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(pins), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "UNKNOWN"):
                verify_release_pins.load_pins(path, VERSION)

    def test_artifact_hash_and_size_are_checked(self):
        pins = sample_pins()
        for entry in pins["artifacts"].values():
            entry["sha256"] = hashlib.sha256(b"x").hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for entry in pins["artifacts"].values():
                path = root / entry["path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"x")
            write_evidence(root, pins)
            verify_release_pins.verify_files(pins, root)
            (root / pins["artifacts"]["core"]["path"]).write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "mismatch"):
                verify_release_pins.verify_files(pins, root)

    def test_evidence_sha256_is_checked(self):
        pins = sample_pins()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_evidence(root, pins)
            matrix_path = root / pins["runtimeEvidence"]["matrixPath"]
            matrix_path.write_text(matrix_path.read_text(encoding="utf-8") + " ", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "runtime matrix summary SHA-256 mismatch"):
                verify_release_pins.verify_evidence(pins, root)

    def test_candidate_source_manifest_schema_is_checked(self):
        pins = sample_pins()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_evidence(root, pins)
            source_path = root / pins["candidateEvidence"]["sourceManifestPath"]
            candidate_manifest = json.loads(source_path.read_text(encoding="utf-8"))
            candidate_manifest["source"]["clean"] = False
            content = (json.dumps(candidate_manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
            source_path.write_bytes(content)
            pins["candidateEvidence"]["sourceManifestSha256"] = hashlib.sha256(content).hexdigest()
            with self.assertRaisesRegex(ValueError, "clean Git commit and tree"):
                verify_release_pins.verify_evidence(pins, root)

    def test_runtime_matrix_cell_metadata_is_checked(self):
        pins = sample_pins()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def corrupt(matrix):
                matrix["cells"][0]["status"] = "FAIL"

            write_evidence(root, pins, mutate_matrix=corrupt)
            with self.assertRaisesRegex(ValueError, "every runtime matrix cell"):
                verify_release_pins.verify_evidence(pins, root)

    def test_downloaded_manifest_checks_non_public_artifacts_by_default(self):
        pins = sample_pins()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_root = root / "artifacts"
            for entry in pins["artifacts"].values():
                path = artifact_root / entry["path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"x")
                entry["sha256"] = hashlib.sha256(b"x").hexdigest()
            write_evidence(root, pins)
            manifest = {
                "schemaVersion": 1,
                "version": VERSION,
                "source": {"commit": "f" * 40, "tree": "a" * 40},
                "candidateEvidence": pins["candidateEvidence"],
                "runtimeEvidence": pins["runtimeEvidence"],
                "waivers": pins["waivers"],
                "securityResiduals": pins["securityResiduals"],
                "artifacts": pins["artifacts"],
            }
            manifest_path = root / "release-manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            verify_release_pins.verify_downloaded_manifest(
                pins, manifest_path, artifact_root, "f" * 40, evidence_root=root
            )

            internal = artifact_root / pins["artifacts"]["acceptance"]["path"]
            internal.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "acceptance mismatch"):
                verify_release_pins.verify_downloaded_manifest(
                    pins, manifest_path, artifact_root, "f" * 40, evidence_root=root
                )

    def test_public_only_downloaded_manifest_skips_internal_artifacts(self):
        pins = sample_pins()
        for key, entry in pins["artifacts"].items():
            if entry["public"]:
                entry["sha256"] = hashlib.sha256(b"x").hexdigest()
        pins["runtimeEvidence"]["candidateJarSha256"] = pins["artifacts"]["core"]["sha256"]

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_root = root / "artifacts"
            for entry in pins["artifacts"].values():
                if not entry["public"]:
                    continue
                path = artifact_root / entry["path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"x")
            write_evidence(root, pins)

            manifest = {
                "schemaVersion": 1,
                "version": VERSION,
                "source": {"commit": "f" * 40, "tree": "a" * 40},
                "candidateEvidence": pins["candidateEvidence"],
                "runtimeEvidence": pins["runtimeEvidence"],
                "waivers": pins["waivers"],
                "securityResiduals": pins["securityResiduals"],
                "artifacts": pins["artifacts"],
            }
            manifest_path = root / "release-manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            pins_path = root / "pins.json"
            pins_path.write_text(json.dumps(pins), encoding="utf-8")

            with patch.object(
                verify_release_pins.sys,
                "argv",
                [
                    "verify_release_pins.py",
                    "--pins",
                    str(pins_path),
                    "--version",
                    VERSION,
                    "--root",
                    str(artifact_root),
                    "--evidence-root",
                    str(root),
                    "--verify-release-manifest",
                    str(manifest_path),
                    "--expected-commit",
                    "f" * 40,
                    "--public-only",
                ],
            ):
                self.assertEqual(verify_release_pins.main(), 0)

            with self.assertRaisesRegex(ValueError, "acceptance is missing"):
                verify_release_pins.verify_downloaded_manifest(
                    pins, manifest_path, artifact_root, "f" * 40, evidence_root=root
                )


if __name__ == "__main__":
    unittest.main()
