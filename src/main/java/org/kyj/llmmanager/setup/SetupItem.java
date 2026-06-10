/*
 * 작성자 : kyj
 * 작성일 : 2026-06-09
 */
package org.kyj.llmmanager.setup;

/**
 * 환경 체크 다이얼로그에서 확인·설치하는 환경 항목.
 * 각 항목은 이름·설명·필수 여부·설치 스크립트 경로를 갖는다.
 * required=true 항목이 미통과면 앱 기동 시 다이얼로그가 표시되고 '계속'이 비활성화된다.
 * 현재 모든 항목은 선택 항목 — Python은 PYTHON 런타임 서비스의 설치/시작 시점에 검사한다.
 */
public enum SetupItem {

    PYTHON(
            "Python 3.x",
            "BGE-M3 서버 실행에 필요한 런타임입니다. 서비스 설치/시작 시점에 다시 확인합니다.",
            false,
            "bin/install-python.ps1"
    ),

    NVIDIA_DRIVER(
            "NVIDIA 드라이버",
            "GPU 가속을 사용할 때 필요합니다. CPU 전용이면 건너뜀 가능.",
            false,
            "bin/install-nvidia-driver.ps1"
    ),

    CUDA(
            "CUDA Toolkit",
            "PyTorch GPU 연산 라이브러리입니다. CPU 전용이면 건너뜀 가능.",
            false,
            "bin/install-nvidia-driver.ps1"
    ),

    NODEJS(
            "Node.js 22+",
            "Cursor 플러그인(@cursor/sdk sidecar) 실행에 필요한 런타임입니다. 플러그인 미사용 시 건너뜀 가능.",
            false,
            "bin/install-nodejs.ps1"
    );

    /** UI에 표시할 항목 이름 */
    private final String displayName;
    /** 항목 역할 설명 */
    private final String description;
    /** true이면 미설치 시 '계속' 버튼이 활성화되지 않는다. */
    private final boolean required;
    /** 설치 실행 스크립트 경로 (앱 루트 기준 상대경로) */
    private final String scriptPath;

    SetupItem(String displayName, String description, boolean required, String scriptPath) {
        this.displayName = displayName;
        this.description = description;
        this.required    = required;
        this.scriptPath  = scriptPath;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public boolean isRequired()    { return required; }
    public String getScriptPath()  { return scriptPath; }
}
