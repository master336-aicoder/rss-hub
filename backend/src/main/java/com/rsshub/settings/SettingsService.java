package com.rsshub.settings;

import com.rsshub.common.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Typed access to the {@code settings} table. Every knob in here is read by production code on the request path —
 * a setting nothing reads is a lie the settings page tells the operator, so adding one means wiring its consumer
 * in the same change.
 *
 * <p>Values are read per request with no cache: a single-row primary-key lookup is cheaper than any staleness bug
 * a cache could introduce, and this is a one-operator install.
 *
 * <p>What is deliberately <strong>not</strong> here: fetch cadence and delivery timeouts. Those are dispatcher-level
 * (03 §4.1/§8.3) and live in {@code application.yml} + environment, because changing them from a web page while the
 * dispatcher is mid-cycle would need a restart-safe propagation story this project has no reason to build yet.
 */
@Service
public class SettingsService {

    public static final String DASHBOARD_ENTRIES_PER_FEED = "dashboard.entriesPerFeed";
    public static final String DASHBOARD_REFRESH_SECONDS = "dashboard.refreshSeconds";
    public static final String INBOX_PAGE_SIZE = "inbox.pageSize";
    public static final String NOTIFICATIONS_PAGE_SIZE = "notifications.pageSize";

    /**
     * The one source for both validation and the UI's hints. The dashboard's per-feed cap (50) and the inbox's
     * (200) intentionally match the controllers' {@code MAX_LIMIT}s: a setting the controller would clamp away is
     * a setting the page accepted and then ignored.
     */
    private enum IntSpec {
        DASHBOARD_ENTRIES(SettingsService.DASHBOARD_ENTRIES_PER_FEED, 1, 50, 20),
        DASHBOARD_REFRESH(SettingsService.DASHBOARD_REFRESH_SECONDS, 0, 600, 60),
        INBOX_PAGE(SettingsService.INBOX_PAGE_SIZE, 10, 200, 50),
        NOTIFICATIONS_PAGE(SettingsService.NOTIFICATIONS_PAGE_SIZE, 10, 500, 100);

        final String key;
        final int min;
        final int max;
        final int def;

        IntSpec(String key, int min, int max, int def) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.def = def;
        }

        static IntSpec byKey(String key) {
            for (IntSpec spec : values()) {
                if (spec.key.equals(key)) {
                    return spec;
                }
            }
            return null;
        }
    }

    /** What {@code GET /api/settings} hands the settings page: resolved values, defaults already applied. */
    public record View(int dashboardEntriesPerFeed, int dashboardRefreshSeconds, int inboxPageSize,
        int notificationsPageSize) {
    }

    private final SettingsRepository store;

    public SettingsService(SettingsRepository store) {
        this.store = store;
    }

    public View view() {
        return new View(intSetting(DASHBOARD_ENTRIES_PER_FEED), intSetting(DASHBOARD_REFRESH_SECONDS),
            intSetting(INBOX_PAGE_SIZE), intSetting(NOTIFICATIONS_PAGE_SIZE));
    }

    /**
     * The one accessor the request path uses. Only keys this class declares can be asked for; anything else is a
     * programming error and fails loudly rather than silently returning a default for a typo.
     */
    public int intSetting(String key) {
        IntSpec spec = IntSpec.byKey(key);
        if (spec == null) {
            throw new IllegalArgumentException("not a declared setting: " + key);
        }
        return store.find(key)
            .map(raw -> parse(key, raw, spec))
            .orElse(spec.def);
    }

    /**
     * Applies a partial patch and returns the resolved view. Unknown keys are refused rather than ignored: a
     * settings page that silently drops a typo'd key is a page that reports success about something it did not do.
     */
    public View update(Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(patch).entrySet()) {
            String key = entry.getKey();
            IntSpec spec = IntSpec.byKey(key);
            if (spec == null) {
                throw ApiException.badRequest("unknown_setting", key + " is not a setting this system knows");
            }
            Object value = entry.getValue();
            // Jackson 3 hands small JSON integers over as Integer; anything else — a string, a float, a boolean —
            // is a client that guessed the type, and the range check below cannot vouch for it.
            if (!(value instanceof Integer number)) {
                throw ApiException.badRequest("bad_setting", key + " must be a whole number");
            }
            if (number < spec.min || number > spec.max) {
                throw ApiException.badRequest("setting_out_of_range",
                    key + " must be between " + spec.min + " and " + spec.max);
            }
            store.save(key, String.valueOf(number));
        }
        return view();
    }

    /** A value written by an earlier version (or by hand) may no longer parse; the default is the honest fallback. */
    private int parse(String key, String raw, IntSpec spec) {
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(spec.min, Math.min(spec.max, value));
        } catch (NumberFormatException e) {
            return spec.def;
        }
    }
}
