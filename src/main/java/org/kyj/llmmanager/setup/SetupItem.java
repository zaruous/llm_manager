/*
 * 작성자 : kyj
 * 작성일 : 2026-06-09
 */
package org.kyj.llmmanager.setup;

/**
 * 앱 기동 전 확인해야 할 환경 항목.
 * 각 항목은 이름·설명·필수 여부·설치 스크립트 경로를 갖는다.
 * required=true 항목이 모두 통과해야 메인 창을 열 수 있다.
 */
public enum SetupItem {

    PYTHON(
            "Python 3.x",
            "BGE-M3 서버 실행에 반드시 필요한 런타임입니다.",
            true,
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
