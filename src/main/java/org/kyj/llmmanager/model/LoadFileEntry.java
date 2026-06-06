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

    /** CheckBoxListCell 바인딩용 선택 상태. 기본값 true(스캔 시 전체 선택). */
    private final BooleanProperty selected = new SimpleBooleanProperty(true);

    public LoadFileEntry(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getRelativePath() { return relativePath; }

    public BooleanProperty selectedProperty() { return selected; }

    public boolean isSelected() { return selected.get(); }

    public void setSelected(boolean v) { selected.set(v); }

    @Override
    public String toString() { return relativePath; }
}
