# PlugDev acceptance tests

Install/use the external PlugDev CLI. A sibling `plugdev-main` checkout is the default; override `PLUGDEV_ROOT` for `prepare.mjs` when needed. Install JDK 21 for compilation/tests and JDK 25 for Minecraft 26.1+. Gradle 9.1 runs on either and uses a Java 21 toolchain.

```powershell
.\gradlew.bat test shadowJar acceptanceJar :syncmoney-papi-expansion:jar
node tools/plugdev/prepare.mjs paper 1.20.4 local off
plugdev --config .plugdev/profile.yml server start
plugdev server command "version"
plugdev server command "plugins"
plugdev server command "smaccept"
plugdev server command "syncmoney breaker status"
plugdev server command "syncmoney shadow status"
plugdev server command "syncmoney audit stats"
plugdev server stop
```

Repeat with `paper 26.2`, `folia 26.2`, and `canvas 26.2`. Read `ACCEPTANCE PASS` or `ACCEPTANCE FAIL` in `.plugdev/run/logs/latest.log`; a started server alone is not a passing plugin test. Inspect the actual server version, especially when the external CLI reports Canvas version aliases.

Use `local_redis` with an isolated Redis on `127.0.0.1:16379`, database 15; never point acceptance tests at production Redis. `on` enables the optional services with local Shadow storage. The probe writes only the offline test accounts AcceptanceA/B. Use `smaccept read` after a restart or on a second server to check persistence/synchronization. For parallel servers use separate disposable project checkouts and ports, with the same isolated Redis database and distinct server names.

## Two-backend network acceptance

The repository includes a reproducible Paper 26.2 network profile using the latest `velocity-ctd` build. It keeps the proxy dependency-free and installs ViaVersion only on the Paper backends, because the CTD proxy owns 26.2 protocol handling.

```powershell
node tools/plugdev/network-prepare.mjs
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.3'
node ..\plugdev-main\packages\cli\dist\cli.js network --config tools/plugdev/network.yml --no-join --no-watch
$env:PLUGDEV_MINEFLAYER_PATH = 'C:\Users\margo\IdeaProjects\mineflayer-master'
node tools/plugdev/network-players.mjs
```

The network profile uses PostgreSQL `127.0.0.1:15432`, database `syncmoney_network_acceptance`, and Redis `127.0.0.1:16379`, database 14. These must be disposable local services; override the PostgreSQL/Redis paths with the environment variables documented in `prepare.mjs` and `network-players.mjs` when needed. The player script fails unless PostgreSQL records `AcceptanceA=990` and `AcceptanceB=530` after the cross-server payment and Redis contains synchronization keys.

The probe is built separately in `build/acceptance`; it is never included in the release JAR. `.plugdev` contains generated configurations, RCON secrets, worlds, JARs and logs and must remain ignored. Preserve a run's logs before preparing another profile. Only run these commands in a disposable development server.

For player-side verification, connect two test clients and exercise `/money`, `/pay`, `/baltop`, reconnect, and cross-world teleport. Vault/PAPI/CMI compatibility must be recorded against the actual installed dependency versions. Optional CMI tests require a locally supplied licensed CMI build.
