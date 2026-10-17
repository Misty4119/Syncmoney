$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required for these T21 runner validation tests.' }
. (Join-Path $PSScriptRoot 't21-upgrade-validation.ps1')

function Assert-ThrowsContaining([string]$name, [scriptblock]$action, [string]$message) {
    try {
        $null = & $action
    } catch {
        if ($_.Exception.Message -notlike ('*' + $message + '*')) {
            throw ($name + ' failed for the wrong reason: ' + $_.Exception.Message)
        }
        Write-Output ('PASS: ' + $name)
        return
    }
    throw ($name + ' was accepted')
}

function Test-T21CommandLineRunsScript {
    param(
        [Parameter(Mandatory)][string]$CommandLine,
        [Parameter(Mandatory)][string]$ExpectedScriptPath
    )

    try {
        if ($null -eq ('T21WindowsCommandLineParser' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class T21WindowsCommandLineParser
{
    [DllImport("shell32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CommandLineToArgvW(string commandLine, out int argumentCount);

    [DllImport("kernel32.dll", SetLastError = false)]
    private static extern IntPtr LocalFree(IntPtr memory);

    public static string[] Parse(string commandLine)
    {
        int argumentCount;
        IntPtr arguments = CommandLineToArgvW(commandLine, out argumentCount);
        if (arguments == IntPtr.Zero)
        {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }

        try
        {
            string[] parsed = new string[argumentCount];
            for (int index = 0; index < argumentCount; index++)
            {
                IntPtr argument = Marshal.ReadIntPtr(arguments, index * IntPtr.Size);
                parsed[index] = Marshal.PtrToStringUni(argument) ?? String.Empty;
            }
            return parsed;
        }
        finally
        {
            LocalFree(arguments);
        }
    }
}
'@ -ErrorAction Stop
        }

        $arguments = [T21WindowsCommandLineParser]::Parse($CommandLine)
        # This harness launches children with exactly these switches and no script arguments.
        # Accepting only that shape rejects PowerShell command-mode aliases and trailing options.
        $expectedArguments = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File')
        if ($arguments.Length -ne ($expectedArguments.Length + 2)) { return $false }
        for ($index = 0; $index -lt $expectedArguments.Length; $index++) {
            if (-not [string]::Equals([string]$arguments[$index + 1], $expectedArguments[$index], [StringComparison]::OrdinalIgnoreCase)) {
                return $false
            }
        }

        $observedScript = [IO.Path]::GetFullPath([string]$arguments[$expectedArguments.Length + 1])
        $expectedScript = [IO.Path]::GetFullPath($ExpectedScriptPath)
        return [string]::Compare($observedScript, $expectedScript, [StringComparison]::OrdinalIgnoreCase) -eq 0
    } catch {
        return $false
    }
}

function Stop-T21VerifiedTestChild {
    param(
        [Parameter(Mandatory)][int]$ProcessId,
        [Parameter(Mandatory)][string]$ExpectedExecutablePath,
        [Parameter(Mandatory)][string]$ExpectedScriptPath
    )

    if ($ProcessId -le 0) { return $false }
    $ownedProcess = $null
    try {
        $ownedProcess = [Diagnostics.Process]::GetProcessById($ProcessId)
        # Open and retain this process handle before querying command-line identity so a later PID reuse
        # cannot redirect Kill() to a different process.
        $ownedHandle = $ownedProcess.Handle
        $ownedProcess.Refresh()
        if ($ownedProcess.HasExited) { return $true }

        $expectedExecutable = [IO.Path]::GetFullPath($ExpectedExecutablePath)
        $expectedScript = [IO.Path]::GetFullPath($ExpectedScriptPath)
        $processInfo = Get-CimInstance -ClassName Win32_Process -Filter ('ProcessId = ' + $ProcessId) -ErrorAction Stop
        if ($null -eq $processInfo -or [string]::IsNullOrWhiteSpace([string]$processInfo.ExecutablePath) -or
            [string]::IsNullOrWhiteSpace([string]$processInfo.CommandLine)) {
            return $false
        }
        $observedExecutable = [IO.Path]::GetFullPath([string]$processInfo.ExecutablePath)
        if ([string]::Compare($observedExecutable, $expectedExecutable, [StringComparison]::OrdinalIgnoreCase) -ne 0 -or
            -not (Test-T21CommandLineRunsScript -CommandLine ([string]$processInfo.CommandLine) -ExpectedScriptPath $expectedScript)) {
            return $false
        }

        $ownedProcess.Kill()
        return $ownedProcess.WaitForExit(5000) -and $ownedProcess.HasExited
    } catch {
        if ($null -ne $ownedProcess) {
            try {
                $ownedProcess.Refresh()
                if ($ownedProcess.HasExited) { return $true }
            } catch { }
        }
        return $false
    } finally {
        if ($null -ne $ownedProcess) { $ownedProcess.Dispose() }
    }
}

$paper = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Paper 1.21.6-48-main@abc (2025-06-30T09:48:37Z) for Minecraft 1.21.6'
Assert-T21PlatformIdentity $paper 'paper' '1.21.6' 48
Write-Output 'PASS: parse minimum Paper startup identity'
$paperMaximum = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Paper 26.3-166-main@abc (2026-10-09T00:54:07Z) for Minecraft 26.3'
Assert-T21PlatformIdentity $paperMaximum 'paper' '26.3' 166
Write-Output 'PASS: parse maximum user-supplied Paper startup identity'
$currentStablePaper = Get-T21PaperEndpoint -PaperEndpoint CurrentStable
if ($currentStablePaper.taskSuffix -cne 'current-stable-Paper' -or
    $currentStablePaper.software -cne 'paper' -or
    $currentStablePaper.minecraftVersion -cne '26.3' -or
    $currentStablePaper.build -ne 194 -or
    $currentStablePaper.sha256 -cne '5fac0b6937b7aecea1b5b40e6a992f7b0802e4271413be35a6913d30df960784') {
    throw 'The T21 current-stable Paper endpoint must remain pinned independently from the user-supplied maximum endpoint.'
}
Write-Output 'PASS: pin current-stable Paper independently from user maximum'

$currentMaximumEndpoints = @(
    [ordered]@{ endpoint='CurrentMaximum'; taskSuffix='current-maximum-Paper'; software='paper'; minecraftVersion='26.3'; build=200; sha256='0e104a19ad818f05266a4571c0a9b0cada241c2016ee64883e5d38435332fa8d' },
    [ordered]@{ endpoint='CurrentMaximumFolia'; taskSuffix='current-maximum-Folia'; software='folia'; minecraftVersion='26.3'; build=3; sha256='0c4cc7dd82c40bd1effa8a9c4cacfad55d41b7dc38377900957c961c2952c022' },
    [ordered]@{ endpoint='CurrentMaximumCanvas'; taskSuffix='current-maximum-Canvas'; software='canvas'; minecraftVersion='26.3'; build=984; sha256='0b94c4cba3e415586dddb69915b5aacf250636c62d058f4651c1ab34eaf265fa' }
)
foreach ($endpointCase in $currentMaximumEndpoints) {
    $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint $endpointCase.endpoint
    if ($endpoint.taskSuffix -cne $endpointCase.taskSuffix -or
        $endpoint.software -cne $endpointCase.software -or
        $endpoint.minecraftVersion -cne $endpointCase.minecraftVersion -or
        $endpoint.build -ne $endpointCase.build -or
        $endpoint.sha256 -cne $endpointCase.sha256) {
        throw "The current T21 maximum endpoint is not pinned exactly: $($endpointCase.endpoint)."
    }
    $report = [ordered]@{
        platform = $endpoint.software
        minecraftVersion = $endpoint.minecraftVersion
        buildNumber = $endpoint.build
        expectedServerSha256 = $endpoint.sha256
    }
    if (-not (Test-T21M04PlatformEndpointReport $report -PlatformEndpoint $endpointCase.endpoint)) {
        throw "The M04 offline auditor must accept the exact current maximum endpoint $($endpointCase.endpoint)."
    }
    Write-Output "PASS: pin and audit exact T21 current maximum endpoint $($endpointCase.endpoint)"
}

$currentStableM04Report = [ordered]@{
    platform = $currentStablePaper.software
    minecraftVersion = $currentStablePaper.minecraftVersion
    buildNumber = $currentStablePaper.build
    expectedServerSha256 = $currentStablePaper.sha256
}
if (-not (Test-T21M04PlatformEndpointReport $currentStableM04Report -PlatformEndpoint CurrentStable)) {
    throw 'The M04 auditor must accept the exact pinned current-stable Paper endpoint.'
}
Write-Output 'PASS: accept exact current-stable Paper identity for M04'
if (Test-T21M04PlatformEndpointReport $currentStableM04Report -PlatformEndpoint Maximum) {
    throw 'The M04 auditor must not accept current-stable Paper evidence as the formal maximum endpoint.'
}
Write-Output 'PASS: keep current-stable Paper separate from the formal maximum in M04'
$currentStableM04Startup = '[ServerMain/INFO]: [bootstrap] Loading Paper 26.3-194-main@fixture (2026-10-16T00:00:00Z) for Minecraft 26.3'
if (-not (Test-T21M04PlatformStartupIdentity $currentStableM04Startup -PlatformEndpoint CurrentStable)) {
    throw 'The M04 auditor must accept the exact current-stable Paper startup identity.'
}
Write-Output 'PASS: accept exact current-stable Paper startup identity for M04'
if (Test-T21M04PlatformStartupIdentity ($currentStableM04Startup.Replace('26.3-194', '26.3-166')) -PlatformEndpoint CurrentStable) {
    throw 'The M04 auditor must reject current-stable startup evidence with the formal maximum build.'
}
Write-Output 'PASS: reject a non-current-stable Paper build under the current-stable M04 selector'

$defaultRuntimeReportsRoot = Resolve-T21RuntimeReportsRoot 'C:\syncmoney' ''
if ($defaultRuntimeReportsRoot -cne [IO.Path]::GetFullPath('C:\syncmoney\build\reports\foundation\T21-sql-runtime')) {
    throw 'The T21 runtime reports root must preserve the repository-local default when no override is supplied.'
}
Write-Output 'PASS: preserve the repository-local T21 reports root by default'
$alternateRuntimeReportsRoot = Resolve-T21RuntimeReportsRoot 'C:\syncmoney' 'D:\syncmoney-t21-reports'
if ($alternateRuntimeReportsRoot -cne [IO.Path]::GetFullPath('D:\syncmoney-t21-reports')) {
    throw 'The T21 runtime reports root override did not resolve to its exact absolute destination.'
}
Write-Output 'PASS: resolve an explicit absolute T21 reports root'
Assert-ThrowsContaining 'reject a relative T21 reports root' {
    Resolve-T21RuntimeReportsRoot 'C:\syncmoney' 'reports'
} 'must be an absolute path'
Assert-ThrowsContaining 'reject a drive-relative T21 reports root' {
    Resolve-T21RuntimeReportsRoot 'C:\syncmoney' 'D:syncmoney-t21-reports'
} 'must be an absolute path'
Assert-ThrowsContaining 'reject a root-relative T21 reports root' {
    Resolve-T21RuntimeReportsRoot 'C:\syncmoney' '\syncmoney-t21-reports'
} 'must be an absolute path'
Assert-ThrowsContaining 'reject a filesystem root as the T21 reports root' {
    Resolve-T21RuntimeReportsRoot 'C:\syncmoney' 'D:\'
} 'cannot be a filesystem root'

$runtimePlatformEndpoints = @(
    [ordered]@{ endpoint='MinimumFolia'; taskSuffix='minimum-Folia'; software='folia'; minecraftVersion='1.21.6'; build=6; sha256='917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234' },
    [ordered]@{ endpoint='MaximumFolia'; taskSuffix='maximum-Folia'; software='folia'; minecraftVersion='26.2'; build=7; sha256='128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' },
    [ordered]@{ endpoint='MinimumCanvas'; taskSuffix='minimum-Canvas'; software='canvas'; minecraftVersion='1.21.8'; build=603; sha256='6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b' },
    [ordered]@{ endpoint='MaximumCanvas'; taskSuffix='maximum-Canvas'; software='canvas'; minecraftVersion='26.3'; build=972; sha256='aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' },
    [ordered]@{ endpoint='CurrentMaximumFolia'; taskSuffix='current-maximum-Folia'; software='folia'; minecraftVersion='26.3'; build=3; sha256='0c4cc7dd82c40bd1effa8a9c4cacfad55d41b7dc38377900957c961c2952c022' },
    [ordered]@{ endpoint='CurrentMaximumCanvas'; taskSuffix='current-maximum-Canvas'; software='canvas'; minecraftVersion='26.3'; build=984; sha256='0b94c4cba3e415586dddb69915b5aacf250636c62d058f4651c1ab34eaf265fa' }
)
foreach ($endpointCase in $runtimePlatformEndpoints) {
    $endpoint = Get-T21PlatformEndpoint -PlatformEndpoint $endpointCase.endpoint
    if ($endpoint.taskSuffix -cne $endpointCase.taskSuffix -or
        $endpoint.software -cne $endpointCase.software -or
        $endpoint.minecraftVersion -cne $endpointCase.minecraftVersion -or
        $endpoint.build -ne $endpointCase.build -or
        $endpoint.sha256 -cne $endpointCase.sha256) {
        throw "The T21 SQL platform endpoint is not pinned exactly: $($endpointCase.endpoint)."
    }
    Write-Output "PASS: pin exact T21 SQL platform endpoint $($endpointCase.endpoint)"
}

$m04MinimumPaperReport = [ordered]@{
    platform = 'paper'
    minecraftVersion = '1.21.6'
    buildNumber = 48
    expectedServerSha256 = '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214'
}
if (-not (Test-T21M04PaperEndpointReport $m04MinimumPaperReport -PaperEndpoint Minimum)) {
    throw 'The M04 offline auditor must continue accepting the exact user-pinned minimum-Paper endpoint.'
}
Write-Output 'PASS: accept the exact M04 minimum-Paper endpoint pin'

$m04MaximumPaperReport = [ordered]@{
    platform = 'paper'
    minecraftVersion = '26.3'
    buildNumber = 166
    expectedServerSha256 = 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd'
}
if (-not (Test-T21M04PaperEndpointReport $m04MaximumPaperReport -PaperEndpoint Maximum)) {
    throw 'The M04 offline auditor must accept the exact user-pinned maximum-Paper endpoint.'
}
Write-Output 'PASS: accept the exact M04 maximum-Paper endpoint pin'

if (Test-T21M04PaperEndpointReport $m04MaximumPaperReport -PaperEndpoint Minimum) {
    throw 'The M04 offline auditor must reject maximum-Paper evidence under the minimum-Paper pin.'
}
Write-Output 'PASS: reject maximum-Paper evidence under the minimum-Paper pin'

$m04WrongPaperHashReport = [ordered]@{
    platform = $m04MaximumPaperReport.platform
    minecraftVersion = $m04MaximumPaperReport.minecraftVersion
    buildNumber = $m04MaximumPaperReport.buildNumber
    expectedServerSha256 = $m04MaximumPaperReport.expectedServerSha256
}
$m04WrongPaperHashReport.expectedServerSha256 = '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214'
if (Test-T21M04PaperEndpointReport $m04WrongPaperHashReport -PaperEndpoint Maximum) {
    throw 'The M04 offline auditor must reject a maximum-Paper report with the minimum-Paper artifact hash.'
}
Write-Output 'PASS: reject maximum-Paper endpoint with a mismatched server artifact pin'

$m04UnpinnedPaperReport = [ordered]@{
    platform = $m04MaximumPaperReport.platform
    minecraftVersion = $m04MaximumPaperReport.minecraftVersion
    buildNumber = $m04MaximumPaperReport.buildNumber
    expectedServerSha256 = $m04MaximumPaperReport.expectedServerSha256
}
$m04UnpinnedPaperReport.buildNumber = 178
$m04UnpinnedPaperReport.expectedServerSha256 = '5dd0c8fb20a96eb7c98f3c4b1c890e99ef6db56fd689a2cf06975a3471fda757'
if (Test-T21M04PaperEndpointReport $m04UnpinnedPaperReport -PaperEndpoint Maximum) {
    throw 'The M04 offline auditor must not treat supplemental current-stable Paper as the user-pinned maximum.'
}
Write-Output 'PASS: reject supplemental current-stable Paper under the maximum-Paper pin'

foreach ($endpointCase in $runtimePlatformEndpoints) {
    $m04PlatformReport = [ordered]@{
        platform = $endpointCase.software
        minecraftVersion = $endpointCase.minecraftVersion
        buildNumber = $endpointCase.build
        expectedServerSha256 = $endpointCase.sha256
    }
    if (-not (Test-T21M04PlatformEndpointReport $m04PlatformReport -PlatformEndpoint $endpointCase.endpoint)) {
        throw "The M04 auditor must accept the exact user-pinned $($endpointCase.endpoint) endpoint."
    }
    Write-Output "PASS: accept exact M04 $($endpointCase.endpoint) platform endpoint pin"

    $softwareName = switch ($endpointCase.software) { 'folia' { 'Folia' } 'canvas' { 'Canvas' } default { 'Paper' } }
    $startupLog = "[ServerMain/INFO]: [bootstrap] Loading $softwareName $($endpointCase.minecraftVersion)-$($endpointCase.build)-main@fixture (2026-10-16T00:00:00Z) for Minecraft $($endpointCase.minecraftVersion)"
    if (-not (Test-T21M04PlatformStartupIdentity $startupLog -PlatformEndpoint $endpointCase.endpoint)) {
        throw "The M04 auditor must accept the exact $($endpointCase.endpoint) identity from its startup log."
    }
    Write-Output "PASS: accept exact M04 $($endpointCase.endpoint) startup identity"
}

$m04MinimumFoliaReport = [ordered]@{
    platform = 'folia'
    minecraftVersion = '1.21.6'
    buildNumber = 6
    expectedServerSha256 = '917a8abaa542c1d0ef0e969693e227a7bfe1c42e671aaa806309e00a64efc234'
}
if (Test-T21M04PlatformEndpointReport $m04MinimumFoliaReport -PlatformEndpoint Minimum) {
    throw 'The M04 auditor must not accept minimum-Folia evidence under the minimum-Paper endpoint.'
}
Write-Output 'PASS: reject minimum Folia evidence under the minimum-Paper endpoint'

$m04MinimumFoliaStartup = '[ServerMain/INFO]: [bootstrap] Loading Folia 1.21.6-6-main@fixture (2026-10-16T00:00:00Z) for Minecraft 1.21.6'
if (Test-T21M04PlatformStartupIdentity $m04MinimumFoliaStartup -PlatformEndpoint Minimum) {
    throw 'The M04 auditor must not accept minimum-Folia startup identity under the minimum-Paper endpoint.'
}
Write-Output 'PASS: reject minimum Folia startup identity under the minimum-Paper endpoint'

if (Test-T21M04PlatformStartupIdentity ($m04MinimumFoliaStartup.Replace('1.21.6-6', '1.21.6-7')) -PlatformEndpoint MinimumFolia) {
    throw 'The M04 auditor must reject a Folia startup log with the wrong server build.'
}
Write-Output 'PASS: reject Folia startup identity with an unpinned build number'

$m04WrongFoliaHashReport = [ordered]@{
    platform = $m04MinimumFoliaReport.platform
    minecraftVersion = $m04MinimumFoliaReport.minecraftVersion
    buildNumber = $m04MinimumFoliaReport.buildNumber
    expectedServerSha256 = '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214'
}
if (Test-T21M04PlatformEndpointReport $m04WrongFoliaHashReport -PlatformEndpoint MinimumFolia) {
    throw 'The M04 auditor must reject minimum-Folia identity with a different platform artifact hash.'
}
Write-Output 'PASS: reject minimum Folia endpoint with a mismatched server artifact pin'

function New-T21PlatformRuntimeReportFixture([Collections.IDictionary]$report, [Collections.IDictionary]$endpointCase, [string]$databaseProduct) {
    $report.schemaVersion = 4
    $report.task = "T21-M05-SYNC-$databaseProduct-$($endpointCase.taskSuffix)"
    $report.apiConsumer = [ordered]@{ sha256 = $apiConsumerSha }
    $report.server.software = $endpointCase.software
    $report.server.minecraftVersion = $endpointCase.minecraftVersion
    $report.server.build = $endpointCase.build
    $report.server.sha256 = $endpointCase.sha256
    foreach ($startup in $report.startupObservations) {
        $startup.runtimeIdentity.software = $endpointCase.software
        $startup.runtimeIdentity.runtimeVersion = $endpointCase.minecraftVersion
        $startup.runtimeIdentity.minecraftVersion = $endpointCase.minecraftVersion
        $startup.runtimeIdentity.build = $endpointCase.build
        $startup.apiConsumerMarker = 'PASS'
    }

    $platformLogs = [Collections.Generic.List[object]]::new()
    $softwareLabel = switch ($endpointCase.software) { 'folia' { 'Folia' } 'canvas' { 'Canvas' } default { 'Paper' } }
    $identityLine = "[ServerMain/INFO]: [bootstrap] Loading $softwareLabel $($endpointCase.minecraftVersion)-$($endpointCase.build)-main@fixture (2026-10-13T00:00:00Z) for Minecraft $($endpointCase.minecraftVersion)"
    foreach ($stage in @('candidate-write', 'candidate-restart-read')) {
        $sourceRelativePath = "logs\paper-$stage.txt"
        $targetRelativePath = "logs\platform-$stage.txt"
        $sourcePath = Join-Path $report.runDirectory $sourceRelativePath
        $targetPath = Join-Path $report.runDirectory $targetRelativePath
        $sourceContent = [IO.File]::ReadAllText($sourcePath)
        $apiConsumerLine = '[Server thread/INFO]: [SyncmoneyApiConsumerFixture] API CLASSLOADER PASS provider=Syncmoney'
        [IO.File]::WriteAllText($targetPath, $identityLine + [Environment]::NewLine + $apiConsumerLine + [Environment]::NewLine + $sourceContent.TrimStart([char[]]@("`r", "`n")), [Text.UTF8Encoding]::new($false))
        $targetFile = Get-Item -LiteralPath $targetPath
        $targetHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $platformLogs.Add([ordered]@{ stage=$stage; path=$targetRelativePath; bytes=[long]$targetFile.Length; sha256=$targetHash })
        $report.logs[$targetRelativePath] = [ordered]@{ bytes=[long]$targetFile.Length; sha256=$targetHash }
    }
    $null = $report.Remove('paperLogs')
    $report.platformLogs = @($platformLogs)
    return $report
}

function Update-T21PlatformRuntimeReportLogHashes([Collections.IDictionary]$report) {
    foreach ($runtimeLog in $report.platformLogs) {
        $logPath = [string]$runtimeLog.path
        $fullLogPath = Join-Path $report.runDirectory $logPath
        $logFile = Get-Item -LiteralPath $fullLogPath
        $logSha256 = (Get-FileHash -LiteralPath $fullLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $runtimeLog.bytes = [long]$logFile.Length
        $runtimeLog.sha256 = $logSha256
        $report.logs[$logPath] = [ordered]@{ bytes=[long]$logFile.Length; sha256=$logSha256 }
    }
    return $report
}

function Remove-T21PlatformRuntimeReportFixtureLogs {
    foreach ($stage in @('candidate-write', 'candidate-restart-read')) {
        Remove-Item -LiteralPath (Join-Path $mysqlRuntimeTestRoot "logs\platform-$stage.txt") -Force -ErrorAction SilentlyContinue
    }
}

$foliaMinimum = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Folia 1.21.6-6-main@abc (2025-06-30T09:48:37Z) for Minecraft 1.21.6'
Assert-T21PlatformIdentity $foliaMinimum 'folia' '1.21.6' 6
Write-Output 'PASS: parse minimum Folia startup identity'
$folia = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Folia 26.2-7-main@abc (2026-10-10T00:00:00Z) for Minecraft 26.2'
Assert-T21PlatformIdentity $folia 'folia' '26.2' 7
Write-Output 'PASS: parse maximum Folia startup identity'

$canvas = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Canvas 1.21.8-603-main@abc (2025-08-10T00:00:00Z) for Minecraft 1.21.8'
Assert-T21PlatformIdentity $canvas 'canvas' '1.21.8' 603
Write-Output 'PASS: parse minimum Canvas startup identity'
$canvasMaximum = Get-T21PlatformIdentity '[ServerMain/INFO]: [bootstrap] Loading Canvas 26.3-972-main@abc (2026-10-10T00:00:00Z) for Minecraft 26.3'
Assert-T21PlatformIdentity $canvasMaximum 'canvas' '26.3' 972
Write-Output 'PASS: parse maximum Canvas startup identity'

Assert-ThrowsContaining 'reject a runtime that differs from the requested endpoint' {
    Assert-T21PlatformIdentity $paper 'paper' '26.3' 166
} 'runtime identity mismatch'
Assert-ThrowsContaining 'reject a startup log with no supported runtime identity' {
    Get-T21PlatformIdentity '[Server thread/INFO]: Done (7.9s)! For help, type "help"'
} 'Could not derive'
$conflictingLog = @(
    '[ServerMain/INFO]: [bootstrap] Loading Paper 1.21.6-48-main@abc for Minecraft 1.21.6',
    '[ServerMain/INFO]: [bootstrap] Loading Paper 26.3-166-main@def for Minecraft 26.3'
) -join "`n"
Assert-ThrowsContaining 'reject conflicting runtime identities in one startup log' {
    Get-T21PlatformIdentity $conflictingLog
} 'conflicting'

$shutdownLog = @(
    '[ERROR]: DB writer stopped with 1 stale transfer',
    '[ERROR]: Failed to flush 3 audit records',
    '[ERROR]: DB writer drain timed out; pending writes require recovery',
    '[ERROR]: DB writer is still using storage after shutdown deadline; database and Redis dependencies will remain open until that writer exits',
    '[ERROR]: Failed while draining database writer',
    '[ERROR]: Shutdown step failed: database writer',
    '[ERROR]: CRITICAL HEALTH: accepted economy writes remain owned'
) -join "`n"
$shutdownMarkers = @(Get-T21ShutdownFailureMarkers $shutdownLog)
if ($shutdownMarkers.Count -ne 7 -or
    -not $shutdownMarkers[0].Contains('1 stale transfer') -or
    -not $shutdownMarkers[1].Contains('3 audit records') -or
    -not ($shutdownMarkers -match 'DB writer drain timed out').Count -or
    -not ($shutdownMarkers -match 'DB writer is still using storage after shutdown deadline').Count -or
    -not ($shutdownMarkers -match 'Failed while draining database writer').Count -or
    -not ($shutdownMarkers -match 'Shutdown step failed').Count -or
    -not ($shutdownMarkers -match 'CRITICAL HEALTH').Count) {
    throw 'Database-writer, audit, or critical shutdown failure markers were not all detected.'
}
Write-Output 'PASS: detect database-writer, audit, and critical shutdown failure markers'

$healthyMarkers = @(Get-T21ShutdownFailureMarkers 'Syncmoney disabled.')
if ($healthyMarkers.Count -ne 0) { throw 'Healthy shutdown was classified as failed.' }
Write-Output 'PASS: accept a healthy shutdown log'

$runtimeObservations = @(
    [ordered]@{ serverName = 'node-a'; stage = 'baseline-1.3.2-node-1' },
    [ordered]@{ serverName = 'node-a'; stage = 'upgrade-1.4-node-1' },
    [ordered]@{ serverName = 'node-a'; stage = 'rollback-1.3.2-node-1' },
    [ordered]@{ serverName = 'node-b'; stage = 'baseline-1.3.2-node-2' },
    [ordered]@{ serverName = 'node-b'; stage = 'upgrade-1.4-node-2' },
    [ordered]@{ serverName = 'node-b'; stage = 'rollback-1.3.2-node-2' }
)
if (-not (Test-T21RuntimeObservationCoverage $runtimeObservations @('node-a', 'node-b'))) {
    throw 'Valid three-start-per-node runtime observations were rejected.'
}
Write-Output 'PASS: accept one startup identity observation for each node and lifecycle version'

$missingRollbackObservation = @($runtimeObservations | Where-Object { $_.stage -cne 'rollback-1.3.2-node-2' })
if (Test-T21RuntimeObservationCoverage $missingRollbackObservation @('node-a', 'node-b')) {
    throw 'A missing rollback startup identity observation was accepted.'
}
Write-Output 'PASS: reject a missing startup identity observation'

$duplicateNodeObservation = @($runtimeObservations | ForEach-Object {
    if ($_.stage -ceq 'rollback-1.3.2-node-2') {
        [ordered]@{ serverName = 'node-a'; stage = $_.stage }
    } else { $_ }
})
if (Test-T21RuntimeObservationCoverage $duplicateNodeObservation @('node-a', 'node-b')) {
    throw 'Startup observations assigned to the wrong node were accepted.'
}
Write-Output 'PASS: reject startup observations assigned to the wrong node'

if (-not (Test-T21StageObservationCoverage $runtimeObservations @('node-a', 'node-b'))) {
    throw 'Valid one-stop-per-node lifecycle stage observations were rejected.'
}
Write-Output 'PASS: accept exact baseline, candidate, and rollback stage coverage'

$mislabeledShutdownObservations = @($runtimeObservations | ForEach-Object {
    if ($_.stage -ceq 'baseline-1.3.2-node-2') {
        [ordered]@{ serverName = $_.serverName; stage = $_.stage + '-server-identity-shared-state-read' }
    } else { $_ }
})
if (Test-T21StageObservationCoverage $mislabeledShutdownObservations @('node-a', 'node-b')) {
    throw 'A shutdown observation with a contaminated lifecycle stage label was accepted.'
}
Write-Output 'PASS: reject contaminated shutdown stage labels'

$m04StageRecords = @(
    [ordered]@{ stage = 'baseline-1.3.2'; startExitCode = 0 },
    [ordered]@{ stage = 'baseline-1.3.2'; stopExitCode = 0 },
    [ordered]@{ stage = 'upgrade-1.4'; startExitCode = 0 },
    [ordered]@{ stage = 'upgrade-1.4'; stopExitCode = 0 },
    [ordered]@{ stage = 'rollback-1.3.2'; startExitCode = 0 },
    [ordered]@{ stage = 'rollback-1.3.2'; stopExitCode = 0 }
)
if (-not (Test-T21M04StageRecordChronology $m04StageRecords)) {
    throw 'The exact baseline, candidate-upgrade, and rollback start/stop sequence was rejected.'
}
Write-Output 'PASS: accept M04 baseline, upgrade, and rollback start/stop chronology'

$reorderedM04StageRecords = @($m04StageRecords)
$temporaryStageRecord = $reorderedM04StageRecords[0]
$reorderedM04StageRecords[0] = $reorderedM04StageRecords[1]
$reorderedM04StageRecords[1] = $temporaryStageRecord
if (Test-T21M04StageRecordChronology $reorderedM04StageRecords) {
    throw 'M04 stage records reordered to stop baseline before it starts were accepted.'
}
Write-Output 'PASS: reject reordered M04 lifecycle stage records'

$incompleteM04StageRecords = @($m04StageRecords | Select-Object -Skip 1)
if (Test-T21M04StageRecordChronology $incompleteM04StageRecords) {
    throw 'M04 chronology without the baseline start record was accepted.'
}
Write-Output 'PASS: reject incomplete M04 lifecycle chronology'

$timeoutHandoffStages = @('baseline-1.3.2', 'upgrade-1.4', 'recovery-1.4', 'rollback-1.3.2')
$timeoutHandoffRecords = @(
    [ordered]@{ stage = 'baseline-1.3.2'; startExitCode = 0 },
    [ordered]@{ stage = 'baseline-1.3.2'; stopExitCode = 0 },
    [ordered]@{ stage = 'upgrade-1.4'; startExitCode = 0 },
    [ordered]@{ stage = 'upgrade-1.4'; stopExitCode = 0 },
    [ordered]@{ stage = 'recovery-1.4'; startExitCode = 0 },
    [ordered]@{ stage = 'recovery-1.4'; stopExitCode = 0 },
    [ordered]@{ stage = 'rollback-1.3.2'; startExitCode = 0 },
    [ordered]@{ stage = 'rollback-1.3.2'; stopExitCode = 0 }
)
if (-not (Test-T21M04StageRecordChronology $timeoutHandoffRecords $timeoutHandoffStages)) {
    throw 'The exact baseline, timeout candidate, recovery candidate, and rollback sequence was rejected.'
}
Write-Output 'PASS: accept M04 timeout-handoff recovery chronology'

$reorderedTimeoutHandoffRecords = @($timeoutHandoffRecords)
$temporaryTimeoutStageRecord = $reorderedTimeoutHandoffRecords[3]
$reorderedTimeoutHandoffRecords[3] = $reorderedTimeoutHandoffRecords[4]
$reorderedTimeoutHandoffRecords[4] = $temporaryTimeoutStageRecord
if (Test-T21M04StageRecordChronology $reorderedTimeoutHandoffRecords $timeoutHandoffStages) {
    throw 'M04 timeout-handoff recovery before the first candidate stop was accepted.'
}
Write-Output 'PASS: reject an M04 timeout-handoff recovery stage before the handoff stop'

$acceptanceBuildArtifact = [ordered]@{
    name = 'acceptance'
    path = 'build/acceptance/SyncmoneyAcceptance.jar'
    bytes = 4096L
    sha256 = ('a' * 64)
}
$acceptanceRuntimeArtifact = [ordered]@{
    name = 'acceptance-runtime'
    path = '.plugdev/run/plugins/SyncmoneyAcceptance.jar'
    bytes = 4096L
    sha256 = ('a' * 64)
}
if (-not (Test-T21M04RuntimeArtifactCopy $acceptanceBuildArtifact $acceptanceRuntimeArtifact 'acceptance' 'build/acceptance/SyncmoneyAcceptance.jar' 'acceptance-runtime' '.plugdev/run/plugins/SyncmoneyAcceptance.jar')) {
    throw 'An exact M04 Acceptance runtime copy was rejected.'
}
Write-Output 'PASS: accept an M04 runtime harness copy matching its staged artifact'

$apiConsumerBuildArtifact = [ordered]@{
    name = 'api-consumer'
    path = 'build/acceptance/SyncmoneyApiConsumer.jar'
    bytes = 8192L
    sha256 = ('b' * 64)
}
$apiConsumerRuntimeArtifact = [ordered]@{
    name = 'api-consumer-runtime'
    path = '.plugdev/run/plugins/SyncmoneyApiConsumer.jar'
    bytes = 8192L
    sha256 = ('b' * 64)
}
if (-not (Test-T21M04RuntimeArtifactCopy $apiConsumerBuildArtifact $apiConsumerRuntimeArtifact 'api-consumer' 'build/acceptance/SyncmoneyApiConsumer.jar' 'api-consumer-runtime' '.plugdev/run/plugins/SyncmoneyApiConsumer.jar')) {
    throw 'An exact M04 API consumer runtime copy was rejected.'
}
Write-Output 'PASS: accept an M04 API consumer runtime copy matching its staged artifact'

$mismatchedAcceptanceRuntime = [ordered]@{
    name = 'acceptance-runtime'
    path = '.plugdev/run/plugins/SyncmoneyAcceptance.jar'
    bytes = 4096L
    sha256 = ('c' * 64)
}
if (Test-T21M04RuntimeArtifactCopy $acceptanceBuildArtifact $mismatchedAcceptanceRuntime 'acceptance' 'build/acceptance/SyncmoneyAcceptance.jar' 'acceptance-runtime' '.plugdev/run/plugins/SyncmoneyAcceptance.jar') {
    throw 'M04 accepted an Acceptance runtime copy with a mismatched SHA-256.'
}
Write-Output 'PASS: reject an M04 runtime harness copy with a mismatched SHA-256'

$undersizedAcceptanceRuntime = [ordered]@{
    name = 'acceptance-runtime'
    path = '.plugdev/run/plugins/SyncmoneyAcceptance.jar'
    bytes = 4095L
    sha256 = ('a' * 64)
}
if (Test-T21M04RuntimeArtifactCopy $acceptanceBuildArtifact $undersizedAcceptanceRuntime 'acceptance' 'build/acceptance/SyncmoneyAcceptance.jar' 'acceptance-runtime' '.plugdev/run/plugins/SyncmoneyAcceptance.jar') {
    throw 'M04 accepted an Acceptance runtime copy with a mismatched byte count.'
}
Write-Output 'PASS: reject an M04 runtime harness copy with a mismatched byte count'

$mispathedApiConsumerRuntime = [ordered]@{
    name = 'api-consumer-runtime'
    path = '.plugdev/run/plugins/UnexpectedApiConsumer.jar'
    bytes = 8192L
    sha256 = ('b' * 64)
}
if (Test-T21M04RuntimeArtifactCopy $apiConsumerBuildArtifact $mispathedApiConsumerRuntime 'api-consumer' 'build/acceptance/SyncmoneyApiConsumer.jar' 'api-consumer-runtime' '.plugdev/run/plugins/SyncmoneyApiConsumer.jar') {
    throw 'M04 accepted an API consumer runtime copy at an unexpected path.'
}
Write-Output 'PASS: reject an M04 runtime harness copy at an unexpected path'

if (Test-T21M04RuntimeArtifactCopy $acceptanceBuildArtifact $null 'acceptance' 'build/acceptance/SyncmoneyAcceptance.jar' 'acceptance-runtime' '.plugdev/run/plugins/SyncmoneyAcceptance.jar') {
    throw 'M04 accepted a missing Acceptance runtime artifact.'
}
Write-Output 'PASS: reject a missing M04 runtime harness artifact'

$mispathedAcceptanceBuildArtifact = [ordered]@{
    name = 'acceptance'
    path = '.plugdev/run/plugins/SyncmoneyAcceptance.jar'
    bytes = 4096L
    sha256 = ('a' * 64)
}
if (Test-T21M04RuntimeArtifactCopy $mispathedAcceptanceBuildArtifact $acceptanceRuntimeArtifact 'acceptance' 'build/acceptance/SyncmoneyAcceptance.jar' 'acceptance-runtime' '.plugdev/run/plugins/SyncmoneyAcceptance.jar') {
    throw 'M04 accepted the Acceptance runtime path as the staged build artifact path.'
}
Write-Output 'PASS: reject an Acceptance artifact source path that aliases its runtime copy'

$mispathedApiConsumerBuildArtifact = [ordered]@{
    name = 'api-consumer'
    path = '.plugdev/run/plugins/SyncmoneyApiConsumer.jar'
    bytes = 8192L
    sha256 = ('b' * 64)
}
if (Test-T21M04RuntimeArtifactCopy $mispathedApiConsumerBuildArtifact $apiConsumerRuntimeArtifact 'api-consumer' 'build/acceptance/SyncmoneyApiConsumer.jar' 'api-consumer-runtime' '.plugdev/run/plugins/SyncmoneyApiConsumer.jar') {
    throw 'M04 accepted the API consumer runtime path as the staged build artifact path.'
}
Write-Output 'PASS: reject an API consumer source path that aliases its runtime copy'

$misassignedStageObservation = @($runtimeObservations | ForEach-Object {
    if ($_.stage -ceq 'rollback-1.3.2-node-2') {
        [ordered]@{ serverName = 'node-a'; stage = $_.stage }
    } else { $_ }
})
if (Test-T21StageObservationCoverage $misassignedStageObservation @('node-a', 'node-b')) {
    throw 'A lifecycle stage observation assigned to the wrong node was accepted.'
}
Write-Output 'PASS: reject lifecycle stage observations assigned to the wrong node'

$observationWithoutNode = @($runtimeObservations | ForEach-Object {
    [ordered]@{ stage = $_.stage }
})
if (Test-T21StageObservationCoverage $observationWithoutNode @('node-a', 'node-b')) {
    throw 'Lifecycle observations without node identities were accepted.'
}
Write-Output 'PASS: reject lifecycle observations without node identities'

$runtimeArtifactObservations = @($runtimeObservations | ForEach-Object {
    [ordered]@{
        serverName = $_.serverName
        stage = $_.stage
        syncmoneyArtifactPin = [ordered]@{ matchesExpectedPin = $true }
    }
})
if (-not (Test-T21RuntimeArtifactPinCoverage $runtimeArtifactObservations @('node-a', 'node-b'))) {
    throw 'Exact lifecycle observations with pinned runtime artifacts were rejected.'
}
Write-Output 'PASS: accept pinned Syncmoney artifacts across exact node lifecycle stages'

$runtimeArtifactWithoutPin = @($runtimeArtifactObservations | ForEach-Object {
    $observation = [ordered]@{ serverName = $_.serverName; stage = $_.stage }
    if ($_.stage -cne 'rollback-1.3.2-node-2') { $observation['syncmoneyArtifactPin'] = $_.syncmoneyArtifactPin }
    $observation
})
if (Test-T21RuntimeArtifactPinCoverage $runtimeArtifactWithoutPin @('node-a', 'node-b')) {
    throw 'A runtime artifact observation without its pin result was accepted.'
}
Write-Output 'PASS: reject runtime artifact observations missing pin results'

$runtimeArtifactWithMismatch = @($runtimeArtifactObservations | ForEach-Object {
    $pin = if ($_.stage -ceq 'upgrade-1.4-node-2') { [ordered]@{ matchesExpectedPin = $false } } else { $_.syncmoneyArtifactPin }
    [ordered]@{ serverName = $_.serverName; stage = $_.stage; syncmoneyArtifactPin = $pin }
})
if (Test-T21RuntimeArtifactPinCoverage $runtimeArtifactWithMismatch @('node-a', 'node-b')) {
    throw 'A mismatched runtime artifact pin was accepted by lifecycle coverage.'
}
Write-Output 'PASS: reject runtime artifact pin mismatches in lifecycle coverage'

$serverInputPath = [IO.Path]::GetFullPath((Join-Path $env:TEMP 't21-provenance\paperclip-bootstrap.jar'))
$serverRunRoot = [IO.Path]::GetFullPath((Join-Path $env:TEMP 't21-provenance\node-a'))
$serverRunDirectory = Join-Path $serverRunRoot '.plugdev\run'
$serverProfilePath = Join-Path $serverRunRoot '.plugdev\profile.yml'
$bootstrapPath = Join-Path $serverRunDirectory 'server.jar'
$runtimeJarPath = Join-Path $serverRunDirectory 'versions\26.3\paper-26.3.jar'
$serverInputSha = 'a' * 64
$serverRuntimeSha = 'b' * 64
$serverProvenanceStages = @('baseline-1.3.2-node-1', 'upgrade-1.4-node-1', 'rollback-1.3.2-node-1')
$serverProvenanceObservations = @($serverProvenanceStages | ForEach-Object {
    [ordered]@{
        serverName = 'node-a'
        stage = $_
        serverJarProvenance = [ordered]@{
            runDirectory = $serverRunDirectory
            input = [ordered]@{ path = $serverInputPath; bytes = 54212025; sha256 = $serverInputSha }
            profile = [ordered]@{
                path = $serverProfilePath; server = 'custom'; version = '26.3'; serverJar = $serverInputPath
            }
            plugDev = [ordered]@{
                markerPath = Join-Path $serverRunDirectory '.plugdev-server-jar.json'
                source = $serverInputPath; preparedSource = $serverInputPath; sourceBytes = 54212025
            }
            bootstrap = [ordered]@{
                path = $bootstrapPath; runRelativePath = '.plugdev/run/server.jar'; sha256 = $serverInputSha
                linkType = 'SYMBOLIC_LINK'; linkTarget = $serverInputPath
                manifest = [ordered]@{ mainClass = 'io.papermc.paperclip.Main' }
                launchCommand = [ordered]@{ observed = $true; processId = 1234; argumentPath = $bootstrapPath }
            }
            runtimeOutput = [ordered]@{
                path = $runtimeJarPath; runRelativePath = '.plugdev/run/versions/26.3/paper-26.3.jar'
                sha256 = $serverRuntimeSha
                manifest = [ordered]@{
                    mainClass = 'org.bukkit.craftbukkit.Main'; implementationTitle = 'Paper'
                    implementationVersion = '26.3-166-cc7b98a'; buildNumber = '166'
                }
            }
        }
    }
})
if (-not (Test-T21ServerJarProvenanceCoverage $serverProvenanceObservations @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166)) {
    throw 'A pinned Paperclip input linked to a distinct, correctly identified Paper runtime JAR was rejected.'
}
Write-Output 'PASS: accept end-to-end server JAR provenance with distinct bootstrap and runtime hashes'

$wrongServerInputPath = @($serverProvenanceObservations | ForEach-Object {
    $copy = [ordered]@{ serverName = $_.serverName; stage = $_.stage; serverJarProvenance = $_.serverJarProvenance }
    if ($_.stage -ceq 'upgrade-1.4-node-1') {
        $provenance = [ordered]@{}
        foreach ($key in $_.serverJarProvenance.Keys) { $provenance[$key] = $_.serverJarProvenance[$key] }
        $input = [ordered]@{}
        foreach ($key in $provenance.input.Keys) { $input[$key] = $provenance.input[$key] }
        $input.path = $serverInputPath + '.wrong'
        $provenance.input = $input
        $copy.serverJarProvenance = $provenance
    }
    $copy
})
if (Test-T21ServerJarProvenanceCoverage $wrongServerInputPath @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166) {
    throw 'A runtime stage with a different pinned input path was accepted.'
}
Write-Output 'PASS: reject a mismatched pinned server input path'

$wrongServerBootstrapHash = @($serverProvenanceObservations | ForEach-Object {
    $copy = [ordered]@{ serverName = $_.serverName; stage = $_.stage; serverJarProvenance = $_.serverJarProvenance }
    if ($_.stage -ceq 'upgrade-1.4-node-1') {
        $provenance = [ordered]@{}
        foreach ($key in $_.serverJarProvenance.Keys) { $provenance[$key] = $_.serverJarProvenance[$key] }
        $bootstrap = [ordered]@{}
        foreach ($key in $provenance.bootstrap.Keys) { $bootstrap[$key] = $provenance.bootstrap[$key] }
        $bootstrap.sha256 = 'c' * 64
        $provenance.bootstrap = $bootstrap
        $copy.serverJarProvenance = $provenance
    }
    $copy
})
if (Test-T21ServerJarProvenanceCoverage $wrongServerBootstrapHash @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166) {
    throw 'A bootstrap JAR with a different SHA-256 from the pinned input was accepted.'
}
Write-Output 'PASS: reject a bootstrap hash that differs from the pinned input'

$wrongPaperclipOutputHash = @($serverProvenanceObservations | ForEach-Object {
    $copy = [ordered]@{ serverName = $_.serverName; stage = $_.stage; serverJarProvenance = $_.serverJarProvenance }
    if ($_.stage -ceq 'upgrade-1.4-node-1') {
        $provenance = [ordered]@{}
        foreach ($key in $_.serverJarProvenance.Keys) { $provenance[$key] = $_.serverJarProvenance[$key] }
        $runtimeOutput = [ordered]@{}
        foreach ($key in $provenance.runtimeOutput.Keys) { $runtimeOutput[$key] = $provenance.runtimeOutput[$key] }
        $runtimeOutput.sha256 = 'not-a-sha256'
        $provenance.runtimeOutput = $runtimeOutput
        $copy.serverJarProvenance = $provenance
    }
    $copy
})
if (Test-T21ServerJarProvenanceCoverage $wrongPaperclipOutputHash @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166) {
    throw 'A runtime JAR without its independently recorded output hash was accepted.'
}
Write-Output 'PASS: validate the runtime output hash independently from the bootstrap hash'

$wrongPaperclipRuntimeIdentity = @($serverProvenanceObservations | ForEach-Object {
    $copy = [ordered]@{ serverName = $_.serverName; stage = $_.stage; serverJarProvenance = $_.serverJarProvenance }
    if ($_.stage -ceq 'rollback-1.3.2-node-1') {
        $provenance = [ordered]@{}
        foreach ($key in $_.serverJarProvenance.Keys) { $provenance[$key] = $_.serverJarProvenance[$key] }
        $runtimeOutput = [ordered]@{}
        foreach ($key in $provenance.runtimeOutput.Keys) { $runtimeOutput[$key] = $provenance.runtimeOutput[$key] }
        $manifest = [ordered]@{}
        foreach ($key in $runtimeOutput.manifest.Keys) { $manifest[$key] = $runtimeOutput.manifest[$key] }
        $manifest.buildNumber = '175'
        $runtimeOutput.manifest = $manifest
        $provenance.runtimeOutput = $runtimeOutput
        $copy.serverJarProvenance = $provenance
    }
    $copy
})
if (Test-T21ServerJarProvenanceCoverage $wrongPaperclipRuntimeIdentity @('node-a') $serverInputPath $serverInputSha 54212025 'paper' '26.3' 166) {
    throw 'A Paperclip runtime JAR from a different server build was accepted.'
}
Write-Output 'PASS: reject a runtime output manifest for the wrong platform build'

$expectedArtifactHash = 'a' * 64
$wrongArtifactHash = 'b' * 64
$pinnedArtifact = [ordered]@{ name = 'Syncmoney-1.4.0-alpha.1.jar'; sha256 = $expectedArtifactHash }
$artifactPin = Get-T21ArtifactPinObservation @($pinnedArtifact) 'Syncmoney-1.4.0-alpha.1.jar' $expectedArtifactHash
if (-not $artifactPin.matchesExpectedPin -or $artifactPin.observedSha256 -cne $expectedArtifactHash) {
    throw 'A deployed Syncmoney artifact matching its expected SHA-256 pin was rejected.'
}
Write-Output 'PASS: accept the exact deployed Syncmoney artifact pin'

$mismatchedArtifact = [ordered]@{ name = 'Syncmoney-1.4.0-alpha.1.jar'; sha256 = $wrongArtifactHash }
$mismatchedPin = Get-T21ArtifactPinObservation @($mismatchedArtifact) 'Syncmoney-1.4.0-alpha.1.jar' $expectedArtifactHash
if ($mismatchedPin.matchesExpectedPin) { throw 'A deployed Syncmoney artifact with a mismatched SHA-256 was accepted.' }
Write-Output 'PASS: reject a mismatched deployed Syncmoney artifact pin'

$missingPin = Get-T21ArtifactPinObservation @() 'Syncmoney-1.4.0-alpha.1.jar' $expectedArtifactHash
if ($missingPin.matchesExpectedPin) { throw 'A missing deployed Syncmoney artifact was accepted.' }
Write-Output 'PASS: reject a missing deployed Syncmoney artifact'

$duplicateArtifacts = @($pinnedArtifact, $pinnedArtifact)
$duplicatePin = Get-T21ArtifactPinObservation $duplicateArtifacts 'Syncmoney-1.4.0-alpha.1.jar' $expectedArtifactHash
if ($duplicatePin.matchesExpectedPin) { throw 'Duplicate deployed Syncmoney artifacts were accepted.' }
Write-Output 'PASS: reject duplicate deployed Syncmoney artifacts'

$validConfig = [ordered]@{
    serverName = 't21-nodeA'; configVersion = 12; mode = 'sync'; dbEnabled = $true; pubsubEnabled = $true
    redisEnabled = $true; redisHost = '127.0.0.1'; redisPort = 24567; redisDatabase = 15
    databaseEnabled = $true; databaseType = 'postgresql'; databaseHost = '127.0.0.1'; databasePort = 24568
    databaseName = 'syncmoney_t21'; syntheticOperatorFixture = 'preserve-through-upgrade'
}
if (-not (Assert-T21NodeConfigObservation $validConfig 't21-nodeA' 24568 'syncmoney_t21' 24567)) {
    throw 'A valid pinned T21 config observation was rejected.'
}
Write-Output 'PASS: accept the expected SYNC backend and operator-config fixture'
$missingConfigFixture = [ordered]@{}
foreach ($key in $validConfig.Keys) { $missingConfigFixture[$key] = $validConfig[$key] }
$missingConfigFixture.syntheticOperatorFixture = $null
Assert-ThrowsContaining 'reject candidate config that lost the operator fixture before rollback' {
    Assert-T21NodeConfigObservation $missingConfigFixture 't21-nodeA' 24568 'syncmoney_t21' 24567
} 'retention assertion failed'

$schemaRows = @('audit|3', 'players|2', 'recovery|1')
if (-not (Assert-T21SchemaVersionRows $schemaRows $schemaRows 'baseline seed')) {
    throw 'An unchanged SQL schema-version snapshot was rejected.'
}
Write-Output 'PASS: accept unchanged non-empty SQL schema-version rows'
Assert-ThrowsContaining 'reject SQL schema-version changes during upgrade' {
    Assert-T21SchemaVersionRows $schemaRows @('audit|3', 'players|3', 'recovery|1') 'candidate shutdown'
} 'changed'
Assert-ThrowsContaining 'reject missing SQL schema-version rows' {
    Assert-T21SchemaVersionRows $schemaRows @() 'rollback'
} 'missing'
Assert-ThrowsContaining 'reject malformed SQL schema-version rows' {
    Assert-T21SchemaVersionRows $schemaRows @('audit|unknown') 'candidate shutdown'
} 'Malformed'

$backendShutdownObservations = @(
    [ordered]@{ backend='redis'; stage='preupgrade-backup'; result='VERIFIED'; shutdownCommand='SHUTDOWN SAVE'; shutdownExitCode=0; processExitCode=0; processExitCodeObserved=$true; processExitVerified=$true },
    [ordered]@{ backend='postgresql'; stage='preupgrade-backup'; result='VERIFIED'; statusBefore='RUNNING'; statusBeforeExitCode=0; shutdownCommand='pg_ctl stop'; shutdownExitCode=0; statusAfter='STOPPED'; statusAfterExitCode=3 },
    [ordered]@{ backend='redis'; stage='final-cleanup'; result='VERIFIED'; shutdownCommand='SHUTDOWN SAVE'; shutdownExitCode=0; processExitCode=0; processExitCodeObserved=$true; processExitVerified=$true },
    [ordered]@{ backend='postgresql'; stage='final-cleanup'; result='VERIFIED'; statusBefore='RUNNING'; statusBeforeExitCode=0; shutdownCommand='pg_ctl stop'; shutdownExitCode=0; statusAfter='STOPPED'; statusAfterExitCode=3 }
)
if (-not (Test-T21BackendShutdownCoverage $backendShutdownObservations)) {
    throw 'Verified Redis and PostgreSQL shutdown observations were rejected.'
}
Write-Output 'PASS: accept exact isolated Redis and PostgreSQL shutdown coverage'

$missingBackendShutdown = @($backendShutdownObservations | Where-Object {
    -not ($_.backend -ceq 'postgresql' -and $_.stage -ceq 'final-cleanup')
})
if (Test-T21BackendShutdownCoverage $missingBackendShutdown) {
    throw 'Missing PostgreSQL cleanup evidence was accepted.'
}
Write-Output 'PASS: reject missing isolated backend shutdown evidence'

$unverifiedPostgresShutdown = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'postgresql' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $copy['statusAfterExitCode'] = 2
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $unverifiedPostgresShutdown) {
    throw 'An unrecognized PostgreSQL status error was accepted as cleanup evidence.'
}
Write-Output 'PASS: reject PostgreSQL cleanup status errors'

$unverifiedRedisShutdown = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'redis' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $copy['processExitVerified'] = $false
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $unverifiedRedisShutdown) {
    throw 'Redis cleanup without verified process exit was accepted.'
}
Write-Output 'PASS: reject Redis cleanup without process-exit evidence'

$missingRedisExitCode = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'redis' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $null = $copy.Remove('processExitCode')
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $missingRedisExitCode) {
    throw 'Redis cleanup with an absent process exit code was accepted as zero.'
}
Write-Output 'PASS: reject absent Redis process exit code instead of casting it to zero'

$nullRedisExitCode = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'redis' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $copy['processExitCode'] = $null
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $nullRedisExitCode) {
    throw 'Redis cleanup with a null process exit code was accepted as zero.'
}
Write-Output 'PASS: reject null Redis process exit code instead of casting it to zero'

