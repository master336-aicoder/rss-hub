package com.rsshub.editorial;

import com.rsshub.common.ApiException;
import com.rsshub.common.Hashing;
import com.rsshub.common.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * The {@code post} / {@code tag} / {@code category} tables (03 §3.6). Writes are content and status only — the slug
 * column has no UPDATE path anywhere in this class, which is how FR-1.1's "永久 URL 创建后禁止改" is actually
 * guaranteed.
 */
@Repository
public class PostRepository {

    /**
     * What the public outlets and the public pages may show. A {@code scheduled} post becomes visible by the clock,
     * not by a job that flips its status: the fingerprint counts rows, so the moment one enters this set the ETag
     * changes and the next poll (within the one-minute budget) sees it.
     */
    private static final String VISIBLE =
        "(p.status = 'published' or (p.status = 'scheduled' and p.published_at <= CURRENT_TIMESTAMP))";

    /** Newest first (doc/rss-guide.md:618); {@code id} breaks a tie so two posts stamped in the same second page stably. */
    private static final String NEWEST_FIRST = "order by p.published_at desc nulls last, p.id desc";

    private static final String POST_COLUMNS = """
        select p.id, p.public_id, p.slug, p.title, p.summary_md, p.body_md, p.body_html, p.status, p.lang,
               p.published_at, p.updated_at, p.canonical_url
        """;

    private static final RowMapper<PostRow> POST_ROW = PostRepository::mapPost;

    private final JdbcClient db;

    public PostRepository(JdbcClient db) {
        this.db = db;
    }

    /** A whole post, as the editor and the public page need it. */
    public record PostRow(long id, UUID publicId, String slug, String title, String summaryMd, String bodyMd,
        String bodyHtml, String status, String lang, Instant publishedAt, Instant updatedAt, String canonicalUrl) {
    }

    /** A row of the admin list: the body is deliberately absent, a list of 50 essays should not load 50 essays. */
    public record PostSummary(long id, String slug, String title, String status, String lang, Instant publishedAt,
        Instant updatedAt, long commentCount) {
    }

    /** A tag or category as a feed and a filter know it. */
    public record Term(long id, String slug, String title) {
    }

    /** One post plus the tag slugs it carries, which is everything a {@code FeedItem} is made of. */
    public record FeedRow(PostRow post, List<String> tags) {
    }

    // ---------------------------------------------------------------- writes

    /**
     * Inserts a new post. {@code slug} is set here and nowhere else, ever; {@code publicId} is left to the column
     * default in both dialects (RANDOM_UUID / gen_random_uuid), because naming a generator would make the insert
     * dialect-specific for no gain.
     */
    public long create(String slug, String title, String summaryMd, String bodyMd, String bodyHtml, String status,
        String lang, Instant publishedAt, String canonicalUrl) {
        Instant now = Instant.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.sql("insert into post (slug, title, summary_md, body_md, body_html, status, lang, published_at, "
                + "updated_at, canonical_url) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(slug, cut(title, 512), summaryMd, bodyMd, bodyHtml, status, cut(lang, 32),
                JdbcTimes.toSql(publishedAt), JdbcTimes.toSql(now), cut(canonicalUrl, 2048))
            // H2 answers getGeneratedKeys() with every defaulted column, so the identity has to be named or
            // GeneratedKeyHolder.getKey() throws on the ambiguity.
            .update(key, "id");
        Number generated = key.getKey();
        if (generated == null) {
            throw new IllegalStateException("the post insert returned no identity");
        }
        return generated.longValue();
    }

    /** Content edit. Status and slug are not arguments: neither is editable through this path. */
    public void updateContent(long id, String title, String summaryMd, String bodyMd, String bodyHtml, String lang,
        Instant publishedAt) {
        db.sql("update post set title = ?, summary_md = ?, body_md = ?, body_html = ?, lang = ?, "
                + "published_at = coalesce(?, published_at), updated_at = ? where id = ?")
            .params(cut(title, 512), summaryMd, bodyMd, bodyHtml, cut(lang, 32),
                JdbcTimes.toSql(publishedAt), JdbcTimes.toSql(Instant.now()), id)
            .update();
    }

    /**
     * The only writer of {@code status}. {@code publishedAt} is stamped by the service, not here: "become published"
     * and "what time was it published" are one decision, and the status machine owns it.
     */
    public void updateStatus(long id, String status, Instant publishedAt) {
        db.sql("update post set status = ?, published_at = ?, updated_at = ? where id = ?")
            .params(status, JdbcTimes.toSql(publishedAt), JdbcTimes.toSql(Instant.now()), id)
            .update();
    }

    /** {@code post_tag} and {@code post_category} are ON DELETE CASCADE in both dialects, so this one statement is enough. */
    public void delete(long id) {
        db.sql("delete from post where id = ?").param(id).update();
    }

    // ---------------------------------------------------------------- reads

    public Optional<PostRow> find(long id) {
        return db.sql(POST_COLUMNS + "from post p where p.id = ?").param(id).query(POST_ROW).optional();
    }

