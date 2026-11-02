package com.rsshub.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsshub.common.ApiException;
import com.rsshub.feedstore.EntryNewEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 03 §8.1's matching semantics, and the two guards the design writes down rather than inherits: the haystack
 * truncation and the nested-quantifier blacklist. Rule evaluation is pure, so none of this needs a database — which
 * is also the point of keeping it out of {@code notify}.
 */
class RuleMatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-18T02:00:00Z");

    private final RuleMatcher matcher = new RuleMatcher();

    private static EntryNewEvent entry(String title, String summary) {
        return entry(title, summary, null);
    }

    private static EntryNewEvent entry(String title, String summary, String contentHash) {
        return new EntryNewEvent(1, 7, "Loopback Weekly", "生产", "http://example.test/1", title, summary, contentHash,
            List.of("Release", "P1"), NOW, NOW);
    }

    private static MatchSpec spec(String json) {
        return MatchSpec.parse(json);
    }

    private RuleMatcher.Outcome match(String json, EntryNewEvent event) {
        return matcher.match(spec(json), event, NOW);
    }

    @Test
    void includeAnyHitsCaseInsensitivelyAndReportsTheWords() {
        RuleMatcher.Outcome outcome = match("""
            {"includeAny":["OOM","panic"]}""", entry("Pod restarting: out of memory", "the OOM killer fired"));

        assertThat(outcome.matched()).isTrue();
        // Only "OOM" is present, and the preview has to show that rather than the whole keyword list.
        assertThat(outcome.keywords()).containsExactly("OOM");
    }

    @Test
    void caseSensitiveNarrowsTheSameTerm() {
        assertThat(match("""
            {"includeAny":["oom"],"caseSensitive":true}""", entry("OOM at midnight", null)).matched()).isFalse();
        assertThat(match("""
            {"includeAny":["OOM"],"caseSensitive":true}""", entry("OOM at midnight", null)).matched()).isTrue();
    }

    @Test
    void wholeWordRefusesAtermEmbeddedInAword() {
        String json = """
            {"includeAny":["OOM"],"wholeWord":true}""";

        assertThat(match(json, entry("OOMKilled", "the container was OOMKilled")).matched()).isFalse();
        assertThat(match(json, entry("an OOM happened", null)).matched()).isTrue();
    }

    @Test
    void wholeWordWorksForChineseWhereWordBoundaryWouldNeverFire() {
        // \b is a no-op between CJK characters, so the look-around is on letters and digits only.
        assertThat(match("""
            {"includeAny":["发布"],"wholeWord":true}""", entry("应用发布了新版本", null)).matched()).isFalse();
        assertThat(match("""
            {"includeAny":["发布"],"wholeWord":true}""", entry("今天 发布 了一个版本", null)).matched()).isTrue();
    }

    @Test
    void includeAllDemandsEveryTerm() {
        RuleMatcher.Outcome outcome = match("""
            {"includeAll":["deploy","failed"]}""", entry("deploy failed on prod", "deploy"));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.keywords()).containsExactly("deploy", "failed");
        assertThat(match("""
            {"includeAll":["deploy","rolled back"]}""", entry("deploy failed on prod", null)).matched()).isFalse();
    }

    @Test
    void excludeAnyVetoesAnOtherwisePositiveHit() {
        assertThat(match("""
            {"includeAny":["OOM"],"excludeAny":["演练"]}""", entry("OOM 演练记录", null)).matched()).isFalse();
        assertThat(match("""
            {"includeAny":["OOM"],"excludeAny":["演练"]}""", entry("真实 OOM 故障", null)).matched()).isTrue();
    }

    @Test
    void conditionsCombineAsAndAcrossGroupsAndOrInsideOne() {
        String json = """
            {"includeAny":["OOM","panic"],"groups":["生产"],"excludeAny":["演练"]}""";

        assertThat(match(json, entry("OOM", null)).matched()).isTrue();
        assertThat(match(json, entry("panic in prod", null)).matched()).isTrue();
        assertThat(match("""
            {"includeAny":["OOM"],"groups":["测试"]}""", entry("OOM", null)).matched()).isFalse();
    }

    @Test
    void tagsAndFeedIdsScopeTheRule() {
        assertThat(match("""
            {"tags":["p1"]}""", entry("anything", null)).matched()).isTrue();
        assertThat(match("""
            {"tags":["p0"]}""", entry("anything", null)).matched()).isFalse();
        assertThat(match("""
            {"feedIds":[7]}""", entry("anything", null)).matched()).isTrue();
        assertThat(match("""
            {"feedIds":[8]}""", entry("anything", null)).matched()).isFalse();
    }

    @Test
    void windowFallsBackToFetchTimeWhenTheSourceSentNoDate() {
        EntryNewEvent undated = new EntryNewEvent(1, 7, "Loopback Weekly", "生产", null, "OOM", null, null,
            List.of(), null, NOW.minusSeconds(120));

        assertThat(match("""
            {"includeAny":["OOM"],"windowMinutes":60}""", undated).matched()).isTrue();
        assertThat(match("""
            {"includeAny":["OOM"],"windowMinutes":1}""", undated).matched()).isFalse();

        EntryNewEvent ancient = new EntryNewEvent(1, 7, "Loopback Weekly", "生产", null, "OOM", null, null,
            List.of(), NOW.minusSeconds(3 * 3600), NOW);
        assertThat(match("""
            {"includeAny":["OOM"],"windowMinutes":60}""", ancient).matched()).isFalse();
    }

    @Test
    void regexIsAnchoredPerLineAndSkipsAPatternThatDoesNotCompile() {
        assertThat(match("""
            {"regex":["^ERROR:"]}""", entry("ERROR: disk full", "a second ERROR: inside")).matched()).isTrue();
        // parse() validates on save; a hand-edited row must cost an alert, not the ingest round.
        assertThat(match("""
            {"regex":["("]}""", entry("whatever", null)).matched()).isFalse();
    }

    @Test
    void haystackIsTruncatedSoALongBodyCannotBeMatchedPastTheCap() {
        String tail = "OOM";
        String summary = "x".repeat(RuleMatcher.HAYSTACK_MAX_CHARS) + tail;

        assertThat(match("""
            {"includeAny":["OOM"]}""", entry("title", summary)).matched()).isFalse();
    }

    @Test
    void validateRefusesARuleWithNothingToMatchOn() {
        assertThatThrownBy(() -> spec("{}").validate())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("at least one condition");

        // An exclude or a window alone would alert on the whole inbox.
        assertThatThrownBy(() -> spec("""
            {"excludeAny":["演练"]}""").validate())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("cannot start one");
        assertThatThrownBy(() -> spec("""
            {"windowMinutes":30}""").validate())
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("cannot start one");
    }

    @Test
    void validateRefusesAPatternThatCanHangAMatch() {
        assertThatThrownBy(() -> spec("{\"regex\":[\"(a+)+\"]}").validate())
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("unsafe_regex"));
        assertThatThrownBy(() -> spec("{\"regex\":[\"(\"]}").validate())
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_regex"));
        // 03 §8.1's length ceiling, not a compile failure: this pattern is valid and simply too long to trust.
        assertThatThrownBy(() -> spec("{\"regex\":[\"" + "a".repeat(201) + "\"]}").validate())
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("regex_too_long"));
        // The flat form of the same intent is allowed: the blacklist is about nesting, not about quantifiers.
        spec("{\"regex\":[\"a+\"]}").validate();
    }

    @Test
    void validateRefusesAnOverlongKeyword() {
        String longTerm = "啊".repeat(201);

        assertThatThrownBy(() -> spec("{\"includeAny\":[\"" + longTerm + "\"]}").validate())
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("keyword_too_long"));
    }

    @Test
    void suppressionKeyChangesGranularityWithTheConfiguredKey() {
        EntryNewEvent first = entry("Deploy v2 shipped", null, "hash-a");
        EntryNewEvent sameTitle = new EntryNewEvent(2, 9, "Other", "生产", null, "deploy  V2  shipped", null, "hash-b",
            List.of(), NOW, NOW);

        assertThat(Suppression.parse("{}").dedupeKey(3, first))
            .isEqualTo("rule:3:perFeed:feed:7");
        assertThat(Suppression.parse("""
            {"key":"perContentHash"}""").dedupeKey(3, first))
            .isEqualTo("rule:3:perContentHash:hash:hash-a");
        // Normalised: case and internal whitespace folded, so the two titles are one key.
        assertThat(Suppression.parse("""
            {"key":"perTitleNorm"}""").dedupeKey(3, first))
            .isEqualTo(Suppression.parse("""
                {"key":"perTitleNorm"}""").dedupeKey(3, sameTitle));
        assertThat(Suppression.parse("""
            {"hours":0}""").enabled()).isFalse();
        assertThat(Suppression.parse("""
            {"hours":6}""").enabled()).isTrue();
        assertThatThrownBy(() -> Suppression.parse("""
            {"key":"perAuthor"}""")).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("unknown_suppression_key"));
    }

    @Test
    void quietHoursWrapPastMidnightInTheRulesOwnZone() {
        QuietHours overnight = QuietHours.parse("""
            {"from":"23:00","to":"07:00","tz":"Asia/Shanghai"}""");

        // 23:30 in Shanghai is 15:30Z; the instant, not the server's clock, decides.
        Instant inWindow = LocalDateTime.of(2026, 10, 17, 15, 30).toInstant(ZoneOffset.UTC);
        Instant outside = LocalDateTime.of(2026, 10, 17, 12, 0).toInstant(ZoneOffset.UTC);
        assertThat(overnight.quietAt(inWindow)).isTrue();
        assertThat(overnight.quietAt(outside)).isFalse();

        QuietHours sameDay = QuietHours.parse("""
            {"from":"09:00","to":"12:00","tz":"UTC"}""");
        // Inclusive at from, exclusive at to: 60 minutes of quiet is 60 minutes.
        assertThat(sameDay.quietAt(Instant.parse("2026-10-17T09:00:00Z"))).isTrue();
        assertThat(sameDay.quietAt(Instant.parse("2026-10-17T11:59:59Z"))).isTrue();
        assertThat(sameDay.quietAt(Instant.parse("2026-10-17T12:00:00Z"))).isFalse();
        // An empty window is a mistake, not a rule that never sends.
        assertThat(QuietHours.parse("""
            {"from":"09:00","to":"09:00","tz":"UTC"}""").quietAt(NOW)).isFalse();
    }

    @Test
    void quietHoursRefuseAnIncompleteOrBadWindow() {
        assertThatThrownBy(() -> QuietHours.parse("""
            {"from":"23:00","to":"07:00"}""")).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("quiet_hours_incomplete"));
        assertThatThrownBy(() -> QuietHours.parse("""
            {"from":"23h","to":"07:00","tz":"UTC"}""")).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("invalid_from"));
        assertThatThrownBy(() -> QuietHours.parse("""
            {"from":"23:00","to":"07:00","tz":"Mars/Olympus"}""")).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo("unknown_tz"));
    }
}
