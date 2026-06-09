#Requires -Version 5.1
<#
.SYNOPSIS
    NVIDIA driver + CUDA automatic installer (for BGE-M3 server)

.DESCRIPTION
    Detects the NVIDIA GPU in this PC, queries the NVIDIA API for the latest driver,
    downloads and installs it silently. Also installs CUDA Toolkit (12.x) for BGE-M3.

.PARAMETER SkipDriver
    Skip driver installation (install CUDA only).

.PARAMETER SkipCuda
    Skip CUDA Toolkit installation.

.PARAMETER CudaVersion
    CUDA version to install (default: 12.4). Supported: 12.1, 12.4

.PARAMETER Force
    Force reinstall even if the current driver is already up to date.

.PARAMETER NonInteractive
    Suppress interactive prompts (restart question). Used when called from Java subprocess.

.EXAMPLE
    # Full install (driver + CUDA)
    powershell -ExecutionPolicy Bypass -File install-nvidia-driver.ps1

    # CUDA only
    powershell -ExecutionPolicy Bypass -File install-nvidia-driver.ps1 -SkipDriver

    # Specific CUDA version
    powershell -ExecutionPolicy Bypass -File install-nvidia-driver.ps1 -CudaVersion 12.1
#>

[CmdletBinding()]
param(
    [switch]$SkipDriver,
    [switch]$SkipCuda,
    [ValidateSet("12.1", "12.4")]
    [string]$CudaVersion = "12.4",
    [switch]$Force,
    [switch]$NonInteractive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Callers (e.g. the Java setup dialog) read our output as UTF-8;
# Windows PowerShell 5.1 defaults to the OEM codepage, so force UTF-8.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ─────────────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────────────

$CUDA_URLS = @{
    "12.4" = "https://developer.download.nvidia.com/compute/cuda/12.4.0/local_installers/cuda_12.4.0_551.61_windows.exe"
    "12.1" = "https://developer.download.nvidia.com/compute/cuda/12.1.0/local_installers/cuda_12.1.0_531.14_windows.exe"
}

# Minimum driver version required for BGE-M3 / PyTorch 2.x with CUDA 12
$MIN_DRIVER_VERSION = [version]"531.14"

$NVIDIA_VENDOR_ID  = "VEN_10DE"
$NVIDIA_LOOKUP_API = "https://gfwsl.geforce.com/services_toolkit/services/com/nvidia/services/AjaxDriverService.php"
$NVIDIA_OS_ID      = 57   # Windows 10/11 64-bit

# ─────────────────────────────────────────────────────────────
# GPU name keyword -> NVIDIA pfid mapping table
# ─────────────────────────────────────────────────────────────

$GPU_PFID_MAP = [ordered]@{
    # RTX 50 Series
    "RTX 5090"    = @{ psid = 127; pfid = 1019 }
    "RTX 5080"    = @{ psid = 127; pfid = 1020 }
    "RTX 5070 Ti" = @{ psid = 127; pfid = 1021 }
    "RTX 5070"    = @{ psid = 127; pfid = 1022 }
    "RTX 5060"    = @{ psid = 127; pfid = 1023 }
    # RTX 40 Series
    "RTX 4090"         = @{ psid = 127; pfid = 933 }
    "RTX 4080 SUPER"   = @{ psid = 127; pfid = 969 }
    "RTX 4080"         = @{ psid = 127; pfid = 940 }
    "RTX 4070 Ti SUPER"= @{ psid = 127; pfid = 970 }
    "RTX 4070 Ti"      = @{ psid = 127; pfid = 944 }
    "RTX 4070 SUPER"   = @{ psid = 127; pfid = 971 }
    "RTX 4070"         = @{ psid = 127; pfid = 941 }
    "RTX 4060 Ti"      = @{ psid = 127; pfid = 946 }
    "RTX 4060"         = @{ psid = 127; pfid = 950 }
    # RTX 30 Series
    "RTX 3090 Ti" = @{ psid = 127; pfid = 915 }
    "RTX 3090"    = @{ psid = 127; pfid = 887 }
    "RTX 3080 Ti" = @{ psid = 127; pfid = 903 }
    "RTX 3080"    = @{ psid = 127; pfid = 888 }
    "RTX 3070 Ti" = @{ psid = 127; pfid = 905 }
    "RTX 3070"    = @{ psid = 127; pfid = 889 }
    "RTX 3060 Ti" = @{ psid = 127; pfid = 900 }
    "RTX 3060"    = @{ psid = 127; pfid = 890 }
    "RTX 3050"    = @{ psid = 127; pfid = 914 }
    # RTX 20 Series
    "RTX 2080 Ti"    = @{ psid = 127; pfid = 817 }
    "RTX 2080 SUPER" = @{ psid = 127; pfid = 857 }
    "RTX 2080"       = @{ psid = 127; pfid = 816 }
    "RTX 2070 SUPER" = @{ psid = 127; pfid = 858 }
    "RTX 2070"       = @{ psid = 127; pfid = 836 }
    "RTX 2060 SUPER" = @{ psid = 127; pfid = 862 }
    "RTX 2060"       = @{ psid = 127; pfid = 837 }
    # GTX 16 Series
    "GTX 1660 Ti"    = @{ psid = 127; pfid = 857 }
    "GTX 1660 SUPER" = @{ psid = 127; pfid = 869 }
    "GTX 1660"       = @{ psid = 127; pfid = 864 }
    "GTX 1650 SUPER" = @{ psid = 127; pfid = 876 }
    "GTX 1650"       = @{ psid = 127; pfid = 854 }
    # GTX 10 Series
    "GTX 1080 Ti" = @{ psid = 127; pfid = 812 }
    "GTX 1080"    = @{ psid = 127; pfid = 829 }
    "GTX 1070 Ti" = @{ psid = 127; pfid = 844 }
    "GTX 1070"    = @{ psid = 127; pfid = 830 }
    "GTX 1060"    = @{ psid = 127; pfid = 831 }
    "GTX 1050 Ti" = @{ psid = 127; pfid = 832 }
    "GTX 1050"    = @{ psid = 127; pfid = 835 }
    # Quadro / Professional
    "RTX 6000 Ada" = @{ psid = 9; pfid = 954 }
    "RTX 5000 Ada" = @{ psid = 9; pfid = 955 }
    "RTX 4000 Ada" = @{ psid = 9; pfid = 956 }
    "RTX A6000"    = @{ psid = 9; pfid = 900 }
    "RTX A5000"    = @{ psid = 9; pfid = 898 }
    "RTX A4000"    = @{ psid = 9; pfid = 897 }
    # TITAN
    "TITAN RTX" = @{ psid = 127; pfid = 863 }
    "TITAN V"   = @{ psid = 127; pfid = 842 }
    "TITAN Xp"  = @{ psid = 127; pfid = 833 }
    "TITAN X"   = @{ psid = 127; pfid = 811 }
}

# ─────────────────────────────────────────────────────────────
# Utility functions
# ─────────────────────────────────────────────────────────────

function Write-Step  { param([string]$m) Write-Host "`n[*] $m" -ForegroundColor Cyan }
function Write-Ok    { param([string]$m) Write-Host "    [OK] $m" -ForegroundColor Green }
function Write-Warn  { param([string]$m) Write-Host "    [!!] $m" -ForegroundColor Yellow }
function Write-Fail  { param([string]$m) Write-Host "    [XX] $m" -ForegroundColor Red }
function Write-Info  { param([string]$m) Write-Host "         $m" -ForegroundColor DarkGray }

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    ([Security.Principal.WindowsPrincipal]$id).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-DriverVersionFromString {
    param([string]$raw)
    if ($raw -match '(\d{3,4}\.\d{2,3})') { return [version]$Matches[1] }
    return $null
}

function Download-File {
    param([string]$Url, [string]$Dest)
    Write-Info "URL : $Url"
    Write-Info "Dest: $Dest"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    # PS 5.1's Invoke-WebRequest progress bar slows downloads drastically — disable it
    $prevProgress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing
    } finally {
        $ProgressPreference = $prevProgress
    }
    if (-not (Test-Path $Dest)) { throw "Download failed: file not found after download" }
}

