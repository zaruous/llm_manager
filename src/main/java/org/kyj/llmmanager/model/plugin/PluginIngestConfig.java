/*
 * 작성자 : kyj
 * 작성일 : 2026-06-13
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * plugin.json의 {@code ingest} 섹션 — 수집 허용 확장자, 제외 경로,
 * MCP 직접 입력 제한과 원본 스테이징 경로를 관리한다.
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

    /** MCP content 직접 입력의 최대 UTF-8 바이트 수. */
    private long contentMaxBytes;

    /** MCP 입력 원본을 보존할 워크스페이스 상대 경로. */
    private String stagingDirectory = "";

    public List<String> getInclude() { return include; }
    public void setInclude(List<String> include) {
        this.include = include != null ? include : new ArrayList<>();
    }

    public List<String> getExclude() { return exclude; }
    public void setExclude(List<String> exclude) {
        this.exclude = exclude != null ? exclude : new ArrayList<>();
    }

    public long getContentMaxBytes() { return contentMaxBytes; }
    public void setContentMaxBytes(long contentMaxBytes) {
        this.contentMaxBytes = contentMaxBytes;
    }

    public String getStagingDirectory() { return stagingDirectory; }
    public void setStagingDirectory(String stagingDirectory) {
        this.stagingDirectory = stagingDirectory != null ? stagingDirectory : "";
    }
}
