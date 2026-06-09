#Requires -Version 5.1
<#
.SYNOPSIS
    Python automatic installer

.DESCRIPTION
    Downloads and installs Python 3.12 on a PC where Python is not installed.
    Registers Python in PATH and upgrades pip after installation.

.NOTES
    Run as Administrator to install system-wide; otherwise installs for current user only.
    To bypass execution policy:
        powershell -ExecutionPolicy Bypass -File install-python.ps1
#>

[CmdletBinding()]
param(
    [string]$PythonVersion = "3.12.10",
    [string]$InstallDir = "",
    [switch]$NoExit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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
# 1. Check if Python is already installed
# ─────────────────────────────────────────────

Write-Step "Checking Python installation..."

$pythonExe = $null
$existingVersion = $null

try {
    $found = Get-Command python -ErrorAction SilentlyContinue
    if ($found) {
        $existingVersion = & python --version 2>&1
        $pythonExe = $found.Source
    }
} catch { }

# Also search registry (handles cases where Python is installed but not in PATH)
if (-not $existingVersion) {
    $regPaths = @(
        "HKLM:\SOFTWARE\Python\PythonCore",
        "HKCU:\SOFTWARE\Python\PythonCore"
    )
    foreach ($rp in $regPaths) {
        if (Test-Path $rp) {
            $versions = Get-ChildItem $rp -ErrorAction SilentlyContinue
            if ($versions) {
                $latest = $versions | Sort-Object Name -Descending | Select-Object -First 1
                $installPath = (Get-ItemProperty "$($latest.PSPath)\InstallPath" -ErrorAction SilentlyContinue)."(default)"
                if ($installPath -and (Test-Path "$installPath\python.exe")) {
                    $existingVersion = & "$installPath\python.exe" --version 2>&1
                    $pythonExe = "$installPath\python.exe"
                }
            }
        }
    }
}

if ($existingVersion) {
    Write-Ok "Python is already installed: $existingVersion"
    Write-Ok "Path: $pythonExe"
    Write-Host ""
    Write-Host "Press Ctrl+C to cancel, or wait 5 seconds to continue with reinstall." -ForegroundColor Yellow
    Start-Sleep -Seconds 5
    if (-not $NoExit) { exit 0 }
}

# ─────────────────────────────────────────────
# 2. Determine download URL (x64 / x86)
# ─────────────────────────────────────────────

Write-Step "Detecting system architecture..."

$arch = if ([System.Environment]::Is64BitOperatingSystem) { "amd64" } else { "win32" }
Write-Ok "Architecture: $arch"

$installerName = "python-$PythonVersion-$arch.exe"
$downloadUrl   = "https://www.python.org/ftp/python/$PythonVersion/$installerName"
$tempPath      = Join-Path $env:TEMP $installerName

Write-Ok "Download URL: $downloadUrl"

# ─────────────────────────────────────────────
# 3. Determine install directory
# ─────────────────────────────────────────────

$isAdmin = Test-Admin

if (-not $InstallDir) {
    if ($isAdmin) {
        $majorMinor = ($PythonVersion -split '\.')[0..1] -join ''
        $InstallDir = "C:\Python$majorMinor"
    } else {
        $majorMinorUser = ($PythonVersion -split '\.')[0..1] -join ''
        $InstallDir = Join-Path $env:LOCALAPPDATA "Programs\Python\Python$majorMinorUser"
    }
}

Write-Step "Install directory: $InstallDir"
if (-not $isAdmin) {
    Write-Warn "No admin rights -- installing for current user only."
}

# ─────────────────────────────────────────────
# 4. Download
# ─────────────────────────────────────────────

Write-Step "Downloading Python $PythonVersion installer..."

if (Test-Path $tempPath) {
    Write-Warn "Cached installer found, reusing: $tempPath"
} else {
    try {
        # Force TLS 1.2 for older Windows compatibility
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

        $wc = New-Object System.Net.WebClient
        $wc.DownloadFile($downloadUrl, $tempPath)
        Write-Ok "Download complete: $tempPath"
    } catch {
        Write-Fail "Download failed: $_"
        Write-Host ""
        Write-Host "Please download manually and re-run:"
        Write-Host "  $downloadUrl" -ForegroundColor Cyan
        exit 1
    }
}

# ─────────────────────────────────────────────
# 5. Run installer
# ─────────────────────────────────────────────

Write-Step "Installing Python (this may take 1-2 minutes)..."

$installAllUsers = if ($isAdmin) { "1" } else { "0" }

$installArgs = @(
    "/quiet",
    "InstallAllUsers=$installAllUsers",
    "TargetDir=`"$InstallDir`"",
    "PrependPath=1",
    "Include_pip=1",
    "Include_launcher=1",
    "Include_doc=0",
    "Include_test=0"
)

try {
    $proc = Start-Process -FilePath $tempPath -ArgumentList $installArgs -Wait -PassThru
    if ($proc.ExitCode -ne 0) {
        Write-Fail "Installation failed (exit code: $($proc.ExitCode))"
        exit $proc.ExitCode
    }
    Write-Ok "Python installed successfully"
} catch {
    Write-Fail "Installation error: $_"
    exit 1
}

# ─────────────────────────────────────────────
# 6. Refresh PATH
# ─────────────────────────────────────────────

Write-Step "Updating PATH environment variable..."

$scope = if ($isAdmin) { "Machine" } else { "User" }
$envPath = [System.Environment]::GetEnvironmentVariable("Path", $scope)

$pathsToAdd = @(
    $InstallDir,
    (Join-Path $InstallDir "Scripts")
)

foreach ($p in $pathsToAdd) {
    if ($envPath -notlike "*$p*") {
        $envPath = "$p;$envPath"
        [System.Environment]::SetEnvironmentVariable("Path", $envPath, $scope)
        Write-Ok "Added to PATH: $p"
    } else {
        Write-Ok "Already in PATH: $p"
    }
}

$env:Path = "$InstallDir;$(Join-Path $InstallDir 'Scripts');$env:Path"

# ─────────────────────────────────────────────
# 7. Verify installation
# ─────────────────────────────────────────────

Write-Step "Verifying installation..."

Start-Sleep -Seconds 2

try {
    $ver = & "$InstallDir\python.exe" --version 2>&1
    Write-Ok "python --version: $ver"
} catch {
    Write-Fail "python.exe failed to run: $_"
    exit 1
}

try {
    $pipVer = & "$InstallDir\Scripts\pip.exe" --version 2>&1
    Write-Ok "pip --version: $pipVer"
} catch {
    Write-Warn "pip check failed (non-critical): $_"
}

# ─────────────────────────────────────────────
# 8. Upgrade pip
# ─────────────────────────────────────────────

Write-Step "Upgrading pip to latest version..."

try {
    & "$InstallDir\python.exe" -m pip install --upgrade pip --quiet
    Write-Ok "pip upgraded successfully"
} catch {
    Write-Warn "pip upgrade failed (non-critical): $_"
}

# ─────────────────────────────────────────────
# 9. Cleanup
# ─────────────────────────────────────────────

Write-Step "Cleaning up temporary files..."
try {
    Remove-Item $tempPath -Force -ErrorAction SilentlyContinue
    Write-Ok "Installer removed: $tempPath"
} catch {
    Write-Warn "Failed to remove temp file (non-critical)"
}

# ─────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────

Write-Host ""
Write-Host "======================================" -ForegroundColor Green
Write-Host "  Python $PythonVersion installed!" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Install dir : $InstallDir"
Write-Host "  python      : $InstallDir\python.exe"
Write-Host "  pip         : $InstallDir\Scripts\pip.exe"
Write-Host ""
Write-Host "Open a new terminal and run 'python --version' to confirm." -ForegroundColor Yellow
Write-Host ""

if ($NoExit) {
    Read-Host "Press Enter to exit"
}
