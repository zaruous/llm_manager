/*
 * 작성자 : kyj
 * 작성일 : 2026-06-13
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * plugin.json의 {@code ingest} 섹션 — 수집 허용 확장자(include)와
 * 제외 경로(exclude)를 gitignore 스타일 glob 패턴 목록으로 관리한다.
 *
 * include 패턴은 파일명에만, exclude 패턴은 워크스페이스 기준 상대 경로
 * 전체에 매칭한다. 두 목록이 모두 비어 있으면 WikiIngestDialog가 기본값을 사용한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginIngestConfig {

    /** 수집 허용 파일명 패턴 목록. 비어 있으면 "모두 허용"으로 해석한다. */
    private List<String> include = new ArrayList<>();

    /** 수집 제외 경로 패턴 목록. 비어 있으면 "제외 없음"으로 해석한다. */
    private List<String> exclude = new ArrayList<>();

    public List<String> getInclude() { return include; }
    public void setInclude(List<String> include) {
        this.include = include != null ? include : new ArrayList<>();
    }

    public List<String> getExclude() { return exclude; }
    public void setExclude(List<String> exclude) {
        this.exclude = exclude != null ? exclude : new ArrayList<>();
    }
}
