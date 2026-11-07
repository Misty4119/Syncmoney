#!/usr/bin/env python3
"""Validate reviewed release pins and bind the built artifacts to a source commit."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GIT_OBJECT_RE = re.compile(r"^[0-9a-f]{40}$")


def expected_artifacts(version: str) -> dict[str, tuple[str, bool]]:
    return {
        "core": (f"build/libs/Syncmoney-{version}.jar", True),
        "papi": (f"syncmoney-papi-expansion/build/libs/SyncmoneyExpansion-{version}.jar", True),
        "acceptance": ("build/acceptance/SyncmoneyAcceptance.jar", False),
        "apiConsumer": ("build/acceptance/SyncmoneyApiConsumer.jar", False),
    }


def _is_safe_relative_path(value: Any) -> bool:
    if not isinstance(value, str) or not value or value.startswith(("/", "\\")) or "\\" in value:
        return False
    path = Path(value)
    return not path.is_absolute() and ".." not in path.parts


def load_pins(path: Path, version: str) -> dict[str, Any]:
    try:
        pins = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read pin document {path}: {exc}") from exc

    if not isinstance(pins, dict) or pins.get("schemaVersion") != 1:
        raise ValueError("pin document must be an object with schemaVersion 1")
    if pins.get("version") != version:
        raise ValueError(f"pin version {pins.get('version')!r} does not match release {version!r}")
    if pins.get("candidateGatePassed") is not True:
        raise ValueError("candidateGatePassed must be true before a draft release can be created")

    expected = expected_artifacts(version)
    artifacts = pins.get("artifacts")
    if not isinstance(artifacts, dict) or set(artifacts) != set(expected):
        raise ValueError(f"artifacts must contain exactly: {', '.join(expected)}")
    for key, (relative_path, public) in expected.items():
        entry = artifacts.get(key)
        if not isinstance(entry, dict):
            raise ValueError(f"artifact {key} must be an object")
        if entry.get("path") != relative_path or entry.get("public") is not public:
            raise ValueError(f"artifact {key} path/public flag must be {relative_path!r}/{public}")
        if type(entry.get("bytes")) is not int or entry["bytes"] <= 0:
            raise ValueError(f"artifact {key} bytes must be a positive integer")
        if not isinstance(entry.get("sha256"), str) or not SHA256_RE.fullmatch(entry["sha256"]):
            raise ValueError(f"artifact {key} sha256 must be 64 lowercase hexadecimal characters")

    candidate = pins.get("candidateEvidence")
    if not isinstance(candidate, dict) or candidate.get("version") != "1.4.0-Beta.3":
        raise ValueError("candidateEvidence must pin the internal 1.4.0-Beta.3 candidate")
    if candidate.get("disposition") != "STATIC_CANDIDATE_NOT_RELEASE_APPROVED":
        raise ValueError("candidateEvidence must remain an internal static candidate, not release-approved")
    for field in ("jarSha256", "sourceManifestSha256"):
        value = candidate.get(field)
        if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
            raise ValueError(f"candidateEvidence.{field} must be a SHA-256 pin")
    if type(candidate.get("bytes")) is not int or candidate["bytes"] <= 0:
        raise ValueError("candidateEvidence.bytes must be a positive integer")
    for field in ("jarPath", "sourceManifestPath"):
        value = candidate.get(field)
        if not _is_safe_relative_path(value):
            raise ValueError(f"candidateEvidence.{field} must be a safe repository-relative path")
    runtime = pins.get("runtimeEvidence")
    if (
        not isinstance(runtime, dict)
        or runtime.get("version") != version
        or runtime.get("status") != "PASS_BOUNDED_SUBCASE"
    ):
        raise ValueError("runtimeEvidence must pass for the exact public release version")
    for field in ("matrixSha256",):
        value = runtime.get(field)
        if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
            raise ValueError(f"runtimeEvidence.{field} must be a SHA-256 pin")
    for field in ("matrixPath",):
        value = runtime.get(field)
        if not _is_safe_relative_path(value):
            raise ValueError(f"runtimeEvidence.{field} must be a safe repository-relative path")
    endpoints = runtime.get("endpoints")
    expected_endpoints = {
        "paper-26.2", "paper-26.3", "folia-26.2", "folia-26.3", "canvas-26.2", "canvas-26.3"
    }
    if not isinstance(endpoints, dict) or set(endpoints) != expected_endpoints:
        raise ValueError("runtimeEvidence must name all six Paper/Folia/Canvas 26.2/26.3 endpoints")
    for name, endpoint in endpoints.items():
        if not isinstance(endpoint, dict) or endpoint.get("status") != "PASS_BOUNDED_SUBCASE":
            raise ValueError(f"runtime endpoint {name} must be PASS_BOUNDED_SUBCASE")
        if type(endpoint.get("build")) not in (str, int) or not str(endpoint["build"]):
            raise ValueError(f"runtime endpoint {name} must pin its server build")
        if not isinstance(endpoint.get("serverSha256"), str) or not SHA256_RE.fullmatch(endpoint["serverSha256"]):
            raise ValueError(f"runtime endpoint {name} must pin the server SHA-256")

    required_waivers = {"T21", "CMI", "PAPI cross-pair"}
    waivers = pins.get("waivers")
    if not isinstance(waivers, dict) or set(waivers) != required_waivers:
        raise ValueError("waivers must explicitly list T21, CMI, and PAPI cross-pair")
    if any(value != "WAIVED / NOT VERIFIED" for value in waivers.values()):
        raise ValueError("accepted runtime exceptions must remain WAIVED / NOT VERIFIED")
    if runtime.get("candidateJarSha256") != artifacts["core"]["sha256"]:
        raise ValueError("runtime evidence must bind to the exact public core artifact hash")

    residuals = pins.get("securityResiduals")
    if not isinstance(residuals, dict):
        raise ValueError("securityResiduals must record accepted release residuals")
    deepmerge = residuals.get("deepmerge")
    if not isinstance(deepmerge, dict) or deepmerge.get("version") != "4.3.1" or deepmerge.get("accepted") is not True:
        raise ValueError("deepmerge 4.3.1 must be recorded as an accepted residual")
    if residuals.get("malwareAlerts") != "UNKNOWN":
        raise ValueError("malwareAlerts must remain UNKNOWN when the feature is unavailable")
    return pins


def verify_files(
    pins: dict[str, Any],
    root: Path,
    *,
    public_only: bool = False,
    evidence_root: Path | None = None,
) -> None:
    for key, entry in pins["artifacts"].items():
        if public_only and entry["public"] is not True:
            continue
        path = root / entry["path"]
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise ValueError(f"artifact {key} is missing or unreadable: {path}") from exc
        digest = hashlib.sha256(data).hexdigest()
        if len(data) != entry["bytes"] or digest != entry["sha256"]:
            raise ValueError(
                f"artifact {key} mismatch: got {len(data)} bytes / {digest}, "
                f"expected {entry['bytes']} bytes / {entry['sha256']}"
            )
    verify_evidence(pins, evidence_root if evidence_root is not None else root)


def _read_pinned_json(path: Path, expected_sha256: str, label: str) -> dict[str, Any]:
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise ValueError(f"{label} is missing or unreadable: {path}") from exc
    actual_sha256 = hashlib.sha256(data).hexdigest()
    if actual_sha256 != expected_sha256:
        raise ValueError(
            f"{label} SHA-256 mismatch: got {actual_sha256}, expected {expected_sha256}"
        )
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is not valid UTF-8 JSON: {path}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def verify_evidence(pins: dict[str, Any], evidence_root: Path) -> None:
    """Verify the committed candidate and runtime evidence referenced by a pin document."""
    candidate_pins = pins["candidateEvidence"]
    candidate_manifest = _read_pinned_json(
        evidence_root / candidate_pins["sourceManifestPath"],
        candidate_pins["sourceManifestSha256"],
        "candidate source manifest",
    )
    candidate = candidate_manifest.get("candidate")
    if (
        candidate_manifest.get("schemaVersion") != 1
        or not isinstance(candidate, dict)
        or candidate.get("version") != candidate_pins["version"]
        or candidate.get("path") != candidate_pins["jarPath"]
        or candidate.get("bytes") != candidate_pins["bytes"]
        or candidate.get("sha256") != candidate_pins["jarSha256"]
        or candidate.get("disposition") != candidate_pins["disposition"]
    ):
        raise ValueError("candidate source manifest does not match the pinned Beta.3 candidate")

    source = candidate_manifest.get("source")
    if (
        not isinstance(source, dict)
        or not isinstance(source.get("commit"), str)
        or not GIT_OBJECT_RE.fullmatch(source["commit"])
        or not isinstance(source.get("tree"), str)
        or not GIT_OBJECT_RE.fullmatch(source["tree"])
        or source.get("clean") is not True
    ):
        raise ValueError("candidate source manifest must identify a clean Git commit and tree")

    runtime_pins = pins["runtimeEvidence"]
    runtime_binding = candidate_manifest.get("runtimeBinding")
    if (
        not isinstance(runtime_binding, dict)
        or runtime_binding.get("version") != runtime_pins["version"]
        or runtime_binding.get("jarSha256") != runtime_pins["candidateJarSha256"]
        or runtime_binding.get("matrixPath") != runtime_pins["matrixPath"]
        or runtime_binding.get("matrixSha256") != runtime_pins["matrixSha256"]
    ):
        raise ValueError("candidate source manifest runtime binding does not match the public pins")

    runtime_matrix = _read_pinned_json(
        evidence_root / runtime_pins["matrixPath"],
        runtime_pins["matrixSha256"],
        "runtime matrix summary",
    )
    public_core = pins["artifacts"]["core"]
    if (
        runtime_matrix.get("candidate") != {
            "path": public_core["path"],
            "sha256": public_core["sha256"],
        }
        or runtime_matrix.get("status") != "PASS_BOUNDED_SUBCASE"
        or type(runtime_matrix.get("platformEndpointCount")) is not int
        or runtime_matrix["platformEndpointCount"] != 6
    ):
        raise ValueError("runtime matrix summary must pass for the exact public core JAR and six endpoints")

    cells = runtime_matrix.get("cells")
    if not isinstance(cells, list) or len(cells) != 6:
        raise ValueError("runtime matrix summary must contain exactly six passing endpoint cells")
    expected_endpoints = runtime_pins["endpoints"]
    observed: set[str] = set()
    for cell in cells:
        if not isinstance(cell, dict) or cell.get("status") != "PASS_BOUNDED_SUBCASE":
            raise ValueError("every runtime matrix cell must be PASS_BOUNDED_SUBCASE")
        software = cell.get("software")
        minecraft_version = cell.get("minecraftVersion")
        endpoint_name = f"{software}-{minecraft_version}"
        endpoint = expected_endpoints.get(endpoint_name)
        if endpoint is None or endpoint_name in observed:
            raise ValueError("runtime matrix cells must uniquely match all six pinned endpoints")
        if (
            str(cell.get("build")) != str(endpoint["build"])
            or cell.get("serverSha256") != endpoint["serverSha256"]
        ):
            raise ValueError(f"runtime matrix cell {endpoint_name} differs from its pinned server endpoint")
        observed.add(endpoint_name)
    if observed != set(expected_endpoints):
        raise ValueError("runtime matrix summary does not cover all six pinned Paper/Folia/Canvas endpoints")


def source_identity(root: Path) -> tuple[str, str]:
    def git(*args: str) -> str:
        result = subprocess.run(
            ["git", *args], cwd=root, check=True, capture_output=True, text=True
        )
        return result.stdout.strip()

    status = git("status", "--porcelain", "--untracked-files=all")
    if status:
        raise ValueError("release checkout is not clean; refusing to bind artifacts to an ambiguous source tree")
    return git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")


def create_release_manifest(pins: dict[str, Any], root: Path, output: Path) -> None:
    commit, tree = source_identity(root)
    manifest = {
        "schemaVersion": 1,
        "version": pins["version"],
        "source": {"commit": commit, "tree": tree},
        "candidateEvidence": pins["candidateEvidence"],
        "runtimeEvidence": pins["runtimeEvidence"],
        "waivers": pins["waivers"],
        "securityResiduals": pins["securityResiduals"],
        "artifacts": pins["artifacts"],
    }
    output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def verify_downloaded_manifest(
    pins: dict[str, Any],
    manifest_path: Path,
    root: Path,
    expected_commit: str,
    *,
    public_only: bool = False,
    evidence_root: Path | None = None,
) -> None:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read generated release manifest {manifest_path}: {exc}") from exc
    if manifest.get("schemaVersion") != 1 or manifest.get("version") != pins["version"]:
        raise ValueError("generated release manifest has the wrong schema or version")
    source = manifest.get("source")
    if (
        not isinstance(source, dict)
        or source.get("commit") != expected_commit
        or not isinstance(source.get("tree"), str)
        or not re.fullmatch(r"[0-9a-f]{40}", source["tree"])
    ):
        raise ValueError("generated release manifest source commit differs from the reviewed tag")
    if manifest.get("artifacts") != pins["artifacts"]:
        raise ValueError("generated release manifest artifact pins differ from the committed pin document")
    for field in ("candidateEvidence", "runtimeEvidence", "waivers", "securityResiduals"):
        if manifest.get(field) != pins.get(field):
            raise ValueError(f"generated release manifest {field} differs from the committed pin document")
    verify_files(pins, root, public_only=public_only, evidence_root=evidence_root)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pins", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--evidence-root",
        type=Path,
        help="root directory for the committed candidate/runtime evidence files (defaults to --root)",
    )
    parser.add_argument("--metadata-only", action="store_true")
    parser.add_argument("--write-release-manifest", type=Path)
    parser.add_argument("--verify-release-manifest", type=Path)
    parser.add_argument("--expected-commit")
    parser.add_argument("--public-only", action="store_true")
    args = parser.parse_args()

    try:
        pins = load_pins(args.pins, args.version)
        if args.verify_release_manifest:
            if not args.expected_commit:
                raise ValueError("--expected-commit is required with --verify-release-manifest")
            verify_downloaded_manifest(
                pins,
                args.verify_release_manifest,
                args.root,
                args.expected_commit,
                public_only=args.public_only,
                evidence_root=args.evidence_root,
            )
        elif args.write_release_manifest:
            verify_files(pins, args.root, evidence_root=args.evidence_root)
            create_release_manifest(pins, args.root.resolve(), args.write_release_manifest)
        elif not args.metadata_only:
            verify_files(
                pins,
                args.root,
                public_only=args.public_only,
                evidence_root=args.evidence_root,
            )
    except (ValueError, subprocess.CalledProcessError) as exc:
        print(f"release pin validation failed: {exc}", file=sys.stderr)
        return 1

    print(f"Release pins validated for {args.version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
