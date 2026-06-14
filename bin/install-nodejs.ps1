#Requires -Version 5.1
<#
.SYNOPSIS
    Node.js automatic installer

.DESCRIPTION
    Downloads and installs Node.js LTS (22.x) on a PC where Node.js is not installed
    or an older major version is present. The Cursor plugin sidecar (@cursor/sdk)
    requires Node.js 22 or later.

.NOTES
    Run as Administrator to install system-wide (the Node.js MSI registers PATH
    machine-wide). To bypass execution policy:
        powershell -ExecutionPolicy Bypass -File install-nodejs.ps1
#>

[CmdletBinding()]
param(
    [string]$NodeVersion = "22.14.0",
    [int]$MinMajor = 22,
    [switch]$NoExit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Callers (e.g. the Java setup dialog) read our output as UTF-8;
# Windows PowerShell 5.1 defaults to the OEM codepage, so force UTF-8.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ─────────────────────────────────────────────
# Utilities
# ─────────────────────────────────────────────

function Write-Step {
    param([string]$Message)
    Write-Host "`n[*] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "    [OK] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "    [!!] $Message" -ForegroundColor Yellow
}

function Write-Fail {
    param([string]$Message)
    Write-Host "    [XX] $Message" -ForegroundColor Red
}

function Test-Admin {
    $identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]$identity
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# ─────────────────────────────────────────────
# Proxy / offline installer support
# ─────────────────────────────────────────────

# Pre-staged installers placed here are used without downloading (air-gapped networks)
$OfflineDir = Join-Path $env:USERPROFILE "llm-services\installers"

# Authenticated corporate proxies (NTLM/Kerberos) return 407 for anonymous
# requests; attach the Windows logon credentials to the system proxy.
$defaultProxy = [System.Net.WebRequest]::DefaultWebProxy
if ($defaultProxy) {
    $defaultProxy.Credentials = [System.Net.CredentialCache]::DefaultNetworkCredentials
}

function Get-EnvProxy {
    foreach ($name in @('HTTPS_PROXY', 'HTTP_PROXY', 'https_proxy', 'http_proxy')) {
        $v = [Environment]::GetEnvironmentVariable($name)
        if ($v) { return $v }
    }
    return $null
}

function Invoke-Download {
    param([string]$Url, [string]$Dest)
    # Force TLS 1.2 for older Windows compatibility
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $iwrParams = @{ Uri = $Url; OutFile = $Dest; UseBasicParsing = $true }
    # HTTP(S)_PROXY environment variables take precedence over the system proxy
    $envProxy = Get-EnvProxy
    if ($envProxy) {
        $iwrParams.Proxy = $envProxy
        $iwrParams.ProxyUseDefaultCredentials = $true
    }
    # PS 5.1's Invoke-WebRequest progress bar slows downloads drastically — disable it
    $prevProgress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest @iwrParams
    } finally {
        $ProgressPreference = $prevProgress
    }
    if (-not (Test-Path $Dest)) { throw "file not found after download" }
}

# ─────────────────────────────────────────────
# 1. Check if Node.js is already installed
# ─────────────────────────────────────────────

Write-Step "Checking Node.js installation..."

$existingVersion = $null
$nodeExe = $null

try {
    $found = Get-Command node -ErrorAction SilentlyContinue
    if ($found) {
        $existingVersion = (& node --version 2>&1).ToString().Trim()
        $nodeExe = $found.Source
    }
} catch { }

# Also check the default install dir (handles PATH not yet refreshed)
if (-not $existingVersion) {
    $defaultExe = Join-Path $env:ProgramFiles "nodejs\node.exe"
    if (Test-Path $defaultExe) {
        $existingVersion = (& $defaultExe --version 2>&1).ToString().Trim()
        $nodeExe = $defaultExe
    }
}

if ($existingVersion -match '^v(\d+)') {
    $existingMajor = [int]$Matches[1]
    if ($existingMajor -ge $MinMajor) {
        Write-Ok "Node.js is already installed: $existingVersion"
        Write-Ok "Path: $nodeExe"
        if (-not $NoExit) { exit 0 }
    } else {
        Write-Warn "Node.js $existingVersion found, but v$MinMajor+ is required (Cursor SDK). Upgrading..."
    }
}

# ─────────────────────────────────────────────
# 2. Determine download URL (x64 / x86 / arm64)
# ─────────────────────────────────────────────

Write-Step "Detecting system architecture..."