# ─────────────────────────────────────────────────────────────
# STEP 0: Admin check
# ─────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "============================================" -ForegroundColor Magenta
Write-Host "  NVIDIA Driver + CUDA Installer (BGE-M3)  " -ForegroundColor Magenta
Write-Host "============================================" -ForegroundColor Magenta

if (-not (Test-Admin)) {
    Write-Warn "No administrator privileges -- driver installation requires admin rights."
    Write-Warn "Requesting elevation (UAC prompt)..."
    Write-Host ""

    # Rebuild arguments PS 5.1-compatible (Join-String is PS 6.2+ only).
    # Switch parameters must be re-passed as bare flags, not "-Name True".
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    foreach ($kv in $PSBoundParameters.GetEnumerator()) {
        if ($kv.Value -is [System.Management.Automation.SwitchParameter] -or $kv.Value -is [bool]) {
            if ($kv.Value) { $argList += "-$($kv.Key)" }
        } else {
            $argList += "-$($kv.Key)"
            $argList += "$($kv.Value)"
        }
    }

    try {
        # -Wait so the caller (Java dialog) sees the real result, not an instant exit 0
        $elevated = Start-Process powershell -ArgumentList $argList -Verb RunAs -Wait -PassThru
        exit $elevated.ExitCode
    } catch {
        # User declined the UAC prompt
        Write-Fail "Elevation cancelled -- installation aborted."
        exit 1
    }
}
Write-Ok "Administrator privileges confirmed"