$unobservedRedisExitCode = @($backendShutdownObservations | ForEach-Object {
    if ($_.backend -ceq 'redis' -and $_.stage -ceq 'final-cleanup') {
        $copy = [ordered]@{}
        foreach ($key in $_.Keys) { $copy[$key] = $_[$key] }
        $copy['processExitCodeObserved'] = $false
        $copy
    } else { $_ }
})
if (Test-T21BackendShutdownCoverage $unobservedRedisExitCode) {
    throw 'Redis cleanup without an explicit process exit-code observation was accepted.'
}
Write-Output 'PASS: reject Redis process exit code without explicit observation'

if ($null -eq (Get-Command Test-T21MySqlRuntimeReport -ErrorAction SilentlyContinue)) {
    throw 'Missing MySQL SYNC runtime report validator for the T21 minimum-Paper dialect cell.'
}
if ($null -eq (Get-Command Test-T21PostgresRuntimeReport -ErrorAction SilentlyContinue)) {
    throw 'Missing PostgreSQL SYNC runtime report validator for the T21 minimum-Paper dialect cell.'
}
if ($null -eq (Get-Command Resolve-T21SqlRuntimeProduct -ErrorAction SilentlyContinue)) {
    throw 'Missing deterministic SQL runtime product resolver.'
}
if ((Resolve-T21SqlRuntimeProduct 'MySQL') -cne 'MySQL' -or
    (Resolve-T21SqlRuntimeProduct 'MariaDB') -cne 'MariaDB' -or
    (Resolve-T21SqlRuntimeProduct 'PostgreSQL') -cne 'PostgreSQL') {
    throw 'The SQL runtime product resolver changed an accepted product identity.'
}
Write-Output 'PASS: resolve MySQL, MariaDB, and PostgreSQL runtime product identities'

