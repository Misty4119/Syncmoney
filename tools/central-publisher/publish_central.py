#!/usr/bin/env python3
"""Validate, consume, and safely stage the Syncmoney API Central bundle.

The upload command deliberately stops at a USER_MANAGED Portal deployment. It
never calls the endpoint that publishes a deployment to Maven Central.
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath, PureWindowsPath


EXPECTED_GROUP_ID = "io.github.misty4119"
EXPECTED_ARTIFACT_ID = "syncmoney-api"
STATE_SCHEMA_VERSION = 1
MAX_BUNDLE_BYTES = 100_000_000
MAX_EXPANDED_BYTES = 200_000_000
CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
TRANSITIONAL_STATES = {"PENDING", "VALIDATING", "PUBLISHING"}
SUCCESS_STATES = {"VALIDATED", "PUBLISHED"}
KNOWN_STATES = TRANSITIONAL_STATES | SUCCESS_STATES | {"FAILED"}


class PublisherError(Exception):
    """A safe-to-display publisher failure without credential material."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _direct_text(element: ET.Element | None, name: str) -> str:
    if element is None:
        return ""
    for child in element:
        if child.tag.rsplit("}", 1)[-1] == name:
            return (child.text or "").strip()
    return ""


def _child(element: ET.Element | None, name: str) -> ET.Element | None:
    if element is None:
        return None
    for child in element:
        if child.tag.rsplit("}", 1)[-1] == name:
            return child
    return None


def _pom_coordinates(content: bytes, archive_path: PurePosixPath) -> tuple[str, str, str]:
    try:
        root = ET.fromstring(content)
    except ET.ParseError as exc:
        raise PublisherError(f"Invalid POM XML at {archive_path.as_posix()}") from exc

    parent = _child(root, "parent")
    group_id = _direct_text(root, "groupId") or _direct_text(parent, "groupId")
    artifact_id = _direct_text(root, "artifactId")
    version = _direct_text(root, "version") or _direct_text(parent, "version")
    if not group_id or not artifact_id or not version:
        raise PublisherError(f"POM is missing groupId, artifactId, or version: {archive_path.as_posix()}")

    expected_suffix = PurePosixPath(*group_id.split("."), artifact_id, version)
    if archive_path.parent.parts[-len(expected_suffix.parts):] != expected_suffix.parts:
        raise PublisherError(f"POM path does not match its GAV coordinates: {archive_path.as_posix()}")
    if archive_path.name != f"{artifact_id}-{version}.pom":
        raise PublisherError(f"POM filename does not match its GAV coordinates: {archive_path.as_posix()}")

    return group_id, artifact_id, version


def _validate_pom_metadata(content: bytes, path: str) -> None:
    root = ET.fromstring(content)
    required = {
        "name": _direct_text(root, "name"),
        "description": _direct_text(root, "description"),
        "url": _direct_text(root, "url"),
    }
    missing = [name for name, value in required.items() if not value]

    licenses = _child(root, "licenses")
    license_entry = _child(licenses, "license")
    if not _direct_text(license_entry, "name") or not _direct_text(license_entry, "url"):
        missing.append("licenses/license/name+url")

    developers = _child(root, "developers")
    developer = _child(developers, "developer")
    if not _direct_text(developer, "id") or not _direct_text(developer, "name"):
        missing.append("developers/developer/id+name")

    scm = _child(root, "scm")
    if not (_direct_text(scm, "url") or _direct_text(scm, "connection")):
        missing.append("scm/url or scm/connection")

    if missing:
        raise PublisherError(f"POM is missing required Central metadata ({', '.join(missing)}): {path}")


