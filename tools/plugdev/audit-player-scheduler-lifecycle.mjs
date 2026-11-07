import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  hasExactExpectedServerKick,
  hasSingleSuccessfulTeleportFuture,
} from "./player-scheduler-lifecycle-assertions.mjs";

const EXPECTED_REPORTS = [
  ["paper-1216-min", "379b6efaf66e67a72cbbdb6db0d1f0ce1545b1c2e8779fe296ca8b0a0e8b7436"],
  ["paper-263-max", "08458fca179aa76318301b1081c9320f5ee190be91859693d2becf611f00b1ba"],
  ["folia-1216-min", "c8a0359afcb11a5ab192ca813f4d6ef331341e2eb04869cad779ca19359b64b5"],
  ["folia-262-max", "ece7f6a1e480a1651e4b705d7cbc6c15500b660f2ce8dc2fca8ceca19815f4ab"],
  ["canvas-1218-min", "35735beb9c76013c8ce83fbb0c10147a231b43f58e30bb14fa53dad26c80db21"],
  ["canvas-263-max", "85fa917f8bc76cb273990e892964b3a1888c14b892bdf9c5a71559e3e830fd45"],
];
const ORIGINAL_AUDIT_SHA256 = "7f7718c69a8f2e2d37721fb752bad93e47e3a3b551e508f764c2f55d71cd6a37";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const reportsRoot = path.resolve(
  process.argv[2] ?? path.join(repositoryRoot, "build/reports/foundation/T21-player-scheduler"),
);
const outputPath = path.join(reportsRoot, "review-corrected-evidence-audit-20261013.json");
const originalAuditPath = path.join(reportsRoot, "final-six-endpoint-audit-20261013.json");

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function listResultFiles(directory) {
  const results = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) results.push(...listResultFiles(entryPath));
    else if (entry.isFile() && entry.name === "result.json") results.push(entryPath);
  }
  return results;
}

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function containedPath(root, relativePath) {
  const resolvedRoot = path.resolve(root);
  const resolvedPath = path.resolve(root, relativePath);
  requireCondition(
    resolvedPath.startsWith(`${resolvedRoot}${path.sep}`),
    `Path escapes its run directory: ${relativePath}`,
  );
  return resolvedPath;
}

const reportFiles = listResultFiles(reportsRoot);
const originalAuditBytes = readFileSync(originalAuditPath);
requireCondition(sha256(originalAuditBytes) === ORIGINAL_AUDIT_SHA256, "Original six-endpoint audit hash changed");
const audited = [];

