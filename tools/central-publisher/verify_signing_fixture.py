#!/usr/bin/env python3
"""Build and verify a Portal bundle with a temporary, non-production PGP key."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from publish_central import PublisherError, inspect_bundle, stage_bundle, verify_bundle_signatures


FIXTURE_UID = "Syncmoney Signing Fixture <signing-fixture@example.invalid>"


class FixturePortalClient:
    loopback_http_for_test = False

    def __init__(self):
        self.upload_count = 0

    def upload(self, _bundle: Path) -> str:
        self.upload_count += 1
        return "fixture-deployment"

    def status(self, deployment_id: str) -> dict[str, str]:
        return {"deploymentId": deployment_id, "deploymentState": "VALIDATED"}


def run(command: list[str], *, env: dict[str, str] | None = None, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        text=True,
        capture_output=capture,
        env=env,
        timeout=240,
    )


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    gpg = shutil.which("gpg")
    if not gpg:
        print("gpg is required for the disposable signing fixture", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory(prefix="syncmoney-signing-fixture-") as temporary:
        fixture_home = Path(temporary) / "gnupg"
        fixture_home.mkdir()
        gpg_env = os.environ.copy()
        gpg_env["GNUPGHOME"] = str(fixture_home)
        generated = run(
            [
                gpg,
                "--batch",
                "--quiet",
                "--pinentry-mode",
                "loopback",
                "--passphrase",
                "",
                "--quick-generate-key",
                FIXTURE_UID,
                "rsa2048",
                "sign",
                "0",
            ],
            env=gpg_env,
            capture=True,
        )
        if generated.returncode != 0:
            print(generated.stderr[-4000:], file=sys.stderr)
            return generated.returncode

        exported = run(
            [gpg, "--batch", "--armor", "--export-secret-keys", FIXTURE_UID],
            env=gpg_env,
            capture=True,
        )
        if exported.returncode != 0 or not exported.stdout:
            print("Could not export the temporary signing fixture", file=sys.stderr)
            return 1

        listed = run(
            [gpg, "--batch", "--with-colons", "--list-keys", FIXTURE_UID],
            env=gpg_env,
            capture=True,
        )
        fixture_fingerprint = next(
            (fields[9] for line in listed.stdout.splitlines() if (fields := line.split(":"))[0] == "fpr" and len(fields) > 9),
            None,
        )
        public_key_export = run(
            [gpg, "--batch", "--armor", "--export", FIXTURE_UID],
            env=gpg_env,
            capture=True,
        )
        if listed.returncode != 0 or not fixture_fingerprint or public_key_export.returncode != 0 or not public_key_export.stdout:
            print("Could not export the temporary signing fixture fingerprint/public key", file=sys.stderr)
            return 1
        trusted_public_key = Path(temporary) / "fixture-public-key.asc"
        trusted_public_key.write_text(public_key_export.stdout, encoding="utf-8")
        wrong_fingerprint = "0" * len(fixture_fingerprint)
        if wrong_fingerprint == fixture_fingerprint:
            wrong_fingerprint = "1" * len(fixture_fingerprint)

        gradle_env = os.environ.copy()
        gradle_env["JAVA_HOME"] = os.environ.get("JAVA_HOME", "")
        gradle_bin = str(Path(gradle_env["JAVA_HOME"]) / "bin") if gradle_env["JAVA_HOME"] else ""
        if gradle_bin:
            gradle_env["PATH"] = gradle_bin + os.pathsep + gradle_env.get("PATH", "")
        gradle_env["ORG_GRADLE_PROJECT_signingInMemoryKey"] = exported.stdout
        gradle_env["ORG_GRADLE_PROJECT_signingInMemoryKeyPassword"] = ""

        wrapper = repo_root / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [
            str(wrapper),
            "--no-daemon",
            "--rerun-tasks",
            "-PreleaseVersion=1.4.0-signing-fixture",
            ":syncmoney-api:check",
            "nmcpZipAggregation",
        ]
        if os.name == "nt":
            command = ["cmd.exe", "/d", "/c", *command]
        built = run(command, env=gradle_env, capture=True)
        if built.returncode != 0:
            print(built.stdout[-6000:], file=sys.stderr)
            print(built.stderr[-6000:], file=sys.stderr)
            return built.returncode

        bundle = repo_root / "build" / "nmcp" / "zip" / "aggregation.zip"
        try:
            inspect_bundle(bundle, require_signatures=True)
            verify_bundle_signatures(bundle, gpg, trusted_public_key, fixture_fingerprint)
            staging_client = FixturePortalClient()
            staged = stage_bundle(
                bundle,
                Path(temporary) / "fixture-state.json",
                staging_client,
                1,
                0.1,
                trusted_public_key,
                fixture_fingerprint,
            )
            if staged != "VALIDATED" or staging_client.upload_count != 1:
                print("The staging path did not verify and accept the exact signed fixture", file=sys.stderr)
                return 1
            try:
                verify_bundle_signatures(bundle, gpg, trusted_public_key, wrong_fingerprint)
            except PublisherError:
                pass
            else:
                print("The staging verifier accepted an untrusted signing fingerprint", file=sys.stderr)
                return 1

            invalid_bundle = Path(temporary) / "invalid-signature.zip"
            with zipfile.ZipFile(bundle, "r") as source, zipfile.ZipFile(invalid_bundle, "w") as target:
                invalidated = False
                for entry in source.infolist():
                    content = source.read(entry)
                    if not invalidated and entry.filename.endswith(".jar.asc"):
                        content = b"invalid-fixture-signature"
                        invalidated = True
                    target.writestr(entry, content)
            if not invalidated:
                print("The signed bundle did not contain a JAR signature", file=sys.stderr)
                return 1
            try:
                verify_bundle_signatures(invalid_bundle, gpg, trusted_public_key, fixture_fingerprint)
            except PublisherError:
                pass
            else:
                print("The staging verifier accepted a tampered PGP signature", file=sys.stderr)
                return 1
            invalid_staging_client = FixturePortalClient()
            try:
                stage_bundle(
                    invalid_bundle,
                    Path(temporary) / "invalid-fixture-state.json",
                    invalid_staging_client,
                    1,
                    0.1,
                    trusted_public_key,
                    fixture_fingerprint,
                )
            except PublisherError:
                pass
            else:
                print("The staging path accepted a bundle with a tampered signature", file=sys.stderr)
                return 1
            if invalid_staging_client.upload_count != 0:
                print("A tampered bundle reached the Portal upload client", file=sys.stderr)
                return 1
        except (OSError, ValueError, zipfile.BadZipFile, PublisherError, subprocess.SubprocessError) as exc:
            print(f"Signed bundle validation failed ({type(exc).__name__})", file=sys.stderr)
            return 1

        print("Disposable PGP fixture verified the exact staging path and rejected a tampered signature before upload")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