def inspect_bundle(bundle: Path, *, require_signatures: bool) -> dict[str, object]:
    if not bundle.is_file():
        raise PublisherError(f"Bundle does not exist: {bundle}")
    if bundle.stat().st_size > MAX_BUNDLE_BYTES:
        raise PublisherError("Central deployment bundle exceeds the local 100 MB safety limit")

    try:
        archive = zipfile.ZipFile(bundle, "r")
    except (OSError, zipfile.BadZipFile) as exc:
        raise PublisherError("Bundle is not a readable ZIP archive") from exc

    with archive:
        file_entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if not file_entries:
            raise PublisherError("Bundle is empty")
        if sum(entry.file_size for entry in file_entries) > MAX_EXPANDED_BYTES:
            raise PublisherError("Bundle expands beyond the local safety limit")

        names: set[str] = set()
        contents: dict[str, bytes] = {}
        for entry in file_entries:
            name = entry.filename
            pure = PurePosixPath(name)
            windows = PureWindowsPath(name)
            if (
                "\\" in name
                or pure.is_absolute()
                or windows.drive
                or any(":" in part for part in pure.parts)
                or any(part in ("", ".", "..") for part in pure.parts)
            ):
                raise PublisherError(f"Unsafe ZIP entry path: {name}")
            if name in names:
                raise PublisherError(f"Duplicate ZIP entry: {name}")
            names.add(name)
            contents[name] = archive.read(entry)

    pom_names = [name for name in contents if name.endswith(".pom")]
    if len(pom_names) != 1:
        raise PublisherError(f"Expected exactly one Maven POM for the approved API, found {len(pom_names)}")

    pom_name = pom_names[0]
    pom_path = PurePosixPath(pom_name)
    group_id, artifact_id, version = _pom_coordinates(contents[pom_name], pom_path)
    if group_id != EXPECTED_GROUP_ID or artifact_id != EXPECTED_ARTIFACT_ID:
        raise PublisherError(
            f"Bundle coordinates are not the approved SDK: {group_id}:{artifact_id}:{version}"
        )
    if version.upper().endswith("-SNAPSHOT"):
        raise PublisherError("Maven Central does not accept SNAPSHOT versions")
    _validate_pom_metadata(contents[pom_name], pom_name)

    coordinate_dir = pom_path.parent
    coordinate_files: dict[str, bytes] = {}
    for name, content in contents.items():
        path = PurePosixPath(name)
        if path.parent == coordinate_dir:
            coordinate_files[path.name] = content
        elif path.is_relative_to(coordinate_dir):
            raise PublisherError(f"Unexpected nested file in Maven coordinate directory: {name}")
        else:
            # A single-API Portal bundle has no unrelated files or second publication.
            raise PublisherError(f"Unexpected file outside the approved API publication: {name}")

    prefix = f"{artifact_id}-{version}"
    required_files = {
        f"{prefix}.jar",
        f"{prefix}-sources.jar",
        f"{prefix}-javadoc.jar",
        f"{prefix}.pom",
    }
    missing = sorted(required_files - coordinate_files.keys())
    if missing:
        raise PublisherError(f"Bundle is missing required API files: {', '.join(missing)}")

    base_files = [
        name
        for name in coordinate_files
        if not name.endswith(CHECKSUM_SUFFIXES) and not name.endswith(".asc")
    ]
    for name in base_files:
        content = coordinate_files[name]
        expected = {
            f"{name}.md5": hashlib.md5(content, usedforsecurity=False).hexdigest(),
            f"{name}.sha1": hashlib.sha1(content, usedforsecurity=False).hexdigest(),
        }
        for checksum_name, checksum_value in expected.items():
            actual = coordinate_files.get(checksum_name, b"").decode("ascii", errors="ignore").split()
            if not actual or actual[0].lower() != checksum_value:
                raise PublisherError(f"Missing or invalid required checksum: {checksum_name}")
        if require_signatures and f"{name}.asc" not in coordinate_files:
            raise PublisherError(f"Missing PGP signature: {name}.asc")

    coordinate_hash = _coordinate_content_hash(coordinate_files)

    return {
        "gav": f"{group_id}:{artifact_id}:{version}",
        "groupId": group_id,
        "artifactId": artifact_id,
        "version": version,
        "coordinateHash": coordinate_hash,
        "bundleSha256": sha256_file(bundle),
        "signed": all(f"{name}.asc" in coordinate_files for name in base_files),
        "sizeBytes": bundle.stat().st_size,
        "pomPath": pom_name,
        "coordinateDirectory": coordinate_dir.as_posix(),
        "entries": sorted(contents),
    }


