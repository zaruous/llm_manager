#Requires -Version 5.1
<#
.SYNOPSIS
    LLM Manager 배포 파일을 빌드해 deploy\ 에 출력합니다.

.DESCRIPTION
    기본 (실행 파일):
        jpackageImage 로 JRE 번들 앱 이미지를 만들고 deploy\ 에 ZIP 으로 압축합니다.
        설치 불필요 — 압축 해제 후 LLMManager.exe 를 바로 실행합니다.
        WiX 필요 없음.

    -Installer 옵션 (설치 파일):
        jpackageExe 로 EXE 인스톨러를 만들고 deploy\ 에 복사합니다.
        더블클릭 설치, 시작 메뉴·바탕화면 단축키 자동 생성.
        WiX Toolset 3.0+ 필요 (없으면 자동 다운로드).

.PARAMETER Installer
    지정하면 설치 파일(EXE 인스톨러)을 빌드합니다.
    생략하면 실행 파일(app-image ZIP)을 빌드합니다.

.EXAMPLE
    # 실행 파일 빌드 (기본)
    powershell -ExecutionPolicy Bypass -File bin\buildExe.ps1

    # 설치 파일 빌드
    powershell -ExecutionPolicy Bypass -File bin\buildExe.ps1 -Installer
#>

[CmdletBinding()]
param(
    [switch]$Installer
)

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

if ($Installer) {
    # ══════════════════════════════════════════════
    #   설치 파일 모드 (EXE 인스톨러)
    # ══════════════════════════════════════════════
    Write-Host ""
    Write-Host "══════════════════════════════════════════════" -ForegroundColor Magenta
    Write-Host "  LLM Manager 설치 파일 빌드 (EXE 인스톨러)" -ForegroundColor Magenta
    Write-Host "══════════════════════════════════════════════" -ForegroundColor Magenta

    # Step 1: WiX 확인 / 다운로드
    Write-Step 1 3 "WiX Toolset 확인"
    if (Test-Path (Join-Path $WixDir 'candle.exe')) {
        Write-Host "  이미 준비됨: $WixDir" -ForegroundColor Green
    } else {
        Write-Host "  candle.exe 없음 → 다운로드 시작" -ForegroundColor Yellow
        & "$ScriptDir\Download-WiX.ps1"
    }

    if ($WixDir -notin ($env:PATH -split ';')) {
        $env:PATH = "$WixDir;$env:PATH"
        Write-Host "  WiX 프로세스 PATH 에 임시 추가" -ForegroundColor Yellow
    }

    # Step 2: jpackageExe 빌드
    Write-Step 2 3 "jpackageExe 빌드 (Gradle)"
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
        if ($LASTEXITCODE -ne 0) { throw "Gradle 빌드 실패 (종료 코드: $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    # Step 3: deploy\ 로 복사
    Write-Step 3 3 "EXE → deploy\ 복사"
    $outFile = Get-ChildItem -Path (Join-Path $ProjectRoot 'build\installer') -ErrorAction SilentlyContinue |
               Where-Object { $_.Extension -in '.exe', '.msi', '.dmg', '.deb' } |
               Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $outFile) { throw "인스톨러 파일을 찾을 수 없습니다." }

    if (-not (Test-Path $DeployDir)) { New-Item -ItemType Directory -Path $DeployDir | Out-Null }
    $dest = Join-Path $DeployDir $outFile.Name
    Copy-Item -Path $outFile.FullName -Destination $dest -Force
    Write-Host "  $($outFile.Name)" -ForegroundColor White
    Write-Host "  → $dest" -ForegroundColor Green

    Write-Host ""
    Write-Host "══════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "  설치 파일 완료: $dest" -ForegroundColor Green
    Write-Host "══════════════════════════════════════════════" -ForegroundColor Green

} else {
    # ══════════════════════════════════════════════
    #   실행 파일 모드 (app-image ZIP) — 기본값
    # ══════════════════════════════════════════════
    Write-Host ""
    Write-Host "══════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  LLM Manager 실행 파일 빌드 (app-image)" -ForegroundColor Cyan
    Write-Host "══════════════════════════════════════════════" -ForegroundColor Cyan

    # Step 1: jpackageImage 빌드
    Write-Step 1 2 "jpackageImage 빌드 (Gradle)"
    Push-Location $ProjectRoot
    try {
        & '.\gradlew' jpackageImage
        if ($LASTEXITCODE -ne 0) { throw "Gradle 빌드 실패 (종료 코드: $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    # Step 2: app-image 폴더를 ZIP 으로 압축해 deploy\ 에 저장
    Write-Step 2 2 "app-image → deploy\ 압축"

    # build.gradle --name 값과 일치하는 폴더 탐색
    $appImageDir = Get-ChildItem -Path (Join-Path $ProjectRoot 'build\installer') -Directory |
                   Select-Object -First 1
    if (-not $appImageDir) { throw "app-image 폴더를 찾을 수 없습니다." }

    if (-not (Test-Path $DeployDir)) { New-Item -ItemType Directory -Path $DeployDir | Out-Null }

    # 버전은 build.gradle 의 version 에서 읽음
    $version = (Select-String -Path (Join-Path $ProjectRoot 'build.gradle') -Pattern "^version\s*=\s*'(.+)'").Matches[0].Groups[1].Value
    $zipName = "LLMManager-${version}.zip"
    $zipPath = Join-Path $DeployDir $zipName

    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Compress-Archive -Path $appImageDir.FullName -DestinationPath $zipPath -Force
    $sizeMB = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
    Write-Host "  $($appImageDir.Name)\" -ForegroundColor White
    Write-Host "  → $zipPath ($sizeMB MB)" -ForegroundColor Green

    Write-Host ""
    Write-Host "══════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "  실행 파일 완료: $zipPath" -ForegroundColor Green
    Write-Host "  압축 해제 후 $($appImageDir.Name)\LLMManager.exe 실행" -ForegroundColor DarkGray
    Write-Host "══════════════════════════════════════════════" -ForegroundColor Green
}

Write-Host ""