    public Optional<PostRow> findBySlug(String slug) {
        return db.sql(POST_COLUMNS + "from post p where p.slug = ?").param(slug).query(POST_ROW).optional();
    }

    public PostRow require(long id) {
        return find(id).orElseThrow(() -> ApiException.notFound("unknown_post", "No post " + id));
    }

    public List<PostSummary> listAll(String status) {
        boolean filtered = status != null && !status.isBlank();
        // Java strips a text block's trailing space before the closing delimiter, so the join space lives on the
        // following fragment instead — a "p" + "where" concatenation is exactly the bug this comment guards against.
        String sql = """
            select p.id, p.slug, p.title, p.status, p.lang, p.published_at, p.updated_at,
                   (select count(*) from post_tag pt where pt.post_id = p.id) as tag_count
            from post p"""
            + (filtered ? " where p.status = ?" : "") + " " + NEWEST_FIRST;
        return db.sql(sql)
            .params(filtered ? List.of(status) : List.of())
            .query((ResultSet rs, int rowNum) -> new PostSummary(
                rs.getLong("id"), rs.getString("slug"), rs.getString("title"), rs.getString("status"),
                rs.getString("lang"), JdbcTimes.fromSql(rs, "published_at"),
                JdbcTimes.fromSql(rs, "updated_at"), rs.getLong("tag_count")))
            .list();
    }

    /** The outlet content: visible posts, newest first, each with its tag slugs. */
    /** What the wall needs about a post: enough to name it and link to it, nothing more. */
    public record WallPost(long id, String slug, String title, Instant publishedAt) {
    }

    /**
     * The wall's "this site's own publishing" rail: the newest posts the outlets carry, in the same visibility
     * spelling the feeds use — a {@code scheduled} post whose time has come is public, and a wall that left it out
     * would understate the site it is supposed to be showing. {@code unlisted} stays out on purpose, exactly as it
     * stays out of {@code /feed.*}: it has a URL but is not on the site's radar.
     */
    public List<WallPost> recentPublished(int limit) {
        return db.sql("""
            select p.id, p.slug, p.title, p.published_at
            from post p
            where (p.status = 'published' or (p.status = 'scheduled' and p.published_at <= CURRENT_TIMESTAMP))
            order by p.published_at desc nulls last, p.id desc fetch first ? rows only
            """)
            .param(limit)
            .query((ResultSet rs, int rowNum) -> new WallPost(rs.getLong("id"), rs.getString("slug"),
                rs.getString("title"), JdbcTimes.fromSql(rs, "published_at")))
            .list();
    }

    /** How many posts the site has published, counting the scheduled ones whose time has come. */
    public long countPublished() {
        return db.sql("""
            select count(*) from post p
            where (p.status = 'published' or (p.status = 'scheduled' and p.published_at <= CURRENT_TIMESTAMP))
            """).query(Long.class).single();
    }

    public List<FeedRow> feedRows(String tagSlug, String categorySlug, int limit) {
        // The limit is applied to posts, before the terms are joined on, which is why the tags are a second query:
        // joining first would let a post with three tags eat three rows of the outlet's budget.
        List<PostRow> posts = db.sql(POST_COLUMNS + "from post p " + visibleWhere(tagSlug, categorySlug) + " "
                + NEWEST_FIRST + " fetch first ? rows only")
            .params(termArgs(tagSlug, categorySlug, limit))
            .query(POST_ROW)
            .list();
        Map<Long, List<String>> byId = new LinkedHashMap<>();
        posts.forEach(post -> byId.put(post.id(), new ArrayList<>()));
        for (TagRef ref : tagSlugsFor(byId.keySet())) {
            List<String> tags = byId.get(ref.postId());
            if (tags != null) {
                tags.add(ref.slug());
            }
        }
        return posts.stream().map(post -> new FeedRow(post, List.copyOf(byId.get(post.id())))).toList();
    }

    /** Row count and newest {@code updated_at} over the items an outlet actually carries. */
    public record FeedStats(long rowCount, Instant newestUpdated, String idDigest) {
    }

    private record IdStamp(long id, Instant updatedAt) {
    }

    /**
     * The ids and update stamps of the rows an outlet would carry, capped the same way {@link #feedRows} is. The
     * digest is assembled here rather than in SQL because {@code LISTAGG} and {@code string_agg} are different
     * functions in the two dialects, and an ETag seed must not be one of the things that diverge.
     */
    public FeedStats feedStats(String tagSlug, String categorySlug, int limit) {
        List<IdStamp> stamps = db.sql("select p.id, p.updated_at from post p " + visibleWhere(tagSlug, categorySlug)
                + " " + NEWEST_FIRST + " fetch first ? rows only")
            .params(termArgs(tagSlug, categorySlug, limit))
            .query((ResultSet rs, int rowNum) -> new IdStamp(rs.getLong("id"),
                JdbcTimes.fromSql(rs, "updated_at")))
            .list();

        long count = stamps.size();
        Instant newest = null;
        StringBuilder ids = new StringBuilder();
        for (IdStamp stamp : stamps) {
            ids.append(stamp.id()).append(',');
            if (newest == null || (stamp.updatedAt() != null && stamp.updatedAt().isAfter(newest))) {
                newest = stamp.updatedAt();
            }
        }
        return new FeedStats(count, newest, Hashing.sha256Hex(ids.toString()));
    }