def _coordinate_content_hash(coordinate_files: dict[str, bytes]) -> str:
    """Hash a canonical length-prefixed encoding of a Maven coordinate's files."""
    digest = hashlib.sha256()
    for name in sorted(coordinate_files):
        encoded_name = name.encode("utf-8")
        content = coordinate_files[name]
        digest.update(len(encoded_name).to_bytes(8, "big"))
        digest.update(encoded_name)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def verify_bundle_signatures(
    bundle: Path,
    gpg_executable: str,
    trusted_public_key: Path,
    expected_fingerprint: str,
) -> None:
    normalized_fingerprint = re.sub(r"\s+", "", expected_fingerprint).upper()
    if not re.fullmatch(r"(?:[0-9A-F]{40}|[0-9A-F]{64})", normalized_fingerprint):
        raise PublisherError("Expected signing fingerprint must be a 40- or 64-character hexadecimal value")
    if not trusted_public_key.is_file():
        raise PublisherError("Trusted signing public key file does not exist")
    executable = shutil.which(gpg_executable) or (gpg_executable if Path(gpg_executable).is_file() else None)
    if not executable:
        raise PublisherError("GnuPG executable was not found")

    info = inspect_bundle(bundle, require_signatures=True)
    with tempfile.TemporaryDirectory(prefix="syncmoney-signature-check-") as temporary:
        root = Path(temporary)
        home = root / "gnupg"
        home.mkdir()
        imported = subprocess.run(
            [executable, "--no-options", "--homedir", str(home), "--batch", "--import", str(trusted_public_key)],
            text=True,
            capture_output=True,
            timeout=60,
        )
        if imported.returncode != 0:
            raise PublisherError("Could not import the trusted signing public key")

        coordinate_directory = PurePosixPath(str(info["coordinateDirectory"]))
        with zipfile.ZipFile(bundle, "r") as archive:
            coordinate_files = {
                PurePosixPath(entry.filename).name: archive.read(entry)
                for entry in archive.infolist()
                if not entry.is_dir() and PurePosixPath(entry.filename).parent == coordinate_directory
            }

        artifact_names = [
            name
            for name in coordinate_files
            if not name.endswith(CHECKSUM_SUFFIXES) and not name.endswith(".asc")
        ]
        for name in artifact_names:
            artifact = root / name
            signature = root / f"{name}.asc"
            artifact.write_bytes(coordinate_files[name])
            signature.write_bytes(coordinate_files[f"{name}.asc"])
            verified = subprocess.run(
                [
                    executable,
                    "--no-options",
                    "--homedir",
                    str(home),
                    "--batch",
                    "--status-fd=1",
                    "--verify",
                    str(signature),
                    str(artifact),
                ],
                text=True,
                capture_output=True,
                timeout=60,
            )
            valid_signatures = [
                line.split()
                for line in verified.stdout.splitlines()
                if line.startswith("[GNUPG:] VALIDSIG ")
            ]
            if verified.returncode != 0 or not valid_signatures:
                raise PublisherError(f"Invalid PGP signature for publication file: {name}")
            fingerprints = {fields[2].upper() for fields in valid_signatures if len(fields) > 2}
            primary_fingerprints = {fields[-1].upper() for fields in valid_signatures if len(fields) > 2}
            if normalized_fingerprint not in fingerprints | primary_fingerprints:
                raise PublisherError(f"PGP signature does not match the trusted fingerprint: {name}")


@contextlib.contextmanager
def _state_file_lock(state_file: Path):
    lock_path = state_file.parent.resolve() / (state_file.name + ".lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as exc:
        raise PublisherError(
            "Another Portal operation owns the state lock; verify no process is active before reconciling a stale lock"
        ) from exc
    try:
        os.write(descriptor, f"pid={os.getpid()}\n".encode("ascii"))
        os.fsync(descriptor)
        yield
    finally:
        os.close(descriptor)
        try:
            lock_path.unlink()
        except OSError:
            pass


def _utc_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _write_state(path: Path, state: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise PublisherError("Refusing to write through a symlink state path")
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, prefix=path.name + ".", suffix=".tmp", delete=False
        ) as temporary:
            temporary_path = Path(temporary.name)
            json.dump(state, temporary, sort_keys=True, indent=2)
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())
        try:
            os.chmod(temporary_path, 0o600)
        except OSError:
            pass
        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            try:
                temporary_path.unlink()
            except OSError:
                pass