$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    "ARM64" { "arm64" }
    "AMD64" { "x64" }
    default { if ([System.Environment]::Is64BitOperatingSystem) { "x64" } else { "x86" } }
}
Write-Ok "Architecture: $arch"

$installerName = "node-v$NodeVersion-$arch.msi"
$downloadUrl   = "https://nodejs.org/dist/v$NodeVersion/$installerName"
$tempPath      = Join-Path $env:TEMP $installerName

Write-Ok "Download URL: $downloadUrl"

# ─────────────────────────────────────────────
# 3. Download
# ─────────────────────────────────────────────

Write-Step "Locating Node.js $NodeVersion installer..."

# Resolution order: offline staging dir -> TEMP cache -> download
$offlinePath   = Join-Path $OfflineDir $installerName
$installerPath = $null

if (Test-Path $offlinePath) {
    Write-Ok "Using offline installer: $offlinePath"
    $installerPath = $offlinePath
} elseif (Test-Path $tempPath) {
    Write-Warn "Cached installer found, reusing: $tempPath"
    $installerPath = $tempPath
} else {
    try {
        Invoke-Download -Url $downloadUrl -Dest $tempPath
        Write-Ok "Download complete: $tempPath"
        $installerPath = $tempPath
    } catch {
        Write-Fail "Download failed: $_"
        Write-Host ""
        Write-Host "If this PC is behind a proxy or has no internet access:" -ForegroundColor Yellow
        Write-Host "  1. Download on another PC: $downloadUrl" -ForegroundColor Cyan
        Write-Host "  2. Place the file at    : $offlinePath" -ForegroundColor Cyan
        Write-Host "  3. Run install again." -ForegroundColor Cyan
        exit 1
    }
}

# ─────────────────────────────────────────────
# 4. Run installer
# ─────────────────────────────────────────────

Write-Step "Installing Node.js (this may take 1-2 minutes)..."

if (-not (Test-Admin)) {
    Write-Warn "No admin rights -- the Node.js MSI normally requires elevation; install may fail."
}

# /qn quiet, /norestart: the MSI adds Program Files\nodejs to the machine PATH itself
$msiArgs = @("/i", "`"$installerPath`"", "/qn", "/norestart")

try {
    $proc = Start-Process -FilePath "msiexec.exe" -ArgumentList $msiArgs -Wait -PassThru
    if ($proc.ExitCode -ne 0) {
        Write-Fail "Installation failed (exit code: $($proc.ExitCode))"
        exit $proc.ExitCode
    }
    Write-Ok "Node.js installed successfully"
} catch {
    Write-Fail "Installation error: $_"
    exit 1
}

# ─────────────────────────────────────────────
# 5. Refresh PATH for this session and verify
# ─────────────────────────────────────────────

Write-Step "Verifying installation..."

$nodeDir = Join-Path $env:ProgramFiles "nodejs"
$env:Path = "$nodeDir;$env:Path"

Start-Sleep -Seconds 2

try {
    $ver = (& "$nodeDir\node.exe" --version 2>&1).ToString().Trim()
    Write-Ok "node --version: $ver"
} catch {
    Write-Fail "node.exe failed to run: $_"
    exit 1
}

try {
    $npmVer = (& cmd.exe /c "`"$nodeDir\npm.cmd`" --version" 2>&1 | Select-Object -Last 1)
    Write-Ok "npm --version: $npmVer"
} catch {
    Write-Warn "npm check failed (non-critical): $_"
}

# ─────────────────────────────────────────────
# 6. Cleanup
# ─────────────────────────────────────────────

Write-Step "Cleaning up temporary files..."
# Keep pre-staged offline installers; remove only the TEMP download
if ($installerPath -eq $tempPath) {
    try {
        Remove-Item $tempPath -Force -ErrorAction SilentlyContinue
        Write-Ok "Installer removed: $tempPath"
    } catch {
        Write-Warn "Failed to remove temp file (non-critical)"
    }
} else {
    Write-Ok "Offline installer kept: $installerPath"
}

# ─────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────

Write-Host ""
Write-Host "======================================" -ForegroundColor Green
Write-Host "  Node.js $NodeVersion installed!" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Install dir : $nodeDir"
Write-Host "  node        : $nodeDir\node.exe"
Write-Host "  npm         : $nodeDir\npm.cmd"
Write-Host ""
Write-Host "Open a new terminal and run 'node --version' to confirm." -ForegroundColor Yellow
Write-Host ""

if ($NoExit) {
    Read-Host "Press Enter to exit"
}
