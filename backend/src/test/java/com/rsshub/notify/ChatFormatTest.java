package com.rsshub.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatFormat}'s budget is the one thing about a chat payload a vendor refuses a send over, and the unit it
 * publishes is bytes — so the test measures in bytes too, in Chinese, because that is where a character-count guard
 * goes wrong. 2000 is written out rather than read from the constant: changing the ceiling should fail loudly here.
 */
class ChatFormatTest {

    private static final int LIMIT_BYTES = 2000;

    private static Notification alert(String title, String summary) {
        return new Notification(1, "wecom", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k", null, null,
            7, "磁盘告警", 42, title, "https://example.org/a", summary, "生产节点", List.of("OOM"),
            Instant.parse("2026-10-18T00:00:00Z"));
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    @DisplayName("A short alert keeps the deciding facts: rule, title, source, link, keyword")
    void theHeadOfTheMessageIsWhatGetsItOpened() {
        String body = ChatFormat.markdown(alert("OOM on node-3", "容器被杀死"));
        assertThat(body).contains("OOM on node-3", "磁盘告警", "生产节点", "https://example.org/a", "命中：OOM",
            "容器被杀死");
        assertThat(bytes(body)).isLessThan(500);
    }

    @Test
    @DisplayName("A long Chinese body is cut at the byte ceiling, not at the character count")
    void cjkContentIsMeasuredInTheUnitsTheVendorPublishes() {
        String title = "内存".repeat(1200);
        String body = ChatFormat.plain(alert(title, null));
        // 2400 characters sail past a 2000-character guard and arrive as ~7200 bytes, three times the budget.
        assertThat(title.length()).isGreaterThan(LIMIT_BYTES);
        assertThat(body).endsWith("…");
        assertThat(bytes(body)).isLessThanOrEqualTo(LIMIT_BYTES);
        assertThat(bytes(body)).isGreaterThan(LIMIT_BYTES - 4);
    }

    @Test
    @DisplayName("The cut never lands in the middle of a multi-byte character")
    void halfACharacterIsMojibakeInTheAlertsTail() {
        String body = ChatFormat.markdown(alert("节点异常".repeat(1000), "摘要".repeat(500)));
        assertThat(body).doesNotContain("\uFFFD");
        assertThat(new String(body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(body);
    }

    @Test
    @DisplayName("Both shapes share one budget, so switching channel cannot change what gets refused")
    void plainAndMarkdownUseTheSameCeiling() {
        String title = "告警".repeat(1000);
        assertThat(bytes(ChatFormat.plain(alert(title, null)))).isLessThanOrEqualTo(LIMIT_BYTES);
        assertThat(bytes(ChatFormat.markdown(alert(title, null)))).isLessThanOrEqualTo(LIMIT_BYTES);
    }
}
