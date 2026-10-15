Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 or newer is required for the T21 runner preflight regression test.'
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureRoot = Join-Path $temporaryRoot "syncmoney-t21-audit-preflight-$([guid]::NewGuid().ToString('N'))"
$workspaceRoot = Join-Path $fixtureRoot 'workspace'
$plugDevScripts = Join-Path $workspaceRoot 'tools\plugdev'
$fixtureRunner = Join-Path $plugDevScripts 'run-upgrade-cycle.ps1'
$reportsRoot = Join-Path $workspaceRoot 'build\reports\foundation\T21-upgrade-cycles'

try {
    New-Item -ItemType Directory -Path $plugDevScripts -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'tools\plugdev\run-upgrade-cycle.ps1') -Destination $fixtureRunner
    foreach ($fileName in @('prepare.mjs', 'prepare.test.mjs', 't21-upgrade-validation.ps1')) {
        Copy-Item -LiteralPath (Join-Path $repositoryRoot "tools\plugdev\$fileName") -Destination (Join-Path $plugDevScripts $fileName)
    }

    $arguments = @{
        Project = 'paper'
        MinecraftVersion = '1.21.6'
        BuildNumber = 48
        ServerJar = Join-Path $fixtureRoot 'missing-paper.jar'
        ExpectedServerSha256 = '0' * 64
        BaselinePluginJar = Join-Path $fixtureRoot 'missing-baseline.jar'
        ExpectedBaselinePluginSha256 = '0' * 64
        BaselineSourceCommit = '0' * 40
        PlugDevRoot = Join-Path $fixtureRoot 'missing-plugdev'
        JavaHome = Join-Path $fixtureRoot 'missing-java'
    }
    $pwsh = (Get-Command pwsh -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $output = (& $pwsh -NoProfile -File $fixtureRunner @arguments 2>&1 | Out-String)
    $exitCode = $LASTEXITCODE

    if ($exitCode -eq 0) { throw 'The T21 runner unexpectedly succeeded without its offline auditor.' }
    if ($output -notmatch '(?i)offline auditor') {
        throw "The T21 runner did not fail specifically for the missing offline auditor. Observed output: $($output.Trim())"
    }
    if (Test-Path -LiteralPath $reportsRoot) {
        throw 'The T21 runner created its report root before rejecting the missing offline auditor.'
    }

    Write-Output 'PASS: missing offline auditor fails before creating the report root or starting the runtime cycle'
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $temporaryPrefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedFixture.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a test fixture outside the temp directory: $resolvedFixture"
    }
    if (Test-Path -LiteralPath $resolvedFixture) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
