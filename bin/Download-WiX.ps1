#Requires -Version 5.1
<#
.SYNOPSIS
    WiX Toolset 3.14.1 바이너리를 deploy\WiX 폴더에 다운로드합니다.

.DESCRIPTION
    jpackage 로 EXE/MSI 인스톨러를 빌드하려면 WiX Toolset (candle.exe, light.exe) 이
    PATH 에 등록되어 있어야 합니다. 이 스크립트는 GitHub Release 에서 바이너리 ZIP 을
    내려받아 deploy\WiX 에 압축 해제하고, 현재 세션의 PATH 에 자동으로 추가합니다.

.EXAMPLE
    # 프로젝트 루트에서 실행
    powershell -ExecutionPolicy Bypass -File deploy\Download-WiX.ps1
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── 설정 ────────────────────────────────────────────────────────────────────
$WIX_VERSION  = '3.14.1'
$RELEASE_TAG  = 'wix3141rtm'
$ZIP_NAME     = 'wix314-binaries.zip'
$DOWNLOAD_URL = "https://github.com/wixtoolset/wix3/releases/download/$RELEASE_TAG/$ZIP_NAME"

# bin\ 기준 상위(프로젝트 루트) → deploy\WiX 에 설치
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir     # 프로젝트 루트
$DeployDir   = Join-Path $ProjectRoot 'deploy'   # deploy\
$WixDir      = Join-Path $DeployDir 'WiX'        # deploy\WiX\
$ZipPath     = Join-Path $env:TEMP "$ZIP_NAME"   # 임시 저장 위치 (TEMP)
# ────────────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  WiX Toolset $WIX_VERSION 다운로더" -ForegroundColor Cyan
Write-Host "══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# 이미 candle.exe 가 있으면 재다운로드 생략
if (Test-Path (Join-Path $WixDir 'candle.exe')) {
    Write-Host "[OK] 이미 설치되어 있습니다: $WixDir" -ForegroundColor Green
    Write-Host "     candle.exe / light.exe 사용 가능"
} else {
    # 대상 디렉토리 생성
    if (-not (Test-Path $WixDir)) {
        New-Item -ItemType Directory -Path $WixDir | Out-Null
        Write-Host "[+] 디렉토리 생성: $WixDir"
    }

    # 다운로드
    Write-Host "[↓] 다운로드 중..."
    Write-Host "    $DOWNLOAD_URL"
    Write-Host "    → $ZipPath"
    Write-Host ""
    $ProgressPreference = 'SilentlyContinue'   # 다운로드 진행 표시 억제 (속도 향상)
    Invoke-WebRequest -Uri $DOWNLOAD_URL -OutFile $ZipPath -UseBasicParsing
    $ProgressPreference = 'Continue'

    # 압축 해제
    Write-Host "[⇒] 압축 해제 중: $WixDir"
    Expand-Archive -Path $ZipPath -DestinationPath $WixDir -Force

    # 임시 ZIP 삭제
    Remove-Item -Path $ZipPath -Force
    Write-Host "[✓] 완료: $(Get-ChildItem $WixDir -Filter '*.exe' | Measure-Object | Select-Object -ExpandProperty Count) 개 실행 파일 설치됨"
}

Write-Host ""

# ── 현재 PowerShell 세션 PATH 에 추가 ───────────────────────────────────────
$envPath = $env:PATH -split ';'
if ($WixDir -notin $envPath) {
    $env:PATH = "$WixDir;$env:PATH"
    Write-Host "[PATH] 현재 세션에 WiX 경로 추가됨: $WixDir" -ForegroundColor Yellow
    Write-Host "       (영구 등록은 아래 '시스템 PATH 등록' 섹션 참고)"
} else {
    Write-Host "[PATH] 이미 현재 세션 PATH 에 포함되어 있습니다." -ForegroundColor Green
}

# ── 동작 확인 ────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "── 설치 확인 ──────────────────────────────────" -ForegroundColor Cyan
try {
    $ver = & "$WixDir\candle.exe" -? 2>&1 | Select-String 'version' | Select-Object -First 1
    Write-Host "[candle] $($ver -replace '\s+', ' ')" -ForegroundColor Green
} catch {
    Write-Host "[candle] 확인 실패 — 경로를 점검하세요: $WixDir" -ForegroundColor Red
}

# ── 안내 메시지 ──────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "── 다음 단계 ──────────────────────────────────" -ForegroundColor Cyan
Write-Host ""
Write-Host "  1. EXE 인스톨러 빌드:" -ForegroundColor White
Write-Host "     .\gradlew jpackageExe" -ForegroundColor Gray
Write-Host ""
Write-Host "  2. 시스템 PATH 영구 등록 (관리자 권한 PowerShell):" -ForegroundColor White
Write-Host "     [System.Environment]::SetEnvironmentVariable(" -ForegroundColor Gray
Write-Host "       'PATH'," -ForegroundColor Gray
Write-Host "       `"$WixDir;`$([System.Environment]::GetEnvironmentVariable('PATH','Machine'))`"," -ForegroundColor Gray
Write-Host "       'Machine'" -ForegroundColor Gray
Write-Host "     )" -ForegroundColor Gray
Write-Host ""
