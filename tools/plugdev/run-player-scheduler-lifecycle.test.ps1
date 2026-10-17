$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$runnerPath = Join-Path $PSScriptRoot 'run-player-scheduler-lifecycle.ps1'
$runnerSource = Get-Content -LiteralPath $runnerPath -Raw -ErrorAction Stop
$expectedPins = @(
    "'paper-263-max' = [ordered]@{ software = 'paper'; version = '26.3'; build = 166; artifact = 'paper-26.3-166.jar'; sha256 = 'dbaae37e10e30e44ad1ff65cf00992240c3e79d33e150f9c4e833ed0338e8ccd' }",
    "'folia-262-max' = [ordered]@{ software = 'folia'; version = '26.2'; build = 7; artifact = 'folia-26.2-7.jar'; sha256 = '128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3' }",
    "'canvas-263-max' = [ordered]@{ software = 'canvas'; version = '26.3'; build = 972; artifact = 'canvas-26.3-972.jar'; sha256 = 'aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5' }",
    "'paper-263-currentmax' = [ordered]@{ software = 'paper'; version = '26.3'; build = 200; artifact = 'paper-26.3-200.jar'; sha256 = '0e104a19ad818f05266a4571c0a9b0cada241c2016ee64883e5d38435332fa8d' }",
    "'folia-263-currentmax' = [ordered]@{ software = 'folia'; version = '26.3'; build = 3; artifact = 'folia-26.3-3.jar'; sha256 = '0c4cc7dd82c40bd1effa8a9c4cacfad55d41b7dc38377900957c961c2952c022' }",
    "'canvas-263-currentmax' = [ordered]@{ software = 'canvas'; version = '26.3'; build = 984; artifact = 'canvas-26.3-984.jar'; sha256 = '0b94c4cba3e415586dddb69915b5aacf250636c62d058f4651c1ab34eaf265fa' }"
)
foreach ($pin in $expectedPins) {
    if (-not $runnerSource.Contains($pin, [StringComparison]::Ordinal)) {
        throw "Scheduler lifecycle endpoint pin is missing or changed: $pin"
    }
}
$expectedSelectorSet = "ValidateSet('paper-1216-min', 'paper-263-max', 'paper-263-currentmax', 'folia-1216-min', 'folia-262-max', 'folia-263-currentmax', 'canvas-1218-min', 'canvas-263-max', 'canvas-263-currentmax')"
if (-not $runnerSource.Contains($expectedSelectorSet, [StringComparison]::Ordinal)) {
    throw 'Scheduler lifecycle runner must retain historical selectors and expose all three current formal maximum selectors.'
}
if (-not $runnerSource.Contains('build\reports\foundation\T21-sql-runtime\current-maximum-artifacts\'' + $pin.artifact', [StringComparison]::Ordinal)) {
    throw 'Scheduler current-maximum selectors must resolve their artifacts from the verified T21 current-maximum artifact directory.'
}
if (-not $runnerSource.Contains("candidateSha -cne '7d4fb070ecdb61ece3d5e39b7a2bf6ceb72c0c6a939f269588344b41f908b354'", [StringComparison]::Ordinal)) {
    throw 'Scheduler lifecycle runner must pin the current candidate used by T21 endpoint validation.'
}
$runnerTokens = $null
$runnerParseErrors = $null
$runnerAst = [System.Management.Automation.Language.Parser]::ParseInput($runnerSource, [ref]$runnerTokens, [ref]$runnerParseErrors)
if ($runnerParseErrors.Count -gt 0) { throw "Scheduler lifecycle runner has PowerShell parse errors: $($runnerParseErrors -join '; ')" }
$nodeInvokerAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-NodeWithMineflayerModulePath'
}, $true)
if ($null -eq $nodeInvokerAst) { throw 'Scheduler lifecycle runner must scope NODE_PATH to Mineflayer client processes.' }
$invokeClientAst = $runnerAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-Client'
}, $true)
if ($null -eq $invokeClientAst -or $null -eq $invokeClientAst.Body.Find({
        param($node)
        $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -ceq 'Invoke-NodeWithMineflayerModulePath'
    }, $true)) {
    throw 'Scheduler lifecycle Mineflayer probes must use the scoped module-path invoker.'
}
if (-not $invokeClientAst.Extent.Text.Contains('moduleSearchPath = $nodeResult.moduleSearchPath', [StringComparison]::Ordinal)) {
    throw 'Scheduler lifecycle client report must retain the Mineflayer module-search path used for the probe.'
}
. ([scriptblock]::Create($nodeInvokerAst.Extent.Text))
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("syncmoney-scheduler-node-path-" + [guid]::NewGuid().ToString('N'))
$modulePath = Join-Path $tempRoot 'node_modules'
$null = New-Item -ItemType Directory -Path $modulePath -Force
$originalNodePath = $env:NODE_PATH
try {
    $nodeProbe = Invoke-NodeWithMineflayerModulePath -Arguments @('-e', 'process.stdout.write(process.env.NODE_PATH || "")') -MineflayerPath $tempRoot
    $observedNodePath = (@($nodeProbe.output | ForEach-Object { $_.ToString() }) -join '').Trim()
    $expectedNodePath = [IO.Path]::GetFullPath($modulePath)
    if ($nodeProbe.exitCode -ne 0 -or
        -not [string]::Equals($observedNodePath, $expectedNodePath, [StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals($nodeProbe.moduleSearchPath, $expectedNodePath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Scheduler lifecycle client child process did not receive the local Mineflayer node_modules path.'
    }
    if (-not [string]::Equals([string]$env:NODE_PATH, [string]$originalNodePath, [StringComparison]::Ordinal)) {
        throw 'Scheduler lifecycle runner did not restore the parent NODE_PATH after the client process exited.'
    }
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
Write-Output 'PASS: scheduler lifecycle pins preserve historical/current maximum endpoints and scope Mineflayer NODE_PATH to the child process'
