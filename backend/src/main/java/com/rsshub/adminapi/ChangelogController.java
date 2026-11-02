package com.rsshub.adminapi;

import com.rsshub.changelog.ChangeLogRepository;
import com.rsshub.changelog.ChangeLogRepository.Change;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 03 §10's {@code /api/changelog}: the same history the public outlet publishes, for the editor's panel.
 *
 * <p>Read-only by design. A change row is a fact about something that already happened, and the outlet that ships it
 * is generated from these rows — offering an edit here would let the operator rewrite history that a subscriber has
 * already been handed a fingerprint of.
 */
@RestController
@RequestMapping("/api/changelog")
public class ChangelogController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    /** {@code url} is the permalink as it stood at the time, so a deleted post still shows where it lived. */
    public record Entry(long id, UUID publicId, long postId, String slug, String title, String url, String action,
        String fromStatus, String toStatus, Instant changedAt) {
    }

    private final ChangeLogRepository changes;

    public ChangelogController(ChangeLogRepository changes) {
        this.changes = changes;
    }

    @GetMapping
    public List<Entry> list(@RequestParam(required = false) Long postId,
        @RequestParam(required = false) Integer limit) {
        int bounded = Math.min(Math.max(limit == null ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        List<Change> rows = postId == null ? changes.list(bounded) : changes.listOfPost(postId, bounded);
        return rows.stream().map(ChangelogController::entry).toList();
    }

    private static Entry entry(Change row) {
        return new Entry(row.id(), row.publicId(), row.postId(), row.slug(), row.title(), row.url(), row.action(),
            row.fromStatus(), row.toStatus(), row.changedAt());
    }
}
