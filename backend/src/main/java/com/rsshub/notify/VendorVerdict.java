package com.rsshub.notify;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two Chinese robots answer {@code 200 OK} and put the verdict in the body, so a status code alone cannot tell
 * a delivered alert from a throttled one. WeCom documents {@code errcode} 45009 as "接口调用超过限制" behind a 200, and
 * DingTalk's custom-robot API answers in the same shape.
 *
 * <p>Absent {@code errcode} counts as accepted: a generic receiver that returns 200 with its own JSON is not this
 * adapter's business, and refusing it would retry a message that arrived.
 */
final class VendorVerdict {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VendorVerdict() {
    }

    /** Turns "200 but the vendor said no" back into a refusal the retry ladder can see. */
    static Result of(Result posted) {
        if (!posted.delivered()) {
            return posted;
        }
        return accepted(posted.body()) ? posted : Result.refused(posted.httpStatus(), posted.body());
    }

    static boolean accepted(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            return true;
        }
        JsonNode code = node.path("errcode");
        return !code.isNumber() || code.asLong() == 0;
    }
}
