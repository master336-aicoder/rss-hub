package com.rsshub.notify;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * The JSON this package sends. Written by a mapper rather than by hand, because a notification's title is somebody
 * else's headline and can contain quotes, backslashes and control characters.
 */
final class JsonBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonBody() {
    }

    static String write(Map<String, Object> value) {
        return MAPPER.writeValueAsString(value);
    }

    /** Ordered key/value pairs, so a nested body reads the way the vendor's example does. */
    static Map<String, Object> pairs(Object... keyValue) {
        if (keyValue.length % 2 != 0) {
            throw new IllegalArgumentException("pairs() wants key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            map.put((String) keyValue[i], keyValue[i + 1]);
        }
        return map;
    }
}
