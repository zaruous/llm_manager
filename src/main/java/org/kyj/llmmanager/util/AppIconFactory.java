/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.util;

import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * LLM Manager 애플리케이션 아이콘을 프로그래밍 방식으로 생성한다.
 * 외부 이미지 파일 없이 Java2D로 그려 AWT(시스템 트레이)와 JavaFX(Stage) 양쪽에서 사용한다.
 *
 * <p>디자인: 둥근 사각형 배경(다크 네이비) + 파란 테두리 + 흰색 "LM" 텍스트
 */
public final class AppIconFactory {
    private static final Logger log = LoggerFactory.getLogger(AppIconFactory.class);

    /** 배경색: 다크 네이비 */
    private static final Color BG       = new Color(0x1a, 0x1a, 0x2e);
    /** 테두리/강조색: 밝은 파란색 */
    private static final Color ACCENT   = new Color(0x33, 0x99, 0xff);
    /** 내부 강조색: 약간 밝은 파란 */
    private static final Color ACCENT2  = new Color(0x55, 0xbb, 0xff);
    /** 텍스트 색: 흰색 */
    private static final Color TEXT     = Color.WHITE;

    private AppIconFactory() {}

    // =========================================================
    // AWT 아이콘 (시스템 트레이용)
    // =========================================================

    /**
     * 지정 크기의 AWT {@link java.awt.Image} 아이콘을 생성한다.
     * 시스템 트레이, AWT 창 등에 사용한다.
     *
     * @param size 아이콘 한 변 크기(px)
     * @return 생성된 AWT 이미지
     */
    public static java.awt.Image createAwtIcon(int size) {
        return createBuffered(size);
    }

    // =========================================================
    // JavaFX 아이콘 (Stage용)
    // =========================================================

    /**
     * 지정 크기의 JavaFX {@link Image}를 생성한다.
     *
     * @param size 아이콘 한 변 크기(px)
     * @return JavaFX 이미지 (실패 시 null)
     */
    public static Image createFxIcon(int size) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(createBuffered(size), "PNG", baos);
            return new Image(new ByteArrayInputStream(baos.toByteArray()));
        } catch (Exception e) {
            log.warn("FX 아이콘 생성 실패 (size={}): {}", size, e.getMessage());
            return null;
        }
    }

    /**
     * Stage.getIcons()에 등록할 다중 해상도 JavaFX 아이콘 목록을 반환한다.
     * JavaFX가 맥락(타이틀바·태스크바·HiDPI 등)에 맞는 크기를 자동으로 선택한다.
     *
     * @return 16·32·48·64·128·256px 아이콘 목록
     */
    public static List<Image> createFxIcons() {
        int[] sizes = {16, 32, 48, 64, 128, 256};
        List<Image> icons = new ArrayList<>();
        for (int size : sizes) {
            Image img = createFxIcon(size);
            if (img != null) icons.add(img);
        }
        return icons;
    }

    // =========================================================
    // 공통 그리기 로직
    // =========================================================

    /**
     * Java2D로 아이콘 비트맵을 생성한다.
     * 크기에 따라 텍스트·테두리 굵기를 비례 조정한다.
     *
     * @param size 한 변 크기(px)
     * @return ARGB BufferedImage
     */
    private static BufferedImage createBuffered(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // 안티앨리어싱 활성화
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,    RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,       RenderingHints.VALUE_RENDER_QUALITY);

        float pad    = size * 0.04f;               // 외곽 여백
        float radius = size * 0.22f;               // 둥근 모서리 반지름
        float border = Math.max(1.5f, size * 0.07f); // 테두리 굵기

        // ── 배경 (둥근 사각형) ──────────────────────────────────
        g.setColor(BG);
        g.fill(new RoundRectangle2D.Float(pad, pad,
                size - 2 * pad, size - 2 * pad, radius * 2, radius * 2));

        // ── 테두리 ───────────────────────────────────────────────
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(border, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(pad + border / 2, pad + border / 2,
                size - 2 * pad - border, size - 2 * pad - border, radius * 2, radius * 2));

        // ── "LM" 텍스트 ──────────────────────────────────────────
        int fontSize = Math.max(8, (int)(size * 0.36));
        g.setFont(new Font("Arial", Font.BOLD, fontSize));
        FontMetrics fm = g.getFontMetrics();
        String text = "LM";
        int tx = (size - fm.stringWidth(text)) / 2;
        int ty = (size - fm.getHeight()) / 2 + fm.getAscent();

        // 텍스트 그림자 (작은 크기에서는 생략)
        if (size >= 32) {
            g.setColor(ACCENT.darker());
            g.drawString(text, tx + 1, ty + 1);
        }
        g.setColor(TEXT);
        g.drawString(text, tx, ty);

        // ── 하단 파란 밑줄 강조 (32px 이상에서만) ─────────────────
        if (size >= 32) {
            float lineW  = size * 0.5f;
            float lineH  = Math.max(2, size * 0.05f);
            float lineX  = (size - lineW) / 2f;
            float lineY  = size - pad - border - lineH * 2;
            g.setColor(ACCENT2);
            g.fillRoundRect((int)lineX, (int)lineY, (int)lineW, (int)lineH,
                    (int)lineH, (int)lineH);
        }

        g.dispose();
        return img;
    }
}
