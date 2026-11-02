package com.rsshub.rules;

import com.rsshub.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * One entry of {@code rule.actions} (03 §8.3): where a hit goes, and which secret it is signed with.
 *
 * <p>The secret itself is never stored here, only its name. 03 §9.1's rule for this install is that credentials
 * come from the environment, so {@code secretRef} is a key into {@code rsshub.notify.secrets} — a rule row is
 * readable by the UI, gets exported with the database, and is not a place a webhook signing key belongs.
 */
public record Action(String channel, String target, String secretRef) {

    /**
     * The channels an adapter exists for in {@code notify}. {@code email} is deliberately absent: 03 §8.3 lists it,
     * but this build has no SMTP client on the classpath and adding one is a dependency decision, not a feature
     * flag — see 03 §9's deviation note.
     */
    public static final Set<String> CHANNELS = Set.of("webhook", "dingtalk", "wecom", "markOnly");

    private static final Set<String> NEEDS_TARGET = Set.of("webhook", "dingtalk", "wecom");

    public static List<Action> parseAll(String actionsJson) {
        JsonNode array = RuleJson.array(actionsJson, "actions");
        if (array.size() == 0) {
            throw ApiException.badRequest("actions_required", "A rule needs at least one action");
        }
        List<Action> actions = new ArrayList<>();
        array.valueStream().forEach(node -> {
            if (!node.isObject()) {
                throw ApiException.badRequest("invalid_actions", "Every action must be an object");
            }
            actions.add(parse(node));
        });
        return List.copyOf(actions);
    }

    private static Action parse(JsonNode node) {
        String channel = RuleJson.text(node, "channel");
        if (channel == null || !CHANNELS.contains(channel)) {
            throw ApiException.badRequest("unknown_channel",
                "channel must be one of " + String.join(", ", new java.util.TreeSet<>(CHANNELS)));
        }
        String target = RuleJson.text(node, "target");
        if (NEEDS_TARGET.contains(channel)) {
            if (target == null) {
                throw ApiException.badRequest("target_required", channel + " needs a target URL");
            }
            if (!target.startsWith("https://") && !target.startsWith("http://")) {
                throw ApiException.badRequest("target_scheme", "A channel target must be an http(s) URL");
            }
        }
        return new Action(channel, target, RuleJson.text(node, "secretRef"));
    }
}
