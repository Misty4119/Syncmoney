import http.server
import base64
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import zipfile
from hashlib import md5, sha1, sha256
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse


SCRIPT = Path(__file__).resolve().parents[1] / "publish_central.py"
sys.path.insert(0, str(SCRIPT.parent))
from publish_central import (
    PublisherError,
    _coordinate_content_hash,
    sha256_file,
    stage_bundle,
    verify_bundle_signatures,
    verify_consumer,
)

GROUP = "io.github.misty4119"
ARTIFACT = "syncmoney-api"
VERSION = "1.4.0-alpha.1"
GAV_PATH = "/".join(GROUP.split(".")) + f"/{ARTIFACT}/{VERSION}"


class PortalHandler(http.server.BaseHTTPRequestHandler):
    uploads = []
    statuses = []
    status_requests = []
    delay_upload_seconds = 0.0
    delay_status_seconds = 0.0
    redirect_upload = False
    block_upload = False
    upload_started = threading.Event()
    release_upload = threading.Event()

    def log_message(self, *_args):
        pass

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path.endswith("/status"):
            self.__class__.status_requests.append({
                "method": "POST",
                "path": parsed.path,
                "query": parse_qs(parsed.query),
                "authorization": self.headers.get("Authorization"),
            })
            self.send_status_response()
            return

        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        self.__class__.uploads.append({
            "path": parsed.path,
            "query": parse_qs(parsed.query),
            "authorization": self.headers.get("Authorization"),
            "body": body,
        })
        if self.__class__.redirect_upload and parsed.path.endswith("/upload"):
            self.send_response(302)
            self.send_header(
                "Location",
                f"http://127.0.0.1:{self.server.server_port}/api/v1/publisher/collect",
            )
            self.end_headers()
            return
        if self.__class__.block_upload and parsed.path.endswith("/upload"):
            self.__class__.upload_started.set()
            self.__class__.release_upload.wait()
        if self.__class__.delay_upload_seconds:
            time.sleep(self.__class__.delay_upload_seconds)
        payload = b"deployment-test-1"
        self.send_response(201)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            pass

    def do_GET(self):
        parsed = urlparse(self.path)
        self.__class__.status_requests.append({
            "method": "GET",
            "path": parsed.path,
            "query": parse_qs(parsed.query),
            "authorization": self.headers.get("Authorization"),
        })
        self.send_error(405)

    def send_status_response(self):
        if self.__class__.delay_status_seconds:
            time.sleep(self.__class__.delay_status_seconds)
        state = self.__class__.statuses.pop(0) if self.__class__.statuses else "VALIDATED"
        payload = json.dumps({
            "deploymentId": "deployment-test-1",
            "deploymentName": "Syncmoney API test",
            "deploymentState": state,
            "errors": [{"message": "fixture validation failure"}] if state == "FAILED" else [],
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            pass


def create_bundle(
    path: Path,
    *,
    signed: bool = True,
    jar_bytes: bytes = b"api-jar-v1",
    extra_files: dict[str, bytes] | None = None,
) -> None:
    version_dir = GAV_PATH
    prefix = f"{ARTIFACT}-{VERSION}"
    files = {
        f"{prefix}.jar": jar_bytes,
        f"{prefix}-sources.jar": b"sources-v1",
        f"{prefix}-javadoc.jar": b"javadoc-v1",
        f"{prefix}.pom": (
            "<project><modelVersion>4.0.0</modelVersion>"
            f"<groupId>{GROUP}</groupId><artifactId>{ARTIFACT}</artifactId>"
            f"<version>{VERSION}</version><name>Syncmoney API</name>"
            "<description>Read-only Syncmoney API</description>"
            "<url>https://github.com/Misty4119/Syncmoney</url>"
            "<licenses><license><name>Apache-2.0</name>"
            "<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>"
            "</license></licenses><developers><developer><id>misty4119</id>"
            "<name>Misty4119</name>"
            "</developer></developers><scm><url>https://github.com/Misty4119/Syncmoney</url>"
            "</scm></project>"
        ).encode("utf-8"),
    }
    files.update(extra_files or {})
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in files.items():
            archive.writestr(f"{version_dir}/{name}", content)
            archive.writestr(f"{version_dir}/{name}.md5", md5(content).hexdigest())
            archive.writestr(f"{version_dir}/{name}.sha1", sha1(content).hexdigest())
            if signed and name.endswith((".jar", ".pom")):
                archive.writestr(f"{version_dir}/{name}.asc", b"fixture-signature")


class PublishCentralTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        PortalHandler.uploads = []
        PortalHandler.statuses = []
        PortalHandler.status_requests = []
        PortalHandler.delay_upload_seconds = 0.0
        PortalHandler.delay_status_seconds = 0.0
        PortalHandler.redirect_upload = False
        PortalHandler.block_upload = False
        PortalHandler.upload_started.clear()
        PortalHandler.release_upload.clear()
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), PortalHandler)
        cls.server.daemon_threads = True
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()
        cls.api_base = f"http://127.0.0.1:{cls.server.server_address[1]}/api/v1/publisher"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.server_thread.join(timeout=2)

    def setUp(self):
        PortalHandler.uploads.clear()
        PortalHandler.status_requests.clear()
        PortalHandler.statuses = []
        PortalHandler.delay_upload_seconds = 0.0
        PortalHandler.delay_status_seconds = 0.0
        PortalHandler.redirect_upload = False
        PortalHandler.block_upload = False
        PortalHandler.upload_started.clear()
        PortalHandler.release_upload.clear()
        self.temp_dir = tempfile.TemporaryDirectory(prefix="syncmoney-central-test-")
        self.root = Path(self.temp_dir.name)
        self.bundle = self.root / "aggregation.zip"
        self.state = self.root / "deployment-state.json"
        create_bundle(self.bundle)

    def tearDown(self):
        self.temp_dir.cleanup()

    def run_cli(self, *args, timeout_seconds="2", api_base_url=None):
        env = os.environ.copy()
        env["SYNCMONEY_CENTRAL_USERNAME"] = "fixture-user"
        env["SYNCMONEY_CENTRAL_PASSWORD"] = "fixture-password"
        command = [
            sys.executable,
            str(SCRIPT),
            *args,
            "--api-base-url",
            api_base_url or self.api_base,
            "--request-timeout-seconds",
            timeout_seconds,
            "--validation-timeout-seconds",
            "2",
            "--poll-interval-seconds",
            "0.001",
            "--allow-loopback-http-for-test",
            "--allow-unverified-signatures-for-test",
        ]
        return subprocess.run(command, text=True, capture_output=True, env=env, timeout=8)

    def upload(self):
        return self.run_cli("upload", "--bundle", str(self.bundle), "--state-file", str(self.state))

    @patch("publish_central.subprocess.run")
    def test_verify_consumer_passes_absolute_gradle_wrapper_path(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, "", "")

        verify_consumer(self.bundle, Path("./gradlew"))

        command = run.call_args.args[0]
        wrapper_index = 3 if os.name == "nt" else 0
        self.assertEqual(Path(command[wrapper_index]), Path("./gradlew").resolve())

    def test_same_bundle_resumes_by_status_without_uploading_again(self):
        first = self.upload()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        second = self.upload()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)

        self.assertEqual(len(PortalHandler.uploads), 1)
        self.assertGreaterEqual(len(PortalHandler.status_requests), 2)
        self.assertTrue(PortalHandler.uploads[0]["path"].endswith("/upload"))
        self.assertEqual(PortalHandler.uploads[0]["query"].get("publishingType"), ["USER_MANAGED"])
        self.assertEqual(
            PortalHandler.uploads[0]["authorization"],
            "Bearer " + base64.b64encode(b"fixture-user:fixture-password").decode("ascii"),
        )
        self.assertTrue(all("/deployment/" not in request["path"] for request in PortalHandler.status_requests))
        self.assertTrue(all(request["method"] == "POST" for request in PortalHandler.status_requests))
        self.assertNotIn("fixture-password", first.stdout + first.stderr + second.stdout + second.stderr)
        saved = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual(saved["deploymentId"], "deployment-test-1")
        self.assertEqual(saved["deploymentState"], "VALIDATED")

    def test_changed_content_for_existing_gav_stops_before_another_upload(self):
        first = self.upload()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        create_bundle(self.bundle, jar_bytes=b"api-jar-v2")

        second = self.upload()

        self.assertNotEqual(second.returncode, 0)
        self.assertIn("GAV content hash changed", second.stdout + second.stderr)
        self.assertEqual(len(PortalHandler.uploads), 1)

    def test_ambiguous_upload_timeout_is_persisted_and_never_retried(self):
        PortalHandler.delay_upload_seconds = 0.5
        first = self.run_cli(
            "upload", "--bundle", str(self.bundle), "--state-file", str(self.state), timeout_seconds="0.1"
        )
        self.assertNotEqual(first.returncode, 0)
        self.assertTrue(self.state.is_file())
        saved = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual(saved["deploymentState"], "UPLOAD_UNKNOWN")

        PortalHandler.delay_upload_seconds = 0.0
        second = self.upload()

        self.assertNotEqual(second.returncode, 0)
        self.assertIn("manual Portal reconciliation required", second.stdout + second.stderr)
        self.assertEqual(len(PortalHandler.uploads), 1)

    def test_status_timeout_is_persisted_and_resumes_without_reuploading(self):
        PortalHandler.delay_status_seconds = 0.5
        first = self.run_cli(
            "upload", "--bundle", str(self.bundle), "--state-file", str(self.state), timeout_seconds="0.1"
        )
        self.assertNotEqual(first.returncode, 0)
        saved = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual(saved["deploymentId"], "deployment-test-1")
        self.assertEqual(saved["deploymentState"], "STATUS_UNKNOWN")

        PortalHandler.delay_status_seconds = 0.0
        second = self.upload()

        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertEqual(len(PortalHandler.uploads), 1)
        self.assertGreaterEqual(len(PortalHandler.status_requests), 2)
        self.assertEqual(json.loads(self.state.read_text(encoding="utf-8"))["deploymentState"], "VALIDATED")

    def test_malformed_status_state_fails_closed_and_resumes_without_reuploading(self):
        PortalHandler.statuses = [[]]

        first = self.upload()
        self.assertNotEqual(first.returncode, 0)
        self.assertIn("unknown deployment state", first.stdout + first.stderr)
        self.assertEqual(json.loads(self.state.read_text(encoding="utf-8"))["deploymentState"], "STATUS_UNKNOWN")

        second = self.upload()

        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertEqual(len(PortalHandler.uploads), 1)
        self.assertEqual(len(PortalHandler.status_requests), 2)

    def test_failed_deployment_is_recorded_and_not_reuploaded(self):
        PortalHandler.statuses = ["FAILED", "FAILED"]

        first = self.upload()
        second = self.upload()

        self.assertNotEqual(first.returncode, 0)
        self.assertNotEqual(second.returncode, 0)
        self.assertIn("Prior Central Portal deployment FAILED", second.stdout + second.stderr)
        self.assertEqual(len(PortalHandler.uploads), 1)
        self.assertEqual(len(PortalHandler.status_requests), 1)
        saved = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual(saved["deploymentState"], "FAILED")

    def test_pending_states_are_polled_until_user_managed_validation(self):
        PortalHandler.statuses = ["PENDING", "VALIDATING", "VALIDATED"]

        result = self.upload()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual([r["query"]["id"][0] for r in PortalHandler.status_requests], ["deployment-test-1"] * 3)
        self.assertEqual(json.loads(self.state.read_text(encoding="utf-8"))["deploymentState"], "VALIDATED")

    def test_redirected_upload_is_rejected_without_forwarding_credentials(self):
        PortalHandler.redirect_upload = True

        result = self.upload()

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(PortalHandler.uploads), 1)
        self.assertTrue(PortalHandler.uploads[0]["path"].endswith("/upload"))
        self.assertEqual(PortalHandler.status_requests, [])

    def test_production_credentials_cannot_be_sent_to_an_arbitrary_http_host(self):
        result = self.run_cli(
            "upload", "--bundle", str(self.bundle), "--state-file", str(self.state),
            api_base_url="http://example.invalid/api/v1/publisher",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("only explicit loopback HTTP is allowed for tests", result.stdout + result.stderr)
        self.assertEqual(PortalHandler.uploads, [])

    def test_local_unsigned_bundle_validation_does_not_require_portal_credentials(self):
        create_bundle(self.bundle, signed=False)
        env = os.environ.copy()
        env.pop("SYNCMONEY_CENTRAL_USERNAME", None)
        env.pop("SYNCMONEY_CENTRAL_PASSWORD", None)

        result = subprocess.run(
            [sys.executable, str(SCRIPT), "validate", "--bundle", str(self.bundle)],
            text=True,
            capture_output=True,
            env=env,
            timeout=8,
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"{GROUP}:{ARTIFACT}:{VERSION}", result.stdout)
        self.assertEqual(PortalHandler.uploads, [])

    def test_windows_drive_relative_entry_is_rejected_before_extraction(self):
        create_bundle(self.bundle, extra_files={f"{GAV_PATH}/D:payload": b"must not escape"})

        result = subprocess.run(
            [sys.executable, str(SCRIPT), "validate", "--bundle", str(self.bundle)],
            text=True,
            capture_output=True,
            timeout=8,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unsafe ZIP entry path", result.stdout + result.stderr)

    def test_coordinate_hash_uses_unambiguous_length_prefixes(self):
        merged = _coordinate_content_hash({"a": b"xb\x00y"})
        split = _coordinate_content_hash({"a": b"x", "b": b"y"})

        self.assertNotEqual(merged, split)

    def test_verified_snapshot_is_the_bundle_uploaded_after_source_replacement(self):
        original_hash = sha256_file(self.bundle)
        source_bundle = self.bundle

        class ReplacingPortalClient:
            loopback_http_for_test = True

            def __init__(self):
                self.uploaded_hash = None

            def upload(self, bundle):
                self.uploaded_hash = sha256(bundle.read_bytes()).hexdigest()
                source_bundle.write_bytes(b"replaced after snapshot verification")
                return "deployment-test-1"

            def status(self, deployment_id):
                return {"deploymentId": deployment_id, "deploymentState": "VALIDATED"}

        client = ReplacingPortalClient()

        def replace_source_after_verification(snapshot, _gpg, _public_key, _fingerprint):
            self.assertEqual(sha256_file(snapshot), original_hash)
            source_bundle.write_bytes(b"replacement source bundle")

        with patch("publish_central.verify_bundle_signatures", side_effect=replace_source_after_verification):
            result = stage_bundle(
                source_bundle,
                self.state,
                client,
                1,
                0.001,
                self.root / "fixture-public-key.asc",
                "A" * 40,
            )

        saved = json.loads(self.state.read_text(encoding="utf-8"))
        self.assertEqual(result, "VALIDATED")
        self.assertEqual(saved["bundleSha256"], original_hash)
        self.assertEqual(client.uploaded_hash, original_hash)
        self.assertNotEqual(sha256_file(source_bundle), original_hash)

    def test_unverified_signature_bypass_is_rejected_for_production_host(self):
        result = self.run_cli(
            "upload",
            "--bundle",
            str(self.bundle),
            "--state-file",
            str(self.state),
            api_base_url="https://central.sonatype.com/api/v1/publisher",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("only for an explicit loopback HTTP test", result.stdout + result.stderr)
        self.assertEqual(PortalHandler.uploads, [])

    def test_production_staging_requires_a_trusted_signing_key(self):
        command = [
            sys.executable,
            str(SCRIPT),
            "upload",
            "--bundle",
            str(self.bundle),
            "--state-file",
            str(self.state),
            "--api-base-url",
            self.api_base,
            "--allow-loopback-http-for-test",
        ]
        env = os.environ.copy()
        env["SYNCMONEY_CENTRAL_USERNAME"] = "fixture-user"
        env["SYNCMONEY_CENTRAL_PASSWORD"] = "fixture-password"

        result = subprocess.run(command, text=True, capture_output=True, env=env, timeout=8)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("requires --trusted-public-key and --signing-fingerprint", result.stdout + result.stderr)
        self.assertEqual(PortalHandler.uploads, [])

    def test_concurrent_upload_is_rejected_by_atomic_state_lock(self):
        PortalHandler.block_upload = True
        command = [
            sys.executable,
            str(SCRIPT),
            "upload",
            "--bundle",
            str(self.bundle),
            "--state-file",
            str(self.state),
            "--api-base-url",
            self.api_base,
            "--request-timeout-seconds",
            "120",
            "--validation-timeout-seconds",
            "2",
            "--poll-interval-seconds",
            "0.001",
            "--allow-loopback-http-for-test",
            "--allow-unverified-signatures-for-test",
        ]
        env = os.environ.copy()
        env["SYNCMONEY_CENTRAL_USERNAME"] = "fixture-user"
        env["SYNCMONEY_CENTRAL_PASSWORD"] = "fixture-password"
        first = subprocess.Popen(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        lock_path = Path(str(self.state) + ".lock")
        try:
            self.assertTrue(
                PortalHandler.upload_started.wait(timeout=4),
                "first upload did not reach the local Portal fixture",
            )
            self.assertTrue(lock_path.exists(), "first upload did not acquire its state lock")
            second = self.upload()
        finally:
            PortalHandler.release_upload.set()
        first_stdout, first_stderr = first.communicate(timeout=5)

        self.assertEqual(first.returncode, 0, first_stdout + first_stderr)
        self.assertNotEqual(second.returncode, 0)
        self.assertIn("state lock", second.stdout + second.stderr)
        self.assertEqual(len(PortalHandler.uploads), 1)
        self.assertFalse(lock_path.exists())


if __name__ == "__main__":
    unittest.main()
