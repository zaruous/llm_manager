#Requires -Version 5.1
<#
.SYNOPSIS
    WiX 준비 → jpackageExe 빌드 → EXE 를 deploy\ 에 출력하는 원스텝 파이프라인.

.DESCRIPTION
    1. deploy\WiX\candle.exe 가 없으면 Download-WiX.ps1 을 실행해 다운로드합니다.
    2. WiX 를 현재 프로세스 PATH 에 임시 추가합니다.
       (시스템·사용자 환경변수는 변경하지 않습니다)
    3. ./gradlew jpackageExe 로 EXE 인스톨러를 빌드합니다.
    4. 생성된 EXE 파일을 build\installer\ 에서 deploy\ 로 복사합니다.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File bin\buildExe.ps1
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$WixDir      = Join-Path $ProjectRoot 'deploy\WiX'
$DeployDir   = Join-Path $ProjectRoot 'deploy'

function Write-Step([int]$n, [int]$total, [string]$msg) {
    Write-Host ""
    Write-Host "  [$n/$total] $msg" -ForegroundColor Cyan
    Write-Host ("  " + "─" * 48) -ForegroundColor DarkGray
}


Write-Host ""
Write-Host "══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  LLM Manager EXE 빌드 파이프라인" -ForegroundColor Cyan
Write-Host "══════════════════════════════════════════════" -ForegroundColor Cyan

# ── Step 1: WiX 확인 / 다운로드 ──────────────────────────────────────────────
Write-Step 1 3 "WiX Toolset 확인"
if (Test-Path (Join-Path $WixDir 'candle.exe')) {
    Write-Host "  이미 준비됨: $WixDir" -ForegroundColor Green
} else {
    Write-Host "  candle.exe 없음 → 다운로드 시작" -ForegroundColor Yellow
    & "$ScriptDir\Download-WiX.ps1"
}

# WiX 를 현재 프로세스 PATH 에 임시 추가 (시스템·사용자 환경변수 변경 없음)
if ($WixDir -notin ($env:PATH -split ';')) {
    $env:PATH = "$WixDir;$env:PATH"
    Write-Host "  WiX 를 프로세스 PATH 에 임시 추가: $WixDir" -ForegroundColor Yellow
}

# ── Step 2: jpackageExe 빌드 ─────────────────────────────────────────────────
Write-Step 2 3 "jpackageExe 빌드 (Gradle)"

# WiX light.exe 는 Windows Defender 의 실시간 스캔이 임시 파일을 건드리면 오류 311 로
# 실패할 수 있다. 최대 2회 재시도한다.
# 반복 실패 시: 관리자 PowerShell 에서 Add-MpPreference -ExclusionPath $env:TEMP 실행 후 재시도.
Push-Location $ProjectRoot
try {
    $attempt = 0
    do {
        $attempt++
        if ($attempt -gt 1) {
            Write-Host "  [재시도 $attempt/2] jpackageExe..." -ForegroundColor Yellow
        }
        & '.\gradlew' jpackageExe
    } while ($LASTEXITCODE -ne 0 -and $attempt -lt 2)

    if ($LASTEXITCODE -ne 0) {
        throw "Gradle 빌드 실패 (종료 코드: $LASTEXITCODE)"
    }
} finally {
    Pop-Location
}

# ── Step 3: EXE 를 deploy\ 로 복사 ───────────────────────────────────────────
Write-Step 3 3 "EXE → deploy\ 복사"

$installerDir = Join-Path $ProjectRoot 'build\installer'
$exeFile = Get-ChildItem -Path $installerDir -ErrorAction SilentlyContinue |
           Where-Object { $_.Extension -in '.exe', '.msi', '.dmg', '.deb' } |
           Sort-Object LastWriteTime |
           Select-Object -Last 1

if (-not $exeFile) {
    throw "인스톨러 파일을 찾을 수 없습니다: $installerDir"
}

if (-not (Test-Path $DeployDir)) {
    New-Item -ItemType Directory -Path $DeployDir | Out-Null
}

$destPath = Join-Path $DeployDir $exeFile.Name
Copy-Item -Path $exeFile.FullName -Destination $destPath -Force
Write-Host "  $($exeFile.Name)" -ForegroundColor White
Write-Host "  → $destPath" -ForegroundColor Green

Write-Host ""
Write-Host "══════════════════════════════════════════════" -ForegroundColor Green
Write-Host "  빌드 완료" -ForegroundColor Green
Write-Host "  $destPath" -ForegroundColor Green
Write-Host "══════════════════════════════════════════════" -ForegroundColor Green
Write-Host ""
