/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.dialog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.scene.control.SplitPane;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 서비스별 도움말을 마크다운으로 읽어 WebView에 렌더링하는 다이얼로그.
 * 좌측 목록에서 주제를 선택하면 우측 WebView에 내용이 표시된다.
 * 마크다운 렌더링에는 marked.js(CDN)를 사용한다.
 */
public class HelpDialog {

    private final Stage owner;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 주제명 → 마크다운 리소스 경로 (클래스패스 기준).
     * 순서가 곧 목록 표시 순서.
     */
    private static final Map<String, String> TOPICS = new LinkedHashMap<>();
    static {
        TOPICS.put("BGE-M3 Embedding Server", "docs/bge-m3.md");
        TOPICS.put("Swagger MCP Server",       "docs/swagger-mcp.md");
        TOPICS.put("SQL Gen MCP Server",       "docs/sql-gen-mcp.md");
        TOPICS.put("ChromaDB",                 "docs/chroma-db.md");
        TOPICS.put("LLM 스킬 설치",             "docs/llm-skills-install.md");
        TOPICS.put("LLM 스킬 로드",             "docs/llm-skills-load.md");
        TOPICS.put("LLM Wiki Agent",           "docs/wiki-agent.md");
        TOPICS.put("Wiki 대화 수집",            "docs/wiki-conversation-ingest.md");
    }

    /**
     * @param owner 오너 Stage
     */
    public HelpDialog(Stage owner) {
        this.owner = owner;
    }

    /**
     * 도움말 창을 표시한다. 비모달로 열어 앱과 함께 사용할 수 있다.
     */
    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("도움말");

        // ── 좌측: 주제 목록 ─────────────────────────────
        ListView<String> topicList = new ListView<>();
        topicList.getItems().addAll(TOPICS.keySet());
        topicList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setStyle(empty || item == null ? ""
                        : "-fx-font-size: 12px; -fx-padding: 6 10;");
            }
        });

        // ── 우측: WebView ────────────────────────────────
        WebView webView = new WebView();
        webView.getEngine().setJavaScriptEnabled(true);
        VBox.setVgrow(webView, Priority.ALWAYS);

        // 첫 주제 기본 표시
        String firstTopic = TOPICS.keySet().iterator().next();
        loadTopic(webView, TOPICS.get(firstTopic));
        topicList.getSelectionModel().selectFirst();

        // 선택 변경 시 콘텐츠 교체
        topicList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, topic) -> {
                    if (topic != null) loadTopic(webView, TOPICS.get(topic));
                });

        // ── 레이아웃 ─────────────────────────────────────
        SplitPane split = new SplitPane(topicList, webView);
        split.setDividerPositions(0.24);

        stage.setScene(SceneFactory.create(split, 1020, 720));
        stage.setResizable(true);
        stage.show();
    }

    /**
     * 마크다운 리소스를 읽어 HTML 템플릿에 주입한 뒤 WebView에 로드한다.
     *
     * @param webView      콘텐츠를 표시할 WebView
     * @param resourcePath 마크다운 파일의 리소스 경로 (docs/*.md)
     */
    private void loadTopic(WebView webView, String resourcePath) {
        try {
            String md       = readResource(resourcePath);
            String template = readResource("docs/help-template.html");

            // Jackson으로 JSON 문자열 직렬화 → 이스케이프 자동 처리
            String jsonMd = MAPPER.writeValueAsString(md);
            String html   = template.replace("__MARKDOWN_JSON__", jsonMd);

            webView.getEngine().loadContent(html, "text/html");
        } catch (Exception e) {
            webView.getEngine().loadContent(
                    "<body style='color:#dd8888;padding:20px;background:#1a1a2e'>"
                    + "도움말을 불러올 수 없습니다: " + e.getMessage() + "</body>",
                    "text/html");
        }
    }

    /**
     * 클래스패스에서 리소스 파일을 문자열로 읽는다.
     *
     * @param path /org/kyj/llmmanager/ 이후의 상대 경로
     * @return 파일 내용 문자열
     * @throws Exception 리소스를 찾을 수 없거나 읽기 실패 시
     */
    private String readResource(String path) throws Exception {
        String fullPath = "/org/kyj/llmmanager/" + path;
        try (InputStream is = getClass().getResourceAsStream(fullPath)) {
            if (is == null) throw new Exception("리소스 없음: " + fullPath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