def _read_state(path: Path) -> dict[str, object]:
    if path.is_symlink():
        raise PublisherError("Refusing to read a symlink state path")
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PublisherError("Publisher state file is missing or invalid") from exc
    if not isinstance(state, dict) or state.get("schemaVersion") != STATE_SCHEMA_VERSION:
        raise PublisherError("Publisher state file has an unsupported schema")
    return state


class PortalClient:
    def __init__(
        self,
        base_url: str,
        username: str,
        password: str,
        timeout_seconds: float,
        allow_loopback_http_for_test: bool = False,
    ):
        parsed = urllib.parse.urlparse(base_url)
        try:
            port = parsed.port
        except ValueError as exc:
            raise PublisherError("Portal API base URL contains an invalid port") from exc
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise PublisherError("Portal API base URL must not contain credentials, query, or fragment")
        if parsed.hostname == "central.sonatype.com":
            if parsed.scheme != "https" or port not in (None, 443) or parsed.path.rstrip("/") != "/api/v1/publisher":
                raise PublisherError("Production Portal URL must use the documented HTTPS publisher API path")
        elif (
            not allow_loopback_http_for_test
            or parsed.scheme != "http"
            or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.path.rstrip("/") != "/api/v1/publisher"
        ):
            raise PublisherError(
                "Production Portal API must use https://central.sonatype.com/api/v1/publisher; "
                "only explicit loopback HTTP is allowed for tests"
            )
        if not parsed.netloc:
            raise PublisherError("Portal API base URL must be absolute")
        if not username or not password:
            raise PublisherError("Central Portal token username and password are required for upload")
        self.base_url = base_url.rstrip("/")
        self.loopback_http_for_test = (
            allow_loopback_http_for_test
            and parsed.scheme == "http"
            and parsed.hostname in {"127.0.0.1", "localhost", "::1"}
        )
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self.authorization = f"Bearer {token}"
        self.timeout_seconds = timeout_seconds
        self.opener = urllib.request.build_opener(_NoRedirectHandler())

    def _request(self, request: urllib.request.Request) -> bytes:
        request.add_header("Authorization", self.authorization)
        try:
            with self.opener.open(request, timeout=self.timeout_seconds) as response:
                return response.read()
        except urllib.error.HTTPError as exc:
            raise PublisherError(f"Central Portal returned HTTP {exc.code}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise PublisherError(f"Central Portal request failed ({type(exc).__name__})") from exc

    def upload(self, bundle: Path) -> str:
        boundary = "syncmoney-" + os.urandom(18).hex()
        filename = bundle.name.replace('"', "")
        payload = bundle.read_bytes()
        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="bundle"; filename="{filename}"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode("utf-8") + payload + f"\r\n--{boundary}--\r\n".encode("ascii")
        request = urllib.request.Request(
            self.base_url + "/upload?publishingType=USER_MANAGED",
            data=body,
            method="POST",
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        )
        response = self._request(request).decode("utf-8", errors="replace").strip()
        try:
            parsed = json.loads(response)
            if isinstance(parsed, dict):
                response = str(parsed.get("deploymentId", "")).strip()
        except json.JSONDecodeError:
            pass
        if not response or "\n" in response or "\r" in response:
            raise PublisherError("Central Portal upload returned no valid deployment ID")
        return response

    def status(self, deployment_id: str) -> dict[str, object]:
        query = urllib.parse.urlencode({"id": deployment_id})
        request = urllib.request.Request(self.base_url + "/status?" + query, data=b"", method="POST")
        try:
            payload = self._request(request)
            result = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise PublisherError("Central Portal returned an invalid deployment status response") from exc
        if not isinstance(result, dict) or result.get("deploymentId") != deployment_id:
            raise PublisherError("Central Portal status response did not match the requested deployment ID")
        state = result.get("deploymentState")
        if not isinstance(state, str) or state not in KNOWN_STATES:
            raise PublisherError("Central Portal returned an unknown deployment state")
        return result


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _state_for_bundle(info: dict[str, object]) -> dict[str, object]:
    return {
        "schemaVersion": STATE_SCHEMA_VERSION,
        "gav": info["gav"],
        "coordinateHash": info["coordinateHash"],
        "bundleSha256": info["bundleSha256"],
        "deploymentId": None,
        "deploymentState": "UPLOAD_STARTED",
        "updatedAt": _utc_now(),
        "lastError": None,
    }


def _match_existing_state(state: dict[str, object], info: dict[str, object]) -> None:
    if state.get("gav") != info["gav"]:
        raise PublisherError("State file belongs to another GAV; use a separate state file for each release")
    if state.get("coordinateHash") != info["coordinateHash"]:
        raise PublisherError("GAV content hash changed; upload is stopped")


def _save_portal_status(state_file: Path, local_state: dict[str, object], portal_result: dict[str, object]) -> str:
    deployment_state = str(portal_result["deploymentState"])
    local_state["deploymentState"] = deployment_state
    local_state["updatedAt"] = _utc_now()
    local_state["lastError"] = "Portal validation failed" if deployment_state == "FAILED" else None
    _write_state(state_file, local_state)
    return deployment_state


def _wait_for_validation(
    client: PortalClient,
    state_file: Path,
    local_state: dict[str, object],
    timeout_seconds: float,
    poll_interval_seconds: float,
) -> str:
    deployment_id = str(local_state["deploymentId"])
    deadline = time.monotonic() + timeout_seconds
    while True:
        try:
            portal_result = client.status(deployment_id)
        except PublisherError as exc:
            local_state["deploymentState"] = "STATUS_UNKNOWN"
            local_state["lastError"] = type(exc).__name__
            local_state["updatedAt"] = _utc_now()
            _write_state(state_file, local_state)
            raise
        state = _save_portal_status(state_file, local_state, portal_result)
        if state == "FAILED":
            raise PublisherError("Central Portal deployment FAILED; inspect the Portal and do not reupload")
        if state in SUCCESS_STATES:
            return state
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise PublisherError(
                f"Central Portal validation timed out in state {state}; keep the state file and rerun to check status"
            )
        time.sleep(min(poll_interval_seconds, remaining))


def stage_bundle(
    bundle: Path,
    state_file: Path,
    client: PortalClient,
    validation_timeout_seconds: float,
    poll_interval_seconds: float,
    trusted_public_key: Path | None = None,
    expected_fingerprint: str | None = None,
    allow_unverified_signatures_for_test: bool = False,
) -> str:
    with _state_file_lock(state_file):
        with tempfile.TemporaryDirectory(prefix="syncmoney-central-bundle-") as temporary:
            snapshot = Path(temporary) / "verified-bundle.zip"
            _copy_bundle_snapshot(bundle, snapshot)
            return _stage_bundle_locked(
                snapshot,
                state_file,
                client,
                validation_timeout_seconds,
                poll_interval_seconds,
                trusted_public_key,
                expected_fingerprint,
                allow_unverified_signatures_for_test,
            )


def _copy_bundle_snapshot(source: Path, destination: Path) -> None:
    if not source.is_file():
        raise PublisherError(f"Bundle does not exist: {source}")
    if source.stat().st_size > MAX_BUNDLE_BYTES:
        raise PublisherError("Central deployment bundle exceeds the local 100 MB safety limit")

    copied_bytes = 0
    with source.open("rb") as input_stream, destination.open("xb") as output_stream:
        while chunk := input_stream.read(1024 * 1024):
            copied_bytes += len(chunk)
            if copied_bytes > MAX_BUNDLE_BYTES:
                raise PublisherError("Central deployment bundle exceeds the local 100 MB safety limit")
            output_stream.write(chunk)
        output_stream.flush()
        os.fsync(output_stream.fileno())


def _stage_bundle_locked(
    bundle: Path,
    state_file: Path,
    client: PortalClient,
    validation_timeout_seconds: float,
    poll_interval_seconds: float,
    trusted_public_key: Path | None,
    expected_fingerprint: str | None,
    allow_unverified_signatures_for_test: bool,
) -> str:
    if allow_unverified_signatures_for_test:
        if not client.loopback_http_for_test:
            raise PublisherError("Unverified signature bypass is allowed only for an explicit loopback HTTP test")
    else:
        if trusted_public_key is None or expected_fingerprint is None:
            raise PublisherError("Upload requires a trusted signing public key and expected fingerprint")
        verify_bundle_signatures(bundle, "gpg", trusted_public_key, expected_fingerprint)

    info = inspect_bundle(bundle, require_signatures=True)
    if state_file.exists():
        local_state = _read_state(state_file)
        _match_existing_state(local_state, info)
        deployment_id = local_state.get("deploymentId")
        if not isinstance(deployment_id, str) or not deployment_id:
            raise PublisherError(
                "Existing upload has no deployment ID; manual Portal reconciliation required; upload will not be retried"
            )
        if local_state.get("deploymentState") == "FAILED":
            raise PublisherError("Prior Central Portal deployment FAILED; do not reupload this GAV")
    else:
        local_state = _state_for_bundle(info)
        _write_state(state_file, local_state)
        try:
            local_state["deploymentId"] = client.upload(bundle)
            local_state["deploymentState"] = "PENDING"
            local_state["lastError"] = None
            local_state["updatedAt"] = _utc_now()
            _write_state(state_file, local_state)
        except PublisherError as exc:
            # The server may have accepted the POST before the response was lost.
            # Keep the prewritten intent and fail closed on every subsequent run.
            local_state["deploymentId"] = None
            local_state["deploymentState"] = "UPLOAD_UNKNOWN"
            local_state["lastError"] = type(exc).__name__
            local_state["updatedAt"] = _utc_now()
            _write_state(state_file, local_state)
            raise PublisherError(
                "Portal upload outcome is unknown; preserve this state and require manual Portal reconciliation"
            ) from exc

    state = _wait_for_validation(
        client,
        state_file,
        local_state,
        validation_timeout_seconds,
        poll_interval_seconds,
    )
    print(f"{info['gav']} deployment {local_state['deploymentId']} state={state}")
    print(f"Bundle SHA-256: {info['bundleSha256']}")
    return state


def _extract_bundle(bundle: Path, destination: Path, info: dict[str, object]) -> Path:
    resolved_destination = destination.resolve()
    with zipfile.ZipFile(bundle, "r") as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            relative = PurePosixPath(entry.filename)
            target = destination.joinpath(*relative.parts).resolve()
            try:
                target.relative_to(resolved_destination)
            except (OSError, ValueError) as exc:
                raise PublisherError(f"ZIP entry escapes its extraction directory: {entry.filename}") from exc
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))

    coordinate_dir = PurePosixPath(str(info["coordinateDirectory"]))
    expected_suffix = PurePosixPath(
        *str(info["groupId"]).split("."), str(info["artifactId"]), str(info["version"])
    )
    prefix_parts = coordinate_dir.parts[:-len(expected_suffix.parts)]
    repository_root = destination.joinpath(*prefix_parts)
    return repository_root