# ─────────────────────────────────────────────────────────────
# STEP 1: Detect NVIDIA GPU
# ─────────────────────────────────────────────────────────────

Write-Step "Detecting NVIDIA GPU..."

$gpuList = Get-WmiObject Win32_VideoController |
           Where-Object { $_.PNPDeviceID -like "*$NVIDIA_VENDOR_ID*" }

if (-not $gpuList) {
    Write-Fail "No NVIDIA GPU found."
    Write-Info "Check Device Manager -> Display Adapters."
    exit 1
}

$gpu = $gpuList |
       Where-Object { $_.AdapterRAM -gt 256MB } |
       Sort-Object AdapterRAM -Descending |
       Select-Object -First 1

if (-not $gpu) { $gpu = $gpuList | Select-Object -First 1 }

$gpuName   = $gpu.Name.Trim()
$gpuRamGB  = [math]::Round($gpu.AdapterRAM / 1GB, 1)
$gpuDriver = $gpu.DriverVersion

$devIdMatch = $gpu.PNPDeviceID -match "DEV_([0-9A-F]{4})"
$deviceId   = if ($devIdMatch) { $Matches[1] } else { "Unknown" }

Write-Ok "GPU detected: $gpuName"
Write-Info "VRAM     : $gpuRamGB GB"
Write-Info "Device ID: $deviceId"
Write-Info "Current driver: $gpuDriver"

# ─────────────────────────────────────────────────────────────
# STEP 2: Check current CUDA via nvidia-smi
# ─────────────────────────────────────────────────────────────

Write-Step "Checking nvidia-smi..."

$nvidiaSmi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
$currentDriverVer = $null
$currentCudaVer   = $null

if ($nvidiaSmi) {
    try {
        $smiOut = & nvidia-smi 2>&1 | Out-String
        Write-Ok "nvidia-smi found"

        if ($smiOut -match "Driver Version:\s*(\d+\.\d+)") {
            $currentDriverVer = [version]$Matches[1]
            Write-Info "Driver version : $currentDriverVer"
        }
        if ($smiOut -match "CUDA Version:\s*(\d+\.\d+)") {
            $currentCudaVer = $Matches[1]
            Write-Info "CUDA version   : $currentCudaVer"
        }
    } catch {
        Write-Warn "nvidia-smi error (ignored): $_"
    }
} else {
    Write-Warn "nvidia-smi not found -- driver not installed or not in PATH"
}

