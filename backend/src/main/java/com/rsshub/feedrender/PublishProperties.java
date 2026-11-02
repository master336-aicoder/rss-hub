package com.rsshub.feedrender;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the public outlets say about themselves (03 §6, FR-1.4).
 *
 * <p>{@code baseUrl} is configured rather than derived from the request because a guid is a promise that outlives the
 * deployment: if the site is ever reached through a different Host header, a feed generated from that header hands
 * every subscriber a second permanent ID for the same article, which is the one mistake a feed cannot take back
 * (doc/rss-quickref.md:133).
 *
 * @param baseUrl      absolute origin of every public URL, no trailing slash
 * @param siteTitle    channel title; required by all three formats
 * @param description  channel description; required by RSS 2.0, so a blank value here is a startup-visible mistake
 * @param language     BCP-47 tag for {@code <language>}/{@code xml:lang}/JSON {@code language}
 * @param imageUrl     channel logo; {@code <image>}/{@code logo}/{@code icon}
 * @param itemLimit    how many recent entries an outlet carries (03 §6.3 rule 5, doc/rss-guide.md:620)
 * @param ttlMinutes   {@code <ttl>} — the interval the outlet asks subscribers to poll at
 * @param jsonMaxBytes soft cap for one JSON Feed document; over it, bodies degrade to summary + link (rule 7)
 * @param generator    the {@code <generator>} string; fixed, so golden files do not move between releases
 * @param displayZone  calendar the public HTML pages show a date in; feeds always carry the real offset
 */
@ConfigurationProperties(prefix = "rsshub.publish")
public record PublishProperties(String baseUrl, String siteTitle, String description, String language,
    String imageUrl, int itemLimit, int ttlMinutes, int jsonMaxBytes, String generator, String displayZone) {

    public PublishProperties {
        baseUrl = trimTrailingSlash(baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl);
        siteTitle = siteTitle == null || siteTitle.isBlank() ? "Notes" : siteTitle;
        description = description == null || description.isBlank() ? null : description;
        language = language == null || language.isBlank() ? "zh-CN" : language;
        itemLimit = itemLimit <= 0 ? 50 : itemLimit;
        ttlMinutes = ttlMinutes <= 0 ? 60 : ttlMinutes;
        jsonMaxBytes = jsonMaxBytes <= 0 ? 100_000 : jsonMaxBytes;
        generator = generator == null || generator.isBlank() ? "rss-hub" : generator;
        displayZone = displayZone == null || displayZone.isBlank() ? "Asia/Shanghai" : displayZone;
    }

    /**
     * Absolute URL for a path we generated: {@code /tag/observability.atom} becomes
     * {@code https://example.org/tag/observability.atom}. An already-absolute value passes through, because item ids
     * come from sources that may publish elsewhere (F7's repack feed).
     */
    public String absolute(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