def verify_consumer(bundle: Path, gradle_wrapper: Path) -> None:
    info = inspect_bundle(bundle, require_signatures=False)
    if not gradle_wrapper.is_file():
        raise PublisherError(f"Gradle wrapper does not exist: {gradle_wrapper}")

    with tempfile.TemporaryDirectory(prefix="syncmoney-central-consumer-") as temporary:
        root = Path(temporary)
        repository = root / "repository"
        repository.mkdir()
        repository_root = _extract_bundle(bundle, repository, info)
        consumer = root / "consumer"
        (consumer / "src" / "main" / "java").mkdir(parents=True)
        repo_uri = repository_root.resolve().as_uri()
        gav = str(info["gav"])
        build_file = (
            "plugins { id 'java' }\n"
            f"repositories {{ maven {{ url = uri('{repo_uri}') }} }}\n"
            f"dependencies {{ implementation '{gav}' }}\n"
        )
        (consumer / "settings.gradle").write_text("rootProject.name = 'syncmoney-api-consumer'\n", encoding="utf-8")
        (consumer / "build.gradle").write_text(build_file, encoding="utf-8")
        source = """import java.util.UUID;
import noietime.syncmoney.api.SyncmoneyApi;

final class Consumer {
    void read(SyncmoneyApi api) {
        api.economy().cachedBalance(UUID.randomUUID())
            .ifPresent(snapshot -> snapshot.balance());
    }
}
"""
        (consumer / "src" / "main" / "java" / "Consumer.java").write_text(source, encoding="utf-8")
        command = [str(gradle_wrapper), "--offline", "--no-daemon", "-p", str(consumer), "compileJava"]
        if os.name == "nt":
            command = ["cmd.exe", "/d", "/c", *command]
        result = subprocess.run(command, text=True, capture_output=True, timeout=180)
        if result.returncode != 0:
            raise PublisherError("Independent local Maven consumer compilation failed:\n" + result.stdout[-4000:] + result.stderr[-4000:])
    print(f"Independent local consumer compiled against {gav}")


