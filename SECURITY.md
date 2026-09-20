# Security Policy

Syncmoney is an economy plugin. Security bugs can affect balances, credentials, cross-server synchronization, audit data, or the embedded administration service. Please report suspected vulnerabilities privately and give maintainers a reasonable opportunity to investigate before public disclosure.

Traditional Chinese: [`docs/SECURITY.zh_tw.md`](docs/SECURITY.zh_tw.md)

## Supported versions

Security fixes are developed against the current repository release line. At the time this document was updated, root `build.gradle` declares version `1.3.2`.

Older releases may no longer receive fixes. If a report affects an older build, reproduce it on the current release or current `main` when practical and include the exact version tested.

## Reporting a vulnerability

Report vulnerabilities privately by email to **security@noie.fun**. This is the project's official security-reporting address.

If email is temporarily unavailable, GitHub's private vulnerability reporting / Security Advisory feature for `Misty4119/Syncmoney` may be used when it is enabled. Do not fall back to a public issue or public community post for exploit details.

Do not open a public issue containing working exploits, credentials, private server addresses, database dumps, API keys, RCON secrets, or player data.

Include enough detail to reproduce and assess the issue:

- affected Syncmoney version/commit and server implementation/version;
- economy mode (`local`, `local_redis`, `sync`, or `cmi`);
- relevant optional components such as Redis, SQL, CMI, PlaceholderAPI, Web Admin, or proxy/network topology;
- minimal reproduction steps and observed result;
- expected result and security impact;
- logs or configuration excerpts with all secrets removed;
- whether the issue requires authentication, a permission node, network access, or a race/concurrency condition;
- any proposed mitigation or patch, if available.

Maintainers may ask for additional reproduction data. No response-time or remediation-time SLA is promised by this project.

## High-impact areas

Reports are especially useful when they involve:

- creation, duplication, loss, or unauthorized transfer of currency;
- bypass of transaction limits, player protection, or circuit-breaker controls;
- stale/replayed cross-server updates defeating monotonic balance versions;
- Pub/Sub spoofing, echo loops, or duplicate-message handling that changes balances;
- queue saturation or shutdown behavior that silently drops accepted economic writes;
- unsafe scheduler access that can corrupt state on Folia/Canvas;
- Web Admin authentication, authorization, CORS, node proxying, config synchronization, or secret exposure;
- SQL/Redis credential exposure, injection, path traversal, or unsafe file export;
- privilege escalation through `/syncmoney`, Vault, or optional integration boundaries.

## Web Admin deployment

Web Admin is an administrative surface and should be treated as privileged infrastructure.

- Replace the shipped `web-admin.security.api-key` value before exposing the service.
- The default configuration currently uses `change-me-in-production`. Configuration validation warns about this value, while `WebServiceManager` only auto-disables the server when the key equals `change-me`. Do not rely on the auto-disable guard to protect an unchanged default.
- Bind the service to a trusted interface or place it behind a properly configured reverse proxy/firewall.
- Restrict CORS origins to trusted origins; `*` is intentionally warned about by the plugin.
- Use TLS at the reverse proxy when traffic leaves a trusted local network.
- Treat node API keys as secrets and limit connectivity between central and managed nodes.
- `/health` is intentionally unauthenticated. Normal `/api/*` endpoints pass through Bearer API-key authentication, subject to endpoint-specific behavior.
- SSE is the implemented live transport used by the frontend. The current `/ws` implementation is incomplete and should not be treated as a hardened production channel.

## Secret handling

Never commit live values for:

- Web Admin or node API keys;
- Redis/SQL credentials;
- migration or Shadow Sync database credentials;
- Discord webhook URLs;
- RCON or acceptance-test credentials;
- private player/audit exports.

If a real credential was committed, deleting it in a later commit does not revoke it or remove it from history. Rotate/revoke the credential first, then assess whether history must be rewritten and whether downstream copies need remediation.

## Security expectations for changes

Economic changes must preserve `BigDecimal` normalization, insufficient-funds checks, atomic/ordered transfer semantics, monotonic versions, accepted-write durability, and echo/duplicate suppression. Hot Vault/placeholder/entity paths must not add blocking network or database I/O.

Scheduler-sensitive changes must respect Paper/Folia entity ownership. CMI mutations and player/entity operations must execute on the owning entity scheduler.

For security-sensitive pull requests, include focused tests or reproducible acceptance steps for the affected boundary. Cross-server, lifecycle, storage, or scheduler changes should use the PlugDev procedures in `tools/plugdev/README.md` when the required dependencies are available.

## Public disclosure

After a fix is available, maintainers and reporters should coordinate public disclosure at a level that helps operators upgrade without unnecessarily exposing unpatched installations. Credit is welcome when the reporter wants it and when disclosure is appropriate.