for (const [endpointLabel, expectedReportSha256] of EXPECTED_REPORTS) {
  const matches = reportFiles.filter((reportPath) => sha256(readFileSync(reportPath)) === expectedReportSha256);
  requireCondition(matches.length === 1, `${endpointLabel}: expected one report with the selected SHA-256, found ${matches.length}`);

  const reportPath = matches[0];
  const report = JSON.parse(readFileSync(reportPath, "utf8"));
  requireCondition(report.status === "PASS", `${endpointLabel}: selected report status is not PASS`);
  requireCondition(report.endpoint.label === endpointLabel, `${endpointLabel}: report endpoint label mismatch`);
  requireCondition(report.endpoint.serverJarSha256, `${endpointLabel}: report omitted the server JAR pin`);
  requireCondition(report.runtime.redis === "4", `${endpointLabel}: expected to verify the historical Redis metadata collision`);
  requireCondition(report.redis.version === 4, `${endpointLabel}: account version is not 4`);
  requireCondition(report.redis.balance === "4.00", `${endpointLabel}: final Redis balance is not 4.00`);
  requireCondition(report.server.processExitVerified && report.server.normalStopExitCode === 0,
    `${endpointLabel}: server normal shutdown evidence is incomplete`);
  requireCondition(report.redis.processExitVerified && report.redis.normalShutdownExitCode === 0 && report.redis.processExitCode === 0,
    `${endpointLabel}: Redis normal shutdown evidence is incomplete`);
  requireCondition(report.forcedCleanup === false, `${endpointLabel}: selected report used forced cleanup`);

  const runRoot = path.resolve(report.isolation.runRoot);
  requireCondition(runRoot.startsWith(`${reportsRoot}${path.sep}`), `${endpointLabel}: report run root is outside the audit root`);
  const serverLogPath = containedPath(runRoot, "logs/server.log");
  const redisLogPath = containedPath(runRoot, "logs/redis-server.log");
  const serverLogBytes = readFileSync(serverLogPath);
  const serverLog = serverLogBytes.toString("utf8");
  const serverLines = serverLog.split(/\r?\n/);
  requireCondition(sha256(serverLogBytes) === report.server.logSha256, `${endpointLabel}: server log hash mismatch`);
  requireCondition(hasSingleSuccessfulTeleportFuture(serverLines), `${endpointLabel}: server log lacks exactly one successful teleport future`);
  requireCondition(hasExactExpectedServerKick(serverLines, "AcceptanceA"), `${endpointLabel}: exact logout reason for AcceptanceA was not verified`);
  requireCondition(serverLog.includes("T15_TELEPORT_REACHED") && serverLog.includes("T15_TELEPORT_FUTURE_RESULT"),
    `${endpointLabel}: server teleport confirmation markers are absent`);

  const redisLogBytes = readFileSync(redisLogPath);
  const redisLog = redisLogBytes.toString("utf8");
  const redisVersionMatches = [...redisLog.matchAll(/Redis version=([^,\s]+)/g)];
  requireCondition(redisVersionMatches.length === 1, `${endpointLabel}: Redis server log does not identify one binary version`);
  const redisServerVersion = redisVersionMatches[0][1];
  requireCondition(redisServerVersion === "8.10.1", `${endpointLabel}: unexpected Redis server version ${redisServerVersion}`);

  const redisFiles = report.redis.files.map((file) => {
    const filePath = containedPath(runRoot, file.path);
    const bytes = readFileSync(filePath);
    const actualHash = sha256(bytes);
    requireCondition(bytes.byteLength === file.bytes, `${endpointLabel}: stopped Redis file size mismatch for ${file.path}`);
    requireCondition(actualHash === file.sha256, `${endpointLabel}: stopped Redis file hash mismatch for ${file.path}`);
    return { path: file.path, bytes: file.bytes, sha256: actualHash };
  });

  audited.push({
    endpoint: report.endpoint,
    report: { path: path.relative(reportsRoot, reportPath).replaceAll(path.sep, "/"), sha256: expectedReportSha256 },
    serverLog: { sha256: sha256(serverLogBytes), successfulTeleportFuture: true, exactAcceptanceAReason: true },
    redisLog: { sha256: sha256(redisLogBytes), observedServerVersion: redisServerVersion },
    historicalMetadataCorrection: {
      reportRuntimeRedisValue: report.runtime.redis,
      interpretation: "account version copied into the runtime Redis field by the historical runner",
      correctedRedisServerVersion: redisServerVersion,
      redisAccountVersion: report.redis.version,
    },
    stoppedRedisFiles: redisFiles,
  });
}

const audit = {
  schemaVersion: 1,
  status: "PASS",
  audit: "T21 lifecycle report provenance and assertion correction",
  generatedAt: new Date().toISOString(),
  scope: "Read-only verification of the six selected historical reports, exact server logs, Redis binary-version logs, and stopped Redis data/AOF files.",
  historicalReportsRewritten: false,
  originalAuditRewritten: false,
  originalAudit: {
    path: path.relative(reportsRoot, originalAuditPath).replaceAll(path.sep, "/"),
    sha256: sha256(originalAuditBytes),
  },
  endpoints: audited,
};

writeFileSync(outputPath, `${JSON.stringify(audit, null, 2)}\n`, { flag: "w" });
console.log(`PASS: audited ${audited.length} selected endpoint reports; Redis server version 8.10.1; historical reports unchanged.`);
console.log(outputPath);