def _finite_float(value: str, *, allow_zero: bool) -> float:
    try:
        number = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a number") from exc
    if number < 0 or number != number or number == float("inf") or (not allow_zero and number == 0):
        qualifier = "finite positive" if not allow_zero else "finite non-negative"
        raise argparse.ArgumentTypeError(f"must be a {qualifier} number")
    return number


def _positive_timeout(value: str) -> float:
    number = _finite_float(value, allow_zero=False)
    if number > 120:
        raise argparse.ArgumentTypeError("must be at most 120 seconds")
    return number


def _validation_timeout(value: str) -> float:
    number = _finite_float(value, allow_zero=False)
    if number > 3600:
        raise argparse.ArgumentTypeError("must be at most 3600 seconds")
    return number


def _poll_interval(value: str) -> float:
    number = _finite_float(value, allow_zero=False)
    if number > 60:
        raise argparse.ArgumentTypeError("must be at most 60 seconds")
    return number


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate", help="validate a local Central ZIP without credentials or network")
    validate.add_argument("--bundle", type=Path, required=True)
    validate.add_argument("--require-signatures", action="store_true")

    consumer = subparsers.add_parser("verify-consumer", help="compile a consumer against the local bundle")
    consumer.add_argument("--bundle", type=Path, required=True)
    consumer.add_argument("--gradle-wrapper", type=Path, required=True)

    upload = subparsers.add_parser("upload", help="upload to a USER_MANAGED Portal deployment and poll validation")
    upload.add_argument("--bundle", type=Path, required=True)
    upload.add_argument("--state-file", type=Path, required=True)
    upload.add_argument("--api-base-url", default="https://central.sonatype.com/api/v1/publisher")
    upload.add_argument("--request-timeout-seconds", type=_positive_timeout, default=30.0)
    upload.add_argument("--validation-timeout-seconds", type=_validation_timeout, default=600.0)
    upload.add_argument("--poll-interval-seconds", type=_poll_interval, default=5.0)
    upload.add_argument("--trusted-public-key", type=Path)
    upload.add_argument("--signing-fingerprint")
    upload.add_argument("--allow-loopback-http-for-test", action="store_true", help=argparse.SUPPRESS)
    upload.add_argument("--allow-unverified-signatures-for-test", action="store_true", help=argparse.SUPPRESS)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "validate":
            info = inspect_bundle(args.bundle, require_signatures=args.require_signatures)
            print(f"{info['gav']} local bundle valid; signed={str(info['signed']).lower()}")
            print(f"Bundle SHA-256: {info['bundleSha256']}")
            return 0
        if args.command == "verify-consumer":
            verify_consumer(args.bundle, args.gradle_wrapper)
            return 0

        client = PortalClient(
            args.api_base_url,
            os.environ.get("SYNCMONEY_CENTRAL_USERNAME", ""),
            os.environ.get("SYNCMONEY_CENTRAL_PASSWORD", ""),
            args.request_timeout_seconds,
            allow_loopback_http_for_test=args.allow_loopback_http_for_test,
        )
        if not args.allow_unverified_signatures_for_test and (
            args.trusted_public_key is None or args.signing_fingerprint is None
        ):
            raise PublisherError("Upload requires --trusted-public-key and --signing-fingerprint")
        stage_bundle(
            args.bundle,
            args.state_file,
            client,
            args.validation_timeout_seconds,
            args.poll_interval_seconds,
            args.trusted_public_key,
            args.signing_fingerprint,
            args.allow_unverified_signatures_for_test,
        )
        return 0
    except PublisherError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except (OSError, zipfile.BadZipFile, ET.ParseError, subprocess.SubprocessError) as exc:
        print(f"Publisher operation failed ({type(exc).__name__})", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
