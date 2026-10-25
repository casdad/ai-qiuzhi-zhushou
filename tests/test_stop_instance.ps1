$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot '..\bin\stop-instance.ps1'
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $scriptPath, [ref]$tokens, [ref]$parseErrors
)
if ($parseErrors.Count -gt 0) { throw ($parseErrors | Out-String) }

# Load only the pure identity checks; never execute the process-stopping body.
foreach ($definition in $ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -in @('Test-ProjectBackend', 'Test-ProjectChrome')
}, $false)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}

$root = 'C:\test workspace\get_jobs-main'
$CdpPort = 7866
$cases = 0
function Assert-Identity([string]$Name, [bool]$Actual, [bool]$Expected) {
    if ($Actual -ne $Expected) { throw "Failed: $Name (got $Actual, expected $Expected)" }
    $script:cases++
}

$java = [pscustomobject]@{
    Name = 'java.exe'
    CommandLine = 'java.exe -cp "C:\test workspace\get_jobs-main\build\classes\java\main;C:\libs\a.jar" com.getjobs.GetJobsApplication'
}
Assert-Identity 'current project' (Test-ProjectBackend $java) $true
$java.CommandLine = $java.CommandLine.Replace('\', '/')
Assert-Identity 'forward slash classpath' (Test-ProjectBackend $java) $true
$java.CommandLine = $java.CommandLine.Replace('get_jobs-main/', 'get_jobs-main-other/')
Assert-Identity 'similar project name' (Test-ProjectBackend $java) $false
$java.CommandLine = 'java.exe -cp "C:\test workspace\get_jobs-main\build\classes\java\main\other" com.getjobs.GetJobsApplication'
Assert-Identity 'classpath prefix is not enough' (Test-ProjectBackend $java) $false
$java.CommandLine = 'java.exe -cp "C:\test workspace\get_jobs-main\build\classes\java\main" com.other.Application'
Assert-Identity 'different application' (Test-ProjectBackend $java) $false
$java.CommandLine = 'java.exe -cp "C:\test workspace\get_jobs-main\build\classes\java\main" com.getjobs.GetJobsApplicationOther'
Assert-Identity 'main class prefix is not enough' (Test-ProjectBackend $java) $false
$java.CommandLine = $null
Assert-Identity 'unreadable command line' (Test-ProjectBackend $java) $false
Assert-Identity 'missing process' (Test-ProjectBackend $null) $false

$chrome = [pscustomobject]@{
    Name = 'chrome.exe'
    CommandLine = 'chrome.exe --remote-debugging-port=7866 "--user-data-dir=C:\test workspace\get_jobs-main\chrome-profile"'
}
Assert-Identity 'dedicated Chrome with quoted argument' (Test-ProjectChrome $chrome) $true
$chrome.CommandLine = 'chrome.exe --remote-debugging-port=7866 --user-data-dir="C:\test workspace\get_jobs-main\chrome-profile"'
Assert-Identity 'dedicated Chrome with quoted value' (Test-ProjectChrome $chrome) $true
$chrome.CommandLine = $chrome.CommandLine.Replace('chrome-profile', 'chrome-profile-other')
Assert-Identity 'different Chrome profile' (Test-ProjectChrome $chrome) $false
$chrome.CommandLine = 'chrome.exe --remote-debugging-port=7867 "--user-data-dir=C:\test workspace\get_jobs-main\chrome-profile"'
Assert-Identity 'different debugging port' (Test-ProjectChrome $chrome) $false
$chrome.Name = 'python.exe'
Assert-Identity 'not Chrome' (Test-ProjectChrome $chrome) $false

Write-Output "$cases process identity checks passed."
