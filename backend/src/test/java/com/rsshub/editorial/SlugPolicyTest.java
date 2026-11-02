package com.rsshub.editorial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rsshub.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SlugPolicyTest {

    @Nested
    @DisplayName("derivation: what a title becomes when the author did not type a slug")
    class Derive {

        @Test
        void spacesPunctuationAndCaseAllGo() {
            assertThat(SlugPolicy.derive(null, "Hello, World! (Again)")).isEqualTo("hello-world-again");
        }

        @Test
        void accentsLoseTheMarkButKeepTheLetter() {
            // NFKD splits é into e plus a combining mark; dropping only the mark is what keeps the word readable.
            assertThat(SlugPolicy.derive(null, "Café Öl")).isEqualTo("cafe-ol");
        }

        @Test
        void nonAsciiWordsAreKeptRatherThanStrippedToNothing() {
            // "2026 年计划" must not degrade to "2026" — the ideographs are legal in a path segment and they are the
            // only meaningful words in the title.
            assertThat(SlugPolicy.derive(null, "2026 年计划")).isEqualTo("2026-年计划");
        }

        @Test
        void fullWidthAsciiCollapses() {
            assertThat(SlugPolicy.derive(null, "２０２６")).isEqualTo("2026");
        }

        @Test
        void aTitleThatIsOnlyPunctuationIsRefused() {
            assertThatThrownBy(() -> SlugPolicy.derive(null, "??? !!!"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_slug"));
        }

        @Test
        void anOverlongTitleIsCutOnAHyphenBoundary() {
            String slug = SlugPolicy.derive(null, "word ".repeat(60));
            assertThat(slug).hasSizeLessThanOrEqualTo(200).doesNotEndWith("-");
        }

        @Test
        void anExplicitSlugGoesThroughTheSameNormalisation() {
            assertThat(SlugPolicy.derive("My Post", "ignored")).isEqualTo("my-post");
        }
    }

    @Nested
    @DisplayName("isLegal: the gate an editor-supplied slug has to pass before it becomes permanent")
    class Legal {

        @Test
        void pathAndQuerySyntaxIsForbidden() {
            for (String candidate : List.of("a/b", "a\\b", "a?b", "a#b", "a%20b", "a&b", "a b", "a+b")) {
                assertThat(SlugPolicy.isLegal(candidate))
                    .as("%s would change which resource the URL addresses", candidate).isFalse();
            }
        }

        @Test
        void emptyEdgeDashesAndOverlongAreIllegal() {
            assertThat(SlugPolicy.isLegal(null)).isFalse();
            assertThat(SlugPolicy.isLegal("")).isFalse();
            assertThat(SlugPolicy.isLegal("-lead")).isFalse();
            assertThat(SlugPolicy.isLegal("trail-")).isFalse();
            assertThat(SlugPolicy.isLegal("a".repeat(201))).isFalse();
        }

        @Test
        void everythingTheDeriverCanProducePasses() {
            // The round trip is the real contract: if derive could emit something isLegal rejects, creation would
            // store a slug its own validator calls broken.
            for (String title : List.of("Hello, World!", "2026 年计划", "Café Öl", "a.b_c", "x".repeat(400))) {
                assertThat(SlugPolicy.isLegal(SlugPolicy.derive(null, title))).as("derived from %s", title).isTrue();
            }
        }
    }

    @Test
    void comparisonIsCaseInsensitiveSoTwoSpellingsOfOneSlugCannotBothExist() {
        assertThat(SlugPolicy.key("My-Post")).isEqualTo(SlugPolicy.key("my-post"));
    }
}
