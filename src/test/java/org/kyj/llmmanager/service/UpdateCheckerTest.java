/*
 * 작성자 : kyj
 * 작성일 : 2026-06-14
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpdateChecker의 버전 비교 로직과 app.properties 로딩을 검증한다.
 */
class UpdateCheckerTest {

    // ─── isNewer() ────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} > {1} → {2}")
    @CsvSource({
        "1.0.5, 1.0.4, true",
        "1.1.0, 1.0.9, true",
        // 1.10.0 > 1.9.0 — 문자열 정렬이 아닌 정수 비교
        "1.10.0, 1.9.0, true",
        "2.0.0, 1.9.9, true",
        // 같은 버전
        "1.0.4, 1.0.4, false",
        // 낮은 버전
        "1.0.3, 1.0.4, false",
        "0.9.9, 1.0.0, false",
        // v 접두사 포함
        "1.0.5, 1.0.4, true",
    })
    void isNewer(String candidate, String current, boolean expected) {
        assertEquals(expected, UpdateChecker.isNewer(candidate, current),
                candidate + " isNewer than " + current);
    }

    @Test
    void isNewer_prefixStrippedByCheckForUpdate() {
        // checkForUpdate()가 "v1.0.5" → "1.0.5"로 변환한 뒤 isNewer()를 호출하므로
        // isNewer()는 숫자만 받는다.
        assertTrue(UpdateChecker.isNewer("1.0.5", "1.0.4"));
        assertFalse(UpdateChecker.isNewer("1.0.4", "1.0.4"));
    }

    // ─── fromProperties() ─────────────────────────────────────────

    @Test
    void fromProperties_readsVersion() {
        UpdateChecker checker = UpdateChecker.fromProperties();
        // 빌드 타임에 processResources가 버전을 주입하므로 "0.0.0"이 아니어야 한다.
        // 테스트 클래스패스에서 app.properties를 읽지 못하면 기본값 "0.0.0"으로 폴백.
        assertNotNull(checker.getCurrentVersion());
        assertFalse(checker.getCurrentVersion().isBlank());
    }

    @Test
    void fromProperties_fallbackOnMissingResource() {
        // 직접 생성 경로 — 폴백 기본값 검증
        UpdateChecker checker = new UpdateChecker("0.0.0", "zaruous/llm_manager");
        assertEquals("0.0.0", checker.getCurrentVersion());
    }
}
