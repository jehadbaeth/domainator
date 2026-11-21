package com.domainator.pricing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registrars label JSON inconsistently (Internet.bs sends text/javascript), so providers read the body as text
 * and parse it here instead of relying on the response content type.
 */
final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("empty response");
        }
        return MAPPER.readTree(body);
    }
}
