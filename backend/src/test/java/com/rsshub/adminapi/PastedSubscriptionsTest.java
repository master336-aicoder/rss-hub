package com.rsshub.adminapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.rsshub.adminapi.PastedSubscriptions.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The split rules from {@link PastedSubscriptions}, tested as pasted text rather than as a parser: an operator copies
 * a chat log or a table column, and the split either matches what they meant or the batch subscribes the wrong address
 * with a name that has nothing to do with it. Every case here is a shape someone actually pastes.
 */
class PastedSubscriptionsTest {

    @Test
    void oneAddressPerLineKeepsItsLineNumber() {
        List<Candidate> items = PastedSubscriptions.split("""
            https://a.example/feed.xml

            https://b.example/feed.xml
            https://c.example/feed.xml
            """);

        assertThat(items).extracting(Candidate::address)
            .containsExactly("https://a.example/feed.xml", "https://b.example/feed.xml", "https://c.example/feed.xml");
        assertThat(items).extracting(Candidate::lineNo).containsExactly(1, 3, 4);
        assertThat(items).allSatisfy(item -> assertThat(item.label()).isNull());
    }

    @Test
    void everySeparatorWidthAndASpaceAllBreakTheListApart() {
        List<Candidate> items = PastedSubscriptions.split(
            "https://a.example/rss，https://b.example/rss;https://c.example/rss、https://d.example/rss；"
                + "e.example/f,,g.example/h");

        assertThat(items).extracting(Candidate::address).containsExactly(
            "https://a.example/rss", "https://b.example/rss", "https://c.example/rss", "https://d.example/rss",
            "e.example/f", "g.example/h");
    }

    @Test
    void aColonInFrontOfAnAddressNamesItInsteadOfSplittingIt() {
        List<Candidate> items = PastedSubscriptions.split("阮一峰:https://r.example/blog/atom.xml");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).label()).isEqualTo("阮一峰");
        assertThat(items.get(0).address()).isEqualTo("https://r.example/blog/atom.xml");
    }

    @Test
    void aFullWidthColonAndASpaceAfterItReadTheSameWay() {
        List<Candidate> items = PastedSubscriptions.split("王垚： http://w.example/rss.xml");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).label()).isEqualTo("王垚");
        assertThat(items.get(0).address()).isEqualTo("http://w.example/rss.xml");
    }

    @Test
    void aNameStandingInFrontOfTheAddressOnTheSameLineIsItsNameToo() {
        // The shape a chat log and a two-column table paste as: name, space, address. Treating the name as another
        // subscription would create https://笔记, which is the mistake this rule exists to prevent.
        List<Candidate> items = PastedSubscriptions.split("笔记 https://n.example/feed.xml");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).label()).isEqualTo("笔记");
        assertThat(items.get(0).address()).isEqualTo("https://n.example/feed.xml");
    }

    @Test
    void theSchemeColonAndAPortColonNeverSplitAnAddress() {
        List<Candidate> items = PastedSubscriptions.split(
            "https://p.example:8443/feed?a=1&b=2\r\nhttp://q.example/rss");

        assertThat(items).extracting(Candidate::address)
            .containsExactly("https://p.example:8443/feed?a=1&b=2", "http://q.example/rss");
        assertThat(items).allSatisfy(item -> assertThat(item.label()).isNull());
    }

    @Test
    void aColonBetweenTwoAddressesIsASecondAddressNotAName() {
        List<Candidate> items = PastedSubscriptions.split("a.example/rss:b.example/rss");

        assertThat(items).extracting(Candidate::address).containsExactly("a.example/rss", "b.example/rss");
        assertThat(items).allSatisfy(item -> assertThat(item.label()).isNull());
    }

    @Test
    void twoNamesOnOneLineBecomeOneName() {
        List<Candidate> items = PastedSubscriptions.split("旧竹新苑 博客 https://j.example/feed.xml");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).label()).isEqualTo("旧竹新苑 博客");
    }

    @Test
    void aLineWithNothingAddressLikeAboutItIsStillOneCandidate() {
        // Dropped silently, a paste of twenty lines that lands eighteen is indistinguishable from a paste of
        // eighteen; the plan table has to show the line the splitter could not read.
        List<Candidate> items = PastedSubscriptions.split("见备注");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).address()).isEqualTo("见备注");
    }

    @Test
    void aNameAfterAnAddressStaysOnTheLineAsItsOwnCandidate() {
        List<Candidate> items = PastedSubscriptions.split("https://x.example/feed 备注");

        assertThat(items).extracting(Candidate::address).containsExactly("https://x.example/feed", "备注");
        assertThat(items.get(0).label()).isNull();
    }

    @Test
    void aBareDomainWithNoPathIsOfferedAsAnAddressRatherThanSwallowedAsAName() {
        // A single-domain paste is common enough, and the splitter has no business deciding that `example.com` is a
        // name with nothing to name: an unclaimed name goes back in as a candidate, so normalisation gets the last word.
        List<Candidate> items = PastedSubscriptions.split("example.com");

        assertThat(items).extracting(Candidate::address).containsExactly("example.com");
        assertThat(items.get(0).label()).isNull();
    }

    @Test
    void nothingAtAllIsNoCandidatesRatherThanAnError() {
        assertThat(PastedSubscriptions.split("   \n\t  \n")).isEmpty();
        assertThat(PastedSubscriptions.split(null)).isEmpty();
    }
}
