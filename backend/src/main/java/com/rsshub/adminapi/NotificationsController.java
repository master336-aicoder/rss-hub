package com.rsshub.adminapi;

import com.rsshub.common.ApiException;
import com.rsshub.notify.DeliveryService;
import com.rsshub.rules.NotificationRepository;
import com.rsshub.rules.NotificationRepository.Row;
import com.rsshub.settings.SettingsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 03 §10's {@code /api/notifications}: the delivery log with its failure reasons, and the manual replay 03 §8.3
 * promises after the ladder runs out.
 *
 * <p>This is read-only apart from the replay. A notification is a fact about something that already happened, so
 * there is deliberately no edit, and the only state change offered is "send the one you already decided on again".
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationsController {

    /** The states the log can hold; anything else is a typo in a query string, and saying so beats an empty list. */
    private static final Set<String> STATES = Set.of(NotificationRepository.PENDING, NotificationRepository.SENT,
        NotificationRepository.SUPPRESSED, NotificationRepository.FAILED, NotificationRepository.SKIPPED_QUIET);

    private static final int MAX_LIMIT = 500;

    /** One row plus the rule's name: {@code rule_id} is null after a deletion, and a blank column would read as a bug. */
    public record Entry(long id, Long ruleId, String ruleName, Long entryId, String channel, String state,
        Integer httpStatus, String error, int attempts, Instant createdAt) {
    }

    private final NotificationRepository notifications;
    private final DeliveryService delivery;
    private final SettingsService settings;

    public NotificationsController(NotificationRepository notifications, DeliveryService delivery,
        SettingsService settings) {
        this.notifications = notifications;
        this.delivery = delivery;
        this.settings = settings;
    }

    @GetMapping
    public List<Entry> list(@RequestParam(required = false) String state,
        @RequestParam(required = false) Long ruleId, @RequestParam(required = false) Integer limit) {
        if (state != null && !state.isBlank() && !STATES.contains(state)) {
            throw ApiException.badRequest("unknown_state", "No notification state " + state);
        }
        // Absent limit means the settings page's number, the same deal the inbox list offers.
        int requested = limit == null ? settings.intSetting(SettingsService.NOTIFICATIONS_PAGE_SIZE) : limit;
        int bounded = Math.min(Math.max(requested, 1), MAX_LIMIT);
        Map<Long, String> names = notifications.ruleNamesById();
        return notifications.list(state == null || state.isBlank() ? null : state, ruleId, bounded).stream()
            .map(row -> entry(row, names))
            .toList();
    }

    /** Re-sends the body that was frozen at hit time; see {@link DeliveryService#replay} for what is replayable. */
    @PostMapping("/{id}/replay")
    public Entry replay(@PathVariable long id) {
        return entry(delivery.replay(id), notifications.ruleNamesById());
    }

    private static Entry entry(Row row, Map<Long, String> names) {
        Long ruleId = row.ruleId();
        String name = ruleId == null ? NotificationRepository.GONE_RULE_NAME : names.get(ruleId);
        return new Entry(row.id(), ruleId, name == null ? NotificationRepository.GONE_RULE_NAME : name,
            row.entryId(), row.channel(), row.state(), row.httpStatus(), row.error(), row.attempts(),
            row.createdAt());
    }
}
