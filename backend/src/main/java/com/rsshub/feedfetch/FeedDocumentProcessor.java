package com.rsshub.feedfetch;

import java.time.Instant;

/**
 * The seam 02 §3 requires: {@code feedfetch} stops at "here are the bytes the publisher sent", and parsing plus
 * storage live behind this interface ({@code feedstore} implements it, calling {@code feedparse}).
 * <p>
 * Injected as a list, so a build with no implementation still starts and the fetcher records health without
 * entries. That is not a fallback — it is the difference between "the queue is broken" and "nothing consumes
 * documents yet", and only the second one must not page anyone.
 */
public interface FeedDocumentProcessor {

    /**
     * @param feedUrl the URL that answered, so relative links inside the document can be absolutised
     * @param contentType the response's declared type; the parser sniffs the real format from the bytes
     */
    record Request(long feedId, String feedUrl, String contentType, byte[] body, Instant fetchedAt) {
    }

    /**
     * @param entryCount entries this document contains, written to {@code raw_response.entry_count}
     * @param format the format actually detected — {@code FeedFormat.stored()}, so {@code rss2}, {@code atom},
     *        {@code rdf} or {@code json}
     * @param changed true when something was inserted or a revision was recorded — drives {@code last_change_at}
     *        and therefore the "quiet since" column of the health view
     */
    record Result(int entryCount, String format, boolean changed) {
    }

    Result process(Request request);
}