# ─────────────────────────────────────────────────────────────
# STEP 3: Map GPU to pfid
# ─────────────────────────────────────────────────────────────

Write-Step "Mapping GPU to NVIDIA product ID..."

$matchedPfid = $null
$matchedPsid = $null
$matchedKey  = $null

foreach ($key in $GPU_PFID_MAP.Keys) {
    if ($gpuName -match [regex]::Escape($key)) {
        $matchedPfid = $GPU_PFID_MAP[$key].pfid
        $matchedPsid = $GPU_PFID_MAP[$key].psid
        $matchedKey  = $key
        break
    }
}

if (-not $matchedPfid) {
    Write-Warn "GPU not found in mapping table: $gpuName"
    Write-Warn "Falling back to GeForce default (psid=127, pfid=887)"
    $matchedPsid = 127
    $matchedPfid = 887
} else {
    Write-Ok "Matched: [$matchedKey] -> psid=$matchedPsid, pfid=$matchedPfid"
}

# ─────────────────────────────────────────────────────────────
# STEP 4: Query NVIDIA API for latest driver
# ─────────────────────────────────────────────────────────────

if (-not $SkipDriver) {

    Write-Step "Querying NVIDIA API for latest driver..."

    $latestDriverVer     = $null
    $latestDriverDownUrl = $null

    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

        $apiUrl = "$NVIDIA_LOOKUP_API" +
                  "?func=DriverManualLookup" +
                  "&pfid=$matchedPfid" +
                  "&osID=$NVIDIA_OS_ID" +
                  "&dch=1" +
                  "&upCRD=0" +
                  "&qnf=0" +
                  "&ctk=0"

        Write-Info "API: $apiUrl"

        $resp    = Invoke-RestMethod -Uri $apiUrl -Method Get -TimeoutSec 30
        $drvInfo = $resp.IDS | Select-Object -First 1

        if ($drvInfo) {
            $latestDriverVer     = $drvInfo.downloadInfo.Version
            $latestDriverDownUrl = $drvInfo.downloadInfo.DownloadURL

            if ($latestDriverDownUrl -notlike "http*") {
                $latestDriverDownUrl = "https://www.nvidia.com$latestDriverDownUrl"
            }

            Write-Ok "Latest driver version: $latestDriverVer"
            Write-Info "Download URL: $latestDriverDownUrl"
        }
    } catch {
        Write-Warn "NVIDIA API query failed: $_"
    }

    if (-not $latestDriverDownUrl) {
        Write-Warn "Automatic driver URL lookup failed."
        Write-Warn "Download manually from the NVIDIA website and use -SkipDriver to install CUDA only."
        Write-Host ""
        Write-Host "  https://www.nvidia.com/Download/index.aspx" -ForegroundColor Cyan
        Write-Host ""
        $latestDriverDownUrl = $null
    }

    # ─────────────────────────────────────────────────────────
    # STEP 5: Compare versions
    # ─────────────────────────────────────────────────────────

    $needDriverInstall = $true

    if ($currentDriverVer -and $latestDriverVer) {
        $latest = Get-DriverVersionFromString -raw $latestDriverVer
        if ($latest -and ($currentDriverVer -ge $latest) -and -not $Force) {
            Write-Ok "Current driver ($currentDriverVer) is up to date (latest: $latestDriverVer). Skipping. Use -Force to reinstall."
            $needDriverInstall = $false
        }
    } elseif ($currentDriverVer -and ($currentDriverVer -ge $MIN_DRIVER_VERSION) -and -not $Force) {
        Write-Ok "Current driver ($currentDriverVer) meets BGE-M3 minimum requirement ($MIN_DRIVER_VERSION). Skipping."
        $needDriverInstall = $false
    }

    # ─────────────────────────────────────────────────────────
    # STEP 6: Download & install driver
    # ─────────────────────────────────────────────────────────

    if ($needDriverInstall -and $latestDriverDownUrl) {

        Write-Step "Downloading driver (several hundred MB, please wait)..."

        $drvFileName = "nvidia-driver-$latestDriverVer.exe"
        $drvTemp     = Join-Path $env:TEMP $drvFileName

        if (Test-Path $drvTemp) {
            Write-Warn "Reusing cached installer: $drvTemp"
        } else {
            try {
                Download-File -Url $latestDriverDownUrl -Dest $drvTemp
                Write-Ok "Download complete: $drvTemp"
            } catch {
                Write-Fail "Driver download failed: $_"
                Write-Warn "Download manually or use -SkipDriver."
                $drvTemp = $null
            }
        }

        if ($drvTemp -and (Test-Path $drvTemp)) {
            Write-Step "Installing driver (PC may restart)..."
            Write-Info "Components: Display.Driver, PhysX, NVCP"

            # /s = silent, /n = no restart (manual restart recommended)
            $installArgs = "/s /n /components Display.Driver,PhysX,NVCP"

            $proc = Start-Process -FilePath $drvTemp -ArgumentList $installArgs -Wait -PassThru
            if ($proc.ExitCode -eq 0) {
                Write-Ok "Driver installed successfully"
            } elseif ($proc.ExitCode -eq 1) {
                Write-Warn "Driver installed (restart required, exit code: 1)"
            } else {
                Write-Fail "Driver installation failed (exit code: $($proc.ExitCode))"
                Write-Info "Log: $env:TEMP\nvidiaDrv*.log"
            }

            Remove-Item $drvTemp -Force -ErrorAction SilentlyContinue
        }
    }
}