    /** The shared half of the two outlet queries: visibility, then the optional tag/category slice. */
    private String visibleWhere(String tagSlug, String categorySlug) {
        StringBuilder where = new StringBuilder("where ").append(VISIBLE);
        if (tagSlug != null) {
            where.append(" and exists (select 1 from post_tag pt join tag t on t.id = pt.tag_id ")
                .append("where pt.post_id = p.id and t.slug = ?)");
        }
        if (categorySlug != null) {
            where.append(" and exists (select 1 from post_category pc join category c on c.id = pc.category_id ")
                .append("where pc.post_id = p.id and c.slug = ?)");
        }
        return where.toString();
    }

    private List<Object> termArgs(String tagSlug, String categorySlug, int limit) {
        List<Object> args = new ArrayList<>();
        if (tagSlug != null) {
            args.add(tagSlug);
        }
        if (categorySlug != null) {
            args.add(categorySlug);
        }
        args.add(limit);
        return args;
    }

    private record TagRef(long postId, String slug) {
    }

    private List<TagRef> tagSlugsFor(Collection<Long> postIds) {
        if (postIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", postIds.stream().map(id -> "?").toList());
        return db.sql("select pt.post_id, t.slug from post_tag pt join tag t on t.id = pt.tag_id "
                + "where pt.post_id in (" + placeholders + ") order by t.slug")
            .params(new ArrayList<>(postIds))
            .query((ResultSet rs, int rowNum) -> new TagRef(rs.getLong("post_id"), rs.getString("slug")))
            .list();
    }

    // ---------------------------------------------------------------- terms

    public List<Term> listTags() {
        return terms("tag");
    }

    /** The tag slugs one post carries. Two lookups for a single row; {@link #feedRows} batches the same thing. */
    public List<String> tagSlugsOf(long postId) {
        return slugsOf("post_tag", "tag", "tag_id", postId);
    }

    public List<String> categorySlugsOf(long postId) {
        return slugsOf("post_category", "category", "category_id", postId);
    }

    private List<String> slugsOf(String link, String table, String column, long postId) {
        return db.sql("select t.slug from " + link + " pt join " + table + " t on t.id = pt." + column
                + " where pt.post_id = ? order by t.slug")
            .param(postId)
            .query(String.class)
            .list();
    }

    public List<Term> listCategories() {
        return terms("category");
    }

    private List<Term> terms(String table) {
        return db.sql("select id, slug, title from " + table + " order by slug")
            .query((ResultSet rs, int rowNum) -> new Term(rs.getLong("id"), rs.getString("slug"),
                rs.getString("title")))
            .list();
    }

    public Optional<Term> findTag(String slug) {
        return term("tag", slug);
    }

    public Optional<Term> findCategory(String slug) {
        return term("category", slug);
    }

    private Optional<Term> term(String table, String slug) {
        return db.sql("select id, slug, title from " + table + " where slug = ?").param(slug)
            .query((ResultSet rs, int rowNum) -> new Term(rs.getLong("id"), rs.getString("slug"),
                rs.getString("title")))
            .optional();
    }

    /** Resolve-or-create: the author types a name, the slug is derived from it, and reusing an existing one is normal. */
    public Term resolveTag(String title) {
        return resolve("tag", title);
    }

    public Term resolveCategory(String title) {
        return resolve("category", title);
    }

    private Term resolve(String table, String title) {
        String slug = SlugPolicy.derive(null, title);
        return term(table, slug).orElseGet(() -> {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            db.sql("insert into " + table + " (slug, title) values (?, ?)").params(slug, cut(title, 512))
                .update(key, "id");
            return new Term(key.getKey().longValue(), slug, title);
        });
    }

    /**
     * Replaces the whole membership of one post. There is no incremental add/remove because the editor always sends
     * the complete set, and a diff of two complete sets is where a stale tab resurrects a tag the operator deleted.
     */
    public void replaceTags(long postId, List<Long> tagIds) {
        replace("post_tag", "tag_id", postId, tagIds);
    }

    public void replaceCategories(long postId, List<Long> categoryIds) {
        replace("post_category", "category_id", postId, categoryIds);
    }

    private void replace(String table, String column, long postId, List<Long> ids) {
        db.sql("delete from " + table + " where post_id = ?").param(postId).update();
        for (Long id : ids) {
            db.sql("insert into " + table + " (post_id, " + column + ") values (?, ?)").params(postId, id).update();
        }
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static PostRow mapPost(ResultSet rs, int rowNum) throws SQLException {
        return new PostRow(rs.getLong("id"), rs.getObject("public_id", UUID.class), rs.getString("slug"),
            rs.getString("title"), rs.getString("summary_md"), rs.getString("body_md"), rs.getString("body_html"),
            rs.getString("status"), rs.getString("lang"), JdbcTimes.fromSql(rs, "published_at"),
            JdbcTimes.fromSql(rs, "updated_at"), rs.getString("canonical_url"));
    }
}
