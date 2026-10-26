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
- stale/replayed cross-server updates bypassing per-account version checks, including CMI's legacy non-global timestamp ordering and compare-and-write mirror guard;
- Pub/Sub spoofing, echo loops, or duplicate-message handling that changes balances;
- queue saturation or shutdown behavior that silently drops accepted economic writes;
- unsafe scheduler access that can corrupt state on Folia/Canvas;
- Web Admin authentication, authorization, CORS, node proxying, config synchronization, or secret exposure;
- SQL/Redis credential exposure, injection, path traversal, or unsafe file export;
- privilege escalation through `/syncmoney`, Vault, or optional integration boundaries.

## Web Admin deployment

Web Admin is an administrative surface and should be treated as privileged infrastructure.

- Replace the shipped `web-admin.security.api-key` value before exposing the service.
- The default configuration uses `change-me-in-production`. `WebServiceManager` disables Web Admin for blank keys and for `change-me` / `change-me-in-production` after trimming and case folding; the economy plugin still starts normally.
- Bind the service to a trusted interface or place it behind a properly configured reverse proxy/firewall.
- Restrict CORS origins to trusted origins; CORS controls browser response access and does not replace API-key authentication. `*` is intentionally warned about by the plugin.
- Use TLS at the reverse proxy when traffic leaves a trusted local network.
- Treat node API keys as secrets and limit connectivity between central and managed nodes.
- Node list, status, management API responses, and node-status SSE events strip URI user-info, query, and fragment from configured URLs; invalid values are labeled `[invalid URL]`. Failed health-check logs omit the configured URL and exception message. Configuration validation rejects new or updated URLs containing user-info, query, or fragment.
- The node proxy allowlists HTTP methods and checks the caller's permission for the target API path before using the configured node key; unmapped target paths require `syncmoney.web.central`. Read proxy methods require `syncmoney.web.nodes.view`; state-changing methods also require `syncmoney.web.nodes.manage` or `syncmoney.web.central`. Proxy request bodies are limited to 1 MiB and oversized requests are rejected with HTTP 413 before parsing or node-key use. Optional query strings are forwarded to the configured node origin. Upstream response bodies are fully buffered up to 8 MiB before forwarding; larger responses return HTTP 502 without forwarding a partial body.
- `/health` is intentionally unauthenticated. Normal `/api/*` endpoints pass through Bearer API-key authentication, subject to endpoint-specific behavior.
- SSE is the implemented live transport used by the frontend. Every `/ws` request returns `503 WEBSOCKET_UNAVAILABLE`; it is not a production channel.

## Known hardening limits

- Node URL validation rejects user-info, query, fragment, and a static list of local/special-use IPv4 ranges, including shared, protocol-assignment, documentation, benchmarking, multicast, and reserved blocks, plus IPv6 unspecified, loopback, link-local, site-local, multicast, and ULA addresses. Validation errors log only the exception type; the URL and its user-info, query, and fragment are not logged. This check does not pin DNS resolution to the eventual HTTP connection. DNS-rebinding resistance is `DEFERRED / NOT VERIFIED`; node URLs are privileged configuration, and operators should restrict server egress to intended nodes.
- `WebDownloader` does not currently enforce bounded transfer size, decompressed size, or archive entry count. These resource limits are `DEFERRED / NOT VERIFIED`; only use trusted download sources and treat this as an open resource-exhaustion hardening gap.
- The CMI mirror's atomic compare-and-write behavior has unit and synthetic Redis integration coverage, but does not establish a licensed/live CMI runtime. CMI authorization is `UNKNOWN`; live CMI acceptance remains `BLOCKED / NOT VERIFIED`.

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

Economic changes must preserve `BigDecimal` normalization, insufficient-funds checks, atomic/ordered transfer semantics, per-account Redis version ordering, accepted-write durability, and echo/duplicate suppression. CMI retains a legacy timestamp-derived version that is not globally monotonic. Hot Vault/placeholder/entity paths must not add blocking network or database I/O.

Scheduler-sensitive changes must respect Paper/Folia entity ownership. CMI mutations and player/entity operations must execute on the owning entity scheduler.

For security-sensitive pull requests, include focused tests or reproducible acceptance steps for the affected boundary. Cross-server, lifecycle, storage, or scheduler changes should use the PlugDev procedures in `tools/plugdev/README.md` when the required dependencies are available.

## Public disclosure

After a fix is available, maintainers and reporters should coordinate public disclosure at a level that helps operators upgrade without unnecessarily exposing unpatched installations. Credit is welcome when the reporter wants it and when disclosure is appropriate.
