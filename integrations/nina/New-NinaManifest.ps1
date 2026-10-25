<#
.SYNOPSIS
Writes the manifest of a release of the plugin, for the N.I.N.A. plugin repository.

.DESCRIPTION
The manifest is written by tools/CreateManifest.ps1 of isbeorn/nina.plugin.manifests, which reads
the metadata of the plugin assembly. That script describes a DLL, while the plugin is installed from
the zip of the release, which contains asterion.exe too: the installer of the manifest is made the zip.
Requires PowerShell 7.

.EXAMPLE
./New-NinaManifest.ps1 -Version 0.3.0 -Zip asterion-nina-0.3.0.zip -Output manifest.json
#>
param(
    # the version of the release, such as 0.3.0
    [Parameter(Mandatory)] [string] $Version,
    # the zip of the plugin, as it is attached to the release
    [Parameter(Mandatory)] [string] $Zip,
    # the manifest to write
    [Parameter(Mandatory)] [string] $Output,
    # the repository of the release
    [string] $Repository = 'asterion-solver/asterion'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# the script of the manifest repository, at the commit which was checked: another one may write
# manifests differently
$scriptCommit = '3dde39048c7cc0ac756fa4e8fdccd2480bc63833'

$work = Join-Path ([IO.Path]::GetTempPath()) "nina-manifest-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory $work | Out-Null
try {
    Invoke-WebRequest -Headers @{ 'User-Agent' = 'asterion-release/1.0' } -OutFile (Join-Path $work 'CreateManifest.ps1') `
        -Uri "https://raw.githubusercontent.com/isbeorn/nina.plugin.manifests/$scriptCommit/tools/CreateManifest.ps1"
    Expand-Archive $Zip (Join-Path $work 'plugin')
    $url = "https://github.com/$Repository/releases/download/v$Version/asterion-nina-$Version.zip"
    # the script writes manifest.json in the current directory, and prints it
    Push-Location $work
    try {
        & ./CreateManifest.ps1 -file (Join-Path $work 'plugin/Asterion.Nina.dll') -installerUrl $url | Out-Null
    } finally {
        Pop-Location
    }
    $manifest = Get-Content (Join-Path $work 'manifest.json') -Raw | ConvertFrom-Json
    $manifest.Installer.Type = 'ARCHIVE'
    $manifest.Installer.Checksum = (Get-FileHash $Zip -Algorithm SHA256).Hash
    $manifest | ConvertTo-Json -Depth 10 | Set-Content $Output
    Write-Output "Wrote the manifest of Asterion Solver $Version in $Output"
} finally {
    Remove-Item -Recurse -Force $work
}
