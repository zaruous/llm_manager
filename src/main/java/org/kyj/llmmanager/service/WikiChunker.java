/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 위키 마크다운 파일을 임베딩용 청크로 분할한다.
 *
 * {@link WikiPreprocessor}가 생성한 의미 보강 텍스트에 SHA-256 content_hash를 부여한다.
 * 기존 호출부 호환성을 유지하면서 formatter 부재 시의 메타데이터 보정도 지원한다.
 */
public final class WikiChunker {

    private WikiChunker() {}

    /**
     * 청크 한 항목. chunkNo는 파일 내 0-based 순번.
     *
     * @param chunkNo     파일 내 0-based 청크 번호
     * @param type        frontmatter type 값 또는 카테고리 디렉토리명
     * @param tags        frontmatter tags 값 (없으면 빈 문자열)
     * @param content     청크 텍스트
     * @param contentHash SHA-256 앞 16자 (증분 재색인 비교용)
     */
    public record Chunk(int chunkNo, String type, String tags, String content, String contentHash) {}

    /**
     * 파일을 읽어 청크 목록으로 분할한다.
     *
     * @param file     마크다운 파일 경로
     * @param category 카테고리 디렉토리명 (type frontmatter 없을 때 사용)
     * @return 청크 목록 (순서 보장)
     */
    public static List<Chunk> chunk(Path file, String category) {
        String relativePath = file != null ? file.toString().replace('\\', '/') : "";
        return chunk(file, category, relativePath, WikiPreprocessor.Options.defaults());
    }

    /**
     * 경로와 청킹 설정을 지정해 파일을 임베딩용 청크로 변환한다.
     *
     * @param file         Markdown 파일
     * @param category     type 누락 시 사용할 카테고리
     * @param relativePath 워크스페이스 기준 페이지 경로
     * @param options      전처리 청킹 설정
     * @return 해시가 부여된 청크 목록
     */
    public static List<Chunk> chunk(Path file, String category, String relativePath,
                                    WikiPreprocessor.Options options) {
        WikiPreprocessor.PreparedDocument document =
                WikiPreprocessor.preprocess(file, category, relativePath, options);
        return chunk(document);
    }

    /**
     * 이미 전처리된 문서에 순번과 content hash를 부여한다.
     *
     * @param document WikiPreprocessor 전처리 결과
     * @return 해시가 부여된 청크 목록
     */
    public static List<Chunk> chunk(WikiPreprocessor.PreparedDocument document) {
        List<Chunk> result = new ArrayList<>();
        for (int i = 0; i < document.chunks().size(); i++) {
            String text = document.chunks().get(i);
            result.add(new Chunk(i, document.type(), document.tags(), text, hash(text)));
        }
        return result;
    }

    /** 텍스트의 SHA-256 앞 16자리를 반환한다. */
    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM에서 보장되므로 도달하지 않는다.
            throw new IllegalStateException(e);
        }
    }
}
