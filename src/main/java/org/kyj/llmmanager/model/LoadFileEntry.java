/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * 로드 탭에서 소스 디렉토리 스캔 결과를 담는 파일 항목 모델.
 * 체크박스 선택 상태를 {@link BooleanProperty}로 관리해 CheckBoxListCell과 바인딩한다.
 */
public class LoadFileEntry {

    /** 소스 루트 기준 상대 경로 (구분자 '/'로 정규화). */
    private final String relativePath;

    /** 트리 셀에 표시할 이름. 파일은 파일명, 디렉토리는 디렉토리명. */
    private final String displayName;

    /** 디렉토리 노드 여부. true이면 미리보기/복사 대상이 아니다. */
    private final boolean directory;

    /** CheckBoxListCell 바인딩용 선택 상태. 기본값 true(스캔 시 전체 선택). */
    private final BooleanProperty selected = new SimpleBooleanProperty(true);

    public LoadFileEntry(String relativePath) {
        this(relativePath, relativePath, false);
    }

    public LoadFileEntry(String relativePath, String displayName, boolean directory) {
        this.relativePath = relativePath;
        this.displayName = displayName;
        this.directory = directory;
    }

    public String getRelativePath() { return relativePath; }

    public String getDisplayName() { return displayName; }

    public boolean isDirectory() { return directory; }

    public BooleanProperty selectedProperty() { return selected; }

    public boolean isSelected() { return selected.get(); }

    public void setSelected(boolean v) { selected.set(v); }

    @Override
    public String toString() { return displayName; }
}
