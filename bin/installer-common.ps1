#Requires -Version 5.1
<#
.SYNOPSIS
    Shared helpers for the install scripts (dot-sourced).

.DESCRIPTION
    Common output helpers, admin check, proxy handling, download wrapper and
    offline-installer fallback used by install-python.ps1, install-nodejs.ps1
    and install-nvidia-driver.ps1. Load from a sibling script with:

        . (Join-Path $PSScriptRoot 'installer-common.ps1')
#>

# Callers (e.g. the Java setup dialog) read our output as UTF-8;
# Windows PowerShell 5.1 defaults to the OEM codepage, so force UTF-8.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ─────────────────────────────────────────────
# Output helpers
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

function Write-Info {
    param([string]$Message)
    Write-Host "         $Message" -ForegroundColor DarkGray
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

# Newest pre-staged installer matching the pattern, or $null when absent
function Find-OfflineInstaller {
    param([string]$Pattern)
    if (-not (Test-Path $OfflineDir)) { return $null }
    $f = Get-ChildItem $OfflineDir -Filter $Pattern -File -ErrorAction SilentlyContinue |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($f) { return $f.FullName }
    return $null
}
