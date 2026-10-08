# PlugDev acceptance for Syncmoney 1.4.0

Use the local PlugDev source checkout and its CLI build. Do not install or invoke the public npm package. The profile helper writes only ignored `.plugdev/` state and refuses to prepare a profile while a PlugDev server session is running. It pins the candidate JAR, VaultUnlocked 2.20.2, and acceptance version; PlugDev runs Gradle `help` and deploys that exact JAR without rebuilding it.

## Minimum Paper smoke

Use Java 25 and build the candidate and acceptance probe first. `PLUGDEV_ROOT` is needed when this repository is running in a worktree that is not beside the local PlugDev checkout.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:PLUGDEV_ROOT = 'C:\path\to\plugdev-main'

.\gradlew.bat -PreleaseVersion=1.4.0-alpha.1 test :syncmoney-papi-expansion:test shadowJar :syncmoney-papi-expansion:jar acceptanceJar apiConsumerJar jacocoTestReport :syncmoney-papi-expansion:jacocoTestReport
node tools/plugdev/prepare.mjs paper 1.21.6 local off 1.4.0-alpha.1
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server start --config .plugdev/profile.yml
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server command "version"
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server command "plugins"
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server command "smaccept"
Select-String .plugdev/run/logs/latest.log -Pattern 'ACCEPTANCE PASS|ACCEPTANCE FAIL'
Select-String .plugdev/run/logs/latest.log -Pattern 'API CLASSLOADER PASS|API CLASSLOADER FAIL'
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server stop

# Start the same profile again after the first graceful stop.
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server start --config .plugdev/profile.yml
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server command "smaccept read"
Select-String .plugdev/run/logs/latest.log -Pattern 'ACCEPTANCE READ a='
node "$env:PLUGDEV_ROOT\packages\cli\dist\cli.js" server stop
```

The generated profile disables PlugDev's idle/orphan auto-stop so the detached server remains available for the RCON acceptance commands. The first `smaccept` call seeds only the synthetic `AcceptanceA` and `AcceptanceB` accounts and checks Vault and VaultUnlocked service registration plus deposit, withdrawal, transfer, and insufficient-funds behavior. The probe waits up to 10 seconds for the existing asynchronous account loader before seeding; it never turns a cold mutation into a synchronous load. After graceful stop and restart, verify the `ACCEPTANCE READ` line reports `cold-vault-a=NaN`, `cold-vault-unlocked-b=null`, `a=1000`, `b=520`, and `vault-unlocked-b=520`; the command response alone is not the assertion. The cold getter checks use separate accounts so the first asynchronous load cannot warm the second check. Check the normal shutdown log for persistence completion. Unit tests separately verify that cold reads return while the cache/SQL loader is blocked. Record the exact Paper build, Java version, Syncmoney JAR SHA-256, VaultUnlocked JAR version/hash, startup/shutdown results, and restart balances. Do not copy server config, RCON credentials, logs, worlds, or `.plugdev/` data into Git.

## Newest supported platform smoke

Repeat the profile and startup/provider/read-write checks on the current pinned Paper, Folia, and Canvas builds used by the validation plan. The helper accepts numeric versions such as `paper 26.2`, `folia 26.2`, and `canvas 26.2`. Record the resolved server build and JAR checksum; a Minecraft version label alone is not a pin. Keep the minimum Paper 1.21.6 run separate from the newest Paper run.

```powershell
node tools/plugdev/prepare.mjs paper 26.2 local off 1.4.0-alpha.1
node tools/plugdev/prepare.mjs folia 26.2 local off 1.4.0-alpha.1
node tools/plugdev/prepare.mjs canvas 26.2 local off 1.4.0-alpha.1
```

`prepare.mjs` rejects Paper versions below 1.21.6. `local` disables Redis and SQL; `local_redis` and `sync` require isolated local services as described by the test profile, never production services. `features=on` enables optional test modules and creates a random local Web Admin key; do not expose that server.