# ─────────────────────────────────────────────────────────────
# STEP 7: Install CUDA Toolkit
# ─────────────────────────────────────────────────────────────

if (-not $SkipCuda) {

    Write-Step "Checking CUDA Toolkit $CudaVersion..."

    $cudaInstalled = $false
    $cudaPaths = @(
        "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA",
        "$env:ProgramFiles\NVIDIA GPU Computing Toolkit\CUDA"
    )
    foreach ($cp in $cudaPaths) {
        if (Test-Path $cp) {
            $existingCuda = Get-ChildItem $cp -Directory -ErrorAction SilentlyContinue |
                            Where-Object { $_.Name -like "v$CudaVersion*" }
            if ($existingCuda) {
                Write-Ok "CUDA $CudaVersion already installed: $($existingCuda.FullName)"
                $cudaInstalled = $true
                break
            }
        }
    }

    if (-not $cudaInstalled -and $env:CUDA_PATH) {
        if ($env:CUDA_PATH -like "*$CudaVersion*") {
            Write-Ok "CUDA $CudaVersion found via CUDA_PATH: $env:CUDA_PATH"
            $cudaInstalled = $true
        }
    }

    if ($cudaInstalled -and -not $Force) {
        Write-Ok "CUDA $CudaVersion is already installed. Use -Force to reinstall."
    } else {

        $cudaUrl = $CUDA_URLS[$CudaVersion]
        if (-not $cudaUrl) {
            Write-Fail "Unsupported CUDA version: $CudaVersion"
        } else {
            $cudaFileName = "cuda_$CudaVersion`_windows.exe"
            $cudaTemp     = Join-Path $env:TEMP $cudaFileName

            Write-Step "Downloading CUDA Toolkit $CudaVersion (~3 GB, this will take a while)..."
            Write-Info "URL: $cudaUrl"

            if (Test-Path $cudaTemp) {
                Write-Warn "Reusing cached installer: $cudaTemp"
            } else {
                try {
                    Download-File -Url $cudaUrl -Dest $cudaTemp
                    Write-Ok "CUDA download complete: $cudaTemp"
                } catch {
                    Write-Fail "CUDA download failed: $_"
                    $cudaTemp = $null
                }
            }

            if ($cudaTemp -and (Test-Path $cudaTemp)) {
                Write-Step "Installing CUDA Toolkit $CudaVersion (5-10 minutes)..."

                $cudaArgs = "-s " +
                            "cuda_documentation_$CudaVersion " +
                            "cuda_nvcc_$CudaVersion " +
                            "cuda_cuobjdump_$CudaVersion " +
                            "cuda_runtime_$CudaVersion " +
                            "cuda_cudart_$CudaVersion " +
                            "libcublas_$CudaVersion " +
                            "libcufft_$CudaVersion " +
                            "libcurand_$CudaVersion " +
                            "libcusolver_$CudaVersion " +
                            "libcusparse_$CudaVersion"

                $proc = Start-Process -FilePath $cudaTemp -ArgumentList $cudaArgs -Wait -PassThru
                if ($proc.ExitCode -eq 0) {
                    Write-Ok "CUDA Toolkit $CudaVersion installed successfully"
                } else {
                    Write-Warn "CUDA install exit code: $($proc.ExitCode)"
                    Write-Info "Log: $env:TEMP\cuda_*.log"
                }

                Remove-Item $cudaTemp -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

# ─────────────────────────────────────────────────────────────
# STEP 8: Verify PATH / CUDA_PATH
# ─────────────────────────────────────────────────────────────

Write-Step "Verifying environment variables..."

$cudaBase = "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v$CudaVersion"
if (Test-Path $cudaBase) {

    $cudaBin = "$cudaBase\bin"
    $sysPath = [System.Environment]::GetEnvironmentVariable("Path", "Machine")

    # Compare exact PATH entries — substring matching gives false positives
    $sysPathItems = $sysPath -split ';' | Where-Object { $_ }

    if ($sysPathItems -notcontains $cudaBin) {
        [System.Environment]::SetEnvironmentVariable("Path", "$cudaBin;$sysPath", "Machine")
        Write-Ok "Added to PATH: $cudaBin"
    } else {
        Write-Ok "Already in PATH: $cudaBin"
    }

    if (-not [System.Environment]::GetEnvironmentVariable("CUDA_PATH", "Machine")) {
        [System.Environment]::SetEnvironmentVariable("CUDA_PATH", $cudaBase, "Machine")
        Write-Ok "CUDA_PATH set: $cudaBase"
    }

    $env:Path      = "$cudaBin;$env:Path"
    $env:CUDA_PATH = $cudaBase
}

# ─────────────────────────────────────────────────────────────
# STEP 9: Final verification
# ─────────────────────────────────────────────────────────────

Write-Step "Running final verification..."

$nvSmi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
if (-not $nvSmi) {
    $nvSmiPath = "C:\Windows\System32\nvidia-smi.exe"
    if (Test-Path $nvSmiPath) { $nvSmi = $nvSmiPath }
}

if ($nvSmi) {
    try {
        $result = & nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv,noheader 2>&1
        Write-Ok "nvidia-smi result:"
        $result | ForEach-Object { Write-Info "  $_" }
    } catch {
        Write-Warn "nvidia-smi failed (check after restart)"
    }
} else {
    Write-Warn "nvidia-smi not found -- check after restart."
}

$nvcc = Get-Command nvcc -ErrorAction SilentlyContinue
if ($nvcc) {
    $nvccVer = & nvcc --version 2>&1 | Select-String "release" | Select-Object -First 1
    Write-Ok "nvcc: $nvccVer"
} else {
    Write-Warn "nvcc not found (CUDA not installed or PATH not refreshed -- check after restart)"
}

# ─────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "  Installation complete! Please restart.   " -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "  After restart, verify GPU:" -ForegroundColor Yellow
Write-Host "    nvidia-smi" -ForegroundColor Cyan
Write-Host "    python -c `"import torch; print(torch.cuda.is_available())`"" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Install BGE-M3 dependencies:" -ForegroundColor Yellow
Write-Host "    pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu$($CudaVersion -replace '\.','')" -ForegroundColor Cyan
Write-Host "    pip install FlagEmbedding" -ForegroundColor Cyan
Write-Host ""

if ($NonInteractive) {
    Write-Host "Restart required: please restart your PC manually to apply the driver." -ForegroundColor Yellow
} else {
    $restart = Read-Host "Restart now to apply the driver? [y/N]"
    if ($restart -eq 'y' -or $restart -eq 'Y') {
        Write-Host "Restarting in 10 seconds..." -ForegroundColor Yellow
        Start-Sleep -Seconds 10
        Restart-Computer -Force
    }
}
