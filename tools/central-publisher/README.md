# Syncmoney API Central bundle

This tool validates the local Maven Central bundle and stages it as a
`USER_MANAGED` deployment. It never publishes or drops a deployment. A
`VALIDATED` result still requires a separate, authorized Portal action before
the artifact becomes public.

## Local verification

Run from the repository root with JDK 25 and the Gradle 9.8.0 wrapper:

```powershell
python -m unittest discover -s tools/central-publisher/tests -v
python tools/central-publisher/verify_signing_fixture.py
$releaseVersion = '1.4.0'
.\gradlew.bat "-PreleaseVersion=$releaseVersion" :syncmoney-api:check nmcpZipAggregation --no-daemon
python tools/central-publisher/publish_central.py validate --bundle build/nmcp/zip/aggregation.zip
python tools/central-publisher/publish_central.py verify-consumer --bundle build/nmcp/zip/aggregation.zip --gradle-wrapper .\gradlew.bat
```

The `releaseVersion` property applies only to this local candidate build; the
committed stable metadata is `1.4.0`. These validation commands do not
stage, sign, upload, or publish an artifact.

The signing fixture creates a temporary PGP key, verifies every signature in
the bundle, and deletes the key with its temporary directory. It does not use
production signing material. It writes a `1.4.0-signing-fixture` bundle to the
usual Gradle output path, so run it before building the intended release
version. The final unsigned local bundle can be validated and consumed without
Portal credentials.

## Portal staging

The operator supplies a Central Portal token through environment variables
`SYNCMONEY_CENTRAL_USERNAME` and `SYNCMONEY_CENTRAL_PASSWORD`. Keep the state
file private and retain it until the deployment is reconciled. Staging also
requires a trusted signing public-key file and its independently confirmed
OpenPGP fingerprint; GnuPG verifies every signature in the exact bundle before
any Portal request. The public key is not secret. For example:

```powershell
$signingFingerprint = 'REPLACE_WITH_THE_CONFIRMED_40_OR_64_HEX_FINGERPRINT'
python tools/central-publisher/publish_central.py upload --bundle build/nmcp/zip/aggregation.zip --state-file .central-state/1.4.0.json --trusted-public-key .local/central-signing-public-key.asc --signing-fingerprint $signingFingerprint
```

The tool pins requests to the documented Central Portal HTTPS host, refuses
redirects, uploads as `USER_MANAGED`, polls the documented POST status endpoint,
and persists the GAV/content hashes and deployment ID. Before staging, it copies
the input into a private snapshot capped at 100 MB; signature verification,
metadata hashing, and the upload all use those exact bytes. If an upload response
is ambiguous, it records `UPLOAD_UNKNOWN` and will not upload again. If status
polling is ambiguous, it preserves the deployment ID and resumes by querying
status. An atomic sidecar lock prevents two invocations from staging the same
state file concurrently. A changed bundle for the same GAV is rejected.
Resolve an `UPLOAD_UNKNOWN` state manually in the Portal before taking any
further action. If a process is forcibly terminated and leaves a `.lock` file,
confirm no matching publisher process is active before removing the stale lock.

The private state file contains deployment metadata but no Portal credentials.
Do not commit it. Production namespace ownership, account eligibility/tier,
protected credentials, and the authorized public-publish action are separate
release gates; this tool does not perform those actions.
