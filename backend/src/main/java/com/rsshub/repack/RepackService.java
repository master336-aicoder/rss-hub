package com.rsshub.repack;

import com.rsshub.common.ApiException;
import com.rsshub.feedrender.FeedKey;
import com.rsshub.feedrender.FeedScope;
import com.rsshub.feedrender.PublishProperties;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's side of a forwarding collection (F7): create it, decide which subscribed feeds it ships, and flip
 * each one's full-content flag.
 *
 * <p>FR-7.2 is the reason this class exists separately from the repository. Summaries are not a setting the
 * collection inherits — they are the default every member starts in, and turning full text on is one explicit act
 * per site, written down in {@code repack_source_audit} so the operator can say afterwards when they took on the
 * copyright obligation.
 */
@Service
public class RepackService {

    /** Same rule as an event stream slug: it goes into a public URL path, so it stays ASCII and short. */
    private static final String SLUG = "[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?";

    private static final int AUDIT_VIEW_LIMIT = 50;

    public record Summary(long id, String slug, String title, String description, int memberCount, String publicUrl) {
    }

    public record Detail(long id, String slug, String title, String description, String publicUrl,
        List<MemberView> members) {
    }

    /**
     * @param fullContentChangedAt {@code repack_source.updated_at}, which only moves when the flag is written — the
     *                             management screen shows it so a member's obligation has a date next to it
     */
    public record MemberView(long feedId, String title, String canonicalUrl, String groupName, String state,
        boolean fullContent, Instant fullContentChangedAt, long entryCount) {
    }

    public record AuditView(long id, long feedId, String action, boolean fullContent, Instant createdAt) {
    }

    private final RepackRepository repacks;
    private final PublishProperties publish;

    public RepackService(RepackRepository repacks, PublishProperties publish) {
        this.repacks = repacks;
        this.publish = publish;
    }

    public List<Summary> list() {
        return repacks.list().stream()
            .map(repack -> new Summary(repack.id(), repack.slug(), repack.title(), repack.description(),
                repacks.countMembers(repack.id()), publicUrl(repack.slug())))
            .toList();
    }

    public Detail get(String slug) {
        RepackRepository.Repack repack = repacks.require(slug);
        return new Detail(repack.id(), repack.slug(), repack.title(), repack.description(), publicUrl(repack.slug()),
            repacks.members(repack.id()).stream().map(RepackService::member).toList());
    }

    public List<AuditView> audit(String slug) {
        RepackRepository.Repack repack = repacks.require(slug);
        return repacks.audit(repack.id(), AUDIT_VIEW_LIMIT).stream()
            .map(row -> new AuditView(row.id(), row.feedId(), row.action(), row.fullContent(), row.createdAt()))
            .toList();
    }

    @Transactional
    public Detail create(String slug, String title, String description) {
        String normalized = normalizeSlug(slug);
        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("title_required", "A repack feed needs a title");
        }
        if (repacks.find(normalized).isPresent()) {
            throw ApiException.conflict("repack_exists", "A repack feed named " + normalized + " already exists");
        }
        repacks.insertRepack(normalized, title.trim(), blankToNull(description));
        return get(normalized);
    }

    /** A PATCH sends the fields it means to change; an absent one keeps its stored value. */
    @Transactional
    public Detail update(String slug, String title, String description) {
        RepackRepository.Repack repack = repacks.require(slug);
        String newTitle = title == null || title.isBlank() ? repack.title() : title.trim();
        String newDescription = description == null ? repack.description() : blankToNull(description);
        repacks.updateRepack(repack.id(), newTitle, newDescription);
        return get(slug);
    }

    @Transactional
    public void delete(String slug) {
        RepackRepository.Repack repack = repacks.require(slug);
        repacks.deleteRepack(repack.id());
    }

    @Transactional
    public Detail addFeed(String slug, long feedId) {
        RepackRepository.Repack repack = repacks.require(slug);
        if (!repacks.feedExists(feedId)) {
            throw ApiException.notFound("feed_not_found", "No subscription with id " + feedId);
        }
        if (repacks.findMember(repack.id(), feedId).isPresent()) {
            throw ApiException.conflict("already_repacked", "Feed " + feedId + " is already in " + slug);
        }
        repacks.insertMember(repack.id(), feedId);
        repacks.insertAudit(repack.id(), feedId, "add", false);
        return get(slug);
    }

    @Transactional
    public Detail removeFeed(String slug, long feedId) {
        RepackRepository.Repack repack = repacks.require(slug);
        RepackRepository.Member member = repacks.findMember(repack.id(), feedId)
            .orElseThrow(() -> ApiException.notFound("not_a_member", "Feed " + feedId + " is not in " + slug));
        repacks.deleteMember(member.id());
        repacks.insertAudit(repack.id(), feedId, "remove", member.fullContent());
        return get(slug);
    }

    /**
     * The flag flip FR-7.2 asks for. An unchanged value writes no audit row: a log of every click would bury the
     * handful of lines that actually changed what ships.
     */
    @Transactional
    public Detail setFullContent(String slug, long feedId, boolean fullContent) {
        RepackRepository.Repack repack = repacks.require(slug);
        RepackRepository.Member member = repacks.findMember(repack.id(), feedId)
            .orElseThrow(() -> ApiException.notFound("not_a_member", "Feed " + feedId + " is not in " + slug));
        if (member.fullContent() != fullContent) {
            repacks.setFullContent(member.id(), fullContent);
            repacks.insertAudit(repack.id(), feedId, fullContent ? "full_content_on" : "full_content_off", fullContent);
        }
        return get(slug);
    }

    /** The address 03 §6.2 fixes for this scope, in the format the management screen links first. */
    public String publicUrl(String slug) {
        return publish.absolute(new FeedKey(FeedScope.REPACK, slug).selfPath() + ".rss");
    }

    private static MemberView member(RepackRepository.Member row) {
        return new MemberView(row.feedId(), row.feedTitle(), row.canonicalUrl(), row.groupName(), row.feedState(),
            row.fullContent(), row.fullContentChangedAt(), row.entryCount());
    }

    private static String normalizeSlug(String value) {
        String candidate = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!candidate.matches(SLUG)) {
            throw ApiException.badRequest("invalid_slug",
                "A repack slug is lowercase letters, digits and single hyphens, 1-64 characters");
        }
        return candidate;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