$postgresFixtureDataDirectory = [IO.Path]::GetFullPath((Join-Path $env:TEMP 'syncmoney-t21-postgres-data'))
$postgresFixtureExecutable = [IO.Path]::GetFullPath((Join-Path $env:TEMP 'postgres.exe'))
$postgresFixturePidLines = @('4242', $postgresFixtureDataDirectory, '1781298897', '15432')
$postgresFixtureStartTimeUtc = [DateTimeOffset]::FromUnixTimeSeconds(1781298897).UtcDateTime
$postgresFixtureCommandLine = '"' + $postgresFixtureExecutable + '" -D "' + $postgresFixtureDataDirectory.Replace('\', '/') + '" -p 15432'
if (-not (Test-T21PostgresProcessIdentity -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresFixtureCommandLine `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc)) {
    throw 'A run-owned PostgreSQL process with a verified postmaster.pid identity was rejected before listener creation.'
}
Write-Output 'PASS: verify PostgreSQL process identity independently of listener readiness for startup-timeout cleanup'

$postgresEmbeddedPortArgument = '"' + $postgresFixtureExecutable + '" -D "' + $postgresFixtureDataDirectory + '" -c "application_name=x -p 15432'
if (Test-T21PostgresProcessIdentity -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresEmbeddedPortArgument `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL -p token embedded in a -c argument was accepted as a standalone port option.'
}
Write-Output 'PASS: reject a PostgreSQL port option embedded in a different argument'

$postgresEmbeddedDataDirectoryArgument = '"' + $postgresFixtureExecutable + '" -p 15432 -c "application_name=x -D ' + $postgresFixtureDataDirectory
if (Test-T21PostgresProcessIdentity -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresEmbeddedDataDirectoryArgument `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL -D token embedded in a -c argument was accepted as a standalone data-directory option.'
}
Write-Output 'PASS: reject a PostgreSQL data-directory option embedded in a different argument'

$postgresDataDirectoryAsConfigValue = '"' + $postgresFixtureExecutable + '" -c -D "' + $postgresFixtureDataDirectory + '" -p 15432'
if (Test-T21PostgresProcessIdentity -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresDataDirectoryAsConfigValue `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL -D token consumed as the operand to -c was accepted as a standalone data-directory option.'
}
Write-Output 'PASS: reject a data-directory flag consumed as another PostgreSQL option operand'

$postgresPortAsConfigValue = '"' + $postgresFixtureExecutable + '" -D "' + $postgresFixtureDataDirectory + '" -c -p 15432'
if (Test-T21PostgresProcessIdentity -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresPortAsConfigValue `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL -p token consumed as the operand to -c was accepted as a standalone port option.'
}
Write-Output 'PASS: reject a port flag consumed as another PostgreSQL option operand'

$postgresDanglingDataDirectoryArgument = $postgresFixtureCommandLine + ' -D'
if (Test-T21PostgresProcessIdentity -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresDanglingDataDirectoryArgument `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A trailing duplicate PostgreSQL -D option without an operand was accepted.'
}
Write-Output 'PASS: reject a trailing duplicate PostgreSQL data-directory option without an operand'

$postgresDanglingPortArgument = $postgresFixtureCommandLine + ' -p'
if (Test-T21PostgresProcessIdentity -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresDanglingPortArgument `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A trailing duplicate PostgreSQL -p option without an operand was accepted.'
}
Write-Output 'PASS: reject a trailing duplicate PostgreSQL port option without an operand'

if (-not (Test-T21PostgresListenerIdentity -ListenerOwners @(4242) -ListenerAddresses @('127.0.0.1') -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresFixtureCommandLine `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc)) {
    throw 'A PostgreSQL PID file with PID, data directory, start time, and port in their documented positions was rejected.'
}
Write-Output 'PASS: parse PostgreSQL postmaster.pid port from line four and verify the run-owned process with Windows slash normalization'

$postgresWrongPortPidLines = @('4242', $postgresFixtureDataDirectory, '15432', '15433')
if (Test-T21PostgresListenerIdentity -ListenerOwners @(4242) -ListenerAddresses @('127.0.0.1') -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresFixtureCommandLine `
    -PostmasterPidLines $postgresWrongPortPidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL PID file whose fourth line names a different port was accepted.'
}
Write-Output 'PASS: reject a PostgreSQL PID file with a mismatched fourth-line port even when its start time matches the expected port'

$postgresInvalidStartTimePidLines = @('4242', $postgresFixtureDataDirectory, 'not-a-unix-timestamp', '15432')
if (Test-T21PostgresListenerIdentity -ListenerOwners @(4242) -ListenerAddresses @('127.0.0.1') -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresFixtureCommandLine `
    -PostmasterPidLines $postgresInvalidStartTimePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL PID file with a nonnumeric third-line start timestamp was accepted.'
}
Write-Output 'PASS: reject a PostgreSQL PID file with a malformed third-line start timestamp'

$postgresStaleStartTimePidLines = @('4242', $postgresFixtureDataDirectory, '1781298837', '15432')
if (Test-T21PostgresListenerIdentity -ListenerOwners @(4242) -ListenerAddresses @('127.0.0.1') -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresFixtureCommandLine `
    -PostmasterPidLines $postgresStaleStartTimePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL PID file whose start time belongs to a different process incarnation was accepted.'
}
Write-Output 'PASS: reject a stale PostgreSQL postmaster.pid start time that does not match the listener process creation time'

if (Test-T21PostgresListenerIdentity -ListenerOwners @(4243) -ListenerAddresses @('127.0.0.1') -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresFixtureCommandLine `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL listener owned by another process was accepted.'
}
if (Test-T21PostgresListenerIdentity -ListenerOwners @(4242) -ListenerAddresses @('0.0.0.0') -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresFixtureCommandLine `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A non-loopback PostgreSQL listener was accepted.'
}
if (Test-T21PostgresListenerIdentity -ListenerOwners @(4242) -ListenerAddresses @('127.0.0.1') -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath (Join-Path $env:TEMP 'other-postgres.exe') -CommandLine $postgresFixtureCommandLine `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL listener from another executable was accepted.'
}
Write-Output 'PASS: reject PostgreSQL listener owner, bind address, and executable mismatches'

$postgresPathOutsideDataArgument = '"' + $postgresFixtureExecutable + '" --label="' + $postgresFixtureDataDirectory + '" -p 15432'
if (Test-T21PostgresProcessIdentity -ProcessId 4242 `
    -ExpectedExecutablePath $postgresFixtureExecutable -ActualExecutablePath $postgresFixtureExecutable -CommandLine $postgresPathOutsideDataArgument `
    -PostmasterPidLines $postgresFixturePidLines -ExpectedDataDirectory $postgresFixtureDataDirectory -ExpectedPort 15432 `
    -ProcessStartTimeUtc $postgresFixtureStartTimeUtc) {
    throw 'A PostgreSQL command line containing the data directory outside its -D argument was accepted.'
}
Write-Output 'PASS: require PostgreSQL process command line to bind the verified data directory through -D'

$paperMinimumSha = '35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214'
$candidateSha = 'b' * 64
$apiConsumerSha = 'c' * 64
$accountA = '35c93f74-c540-3b38-8093-b5cb3c2263be'
$accountB = 'b748781d-824c-36d9-9de4-59cee731f37f'
$mysqlRuntimeTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('syncmoney-t21-mysql-report-' + [guid]::NewGuid().ToString('N'))
$mysqlRuntimeReparseTarget = $null
try {
$mysqlRuntimeTestLogs = Join-Path $mysqlRuntimeTestRoot 'logs'
New-Item -ItemType Directory -Path $mysqlRuntimeTestLogs -Force | Out-Null
$mysqlWriteMarker = '[13:32:16] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_WRITE_PASS expected-a=1337.25 expected-b=765.50 receipt-a=SUCCESS:DEPOSIT:1337.25:1337.25 receipt-b=SUCCESS:DEPOSIT:765.50:765.50'
$mysqlRestartReadMarker = '[13:32:16] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_READ_PASS expected-a=1337.25 expected-b=765.50'
$mysqlRestartReadObservation = '[13:32:16] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] ACCEPTANCE READ cold-vault-a=NaN cold-vault-unlocked-b=null a=1337.25 b=765.50 vault-unlocked-b=765.50'
$mysqlWriteLogPath = Join-Path $mysqlRuntimeTestLogs 'paper-candidate-write.txt'
$mysqlRestartReadLogPath = Join-Path $mysqlRuntimeTestLogs 'paper-candidate-restart-read.txt'
$null = [IO.File]::WriteAllText($mysqlWriteLogPath, $mysqlWriteMarker + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
$null = [IO.File]::WriteAllText($mysqlRestartReadLogPath, ($mysqlRestartReadMarker + [Environment]::NewLine + $mysqlRestartReadObservation + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
$mysqlWriteLogItem = Get-Item -LiteralPath $mysqlWriteLogPath
$mysqlRestartReadLogItem = Get-Item -LiteralPath $mysqlRestartReadLogPath
$mysqlWriteLogSha = (Get-FileHash -LiteralPath $mysqlWriteLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
$mysqlRestartReadLogSha = (Get-FileHash -LiteralPath $mysqlRestartReadLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
$mysqlRuntimeRows = @(
    [ordered]@{ name='AcceptanceA'; uuid=$accountA; balance='1337.25'; version=1 },
    [ordered]@{ name='AcceptanceB'; uuid=$accountB; balance='765.50'; version=1 }
)
$mysqlRuntimeReport = [ordered]@{
    schemaVersion = 1
    task = 'T21-M05-SYNC-MySQL-minimum-Paper'
    status = 'PASS_BOUNDED_SUBCASE'
    server = [ordered]@{ software='paper'; minecraftVersion='1.21.6'; build=48; sha256=$paperMinimumSha }
    candidate = [ordered]@{ version='1.4.0-alpha.1'; sha256=$candidateSha }
    java = [ordered]@{ feature=25 }
    mysql = [ordered]@{ version='8.0.42'; serverVersion='8.0.42' }
    redis = [ordered]@{ version='8.10.1' }
    backendProcesses = [ordered]@{
        mysql = [ordered]@{ version='8.0.42'; processId=1111; launchProcessId=1110; listenerAddresses=@('127.0.0.1'); runOwned=$true; listenerOwnerVerified=$true }
        redis = [ordered]@{ version='8.10.1'; processId=2222; runOwned=$true; listenerOwnerVerified=$true }
    }
    configuration = [ordered]@{
        schema=12; mode='sync'; databaseEnabled=$true; databaseType='mysql'; databaseIsolated=$true
        databaseHost='127.0.0.1'; databasePort=3306; databaseName='syncmoney_t21'
        redisEnabled=$true; redisDatabase=15; redisIsolated=$true; redisHost='127.0.0.1'; redisPort=16379
        configSchema=12; observedMode='sync'; databaseNameObserved='syncmoney_t21'
    }
    backendShutdowns = @(
        [ordered]@{ backend='mysql'; command='mysqladmin shutdown'; commandExitCode=0; processId=1111; shutdownTargetVerified=$true; processExitCode=0; processExitCodeObserved=$true; processExited=$true; launchProcessId=1110; launchProcessExitCode=0; launchProcessExitCodeObserved=$true; launchProcessExited=$true; forced=$false },
        [ordered]@{ backend='redis'; command='SHUTDOWN SAVE'; commandExitCode=1; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false }
    )
    startupObservations = @(
        [ordered]@{ stage='candidate-write'; enabled=$true; pluginVersion='1.4.0-alpha.1'; artifactSha256=$candidateSha; processId=3333; runtimeIdentity=[ordered]@{software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=48} },
        [ordered]@{ stage='candidate-restart-read'; enabled=$true; pluginVersion='1.4.0-alpha.1'; artifactSha256=$candidateSha; processId=4444; runtimeIdentity=[ordered]@{software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=48} }
    )
    writeReceipts = @(
        'SUCCESS:DEPOSIT:1337.25:1337.25',
        'SUCCESS:DEPOSIT:765.50:765.50'
    )
    writeMarker = $mysqlWriteMarker
    paperLogs = @(
        [ordered]@{ stage='candidate-write'; path='logs\paper-candidate-write.txt'; bytes=[long]$mysqlWriteLogItem.Length; sha256=$mysqlWriteLogSha },
        [ordered]@{ stage='candidate-restart-read'; path='logs\paper-candidate-restart-read.txt'; bytes=[long]$mysqlRestartReadLogItem.Length; sha256=$mysqlRestartReadLogSha }
    )
    logs = [ordered]@{
        'logs\paper-candidate-write.txt' = [ordered]@{ bytes=[long]$mysqlWriteLogItem.Length; sha256=$mysqlWriteLogSha }
        'logs\paper-candidate-restart-read.txt' = [ordered]@{ bytes=[long]$mysqlRestartReadLogItem.Length; sha256=$mysqlRestartReadLogSha }
    }
    runDirectory = $mysqlRuntimeTestRoot
    restartReadMarker = $mysqlRestartReadMarker
    restartReadObservation = $mysqlRestartReadObservation
    normalStops = @(
        [ordered]@{ stage='after-write'; commandExitCode=0; processId=3333; processExitCode=0; processExitCodeObserved=$true; processExited=$true; disabledMarker=$true },
        [ordered]@{ stage='after-restart-read'; commandExitCode=0; processId=4444; processExitCode=0; processExitCodeObserved=$true; processExited=$true; disabledMarker=$true }
    )
    snapshots = @(
        [ordered]@{ stage='after-write-stop'; sqlAccounts=$mysqlRuntimeRows; redisAccounts=$mysqlRuntimeRows },
        [ordered]@{ stage='after-restart-read-stop'; sqlAccounts=$mysqlRuntimeRows; redisAccounts=$mysqlRuntimeRows }
    )
    cleanupFailures = @()
    failure = $null
}
if (-not (Test-T21MySqlRuntimeReport $mysqlRuntimeReport $paperMinimumSha $candidateSha)) {
    throw 'The complete isolated minimum-Paper MySQL SYNC runtime report was rejected.'
}
Write-Output 'PASS: accept complete MySQL SYNC write, normal-restart, SQL/Redis parity evidence'

$mysqlRuntimeReportJsonRoundtrip = $mysqlRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json
if (-not (Test-T21MySqlRuntimeReport $mysqlRuntimeReportJsonRoundtrip $paperMinimumSha $candidateSha)) {
    throw 'The complete MySQL SYNC report was rejected after JSON serialization and deserialization.'
}
Write-Output 'PASS: accept the serialized report with rehashed Paper server logs'

$mysqlRuntimeReportV2 = $mysqlRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mysqlRuntimeReportV2.schemaVersion = 2
$mysqlRuntimeReportV2.mysql.product = 'MySQL'
$mysqlRuntimeReportV2.mysql.executableVersion = 'mysqld.exe  Ver 8.0.42 for Win64 on x86_64'
$mysqlRuntimeReportV2.backendProcesses.mysql.product = 'MySQL'
if (-not (Test-T21MySqlRuntimeReport $mysqlRuntimeReportV2 $paperMinimumSha $candidateSha)) {
    throw 'A complete isolated minimum-Paper MySQL schema-2 report was rejected.'
}
Write-Output 'PASS: accept the current MySQL runtime report schema'

$mysqlRuntimeReportV3 = $mysqlRuntimeReportV2 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mysqlRuntimeReportV3.schemaVersion = 3
$mysqlRuntimeReportV3.gradleDaemonCleanup = [ordered]@{
    isolatedUserHome = Join-Path $mysqlRuntimeTestRoot '.gradle-user-home'
    processIdsBeforeStop = @()
    stopCommandExitCode = $null
    processIdsAfterStop = @()
    processExitVerified = $true
}
$mysqlBackendLogPath = Join-Path $mysqlRuntimeTestLogs 'mysql-server.log'
$mysqlBackendLogContent = "MySQL 8.0.42 ready for connections.`n"
$null = [IO.File]::WriteAllText($mysqlBackendLogPath, $mysqlBackendLogContent, [Text.UTF8Encoding]::new($false))
$mysqlBackendLogItem = Get-Item -LiteralPath $mysqlBackendLogPath
$mysqlBackendLogSha = (Get-FileHash -LiteralPath $mysqlBackendLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
$mysqlRuntimeReport.logs['logs\mysql-server.log'] = [ordered]@{ bytes=[long]$mysqlBackendLogItem.Length; sha256=$mysqlBackendLogSha }
$mysqlRuntimeReportV3.logs['logs\mysql-server.log'] = [ordered]@{ bytes=[long]$mysqlBackendLogItem.Length; sha256=$mysqlBackendLogSha }
if (-not (Test-T21MySqlRuntimeReport $mysqlRuntimeReportV3 $paperMinimumSha $candidateSha)) {
    throw 'A clean schema-3 runtime report with verified isolated Gradle daemon cleanup was rejected.'
}
Write-Output 'PASS: accept a schema-3 runtime report with verified isolated Gradle daemon cleanup'

foreach ($endpointCase in $runtimePlatformEndpoints) {
    $platformRuntimeReport = $mysqlRuntimeReportV3 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $platformRuntimeReport = New-T21PlatformRuntimeReportFixture $platformRuntimeReport $endpointCase 'MySQL'
    if (-not (Test-T21MySqlRuntimeReport $platformRuntimeReport $endpointCase.sha256 $candidateSha -PlatformEndpoint $endpointCase.endpoint -ExpectedApiConsumerSha256 $apiConsumerSha)) {
        throw "A complete schema-4 SQL report for $($endpointCase.endpoint) was rejected."
    }
    Write-Output "PASS: accept schema-4 platform SQL runtime report for $($endpointCase.endpoint)"
    Remove-T21PlatformRuntimeReportFixtureLogs
}

foreach ($endpointCase in $runtimePlatformEndpoints) {
    $legacyNonPaperReport = $mysqlRuntimeReportV3 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
    $legacyNonPaperReport.task = "T21-M05-SYNC-MySQL-$($endpointCase.taskSuffix)"
    $legacyNonPaperReport.server.software = $endpointCase.software
    $legacyNonPaperReport.server.minecraftVersion = $endpointCase.minecraftVersion
    $legacyNonPaperReport.server.build = $endpointCase.build
    $legacyNonPaperReport.server.sha256 = $endpointCase.sha256
    foreach ($startup in $legacyNonPaperReport.startupObservations) {
        $startup.runtimeIdentity.software = $endpointCase.software
        $startup.runtimeIdentity.runtimeVersion = $endpointCase.minecraftVersion
        $startup.runtimeIdentity.minecraftVersion = $endpointCase.minecraftVersion
        $startup.runtimeIdentity.build = $endpointCase.build
    }
    if (Test-T21MySqlRuntimeReport $legacyNonPaperReport $endpointCase.sha256 $candidateSha -PlatformEndpoint $endpointCase.endpoint) {
        throw "A legacy Paper-log schema-3 report was accepted for non-Paper endpoint $($endpointCase.endpoint)."
    }
    Write-Output "PASS: reject schema-3 Paper-log report for non-Paper endpoint $($endpointCase.endpoint)"
}

$wrongFoliaRuntimeReport = $mysqlRuntimeReportV3 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$wrongFoliaRuntimeReport = New-T21PlatformRuntimeReportFixture $wrongFoliaRuntimeReport $runtimePlatformEndpoints[0] 'MySQL'
$wrongFoliaRuntimeReport.server.build = 7
foreach ($startup in $wrongFoliaRuntimeReport.startupObservations) {
    $startup.runtimeIdentity.build = 7
}
if (Test-T21MySqlRuntimeReport $wrongFoliaRuntimeReport $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'A runtime report whose Folia build disagrees with its pinned endpoint was accepted.'
}
Write-Output 'PASS: reject SQL runtime report with a mismatched Folia build'
Remove-T21PlatformRuntimeReportFixtureLogs

$mysqlReportMissingBackendLog = $mysqlRuntimeReportV3 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mysqlReportMissingBackendLog.logs.Remove('logs\mysql-server.log')
if (Test-T21MySqlRuntimeReport $mysqlReportMissingBackendLog $paperMinimumSha $candidateSha) {
    throw 'A schema-3 report omitting a retained backend log from its manifest was accepted.'
}
Write-Output 'PASS: reject a schema-3 report that omits a retained backend log'

$null = [IO.File]::WriteAllText($mysqlBackendLogPath, "tampered MySQL runtime log`n", [Text.UTF8Encoding]::new($false))
if (Test-T21MySqlRuntimeReport $mysqlRuntimeReportV3 $paperMinimumSha $candidateSha) {
    throw 'A schema-3 report with a tampered retained backend log was accepted.'
}
$null = [IO.File]::WriteAllText($mysqlBackendLogPath, $mysqlBackendLogContent, [Text.UTF8Encoding]::new($false))
Write-Output 'PASS: rehash every retained schema-3 backend log'

$postgresRuntimeReport = $mysqlRuntimeReportV3 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeReport.task = 'T21-M05-SYNC-PostgreSQL-minimum-Paper'
$postgresRuntimeReport.configuration.databaseType = 'postgresql'
$postgresRuntimeReport.configuration.databasePort = 15432
$postgresRuntimeReport.postgresql = [ordered]@{
    product='PostgreSQL'; version='18.1'; serverVersion='18.1'; executableVersion='postgres (PostgreSQL) 18.1'
    bin='C:\Program Files\PostgreSQL\18\bin'; dataDirectory=(Join-Path $mysqlRuntimeTestRoot 'postgresql\data')
    processId=1111; listenerAddresses=@('127.0.0.1')
    startCommand='pg_ctl start -w'; startCommandExitCode=0; startCommandProcessExited=$true
}
$postgresRuntimeReport.Remove('mysql')
$postgresRuntimeReport.backendProcesses.Remove('mysql')
$postgresRuntimeReport.backendProcesses.postgresql = [ordered]@{
    product='PostgreSQL'; version='18.1'; processId=1111; runOwned=$true; listenerOwnerVerified=$true
    listenerAddresses=@('127.0.0.1')
}
$postgresRuntimeReport.backendShutdowns = @(
    [ordered]@{ backend='postgresql'; command='pg_ctl stop -m fast -w'; commandExitCode=0; processId=1111; shutdownTargetVerified=$true; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$false },
    $postgresRuntimeReport.backendShutdowns[1]
)
if (-not (Test-T21PostgresRuntimeReport $postgresRuntimeReport $paperMinimumSha $candidateSha)) {
    throw 'The complete isolated minimum-Paper PostgreSQL SYNC runtime report was rejected.'
}
Write-Output 'PASS: accept PostgreSQL SYNC write, normal-restart, SQL/Redis parity, and process-exit evidence'

$foliaPostgresRuntimeReport = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$foliaPostgresRuntimeReport = New-T21PlatformRuntimeReportFixture $foliaPostgresRuntimeReport $runtimePlatformEndpoints[0] 'PostgreSQL'
$foliaPostgresRuntimeReport.configuration.databaseType = 'postgresql'
if (-not (Test-T21PostgresRuntimeReport $foliaPostgresRuntimeReport $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha)) {
    throw 'The PostgreSQL report validator rejected a complete schema-4 Folia SQL runtime report.'
}
Write-Output 'PASS: accept schema-4 PostgreSQL SYNC runtime evidence for minimum Folia'

if (Test-T21PostgresRuntimeReport $foliaPostgresRuntimeReport $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia) {
    throw 'The PostgreSQL runtime report accepted schema-4 evidence without a caller-supplied API-consumer SHA-256.'
}
Write-Output 'PASS: require a caller-supplied API-consumer SHA-256 for schema-4 runtime evidence'

$failedApiConsumerMarker = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$failedApiConsumerMarker.startupObservations[0].apiConsumerMarker = 'FAIL'
if (Test-T21PostgresRuntimeReport $failedApiConsumerMarker $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'The PostgreSQL runtime report accepted a failed API-consumer startup marker.'
}
Write-Output 'PASS: reject PostgreSQL runtime evidence with a failed API-consumer startup marker'

$failedRestartApiConsumerMarker = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$failedRestartApiConsumerMarker.startupObservations[1].apiConsumerMarker = 'FAIL'
if (Test-T21PostgresRuntimeReport $failedRestartApiConsumerMarker $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'The PostgreSQL runtime report accepted a failed restart API-consumer marker.'
}
Write-Output 'PASS: reject PostgreSQL runtime evidence with a failed restart API-consumer marker'

$wrongApiConsumerSha = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$wrongApiConsumerSha.apiConsumer.sha256 = '0' * 64
if (Test-T21PostgresRuntimeReport $wrongApiConsumerSha $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'The PostgreSQL runtime report accepted a mismatched API-consumer SHA-256.'
}
Write-Output 'PASS: reject PostgreSQL runtime evidence with a mismatched API-consumer SHA-256'

$missingApiConsumerLog = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$missingApiConsumerLogPath = Join-Path $missingApiConsumerLog.runDirectory $missingApiConsumerLog.platformLogs[0].path
$missingApiConsumerLogOriginalContent = [IO.File]::ReadAllText($missingApiConsumerLogPath)
$missingApiConsumerLogContent = $missingApiConsumerLogOriginalContent
$missingApiConsumerLogContent = [regex]::Replace($missingApiConsumerLogContent, '(?im)^.*API CLASSLOADER PASS provider=Syncmoney.*(?:\r?\n|$)', '')
[IO.File]::WriteAllText($missingApiConsumerLogPath, $missingApiConsumerLogContent, [Text.UTF8Encoding]::new($false))
$missingApiConsumerLog = Update-T21PlatformRuntimeReportLogHashes $missingApiConsumerLog
if (Test-T21PostgresRuntimeReport $missingApiConsumerLog $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'The PostgreSQL runtime report accepted schema-4 evidence without an API-consumer PASS marker in the corresponding startup log.'
}
[IO.File]::WriteAllText($missingApiConsumerLogPath, $missingApiConsumerLogOriginalContent, [Text.UTF8Encoding]::new($false))
Write-Output 'PASS: reject schema-4 API-consumer report markers missing from a retained startup log'

$failedApiConsumerLog = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$failedApiConsumerLogPath = Join-Path $failedApiConsumerLog.runDirectory $failedApiConsumerLog.platformLogs[1].path
$failedApiConsumerLogOriginalContent = [IO.File]::ReadAllText($failedApiConsumerLogPath)
$failedApiConsumerLogContent = $failedApiConsumerLogOriginalContent
$failedApiConsumerLogContent += '[Server thread/INFO]: [SyncmoneyApiConsumerFixture] API CLASSLOADER FAIL provider=Syncmoney' + [Environment]::NewLine
[IO.File]::WriteAllText($failedApiConsumerLogPath, $failedApiConsumerLogContent, [Text.UTF8Encoding]::new($false))
$failedApiConsumerLog = Update-T21PlatformRuntimeReportLogHashes $failedApiConsumerLog
if (Test-T21PostgresRuntimeReport $failedApiConsumerLog $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'The PostgreSQL runtime report accepted schema-4 evidence with an API-consumer failure marker in the corresponding startup log.'
}
[IO.File]::WriteAllText($failedApiConsumerLogPath, $failedApiConsumerLogOriginalContent, [Text.UTF8Encoding]::new($false))
Write-Output 'PASS: reject schema-4 API-consumer report markers with a retained startup failure marker'

$wrongPinnedHashReport = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$wrongPinnedHashReport.server.sha256 = $paperMinimumSha
if (Test-T21PostgresRuntimeReport $wrongPinnedHashReport $paperMinimumSha $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'A Folia report and caller that agreed on the wrong server hash were accepted.'
}
Write-Output 'PASS: reject runtime report whose caller/server hash disagrees with the selected platform pin'

$maximumCanvasEndpoint = $runtimePlatformEndpoints[3]
$relabeledPlatformLogReport = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$relabeledPlatformLogReport.task = "T21-M05-SYNC-PostgreSQL-$($maximumCanvasEndpoint.taskSuffix)"
$relabeledPlatformLogReport.server.software = $maximumCanvasEndpoint.software
$relabeledPlatformLogReport.server.minecraftVersion = $maximumCanvasEndpoint.minecraftVersion
$relabeledPlatformLogReport.server.build = $maximumCanvasEndpoint.build
$relabeledPlatformLogReport.server.sha256 = $maximumCanvasEndpoint.sha256
foreach ($startup in $relabeledPlatformLogReport.startupObservations) {
    $startup.runtimeIdentity.software = $maximumCanvasEndpoint.software
    $startup.runtimeIdentity.runtimeVersion = $maximumCanvasEndpoint.minecraftVersion
    $startup.runtimeIdentity.minecraftVersion = $maximumCanvasEndpoint.minecraftVersion
    $startup.runtimeIdentity.build = $maximumCanvasEndpoint.build
}
if (Test-T21PostgresRuntimeReport $relabeledPlatformLogReport $maximumCanvasEndpoint.sha256 $candidateSha -PlatformEndpoint MaximumCanvas -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'A schema-4 Canvas report with retained Folia startup logs was accepted.'
}
Write-Output 'PASS: reject schema-4 report when retained logs identify a different platform'

$foliaWritePlatformLogPath = Join-Path $foliaPostgresRuntimeReport.runDirectory $foliaPostgresRuntimeReport.platformLogs[0].path
$foliaWritePlatformLogContent = [IO.File]::ReadAllText($foliaWritePlatformLogPath)
$missingPlatformIdentityReport = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$missingPlatformIdentityContent = [regex]::Replace($foliaWritePlatformLogContent, '(?im)^.*Loading\s+(?:Paper|Folia|Canvas)\s+\d+(?:\.\d+){1,2}-\d+\b.*(?:\r?\n|$)', '')
[IO.File]::WriteAllText($foliaWritePlatformLogPath, $missingPlatformIdentityContent, [Text.UTF8Encoding]::new($false))
$missingPlatformIdentityReport = Update-T21PlatformRuntimeReportLogHashes $missingPlatformIdentityReport
if (Test-T21PostgresRuntimeReport $missingPlatformIdentityReport $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'A schema-4 report without platform identity in a retained server log was accepted.'
}
Write-Output 'PASS: reject schema-4 report when a retained server log has no platform identity'

$conflictingPlatformIdentityReport = $foliaPostgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$conflictingIdentityLine = '[ServerMain/INFO]: [bootstrap] Loading Canvas 26.3-972-main@fixture (2026-10-13T00:00:00Z) for Minecraft 26.3'
[IO.File]::WriteAllText($foliaWritePlatformLogPath, $foliaWritePlatformLogContent + $conflictingIdentityLine + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
$conflictingPlatformIdentityReport = Update-T21PlatformRuntimeReportLogHashes $conflictingPlatformIdentityReport
if (Test-T21PostgresRuntimeReport $conflictingPlatformIdentityReport $runtimePlatformEndpoints[0].sha256 $candidateSha -PlatformEndpoint MinimumFolia -ExpectedApiConsumerSha256 $apiConsumerSha) {
    throw 'A schema-4 report with conflicting platform identities in a retained server log was accepted.'
}
Write-Output 'PASS: reject schema-4 report when a retained server log has conflicting platform identities'
Remove-T21PlatformRuntimeReportFixtureLogs

$postgresRuntimeStringConfigBoolean = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeStringConfigBoolean.configuration.databaseEnabled = 'True'
if (Test-T21PostgresRuntimeReport $postgresRuntimeStringConfigBoolean $paperMinimumSha $candidateSha) {
    throw 'A string-valued PostgreSQL databaseEnabled field was accepted as Boolean evidence.'
}
Write-Output 'PASS: reject string-valued PostgreSQL configuration Boolean evidence'

$postgresRuntimeNumericConfigBoolean = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeNumericConfigBoolean.configuration.redisIsolated = 1
if (Test-T21PostgresRuntimeReport $postgresRuntimeNumericConfigBoolean $paperMinimumSha $candidateSha) {
    throw 'A numeric PostgreSQL redisIsolated field was accepted as Boolean evidence.'
}
Write-Output 'PASS: reject numeric PostgreSQL configuration Boolean evidence'

$postgresRuntimeStringOwnershipBoolean = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeStringOwnershipBoolean.backendProcesses.postgresql.runOwned = 'True'
if (Test-T21PostgresRuntimeReport $postgresRuntimeStringOwnershipBoolean $paperMinimumSha $candidateSha) {
    throw 'A string-valued PostgreSQL runOwned field was accepted as ownership evidence.'
}
Write-Output 'PASS: reject string-valued PostgreSQL process ownership evidence'

$postgresRuntimeNumericListenerBoolean = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeNumericListenerBoolean.backendProcesses.postgresql.listenerOwnerVerified = 1
if (Test-T21PostgresRuntimeReport $postgresRuntimeNumericListenerBoolean $paperMinimumSha $candidateSha) {
    throw 'A numeric PostgreSQL listenerOwnerVerified field was accepted as ownership evidence.'
}
Write-Output 'PASS: reject numeric PostgreSQL listener ownership evidence'

$postgresRuntimeStringRedisOwnershipBoolean = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeStringRedisOwnershipBoolean.backendProcesses.redis.runOwned = 'True'
if (Test-T21PostgresRuntimeReport $postgresRuntimeStringRedisOwnershipBoolean $paperMinimumSha $candidateSha) {
    throw 'A string-valued Redis runOwned field was accepted as ownership evidence.'
}
Write-Output 'PASS: reject string-valued Redis process ownership evidence'

$postgresRuntimeMissingStartExit = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeMissingStartExit.postgresql.Remove('startCommandExitCode')
if (Test-T21PostgresRuntimeReport $postgresRuntimeMissingStartExit $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL runtime report without an observed pg_ctl start exit code was accepted.'
}
Write-Output 'PASS: reject PostgreSQL runtime evidence missing the pg_ctl start exit code'

$postgresRuntimeMissingStartCommand = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeMissingStartCommand.postgresql.Remove('startCommand')
if (Test-T21PostgresRuntimeReport $postgresRuntimeMissingStartCommand $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL runtime report without the observed pg_ctl start command was accepted.'
}
Write-Output 'PASS: reject PostgreSQL runtime evidence missing the pg_ctl start command'

$postgresRuntimeFailedStart = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeFailedStart.postgresql.startCommandExitCode = 1
if (Test-T21PostgresRuntimeReport $postgresRuntimeFailedStart $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL runtime report with a failed pg_ctl start exit code was accepted.'
}
Write-Output 'PASS: reject PostgreSQL runtime evidence with a failed pg_ctl start command'

$postgresRuntimeUnobservedStart = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeUnobservedStart.postgresql.startCommandProcessExited = $false
if (Test-T21PostgresRuntimeReport $postgresRuntimeUnobservedStart $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL runtime report without observed pg_ctl start process exit was accepted.'
}
Write-Output 'PASS: reject PostgreSQL runtime evidence without observed pg_ctl start process exit'

$postgresRuntimeNumericStartExited = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeNumericStartExited.postgresql.startCommandProcessExited = 1
if (Test-T21PostgresRuntimeReport $postgresRuntimeNumericStartExited $paperMinimumSha $candidateSha) {
    throw 'A numeric 1 was accepted as the PostgreSQL pg_ctl process exit observation.'
}
Write-Output 'PASS: reject numeric coercion for the PostgreSQL startup process exit observation'

$postgresRuntimeFractionalPids = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeFractionalPids.postgresql.processId = 1111.4
$postgresRuntimeFractionalPids.backendProcesses.postgresql.processId = 1111.4
$postgresRuntimeFractionalPids.backendShutdowns[0].processId = 1111.4
if (Test-T21PostgresRuntimeReport $postgresRuntimeFractionalPids $paperMinimumSha $candidateSha) {
    throw 'Fractional PostgreSQL PID evidence was accepted after PowerShell integer coercion.'
}
Write-Output 'PASS: reject fractional PostgreSQL PIDs in report, backend process, and shutdown evidence'

$postgresRuntimeBooleanPid = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeBooleanPid.postgresql.processId = $true
if (Test-T21PostgresRuntimeReport $postgresRuntimeBooleanPid $paperMinimumSha $candidateSha) {
    throw 'A Boolean PostgreSQL PID was accepted as an integer process ID.'
}
Write-Output 'PASS: reject Boolean PostgreSQL process IDs'

$postgresRuntimeFractionalExitCode = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeFractionalExitCode.postgresql.startCommandExitCode = 0.4
if (Test-T21PostgresRuntimeReport $postgresRuntimeFractionalExitCode $paperMinimumSha $candidateSha) {
    throw 'A fractional PostgreSQL pg_ctl exit code was accepted after PowerShell integer coercion.'
}
Write-Output 'PASS: reject fractional PostgreSQL exit codes'

$postgresRuntimeWrongStartCommand = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeWrongStartCommand.postgresql.startCommand = 'pg_ctl stop -m fast -w'
if (Test-T21PostgresRuntimeReport $postgresRuntimeWrongStartCommand $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL runtime report with the wrong startup command was accepted.'
}
Write-Output 'PASS: reject PostgreSQL runtime evidence with the wrong startup command'

$postgresRuntimeWrongConfig = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeWrongConfig.configuration.databaseType = 'mysql'
if (Test-T21PostgresRuntimeReport $postgresRuntimeWrongConfig $paperMinimumSha $candidateSha) {
    throw 'A MySQL configuration was accepted as PostgreSQL runtime evidence.'
}
Write-Output 'PASS: reject a PostgreSQL report with the wrong configured SQL dialect'

$postgresRuntimeWrongProduct = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeWrongProduct.postgresql.product = 'MySQL'
if (Test-T21PostgresRuntimeReport $postgresRuntimeWrongProduct $paperMinimumSha $candidateSha) {
    throw 'A report with the wrong PostgreSQL runtime product was accepted.'
}
Write-Output 'PASS: reject a PostgreSQL report with a mismatched server product identity'

$postgresRuntimeWrongProcessProduct = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeWrongProcessProduct.backendProcesses.postgresql.product = 'MySQL'
if (Test-T21PostgresRuntimeReport $postgresRuntimeWrongProcessProduct $paperMinimumSha $candidateSha) {
    throw 'A report with the wrong PostgreSQL process product was accepted.'
}
Write-Output 'PASS: reject a PostgreSQL report with a mismatched backend-process product identity'

$postgresRuntimeMismatchedProcessId = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeMismatchedProcessId.postgresql.processId = 2222
if (Test-T21PostgresRuntimeReport $postgresRuntimeMismatchedProcessId $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL report whose backend process PID differs from the report-level listener PID was accepted.'
}
Write-Output 'PASS: reject PostgreSQL report and backend-process PID disagreement'

$postgresRuntimeMismatchedAddresses = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeMismatchedAddresses.postgresql.listenerAddresses = @('::1')
if (Test-T21PostgresRuntimeReport $postgresRuntimeMismatchedAddresses $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL report whose backend listener addresses differ from the report-level addresses was accepted.'
}
Write-Output 'PASS: reject PostgreSQL report and backend-process listener address disagreement'

$postgresRuntimeWrongShutdown = $postgresRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$postgresRuntimeWrongShutdown.backendShutdowns[0].processId = 3333
if (Test-T21PostgresRuntimeReport $postgresRuntimeWrongShutdown $paperMinimumSha $candidateSha) {
    throw 'PostgreSQL shutdown evidence for a different listener PID was accepted.'
}
Write-Output 'PASS: reject PostgreSQL shutdown evidence for a different listener process'

$mysqlRuntimeReportLeakedGradleDaemon = $mysqlRuntimeReportV3 | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mysqlRuntimeReportLeakedGradleDaemon.gradleDaemonCleanup = [ordered]@{
    isolatedUserHome = Join-Path $mysqlRuntimeTestRoot '.gradle-user-home'
    processIdsBeforeStop = @(5555)
    stopCommandExitCode = 0
    processIdsAfterStop = @(5555)
    processExitVerified = $false
}
if (Test-T21MySqlRuntimeReport $mysqlRuntimeReportLeakedGradleDaemon $paperMinimumSha $candidateSha) {
    throw 'A runtime report with a run-owned isolated Gradle daemon left alive after cleanup was accepted.'
}
Write-Output 'PASS: reject a runtime report with a surviving isolated Gradle daemon'

$mariadbRuntimeReport = $mysqlRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mariadbRuntimeReport.schemaVersion = 2
$mariadbRuntimeReport.task = 'T21-M05-SYNC-MariaDB-minimum-Paper'
$mariadbRuntimeReport.mysql.product = 'MariaDB'
$mariadbRuntimeReport.mysql.version = '12.3.3-MariaDB'
$mariadbRuntimeReport.mysql.serverVersion = '12.3.3-MariaDB'
$mariadbRuntimeReport.mysql.executableVersion = 'mariadbd.exe  Ver 12.3.3-MariaDB for Win64 on AMD64 (MariaDB Server)'
$mariadbRuntimeReport.backendProcesses.mysql.product = 'MariaDB'
$mariadbRuntimeReport.backendProcesses.mysql.version = '12.3.3-MariaDB'
if (-not (Test-T21MySqlRuntimeReport $mariadbRuntimeReport $paperMinimumSha $candidateSha -DatabaseProduct MariaDB)) {
    throw 'A complete isolated minimum-Paper MariaDB SYNC runtime report was rejected.'
}
Write-Output 'PASS: accept MariaDB runtime evidence through the MySQL protocol'

$mariadbRuntimeReportWrongProduct = $mariadbRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mariadbRuntimeReportWrongProduct.mysql.product = 'MySQL'
if (Test-T21MySqlRuntimeReport $mariadbRuntimeReportWrongProduct $paperMinimumSha $candidateSha -DatabaseProduct MariaDB) {
    throw 'A MariaDB runtime report with MySQL product identity was accepted.'
}
Write-Output 'PASS: reject a MariaDB report with a different server product'

$mariadbRuntimeReportWrongVersion = $mariadbRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$mariadbRuntimeReportWrongVersion.mysql.serverVersion = '12.3.2-MariaDB for Win64 on AMD64 (MariaDB Server)'
if (Test-T21MySqlRuntimeReport $mariadbRuntimeReportWrongVersion $paperMinimumSha $candidateSha -DatabaseProduct MariaDB) {
    throw 'A MariaDB runtime report with the wrong pinned server version was accepted.'
}
Write-Output 'PASS: reject a MariaDB report with the wrong exact server version'

$paperMaximumSha = 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd'
$maximumPaperRuntimeReport = $mariadbRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$maximumPaperRuntimeReport.schemaVersion = 3
$maximumPaperRuntimeReport.task = 'T21-M05-SYNC-MariaDB-maximum-Paper'
$maximumPaperRuntimeReport.server.minecraftVersion = '26.3'
$maximumPaperRuntimeReport.server.build = 166
$maximumPaperRuntimeReport.server.sha256 = $paperMaximumSha
$maximumPaperRuntimeReport.gradleDaemonCleanup = [ordered]@{
    isolatedUserHome = Join-Path $maximumPaperRuntimeReport.runDirectory '.gradle-user-home'
    processIdsBeforeStop = @()
    stopCommandExitCode = $null
    stopLogPath = $null
    processIdsAfterStop = @()
    processExitVerified = $true
}
foreach ($startup in $maximumPaperRuntimeReport.startupObservations) {
    $startup.runtimeIdentity.runtimeVersion = '26.3'
    $startup.runtimeIdentity.minecraftVersion = '26.3'
    $startup.runtimeIdentity.build = 166
}
if (-not (Test-T21MySqlRuntimeReport $maximumPaperRuntimeReport $paperMaximumSha $candidateSha -DatabaseProduct MariaDB -PaperEndpoint Maximum)) {
    throw 'The complete MariaDB SYNC runtime report for user-pinned maximum Paper 26.3#166 was rejected.'
}
Write-Output 'PASS: accept MariaDB runtime evidence for user-pinned maximum Paper 26.3#166'
if (Test-T21MySqlRuntimeReport $maximumPaperRuntimeReport $paperMaximumSha $candidateSha -DatabaseProduct MariaDB) {
    throw 'A maximum-Paper runtime report was accepted under the default minimum-Paper expectation.'
}
Write-Output 'PASS: reject maximum-Paper evidence under the minimum-Paper expectation'

$currentMaximumPaper = Get-T21PlatformEndpoint -PlatformEndpoint CurrentMaximum
$currentMaximumPaperRuntimeReport = $maximumPaperRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$currentMaximumPaperRuntimeReport.task = "T21-M05-SYNC-MariaDB-$($currentMaximumPaper.taskSuffix)"
$currentMaximumPaperRuntimeReport.server.minecraftVersion = $currentMaximumPaper.minecraftVersion
$currentMaximumPaperRuntimeReport.server.build = $currentMaximumPaper.build
$currentMaximumPaperRuntimeReport.server.sha256 = $currentMaximumPaper.sha256
foreach ($startup in $currentMaximumPaperRuntimeReport.startupObservations) {
    $startup.runtimeIdentity.runtimeVersion = $currentMaximumPaper.minecraftVersion
    $startup.runtimeIdentity.minecraftVersion = $currentMaximumPaper.minecraftVersion
    $startup.runtimeIdentity.build = $currentMaximumPaper.build
}
if (-not (Test-T21MySqlRuntimeReport $currentMaximumPaperRuntimeReport $currentMaximumPaper.sha256 $candidateSha -DatabaseProduct MariaDB -PaperEndpoint CurrentMaximum)) {
    throw 'The current Paper 26.3#200 MariaDB SYNC runtime report was rejected.'
}
Write-Output 'PASS: accept MariaDB runtime report for current maximum Paper 26.3#200'
if (Test-T21MySqlRuntimeReport $currentMaximumPaperRuntimeReport $currentMaximumPaper.sha256 $candidateSha -DatabaseProduct MariaDB -PaperEndpoint Maximum) {
    throw 'Current maximum Paper evidence was accepted under the historical maximum Paper selector.'
}
Write-Output 'PASS: reject current maximum Paper evidence under the historical maximum selector'

$maximumPaperWrongReportedProduct = $maximumPaperRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$maximumPaperWrongReportedProduct.mysql.product = 'MySQL'
if (Test-T21MySqlRuntimeReport $maximumPaperWrongReportedProduct $paperMaximumSha $candidateSha -DatabaseProduct MariaDB -PaperEndpoint Maximum) {
    throw 'A schema-3 MariaDB report with a MySQL server product identity was accepted.'
}
Write-Output 'PASS: reject a schema-3 MariaDB report with a mismatched server product identity'

$maximumPaperWrongProcessProduct = $maximumPaperRuntimeReport | ConvertTo-Json -Depth 24 | ConvertFrom-Json -AsHashtable
$maximumPaperWrongProcessProduct.backendProcesses.mysql.product = 'MySQL'
if (Test-T21MySqlRuntimeReport $maximumPaperWrongProcessProduct $paperMaximumSha $candidateSha -DatabaseProduct MariaDB -PaperEndpoint Maximum) {
    throw 'A schema-3 MariaDB report with a mismatched backend-process product identity was accepted.'
}
Write-Output 'PASS: reject a schema-3 MariaDB report with a mismatched process product identity'

if (Test-T21PathContainsReparsePoint $mysqlWriteLogPath) {
    throw 'A normal temporary Paper report fixture unexpectedly traverses a reparse point.'
}
Write-Output 'PASS: accept the normal report fixture path without reparse points'

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
$tempRootPrefix = $tempRoot + [IO.Path]::DirectorySeparatorChar
$mysqlRuntimeReparseTarget = Join-Path $tempRoot ('syncmoney-t21-mysql-reparse-target-' + [guid]::NewGuid().ToString('N'))
$mysqlRuntimeReparseTarget = [IO.Path]::GetFullPath($mysqlRuntimeReparseTarget)
if (-not $mysqlRuntimeReparseTarget.StartsWith($tempRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create a T21 reparse fixture outside the current temporary directory.'
}
Move-Item -LiteralPath $mysqlRuntimeTestLogs -Destination $mysqlRuntimeReparseTarget
try {
    New-Item -ItemType Junction -Path $mysqlRuntimeTestLogs -Target $mysqlRuntimeReparseTarget -ErrorAction Stop | Out-Null
    if (-not (Test-T21PathContainsReparsePoint $mysqlWriteLogPath)) {
        throw 'The validator did not detect a junction in the retained Paper log path.'
    }
    if (Test-T21MySqlRuntimeReport $mysqlRuntimeReport $paperMinimumSha $candidateSha) {
        throw 'A report that reaches retained Paper logs through a junction was accepted.'
    }
    Write-Output 'PASS: reject a report whose retained Paper log path traverses a junction'
}
finally {
    if (Test-Path -LiteralPath $mysqlRuntimeTestLogs) {
        $junction = Get-Item -LiteralPath $mysqlRuntimeTestLogs -Force
        if (($junction.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            Remove-Item -LiteralPath $mysqlRuntimeTestLogs -Force
        } else {
            throw 'Refusing to replace a non-junction path while restoring the T21 fixture.'
        }
    }
    if (Test-Path -LiteralPath $mysqlRuntimeReparseTarget -PathType Container) {
        if (Test-Path -LiteralPath $mysqlRuntimeTestLogs) {
            throw 'Refusing to restore the original T21 log fixture over an existing path.'
        }
        Move-Item -LiteralPath $mysqlRuntimeReparseTarget -Destination $mysqlRuntimeTestLogs
    }
}

$mysqlRuntimeNestedReparseTarget = Join-Path $tempRoot ('syncmoney-t21-mysql-nested-reparse-target-' + [guid]::NewGuid().ToString('N'))
$mysqlRuntimeNestedReparseTarget = [IO.Path]::GetFullPath($mysqlRuntimeNestedReparseTarget)
$mysqlRuntimeNestedJunction = Join-Path $mysqlRuntimeTestLogs 'nested-log-junction'
if (-not $mysqlRuntimeNestedReparseTarget.StartsWith($tempRootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    (Test-Path -LiteralPath $mysqlRuntimeNestedReparseTarget) -or
    (Test-Path -LiteralPath $mysqlRuntimeNestedJunction)) {
    throw 'Refusing to create the nested T21 junction fixture over an existing or out-of-temp path.'
}
New-Item -ItemType Directory -Path $mysqlRuntimeNestedReparseTarget -ErrorAction Stop | Out-Null
[IO.File]::WriteAllText((Join-Path $mysqlRuntimeNestedReparseTarget 'unmanifested.log'), "hidden retained log`n", [Text.UTF8Encoding]::new($false))
try {
    New-Item -ItemType Junction -Path $mysqlRuntimeNestedJunction -Target $mysqlRuntimeNestedReparseTarget -ErrorAction Stop | Out-Null
    if (-not (Test-T21PathContainsReparsePoint $mysqlRuntimeNestedJunction)) {
        throw 'The validator did not detect a junction nested under the retained log root.'
    }
    if (Test-T21MySqlRuntimeReport $mysqlRuntimeReportV3 $paperMinimumSha $candidateSha) {
        throw 'A schema-3 report with an unmanifested log behind a nested junction was accepted.'
    }
    Write-Output 'PASS: reject an unmanifested log behind a nested log-directory junction'
}
finally {
    if (Test-Path -LiteralPath $mysqlRuntimeNestedJunction) {
        $nestedJunctionItem = Get-Item -LiteralPath $mysqlRuntimeNestedJunction -Force
        if (($nestedJunctionItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) {
            throw 'Refusing to remove a nested T21 fixture path that is not a junction.'
        }
        Remove-Item -LiteralPath $mysqlRuntimeNestedJunction -Force
    }
    if (Test-Path -LiteralPath $mysqlRuntimeNestedReparseTarget -PathType Container) {
        $nestedTargetItem = Get-Item -LiteralPath $mysqlRuntimeNestedReparseTarget -Force
        if (($nestedTargetItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Refusing recursive cleanup of a reparse-point nested T21 target.'
        }
        Remove-Item -LiteralPath $mysqlRuntimeNestedReparseTarget -Recurse -Force
    }
}

$mysqlWriteLogContents = [IO.File]::ReadAllText($mysqlWriteLogPath)
Remove-Item -LiteralPath $mysqlWriteLogPath -Force
if (Test-T21MySqlRuntimeReport $mysqlRuntimeReport $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report whose retained Paper write log is absent was accepted.'
}
Write-Output 'PASS: reject a missing retained Paper write log'
$null = [IO.File]::WriteAllText($mysqlWriteLogPath, $mysqlWriteLogContents, [Text.UTF8Encoding]::new($false))

$null = [IO.File]::WriteAllText($mysqlWriteLogPath, ($mysqlWriteLogContents + 'tampered' + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
if (Test-T21MySqlRuntimeReport $mysqlRuntimeReport $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report whose retained Paper write log changed after hashing was accepted.'
}
Write-Output 'PASS: reject Paper write-log hash drift after capture'
$null = [IO.File]::WriteAllText($mysqlWriteLogPath, $mysqlWriteLogContents, [Text.UTF8Encoding]::new($false))

$mysqlReportMissingWriteMarker = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportMissingWriteMarker[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportMissingWriteMarker.Remove('writeMarker')
if (Test-T21MySqlRuntimeReport $mysqlReportMissingWriteMarker $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report without its captured Vault write marker was accepted.'
}
Write-Output 'PASS: reject missing captured Vault write marker'

$mysqlReportWrongWriteMarker = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongWriteMarker[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongWriteMarker.writeMarker = '[13:32:17] [Paper Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] T21_WRITE_PASS expected-a=1337.25 expected-b=765.50 receipt-a=SUCCESS:DEPOSIT:1337.25:1337.25 receipt-b=SUCCESS:DEPOSIT:765.50:765.50'
if (Test-T21MySqlRuntimeReport $mysqlReportWrongWriteMarker $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report whose write marker does not match the recorded capture was accepted.'
}
Write-Output 'PASS: reject a mismatched captured Vault write marker'

$mysqlReportMissingPaperLog = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportMissingPaperLog[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportMissingPaperLog.logs = [ordered]@{
    'logs\paper-candidate-restart-read.txt' = $mysqlRuntimeReport.logs['logs\paper-candidate-restart-read.txt']
}
if (Test-T21MySqlRuntimeReport $mysqlReportMissingPaperLog $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report without the Paper log containing the accepted write was accepted.'
}
Write-Output 'PASS: reject missing hashed Paper server log'

$mysqlReportPaperLogPinMismatch = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportPaperLogPinMismatch[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportPaperLogPinMismatch.logs = [ordered]@{
    'logs\paper-candidate-write.txt' = [ordered]@{ bytes=1024; sha256=('e' * 64) }
    'logs\paper-candidate-restart-read.txt' = $mysqlRuntimeReport.logs['logs\paper-candidate-restart-read.txt']
}
if (Test-T21MySqlRuntimeReport $mysqlReportPaperLogPinMismatch $paperMinimumSha $candidateSha) {
    throw 'A MySQL SYNC report whose Paper log manifest disagrees with the hashed log map was accepted.'
}
Write-Output 'PASS: reject Paper server-log hash metadata that disagrees with the report log map'

$mysqlReportWrongRestartRead = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongRestartRead[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongRestartRead.restartReadObservation = '[13:32:16] [Folia Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] ACCEPTANCE READ cold-vault-a=NaN cold-vault-unlocked-b=null a=0.00 b=765.50 vault-unlocked-b=765.50'
if (Test-T21MySqlRuntimeReport $mysqlReportWrongRestartRead $paperMinimumSha $candidateSha) {
    throw 'A restart read whose actual SQL-backed balance differs from the accepted write was accepted.'
}
Write-Output 'PASS: reject a restart read with balances that differ from accepted writes'

$mysqlReportColdVaultMismatch = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportColdVaultMismatch[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportColdVaultMismatch.restartReadObservation = '[13:32:16] [Folia Async Scheduler Thread #1/INFO]: [SyncmoneyAcceptance] ACCEPTANCE READ cold-vault-a=0.0 cold-vault-unlocked-b=null a=1337.25 b=765.50 vault-unlocked-b=765.50'
if (Test-T21MySqlRuntimeReport $mysqlReportColdVaultMismatch $paperMinimumSha $candidateSha) {
    throw 'A restart read that violated the accepted cold-Vault unavailable contract was accepted.'
}
Write-Output 'PASS: reject restart read evidence that violates cold-Vault semantics'

$mysqlReportWrongShutdownPid = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongShutdownPid[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongShutdownPid.backendShutdowns = @(
    [ordered]@{ backend='mysql'; command='mysqladmin shutdown'; commandExitCode=0; processId=3333; shutdownTargetVerified=$true; processExitCode=0; processExitCodeObserved=$true; processExited=$true; launchProcessId=1110; launchProcessExitCode=0; launchProcessExitCodeObserved=$true; launchProcessExited=$true; forced=$false },
    $mysqlRuntimeReport.backendShutdowns[1]
)
if (Test-T21MySqlRuntimeReport $mysqlReportWrongShutdownPid $paperMinimumSha $candidateSha) {
    throw 'A MySQL shutdown targeting a different listener process was accepted.'
}
Write-Output 'PASS: reject MySQL shutdown evidence for a different listener process'

$mysqlReportUnverifiedShutdown = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportUnverifiedShutdown[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportUnverifiedShutdown.backendShutdowns = @(
    [ordered]@{ backend='mysql'; command='mysqladmin shutdown'; commandExitCode=0; processId=1111; shutdownTargetVerified=$false; processExitCode=0; processExitCodeObserved=$true; processExited=$true; launchProcessId=1110; launchProcessExitCode=0; launchProcessExitCodeObserved=$true; launchProcessExited=$true; forced=$false },
    $mysqlRuntimeReport.backendShutdowns[1]
)
if (Test-T21MySqlRuntimeReport $mysqlReportUnverifiedShutdown $paperMinimumSha $candidateSha) {
    throw 'A MySQL shutdown without run-owned listener verification was accepted.'
}
Write-Output 'PASS: reject MySQL shutdown evidence without run-owned target verification'

$mysqlReportWrongDialect = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongDialect[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongDialect.configuration = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.configuration.Keys) { $mysqlReportWrongDialect.configuration[$key] = $mysqlRuntimeReport.configuration[$key] }
$mysqlReportWrongDialect.configuration.databaseType = 'postgresql'
if (Test-T21MySqlRuntimeReport $mysqlReportWrongDialect $paperMinimumSha $candidateSha) {
    throw 'A PostgreSQL report was accepted as MySQL dialect evidence.'
}
Write-Output 'PASS: reject a report for the wrong SQL dialect'

$mysqlReportForeignDatabase = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportForeignDatabase[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportForeignDatabase.configuration = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.configuration.Keys) { $mysqlReportForeignDatabase.configuration[$key] = $mysqlRuntimeReport.configuration[$key] }
$mysqlReportForeignDatabase.configuration.databaseHost = '10.10.10.10'
if (Test-T21MySqlRuntimeReport $mysqlReportForeignDatabase $paperMinimumSha $candidateSha) {
    throw 'A report that used a non-loopback SQL host was accepted as isolated test evidence.'
}
Write-Output 'PASS: reject a SQL snapshot from a non-loopback host'

$mysqlReportInvalidPort = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportInvalidPort[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportInvalidPort.configuration = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.configuration.Keys) { $mysqlReportInvalidPort.configuration[$key] = $mysqlRuntimeReport.configuration[$key] }
$mysqlReportInvalidPort.configuration.redisPort = 0
if (Test-T21MySqlRuntimeReport $mysqlReportInvalidPort $paperMinimumSha $candidateSha) {
    throw 'A report with no isolated Redis port was accepted.'
}
Write-Output 'PASS: reject missing isolated backend port evidence'

$mysqlReportForcedBackendStop = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportForcedBackendStop[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportForcedBackendStop.backendShutdowns = @(
    $mysqlRuntimeReport.backendShutdowns[0],
    [ordered]@{ backend='redis'; command='SHUTDOWN SAVE'; commandExitCode=1; processExitCode=0; processExitCodeObserved=$true; processExited=$true; forced=$true }
)
if (Test-T21MySqlRuntimeReport $mysqlReportForcedBackendStop $paperMinimumSha $candidateSha) {
    throw 'A forced Redis cleanup was accepted as normal backend shutdown evidence.'
}
Write-Output 'PASS: reject forced backend cleanup from a passing runtime report'

$mysqlReportMismatch = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportMismatch[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportMismatch.snapshots = @(
    $mysqlRuntimeReport.snapshots[0],
    [ordered]@{
        stage='after-restart-read-stop'
        sqlAccounts=$mysqlRuntimeRows
        redisAccounts=@(
            [ordered]@{ name='AcceptanceA'; uuid=$accountA; balance='1337.25'; version=3 },
            [ordered]@{ name='AcceptanceB'; uuid=$accountB; balance='765.50'; version=2 }
        )
    }
)
if (Test-T21MySqlRuntimeReport $mysqlReportMismatch $paperMinimumSha $candidateSha) {
    throw 'SQL/Redis version disagreement after restart was accepted.'
}
Write-Output 'PASS: reject SQL/Redis version disagreement after restart'

$mysqlReportMissingStop = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportMissingStop[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportMissingStop.normalStops = @($mysqlRuntimeReport.normalStops[0])
if (Test-T21MySqlRuntimeReport $mysqlReportMissingStop $paperMinimumSha $candidateSha) {
    throw 'A report missing the second normal server stop was accepted.'
}
Write-Output 'PASS: reject incomplete candidate restart/shutdown lifecycle evidence'

$mysqlReportWrongPersistedValues = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongPersistedValues[$key] = $mysqlRuntimeReport[$key] }
$wrongPersistedRows = @(
    [ordered]@{ name='AcceptanceA'; uuid=$accountA; balance='0.00'; version=2 },
    [ordered]@{ name='AcceptanceB'; uuid=$accountB; balance='0.00'; version=2 }
)
$mysqlReportWrongPersistedValues.snapshots = @(
    [ordered]@{ stage='after-write-stop'; sqlAccounts=$wrongPersistedRows; redisAccounts=$wrongPersistedRows },
    [ordered]@{ stage='after-restart-read-stop'; sqlAccounts=$wrongPersistedRows; redisAccounts=$wrongPersistedRows }
)
if (Test-T21MySqlRuntimeReport $mysqlReportWrongPersistedValues $paperMinimumSha $candidateSha) {
    throw 'Matching SQL and Redis snapshots with balances different from the accepted Vault writes were accepted.'
}
Write-Output 'PASS: reject SQL/Redis snapshots that agree with each other but disagree with accepted Vault writes'

$mysqlReportWrongStartupIdentity = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongStartupIdentity[$key] = $mysqlRuntimeReport[$key] }
$wrongStartupWrite = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.startupObservations[0].Keys) { $wrongStartupWrite[$key] = $mysqlRuntimeReport.startupObservations[0][$key] }
$wrongStartupWrite.runtimeIdentity = [ordered]@{ software='paper'; runtimeVersion='1.21.6'; minecraftVersion='1.21.6'; build=47 }
$mysqlReportWrongStartupIdentity.startupObservations = @($wrongStartupWrite, $mysqlRuntimeReport.startupObservations[1])
if (Test-T21MySqlRuntimeReport $mysqlReportWrongStartupIdentity $paperMinimumSha $candidateSha) {
    throw 'A Paper startup observation for the wrong build was accepted under the minimum-endpoint report label.'
}
Write-Output 'PASS: reject startup runtime identity that differs from the pinned Paper build'

$mysqlReportWithFailure = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWithFailure[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWithFailure.failure = 'A runtime stage failed.'
if (Test-T21MySqlRuntimeReport $mysqlReportWithFailure $paperMinimumSha $candidateSha) {
    throw 'A passing runtime report with a recorded failure was accepted.'
}
Write-Output 'PASS: reject a passing status with a recorded runtime failure'

$mysqlReportWithCleanupFailure = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWithCleanupFailure[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWithCleanupFailure.cleanupFailures = @('A run-owned process remained after cleanup.')
if (Test-T21MySqlRuntimeReport $mysqlReportWithCleanupFailure $paperMinimumSha $candidateSha) {
    throw 'A passing runtime report with a cleanup failure was accepted.'
}
Write-Output 'PASS: reject a passing status with a cleanup failure'

$mysqlReportNullCleanupFailures = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportNullCleanupFailures[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportNullCleanupFailures.cleanupFailures = $null
if (Test-T21MySqlRuntimeReport $mysqlReportNullCleanupFailures $paperMinimumSha $candidateSha) {
    throw 'A passing runtime report with null cleanupFailures was accepted.'
}
Write-Output 'PASS: reject a passing status with null cleanupFailures'

$mysqlReportConfigAuditMismatch = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportConfigAuditMismatch[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportConfigAuditMismatch.configuration = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.configuration.Keys) { $mysqlReportConfigAuditMismatch.configuration[$key] = $mysqlRuntimeReport.configuration[$key] }
$mysqlReportConfigAuditMismatch.configuration.databaseNameObserved = 'syncmoney_t21_different'
if (Test-T21MySqlRuntimeReport $mysqlReportConfigAuditMismatch $paperMinimumSha $candidateSha) {
    throw 'A runtime report whose captured database name differs from its isolated profile was accepted.'
}
Write-Output 'PASS: reject disagreement between configured and observed SQL schema names'

$mysqlReportWrongBackendVersion = [ordered]@{}
foreach ($key in $mysqlRuntimeReport.Keys) { $mysqlReportWrongBackendVersion[$key] = $mysqlRuntimeReport[$key] }
$mysqlReportWrongBackendVersion.mysql = [ordered]@{ version='8.0.41'; serverVersion='8.0.41' }
if (Test-T21MySqlRuntimeReport $mysqlReportWrongBackendVersion $paperMinimumSha $candidateSha) {
    throw 'A report with conflicting top-level MySQL version evidence was accepted.'
}
Write-Output 'PASS: reject conflicting MySQL runtime version evidence'

$mysqlRunnerPath = Join-Path $PSScriptRoot 'run-mysql-sync-runtime.ps1'
if (-not (Test-Path -LiteralPath $mysqlRunnerPath -PathType Leaf)) {
    throw 'Missing isolated MySQL SYNC runtime runner for the T21 minimum-Paper dialect cell.'
}
$runnerTokens = $null
$runnerParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($mysqlRunnerPath, [ref]$runnerTokens, [ref]$runnerParseErrors) > $null
if (@($runnerParseErrors).Count -gt 0) {
    $details = @($runnerParseErrors | ForEach-Object { '{0}: {1}' -f $_.Extent.StartLineNumber, $_.Message }) -join '; '
    throw "The isolated MySQL SYNC runtime runner has PowerShell parse errors: $details"
}
Write-Output 'PASS: parse the isolated MySQL SYNC runtime runner'

$runnerSource = Get-Content -LiteralPath $mysqlRunnerPath -Raw
if ($runnerSource -notmatch 'Test-T21PostgresRuntimeReport \$script:report \$ExpectedServerSha256 \$ExpectedCandidateSha256 -PlatformEndpoint \$PlatformEndpoint -ExpectedApiConsumerSha256 \$apiConsumerSha256' -or
    $runnerSource -notmatch 'Test-T21MySqlRuntimeReport \$script:report \$ExpectedServerSha256 \$ExpectedCandidateSha256 -DatabaseProduct \$script:databaseProduct -PlatformEndpoint \$PlatformEndpoint -ExpectedApiConsumerSha256 \$apiConsumerSha256' -or
    $runnerSource -notmatch 'apiConsumerMarker = \$apiConsumerMarker') {
    throw 'Schema-4 SQL runtime reports must validate both API-consumer startup markers and bind the recorded SHA-256 to the staged artifact.'
}
Write-Output 'PASS: bind SQL runtime API-consumer markers and SHA-256 to the staged artifact'
if ($runnerSource -notmatch '\$requestedDatabaseProduct\s*=\s*\$DatabaseProduct' -or
    $runnerSource -notmatch '\$script:databaseProduct\s*=\s*Resolve-T21SqlRuntimeProduct\s+\$requestedDatabaseProduct') {
    throw 'The SQL runtime runner does not preserve its requested product before script-scope mapping.'
}
if ($runnerSource -notmatch "ValidateSet\('MySQL', 'MariaDB', 'PostgreSQL'\)" -or
    $runnerSource -notmatch "'postgres\.exe'" -or
    $runnerSource -notmatch "'pg_ctl\.exe'" -or
    $runnerSource -notmatch "'psql\.exe'" -or
    $runnerSource -notmatch "'initdb\.exe'") {
    throw 'The isolated SQL SYNC runtime runner does not expose the pinned PostgreSQL runtime path.'
}
Write-Output 'PASS: declare PostgreSQL binaries in the isolated SQL SYNC runtime runner'
if ($runnerSource -notmatch 'function Invoke-PostgresStart' -or
    $runnerSource -notmatch '\$process\.WaitForExit\(' -or
    $runnerSource -notmatch 'Invoke-PostgresStart \$script:mysqlAdmin' -or
    $runnerSource -match 'Invoke-LoggedCommand \$script:mysqlAdmin @\(''start''') {
    throw 'PostgreSQL startup must wait for the direct pg_ctl process without piping output from its long-lived server child.'
}
Write-Output 'PASS: wait for the pg_ctl startup process directly with file-backed output redirection'

$runnerAst = [System.Management.Automation.Language.Parser]::ParseFile($mysqlRunnerPath, [ref]$runnerTokens, [ref]$runnerParseErrors)
$postgresStartFunction = @($runnerAst.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Invoke-PostgresStart' }, $true))
if ($postgresStartFunction.Count -ne 1) { throw 'Expected exactly one PostgreSQL startup helper to exercise.' }
. ([scriptblock]::Create($postgresStartFunction[0].Extent.Text))
$postgresLauncherTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('syncmoney t21 postgres launcher-' + [guid]::NewGuid().ToString('N'))
$postgresLauncherTestStarted = Join-Path $postgresLauncherTestRoot 'child-started.txt'
$postgresLauncherTestFinished = Join-Path $postgresLauncherTestRoot 'child-finished.txt'
$postgresLauncherTestPid = Join-Path $postgresLauncherTestRoot 'child-pid.txt'
$postgresLauncherChildScript = Join-Path $postgresLauncherTestRoot 'fake-postgres-child.ps1'
$postgresLauncherScript = Join-Path $postgresLauncherTestRoot 'fake-pg-ctl.ps1'
$postgresTimeoutScript = Join-Path $postgresLauncherTestRoot 'fake-pg-ctl-timeout.ps1'
$postgresLauncherTestVariables = @{
    T21_POSTGRES_START_TEST_PWSH = [Environment]::GetEnvironmentVariable('T21_POSTGRES_START_TEST_PWSH', 'Process')
    T21_POSTGRES_START_TEST_STARTED = [Environment]::GetEnvironmentVariable('T21_POSTGRES_START_TEST_STARTED', 'Process')
    T21_POSTGRES_START_TEST_FINISHED = [Environment]::GetEnvironmentVariable('T21_POSTGRES_START_TEST_FINISHED', 'Process')
    T21_POSTGRES_START_TEST_PID = [Environment]::GetEnvironmentVariable('T21_POSTGRES_START_TEST_PID', 'Process')
    T21_POSTGRES_START_TEST_CHILD = [Environment]::GetEnvironmentVariable('T21_POSTGRES_START_TEST_CHILD', 'Process')
    T21_POSTGRES_START_TEST_CLEANUP_STARTED = [Environment]::GetEnvironmentVariable('T21_POSTGRES_START_TEST_CLEANUP_STARTED', 'Process')
}
New-Item -ItemType Directory -Path $postgresLauncherTestRoot -Force | Out-Null
try {
    $env:T21_POSTGRES_START_TEST_PWSH = Join-Path $PSHOME 'pwsh.exe'
    $env:T21_POSTGRES_START_TEST_STARTED = $postgresLauncherTestStarted
    $env:T21_POSTGRES_START_TEST_FINISHED = $postgresLauncherTestFinished
    $env:T21_POSTGRES_START_TEST_PID = $postgresLauncherTestPid
    $env:T21_POSTGRES_START_TEST_CHILD = $postgresLauncherChildScript
    @'
[IO.File]::WriteAllText($env:T21_POSTGRES_START_TEST_STARTED, [string]$PID)
Write-Output 'fake postgres child started'
Start-Sleep -Seconds 8
Write-Output 'fake postgres child finished'
[IO.File]::WriteAllText($env:T21_POSTGRES_START_TEST_FINISHED, 'finished')
'@ | Set-Content -LiteralPath $postgresLauncherChildScript -Encoding utf8
    $verifiedChildCleanup = Get-Command -Name 'Stop-T21VerifiedTestChild' -CommandType Function -ErrorAction SilentlyContinue
    if ($null -eq $verifiedChildCleanup) {
        throw 'The PostgreSQL launcher test requires identity-verified child cleanup before starting any child process.'
    }
    $postgresCleanupTestScript = Join-Path $postgresLauncherTestRoot 'fake-postgres-cleanup-child.ps1'
    $postgresCleanupTestStarted = Join-Path $postgresLauncherTestRoot 'cleanup-child-started.txt'
    $env:T21_POSTGRES_START_TEST_CLEANUP_STARTED = $postgresCleanupTestStarted
    @'
[IO.File]::WriteAllText($env:T21_POSTGRES_START_TEST_CLEANUP_STARTED, [string]$PID)
Start-Sleep -Seconds 30
'@ | Set-Content -LiteralPath $postgresCleanupTestScript -Encoding utf8
    $scriptPathWithSpaces = Join-Path $postgresLauncherTestRoot 'script with spaces.ps1'
    $quotedScriptCommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $scriptPathWithSpaces + '"'
    if (-not (Test-T21CommandLineRunsScript -CommandLine $quotedScriptCommandLine -ExpectedScriptPath $scriptPathWithSpaces)) {
        throw 'Windows argv parsing rejected an exact quoted -File target containing spaces.'
    }
    $embeddedScriptCommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -Command "Write-Output ' + $scriptPathWithSpaces + '"'
    if (Test-T21CommandLineRunsScript -CommandLine $embeddedScriptCommandLine -ExpectedScriptPath $scriptPathWithSpaces) {
        throw 'Windows argv parsing accepted a script path embedded in a non--File argument.'
    }
    $commandThenFileCommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -Command "Start-Sleep -Seconds 30" -File "' + $scriptPathWithSpaces + '"'
    if (Test-T21CommandLineRunsScript -CommandLine $commandThenFileCommandLine -ExpectedScriptPath $scriptPathWithSpaces) {
        throw 'Windows argv parsing accepted a trailing -File token after a PowerShell command mode.'
    }
    $commandAliasThenFileCommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -c "Start-Sleep -Seconds 30" -File "' + $scriptPathWithSpaces + '"'
    $encodedCommandAliasThenFileCommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -ec ZQBjAGgAbwAgACcAdABlAHMAdAAn -File "' + $scriptPathWithSpaces + '"'
    $commandWithArgsAliasThenFileCommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -cwa "Start-Sleep -Seconds 30" -File "' + $scriptPathWithSpaces + '"'
    if ((Test-T21CommandLineRunsScript -CommandLine $commandAliasThenFileCommandLine -ExpectedScriptPath $scriptPathWithSpaces) -or
        (Test-T21CommandLineRunsScript -CommandLine $encodedCommandAliasThenFileCommandLine -ExpectedScriptPath $scriptPathWithSpaces) -or
        (Test-T21CommandLineRunsScript -CommandLine $commandWithArgsAliasThenFileCommandLine -ExpectedScriptPath $scriptPathWithSpaces)) {
        throw 'Windows argv parsing accepted an abbreviated PowerShell command mode followed by a trailing -File token.'
    }
    $longerScriptCommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -NoProfile -File "' + $scriptPathWithSpaces + '.backup"'
    if (Test-T21CommandLineRunsScript -CommandLine $longerScriptCommandLine -ExpectedScriptPath $scriptPathWithSpaces) {
        throw 'Windows argv parsing accepted a longer path that only contains the expected script path as a prefix.'
    }
    Write-Output 'PASS: identify only the exact quoted Windows -File target, including paths with spaces'

    $postgresCleanupChild = $null
    try {
        $postgresCleanupChild = Start-Process -FilePath $env:T21_POSTGRES_START_TEST_PWSH `
            -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $postgresCleanupTestScript + '"')) `
            -NoNewWindow -PassThru
        $postgresCleanupDeadline = [DateTime]::UtcNow.AddSeconds(5)
        while (-not (Test-Path -LiteralPath $postgresCleanupTestStarted -PathType Leaf) -and [DateTime]::UtcNow -lt $postgresCleanupDeadline) {
            Start-Sleep -Milliseconds 50
        }
        if (-not (Test-Path -LiteralPath $postgresCleanupTestStarted -PathType Leaf)) {
            throw 'The cleanup fixture child did not start.'
        }
        $script:cleanupMockProcessInfo = [pscustomobject]@{
            ExecutablePath = $env:T21_POSTGRES_START_TEST_PWSH
            CommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -Command "Write-Output ' + $postgresCleanupTestScript + '"'
        }
        $embeddedScriptCleanupResult = & {
            function Get-CimInstance { return $script:cleanupMockProcessInfo }
            Stop-T21VerifiedTestChild -ProcessId $postgresCleanupChild.Id `
                -ExpectedExecutablePath $env:T21_POSTGRES_START_TEST_PWSH -ExpectedScriptPath $postgresCleanupTestScript
        }
        if ($embeddedScriptCleanupResult -or $null -eq (Get-Process -Id $postgresCleanupChild.Id -ErrorAction SilentlyContinue)) {
            throw 'The child cleanup helper accepted a script path embedded in a non--File argument and terminated the fixture process.'
        }
        $script:cleanupMockProcessInfo.CommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -Command "Start-Sleep -Seconds 30" -File "' + $postgresCleanupTestScript + '"'
        $commandThenFileCleanupResult = & {
            function Get-CimInstance { return $script:cleanupMockProcessInfo }
            Stop-T21VerifiedTestChild -ProcessId $postgresCleanupChild.Id `
                -ExpectedExecutablePath $env:T21_POSTGRES_START_TEST_PWSH -ExpectedScriptPath $postgresCleanupTestScript
        }
        if ($commandThenFileCleanupResult -or $null -eq (Get-Process -Id $postgresCleanupChild.Id -ErrorAction SilentlyContinue)) {
            throw 'The child cleanup helper accepted a trailing -File token after -Command or terminated the fixture process.'
        }
        $script:cleanupMockProcessInfo.CommandLine = '"' + $env:T21_POSTGRES_START_TEST_PWSH + '" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $postgresCleanupTestScript + '"'
        $wrongExecutableCleanupResult = & {
            function Get-CimInstance { return $script:cleanupMockProcessInfo }
            Stop-T21VerifiedTestChild -ProcessId $postgresCleanupChild.Id `
                -ExpectedExecutablePath (Join-Path $env:TEMP 'syncmoney-t21-wrong-pwsh.exe') -ExpectedScriptPath $postgresCleanupTestScript
        }
        if ($wrongExecutableCleanupResult -or $null -eq (Get-Process -Id $postgresCleanupChild.Id -ErrorAction SilentlyContinue)) {
            throw 'The child cleanup helper accepted an executable mismatch or terminated the fixture process.'
        }
        $selfCleanupResult = Stop-T21VerifiedTestChild -ProcessId $PID `
            -ExpectedExecutablePath $env:T21_POSTGRES_START_TEST_PWSH -ExpectedScriptPath $postgresLauncherChildScript
        if ($selfCleanupResult -or $null -eq (Get-Process -Id $PID -ErrorAction SilentlyContinue)) {
            throw 'The child cleanup helper did not refuse the test runner process when its command line did not match the unique child script.'
        }
        $verifiedCleanupResult = Stop-T21VerifiedTestChild -ProcessId $postgresCleanupChild.Id `
            -ExpectedExecutablePath $env:T21_POSTGRES_START_TEST_PWSH -ExpectedScriptPath $postgresCleanupTestScript
        if (-not $verifiedCleanupResult -or -not $postgresCleanupChild.WaitForExit(3000)) {
            throw 'The child cleanup helper did not terminate the process whose executable and command line matched the unique child script.'
        }
        Write-Output 'PASS: verify child executable and unique script before terminating through its process handle'
    } finally {
        if ($null -ne $postgresCleanupChild) {
            $postgresCleanupChild.Refresh()
            if (-not $postgresCleanupChild.HasExited) {
                try {
                    $postgresCleanupChild.Kill()
                    $null = $postgresCleanupChild.WaitForExit(3000)
                } catch { }
            }
        }
    }
    @'
$child = Start-Process -FilePath $env:T21_POSTGRES_START_TEST_PWSH `
    -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $env:T21_POSTGRES_START_TEST_CHILD + '"')) `
    -NoNewWindow -PassThru
[IO.File]::WriteAllText($env:T21_POSTGRES_START_TEST_PID, [string]$child.Id)
'@ | Set-Content -LiteralPath $postgresLauncherScript -Encoding utf8
    'Start-Sleep -Seconds 30' | Set-Content -LiteralPath $postgresTimeoutScript -Encoding utf8

    $postgresLauncherTimer = [Diagnostics.Stopwatch]::StartNew()
    $postgresLauncherResult = Invoke-PostgresStart $env:T21_POSTGRES_START_TEST_PWSH `
        @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $postgresLauncherScript) `
        'fake-postgres-start' (Join-Path $postgresLauncherTestRoot 'fake-start.log') 5
    $postgresLauncherTimer.Stop()
    if ($postgresLauncherResult.exitCode -ne 0 -or -not $postgresLauncherResult.processExited -or
        $postgresLauncherTimer.Elapsed.TotalSeconds -ge 5 -or -not (Test-Path -LiteralPath $postgresLauncherTestPid -PathType Leaf)) {
        throw 'The PostgreSQL startup helper did not return after its direct launcher exited while a child process retained inherited output handles.'
    }
    $postgresChildPid = [int](Get-Content -LiteralPath $postgresLauncherTestPid -Raw)
    if ($null -eq (Get-Process -Id $postgresChildPid -ErrorAction SilentlyContinue) -or
        (Test-Path -LiteralPath $postgresLauncherTestFinished -PathType Leaf)) {
        throw 'The fake PostgreSQL child did not remain alive after its direct launcher exited.'
    }
    $postgresChildDeadline = [DateTime]::UtcNow.AddSeconds(12)
    while (-not (Test-Path -LiteralPath $postgresLauncherTestFinished -PathType Leaf) -and [DateTime]::UtcNow -lt $postgresChildDeadline) {
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $postgresLauncherTestFinished -PathType Leaf)) {
        throw 'The fake PostgreSQL child did not finish its delayed output after the launcher returned.'
    }
    Write-Output 'PASS: execute PostgreSQL startup helper with a launcher whose child keeps inherited output handles open'

    $postgresTimeoutLog = Join-Path $postgresLauncherTestRoot 'fake-timeout.log'
    $postgresTimeoutError = $null
    try {
        $null = Invoke-PostgresStart $env:T21_POSTGRES_START_TEST_PWSH `
            @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $postgresTimeoutScript) `
            'fake-postgres-timeout' $postgresTimeoutLog 2
    } catch {
        $postgresTimeoutError = $_.Exception.Message
    }
    if ($null -eq $postgresTimeoutError -or $postgresTimeoutError -notlike '*did not exit within 2 seconds*' -or
        -not (Test-Path -LiteralPath $postgresTimeoutLog -PathType Leaf)) {
        throw 'The PostgreSQL startup helper did not report and clean up its timed-out direct launcher.'
    }
    $postgresTimeoutEvidence = Get-Content -LiteralPath $postgresTimeoutLog -Raw
    if ($postgresTimeoutEvidence -notmatch '(?m)^processExitedAfterCleanup=True\r?$' -or
        $postgresTimeoutEvidence -notmatch '(?m)^cleanupAttempted=True\r?$') {
        throw "The PostgreSQL startup timeout log did not confirm bounded cleanup of its direct launcher. Observed log: $postgresTimeoutEvidence"
    }
    Write-Output 'PASS: terminate and record a timed-out direct PostgreSQL launcher'
} finally {
    $childPidText = if (Test-Path -LiteralPath $postgresLauncherTestPid -PathType Leaf) { (Get-Content -LiteralPath $postgresLauncherTestPid -Raw).Trim() } else { '' }
    $testChildPid = 0
    if ([int]::TryParse($childPidText, [ref]$testChildPid) -and $testChildPid -gt 0) {
        $childCleanupComplete = Stop-T21VerifiedTestChild -ProcessId $testChildPid `
            -ExpectedExecutablePath $env:T21_POSTGRES_START_TEST_PWSH -ExpectedScriptPath $postgresLauncherChildScript
        $savedChildProcessStillExists = Get-Process -Id $testChildPid -ErrorAction SilentlyContinue
        if (-not $childCleanupComplete -and $null -ne $savedChildProcessStillExists) {
            Write-Warning 'Did not stop the saved PostgreSQL test child PID because its executable and unique script identity could not be verified.'
        }
    }
    foreach ($name in $postgresLauncherTestVariables.Keys) {
        [Environment]::SetEnvironmentVariable($name, $postgresLauncherTestVariables[$name], 'Process')
    }
    if (Test-Path -LiteralPath $postgresLauncherTestRoot -PathType Container) {
        Remove-Item -LiteralPath $postgresLauncherTestRoot -Recurse -Force
    }
}

$postgresStopFunction = @($runnerAst.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Stop-PostgresBackend' }, $true))
if ($postgresStopFunction.Count -ne 1) { throw 'Expected exactly one PostgreSQL shutdown helper to exercise.' }
. ([scriptblock]::Create($postgresStopFunction[0].Extent.Text))
$postgresStopTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('syncmoney-t21-postgres-stop-' + [guid]::NewGuid().ToString('N'))
$script:mysqlData = Join-Path $postgresStopTestRoot 'postgresql-data'
$script:logsRoot = Join-Path $postgresStopTestRoot 'logs'
$script:mysqlPort = 15432
$script:mysqlAdmin = 'pg_ctl.exe'
$script:postgresStopTestIdentityValid = $true
$script:postgresStopTestCommandCount = 0
$script:postgresStopTestProcess = [pscustomobject]@{ Id=4242; HasExited=$false; ExitCode=0 }
$script:postgresStopTestProcess | Add-Member -MemberType ScriptMethod -Name Refresh -Value { }
$script:postgresStopTestProcess | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value {
    param([int]$timeoutMilliseconds)
    $this.HasExited = $true
    return $true
}
$script:postgresStopTestProcess | Add-Member -MemberType ScriptMethod -Name Kill -Value { $this.HasExited = $true }
$script:cleanupFailures = [Collections.Generic.List[string]]::new()
$script:backendShutdowns = [Collections.Generic.List[object]]::new()
$script:mysqlProcess = $null
New-Item -ItemType Directory -Path $script:mysqlData -Force | Out-Null
New-Item -ItemType Directory -Path $script:logsRoot -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $script:mysqlData 'postmaster.pid'), "4242`n$script:mysqlData`n1781298897`n15432`n")
function Get-PostgresListenerProcess { return $null }
function Get-PostgresPidFileProcess {
    if (-not $script:postgresStopTestIdentityValid) { throw 'fixture process identity rejected' }
    return [ordered]@{ process=$script:postgresStopTestProcess; processInfo=$null; pidLines=@() }
}
function Invoke-LoggedCommand {
    param([string]$executable, [string[]]$arguments, [string]$label, [string]$logPath)
    $script:postgresStopTestCommandCount++
    $script:postgresStopTestProcess.HasExited = $true
    return [pscustomobject]@{ exitCode=0; output=''; logPath=$logPath }
}
function Get-NetTCPConnection {
    [CmdletBinding()]
    param([string]$State, [int]$LocalPort)
    return @()
}
try {
    Stop-PostgresBackend
    if ($script:postgresStopTestCommandCount -ne 1 -or $script:backendShutdowns.Count -ne 1 -or
        -not $script:backendShutdowns[0].shutdownTargetVerified -or -not $script:backendShutdowns[0].processExited -or
        $script:backendShutdowns[0].forced -or $script:cleanupFailures.Count -ne 0) {
        throw 'A verified postmaster.pid process without a listener did not receive clean graceful shutdown evidence.'
    }
    Write-Output 'PASS: gracefully stop the verified PostgreSQL postmaster from postmaster.pid when no listener exists'

    $script:mysqlProcess = $null
    $script:postgresStopTestCommandCount = 0
    $script:postgresStopTestIdentityValid = $false
    $script:cleanupFailures.Clear()
    $script:backendShutdowns.Clear()
    Stop-PostgresBackend
    if ($script:postgresStopTestCommandCount -ne 0 -or $script:backendShutdowns.Count -ne 0 -or $script:cleanupFailures.Count -eq 0) {
        throw 'PostgreSQL shutdown was attempted without a verified process identity.'
    }
    Write-Output 'PASS: refuse PostgreSQL shutdown when postmaster.pid process identity cannot be verified'
} finally {
    if (Test-Path -LiteralPath $postgresStopTestRoot -PathType Container) {
        Remove-Item -LiteralPath $postgresStopTestRoot -Recurse -Force
    }
}

$mysqlPortAssignments = [regex]::Matches($runnerSource, '(?m)^\s*\$script:mysqlPort\s*=\s*Get-FreeLoopbackPort')
if ($mysqlPortAssignments.Count -ne 1) {
    throw "The isolated MySQL SYNC runtime runner must allocate its database port once before publishing it to services and PlugDev; observed $($mysqlPortAssignments.Count) allocations."
}
Write-Output 'PASS: keep one reserved MySQL port across backend and PlugDev configuration'
if ($runnerSource -notmatch "'mariadb-install-db\.exe'" -or
    $runnerSource -notmatch "'--allow-remote-root-access'" -or
    $runnerSource -match "'--service(?:=|')") {
    throw 'MariaDB initialization must use the pinned isolated data directory and must not install a Windows service.'
}
Write-Output 'PASS: initialize MariaDB without registering a Windows service'
$mysqlAdminStart = $runnerSource.IndexOf('function Invoke-MySqlAdmin', [StringComparison]::Ordinal)
$mysqlListenerStart = $runnerSource.IndexOf('function Assert-BackendListenerOwner', [StringComparison]::Ordinal)
if ($mysqlAdminStart -lt 0 -or $mysqlListenerStart -le $mysqlAdminStart) {
    throw 'Could not isolate the MySQL-protocol admin helper for credential-mode validation.'
}
$mysqlAdminSource = $runnerSource.Substring($mysqlAdminStart, $mysqlListenerStart - $mysqlAdminStart)
if (-not $mysqlAdminSource.Contains('MYSQL_PWD') -or
    -not $mysqlAdminSource.Contains('MariaDB') -or
    -not $mysqlAdminSource.Contains('$script:databasePassword') -or
    -not $mysqlAdminSource.Contains("else { '' }")) {
    throw 'The MariaDB mysqladmin helper must authenticate with the isolated root password while MySQL retains passwordless root.'
}
Write-Output 'PASS: authenticate MariaDB admin calls with the isolated root password'

$mysqlSecurityHelperPattern = '(?s)function Get-MySqlLoopbackClientArgs(?:\(\))?\s*\{(?:(?!function ).)*''MariaDB''(?:(?!function ).)*''--skip-ssl-verify-server-cert''(?:(?!function ).)*return @\(\)(?:(?!function ).)*\}'
if ($runnerSource -notmatch $mysqlSecurityHelperPattern -or
    [regex]::Matches($runnerSource, '\(Get-MySqlLoopbackClientArgs\)').Count -ne 2) {
    throw 'MariaDB client calls must explicitly skip certificate verification for isolated loopback connections, without changing MySQL client options.'
}
Write-Output 'PASS: keep MariaDB loopback client output free of default TLS verification warnings'

if ($runnerSource -notmatch "Invoke-PlugDev @\('server', 'command', 'stop'\)" -or
    $runnerSource -match "Invoke-PlugDev @\('server', 'stop'\)") {
    throw 'The runtime runner must send a graceful RCON stop command and then observe process exit; PlugDev server stop may terminate its process tree before plugin shutdown completes.'
}
Write-Output 'PASS: use graceful RCON stop and observe normal plugin shutdown'

if ($runnerSource -notmatch '\$processExitCode\s*=\s*\[int\]\$serverProcess\.ExitCode' -or
    $runnerSource -notmatch 'processExitCodeObserved\s*=\s*\$true') {
    throw 'The runtime report must capture the actual run-owned Paper exit code instead of synthesizing a successful shutdown.'
}
Write-Output 'PASS: report the observed run-owned Paper exit code'

if ($runnerSource -notmatch '--database=\$script:databaseName') {
    throw 'MySQL snapshot reads must select the isolated schema instead of relying on a default database.'
}
Write-Output 'PASS: bind MySQL snapshots to the isolated schema'

$mysqlShutdownCallIndex = $runnerSource.IndexOf('try { Stop-MySqlBackend }', [StringComparison]::Ordinal)
$redisShutdownCallIndex = $runnerSource.IndexOf('try { Stop-RedisBackend }', [StringComparison]::Ordinal)
$logHashIndex = $runnerSource.IndexOf('foreach ($log in @(Get-ChildItem -LiteralPath $script:logsRoot -File -Recurse))', [StringComparison]::Ordinal)
if ($mysqlShutdownCallIndex -lt 0 -or $redisShutdownCallIndex -lt 0 -or $logHashIndex -lt 0 -or
    $logHashIndex -lt [Math]::Max($mysqlShutdownCallIndex, $redisShutdownCallIndex)) {
    throw 'Log hashes must be computed only after run-owned MySQL and Redis processes release their redirected log files.'
}
Write-Output 'PASS: hash runtime logs after isolated backends exit'

if ($runnerSource -notmatch 'backend=''redis''; command=''SHUTDOWN SAVE''; commandExitCode=\$commandExitCode; processId=\[int\]\$process\.Id') {
    throw 'The Redis shutdown observation must bind its exit evidence to the run-owned backend process ID.'
}
Write-Output 'PASS: bind Redis shutdown evidence to the run-owned process ID'

if ($runnerSource -notmatch 'Get-T21PlatformEndpoint -PlatformEndpoint \$PlatformEndpoint' -or
    $runnerSource -notmatch '\[string\]\$RuntimeReportsRoot' -or
    $runnerSource -notmatch 'Resolve-T21RuntimeReportsRoot \$script:root \$RuntimeReportsRoot' -or
    $runnerSource -notmatch 'Copy-Item -LiteralPath \$script:serverLogPath -Destination \$platformLogPath' -or
    $runnerSource -notmatch '\$script:report\.platformLogs = @\(\$script:platformServerLogs\)' -or
    $runnerSource -notmatch 'schemaVersion = 4') {
    throw 'The runner must select an exact user-pinned platform endpoint and report schema-4 platform-owned stage logs.'
}
Write-Output 'PASS: preserve and report stage-specific platform logs under schema 4'

$upgradeCycleRunnerPath = Join-Path $PSScriptRoot 'run-upgrade-cycle.ps1'
$upgradeCycleTokens = $null
$upgradeCycleParseErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile($upgradeCycleRunnerPath, [ref]$upgradeCycleTokens, [ref]$upgradeCycleParseErrors)
if ($upgradeCycleParseErrors.Count -gt 0) {
    $details = @($upgradeCycleParseErrors | ForEach-Object { '{0}: {1}' -f $_.Extent.StartLineNumber, $_.Message }) -join '; '
    throw "The M04 LOCAL upgrade-cycle runner has PowerShell parse errors: $details"
}
Write-Output 'PASS: parse the M04 LOCAL upgrade-cycle runner'

$upgradeCycleSource = [IO.File]::ReadAllText($upgradeCycleRunnerPath)
$configObservationCalls = [regex]::Matches($upgradeCycleSource, "Invoke-ProfileEditor '(?:seed-config|assert-config)'").Count
$timeoutHandoffBranchMatch = [regex]::Match(
    $upgradeCycleSource,
    '(?s)if \(\$RecoveryScenario -eq ''shutdown-timeout-handoff''\) \{(?<branch>.*?)\r?\n\s*\} else \{'
)
$timeoutHandoffConfigObservationCalls = if ($timeoutHandoffBranchMatch.Success) {
    [regex]::Matches($timeoutHandoffBranchMatch.Groups['branch'].Value, "Invoke-ProfileEditor '(?:seed-config|assert-config)'").Count
} else {
    0
}
if ($configObservationCalls -ne 9 -or $timeoutHandoffConfigObservationCalls -ne 3) {
    throw "The M04 runner must retain six normal config observations and add three timeout-handoff observations; found $configObservationCalls total / $timeoutHandoffConfigObservationCalls timeout-specific."
}
$directPlugDevCliCalls = [regex]::Matches($upgradeCycleSource, '(?m)^\s*&\s+node\s+\$cli\b').Count
$verifiedPlugDevCliCallSites = [regex]::Matches($upgradeCycleSource, '(?m)^\s*(?:\$\w+\s*=\s*)?Invoke-VerifiedPlugDevCli\s+-Arguments\s+@\(').Count
if ($directPlugDevCliCalls -ne 1 -or $verifiedPlugDevCliCallSites -ne 5 -or
    $upgradeCycleSource -notmatch 'Assert-PlugDevDistributionPinned "\$stage invocation"' -or
    $upgradeCycleSource -notmatch 'Assert-PlugDevDistributionPinned "\$stage completion"' -or
    $upgradeCycleSource -notmatch 'Get-ChildItem -LiteralPath \$distRoot -File -Recurse -Force' -or
    $upgradeCycleSource -notmatch 'Get-ChildItem -LiteralPath \$logs -File -Recurse -Force') {
    throw 'Every M04 PlugDev CLI invocation must pin the complete distribution, including hidden files, and each log manifest must include hidden logs.'
}
Write-Output 'PASS: pin the complete local PlugDev CLI distribution around every invocation'

if (-not $upgradeCycleSource.Contains(".Replace('\', '/')") -or
    -not $upgradeCycleSource.Contains(".Replace('/', '\')") -or
    $upgradeCycleSource -notmatch 'plugDevDistManifest\s*=\s*@\(\$plugDevDistManifest\)' -or
    $upgradeCycleSource -notmatch 'plugDevDistSha256\s*=\s*\$plugDevDistSha256') {
    throw 'M04 must normalize and report every PlugDev distribution path and its manifest digest.'
}
Write-Output 'PASS: normalize and report the PlugDev distribution manifest'

if ($upgradeCycleSource -notmatch 'Copy-Item -LiteralPath \$serverJarSourcePath -Destination \$stagedServerJarPath' -or
    $upgradeCycleSource -notmatch 'Copy-Item -LiteralPath \$candidateJar -Destination \$candidateRunJar' -or
    $upgradeCycleSource -notmatch 'Copy-Item -LiteralPath \$baselineJarPath -Destination \$baselineRunJar' -or
    $upgradeCycleSource -notmatch 'Copy-Item -LiteralPath \$acceptanceJar -Destination \$acceptanceRunJar' -or
    $upgradeCycleSource -notmatch 'Copy-Item -LiteralPath \$apiConsumerJar -Destination \$apiConsumerRunJar' -or
    $upgradeCycleSource -notmatch '\$env:SYNCMONEY_TEST_SERVER_JAR\s*=\s*\$stagedServerJarPath' -or
    $upgradeCycleSource -notmatch 'artifactManifest\s*=\s*@\(\$script:artifactManifest\)' -or
    $upgradeCycleSource -notmatch 'expectedServerSha256\s*=\s*\$ExpectedServerSha256\.ToLowerInvariant\(\)' -or
    $upgradeCycleSource -notmatch 'expectedBaselinePluginSha256\s*=\s*\$ExpectedBaselinePluginSha256\.ToLowerInvariant\(\)' -or
    $upgradeCycleSource -notmatch 'plugDevDistManifest\s*=\s*@\(\$plugDevDistManifest\)' -or
    $upgradeCycleSource -notmatch 'function Register-VaultUnlockedArtifacts' -or
    $upgradeCycleSource -notmatch 'Register-VaultUnlockedArtifacts' -or
    $upgradeCycleSource -notmatch 'name = ''vault-unlocked-cache''' -or
    $upgradeCycleSource -notmatch 'name = ''vault-unlocked-runtime''' -or
    $upgradeCycleSource -notmatch 'vaultUnlockedDependencyVersion = ''2\.20\.2''' -or
    $upgradeCycleSource -notmatch 'vaultUnlockedPluginSha256 = \$script:vaultUnlockedPluginSha256' -or
    $upgradeCycleSource -notmatch 'function Register-RuntimeHarnessArtifacts' -or
    $upgradeCycleSource -notmatch "name = 'acceptance-runtime'" -or
    $upgradeCycleSource -notmatch "name = 'api-consumer-runtime'" -or
    $upgradeCycleSource -notmatch "relativePath = '\.plugdev/run/plugins/SyncmoneyAcceptance\.jar'" -or
    $upgradeCycleSource -notmatch "relativePath = '\.plugdev/run/plugins/SyncmoneyApiConsumer\.jar'" -or
    $upgradeCycleSource -notmatch "Assert-StagedArtifactsPinned 'runtime harness preparation'" -or
    -not $upgradeCycleSource.Contains('Vault \(2\.20\.2\)') -or
    $upgradeCycleSource -notmatch 'function Assert-RunInputsUnchanged' -or
    $upgradeCycleSource -notmatch 'Assert-RunInputsUnchanged' -or
    $upgradeCycleSource -notmatch 'Assert-StagedArtifactsPinned "\$stage invocation"' -or
    $upgradeCycleSource -notmatch 'Assert-StagedArtifactsPinned "\$stage completion"') {
    throw 'M04 must launch the staged server artifact, pin every staged JAR, and include the staged manifest in its result.'
}
Write-Output 'PASS: run and report isolated, SHA-256-pinned M04 artifacts'

$upgradeCycleAuditPath = Join-Path $PSScriptRoot 't21-upgrade-cycle-audit.ps1'
if (-not (Test-Path -LiteralPath $upgradeCycleAuditPath -PathType Leaf)) {
    throw 'The M04 LOCAL upgrade-cycle report requires a reusable offline artifact and log auditor.'
}
$upgradeCycleAuditTokens = $null
$upgradeCycleAuditParseErrors = $null
$null = [System.Management.Automation.Language.Parser]::ParseFile($upgradeCycleAuditPath, [ref]$upgradeCycleAuditTokens, [ref]$upgradeCycleAuditParseErrors)
if ($upgradeCycleAuditParseErrors.Count -gt 0) {
    $details = @($upgradeCycleAuditParseErrors | ForEach-Object { '{0}: {1}' -f $_.Extent.StartLineNumber, $_.Message }) -join '; '
    throw "The M04 offline report auditor has PowerShell parse errors: $details"
}
$upgradeCycleAuditSource = [IO.File]::ReadAllText($upgradeCycleAuditPath)
$upgradeCycleAuditContractTestPath = Join-Path $PSScriptRoot 't21-upgrade-cycle-audit-contract.test.ps1'
if (-not (Test-Path -LiteralPath $upgradeCycleAuditContractTestPath -PathType Leaf)) {
    throw 'The M04 offline auditor requires behavioral regression coverage for replay chronology and receipt binding.'
}
$expectedM04DatabasePath = 'expectedDatabaseRelativePath = ''.plugdev\run\plugins\Syncmoney\t21-data\legacy-local.db'''
$m04DatabasePathBinding = 'Assert-Equal ''report SQLite path matches M04 runtime fixture'' $expectedDatabaseRelativePath ([string]$report.sqlitePath)'
$expectedM04StartPins = @(
    '$expectedStartRecords.Add([ordered]@{ stage = ''baseline-1.3.2''; pluginVersion = ''1.3.2''; apiClassLoader = ''NOT_APPLICABLE'' })',
    '$expectedStartRecords.Add([ordered]@{ stage = ''upgrade-1.4''; pluginVersion = [string]$report.candidatePlugin; apiClassLoader = ''PASS'' })',
    '$expectedStartRecords.Add([ordered]@{ stage = ''recovery-1.4''; pluginVersion = [string]$report.candidatePlugin; apiClassLoader = ''PASS'' })',
    '$expectedStartRecords.Add([ordered]@{ stage = ''rollback-1.3.2''; pluginVersion = ''1.3.2''; apiClassLoader = ''NOT_APPLICABLE'' })'
)
if ($upgradeCycleAuditSource -notmatch 'artifactManifest' -or
    $upgradeCycleAuditSource -notmatch "'acceptance-runtime'" -or
    $upgradeCycleAuditSource -notmatch "'api-consumer-runtime'" -or
    $upgradeCycleAuditSource -notmatch 'Test-T21M04RuntimeArtifactCopy' -or
    -not $upgradeCycleAuditSource.Contains("Assert-Equal 'Acceptance staged build artifact path' 'build/acceptance/SyncmoneyAcceptance.jar'") -or
    -not $upgradeCycleAuditSource.Contains("Assert-Equal 'API consumer staged build artifact path' 'build/acceptance/SyncmoneyApiConsumer.jar'") -or
    -not $upgradeCycleAuditSource.Contains("Assert-Equal 'Acceptance runtime artifact path' '.plugdev/run/plugins/SyncmoneyAcceptance.jar'") -or
    -not $upgradeCycleAuditSource.Contains("Assert-Equal 'API consumer runtime artifact path' '.plugdev/run/plugins/SyncmoneyApiConsumer.jar'") -or
    $upgradeCycleAuditSource -notmatch 'vault-unlocked-cache' -or
    $upgradeCycleAuditSource -notmatch 'vault-unlocked-runtime' -or
    $upgradeCycleAuditSource -notmatch 'vaultUnlockedPluginSha256' -or
    $upgradeCycleAuditSource -notmatch 'vaultUnlockedDependencyVersion' -or
    -not $upgradeCycleAuditSource.Contains("Assert-Equal 'VaultUnlocked cache and runtime hashes match'") -or
    -not $upgradeCycleAuditSource.Contains("Assert-Equal 'VaultUnlocked loaded artifact path' '.plugdev/run/plugins/TNE-VaultUnlocked-2.20.2.jar'") -or
    -not $upgradeCycleAuditSource.Contains('Vault \(2\.20\.2\)') -or
    $upgradeCycleAuditSource -notmatch 'plugDevDistManifest' -or
    $upgradeCycleAuditSource -notmatch 'logManifest' -or
    $upgradeCycleAuditSource -notmatch 'Get-FileHash' -or
    $upgradeCycleAuditSource -notmatch 'Get-ChildItem -LiteralPath \$distRoot -File -Recurse -Force' -or
    $upgradeCycleAuditSource -notmatch 'Get-ChildItem -LiteralPath \$logsRoot -File -Recurse -Force' -or
    $upgradeCycleAuditSource -notmatch 'Test-T21M04PlatformStartupIdentity' -or
    $upgradeCycleAuditSource -notmatch 'function Assert-T21StartupRecoveryChronology' -or
    $upgradeCycleAuditSource -notmatch 'Assert-T21StartupRecoveryChronology \$upgradeLog' -or
    $upgradeCycleAuditSource -notmatch 'function Assert-T21SourcePin' -or
    $upgradeCycleAuditSource -notmatch 'Assert-T21SourcePin \$sourcePin\.name' -or
    $upgradeCycleAuditSource -notmatch 'function Assert-T21ReplayReceiptBinding' -or
    $upgradeCycleAuditSource -notmatch 'Assert-T21ReplayReceiptBinding \$observation \$expectedReplayedTransaction \$replayedTransaction' -or
    $upgradeCycleAuditSource -notmatch 'Get-ChildItem -LiteralPath \$runRoot -File -Recurse -Filter ''recovery-v1\.jsonl'' -Force' -or
    -not $upgradeCycleAuditSource.Contains($expectedM04DatabasePath) -or
    -not $upgradeCycleAuditSource.Contains($m04DatabasePathBinding) -or
    -not $upgradeCycleAuditSource.Contains("Assert-Equal 'complete stage record count' `$expectedStageRecordCount ([string]`$stageRecords.Count)") -or
    -not $upgradeCycleAuditSource.Contains("Assert-Equal 'invalid stage record shape count' '0' ([string]`$invalidStageRecords.Count)") -or
    -not $upgradeCycleAuditSource.Contains("Assert-True 'M04 lifecycle stage chronology' (Test-T21M04StageRecordChronology `$stageRecords `$expectedLifecycleStages)") -or
    $upgradeCycleAuditSource -notmatch 'shutdown-timeout-handoff' -or
    $upgradeCycleAuditSource -notmatch 'Read-T21AuditLocalJournal' -or
    $upgradeCycleAuditSource -notmatch 'Recovered 2 accepted LOCAL SQLite write\(s\) from the shutdown journal' -or
    $upgradeCycleAuditSource -notmatch 'inputs/pre-timeout-handoff-local\.db' -or
    $upgradeCycleAuditSource -notmatch 'inputs/timeout-handoff-local-persistence\.journal' -or
    $upgradeCycleAuditSource -notmatch 'validationSha256' -or
    $upgradeCycleAuditSource -notmatch '\$expectedStartRecords\s*=\s*\[System\.Collections\.Generic\.List\[object\]\]::new\(\)' -or
    $upgradeCycleAuditSource -notmatch 'start record count for \$\(\$expectedStart\.stage\)' -or
    $upgradeCycleAuditSource -notmatch 'stop record count for \$stage' -or
    $upgradeCycleAuditSource -notmatch 'API consumer marker for \$\(\$expectedStart\.stage\)' -or
    -not $upgradeCycleAuditSource.Contains('$upgradeStartRecords = @($startRecords | Where-Object { [string]$_.stage -ceq ''upgrade-1.4'' -and [string]$_.pluginVersion -ceq [string]$report.candidatePlugin })') -or
    -not $upgradeCycleAuditSource.Contains('Assert-Equal ''candidate API consumer classloader marker'' ''PASS'' ([string]$upgradeStartRecords[0].apiClassLoader)') -or
    -not $upgradeCycleAuditSource.Contains('API CLASSLOADER PASS provider=Syncmoney') -or
    -not $upgradeCycleAuditSource.Contains('T21_LEGACY_SQLITE_PASS operation=write expected-a=1337.25 expected-b=765.50') -or
    $upgradeCycleAuditSource -notmatch 'same-request SQLite receipt predates replay' -or
    $upgradeCycleAuditSource -notmatch 'snapshot account rows match receipt state' -or
    $upgradeCycleAuditSource -notmatch 'Assert-T21AbsentPreReplayReceipt \(Get-Field \$seed ''preReplayReplayTransaction''\) @\(\)' -or
    $upgradeCycleAuditSource -notmatch 'preReplayRequestIdColumnCount' -or
    $upgradeCycleAuditSource -notmatch 'preReplayRequestIdIndexCount' -or
    $upgradeCycleAuditSource -notmatch 'startupSchemaObservation' -or
    $upgradeCycleAuditSource -notmatch 'candidate startup request-ID column count' -or
    $upgradeCycleAuditSource -notmatch 'retained pre-replay SQLite snapshot SHA-256' -or
    $upgradeCycleAuditSource -notmatch 'Assert-T21ReplayReceiptBinding \$preReplayObservation \$expectedReplayedTransaction \$preReplayDatabaseReceipt') {
    throw 'The reusable M04 offline auditor must independently rehash copied artifacts, PlugDev distribution files, and retained logs.'
}
foreach ($expectedPin in $expectedM04StartPins) {
    if (-not $upgradeCycleAuditSource.Contains($expectedPin)) {
        throw "The M04 offline auditor must bind its lifecycle to the expected stage/version pair: $expectedPin"
    }
}
Write-Output 'PASS: parse the M04 offline artifact and log auditor'

Write-Output 'T21 runner validation tests PASS'
}
finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]@('\', '/'))
    $testRoot = [IO.Path]::GetFullPath($mysqlRuntimeTestRoot).TrimEnd([char[]]@('\', '/'))
    $tempRootPrefix = $tempRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $testRoot.StartsWith($tempRootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to clean a T21 validation fixture outside the current temporary directory.'
    }
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        $fixtureRoot = Get-Item -LiteralPath $testRoot -Force
        if (($fixtureRoot.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            Remove-Item -LiteralPath $testRoot -Force
        } else {
            $fixtureLogs = Join-Path $testRoot 'logs'
            if (Test-Path -LiteralPath $fixtureLogs -PathType Container) {
                $logsItem = Get-Item -LiteralPath $fixtureLogs -Force
                if (($logsItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    Remove-Item -LiteralPath $fixtureLogs -Force
                } else {
                    $reparseChildren = @(Get-ChildItem -LiteralPath $fixtureLogs -Force | Where-Object {
                        ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
                    })
                    if ($reparseChildren.Count -gt 0) {
                        throw 'Refusing recursive cleanup while a T21 fixture log path contains a reparse point.'
                    }
                }
            }
            if ($mysqlRuntimeReparseTarget -and (Test-Path -LiteralPath $mysqlRuntimeReparseTarget -PathType Container)) {
                if (Test-Path -LiteralPath $fixtureLogs) {
                    throw 'Refusing to clean the T21 fixture while its reparse target has not been restored.'
                }
                Move-Item -LiteralPath $mysqlRuntimeReparseTarget -Destination $fixtureLogs
            }
            Remove-Item -LiteralPath $testRoot -Recurse -Force
        }
    }
}
