package com.domainator.pricing;

import java.math.BigDecimal;

import tools.jackson.databind.JsonNode;

/** Lenient price parsing: registrars send numbers, strings, "n/a", "--" and thousands separators. */
public final class Money {

    private Money() {
    }

    public static BigDecimal parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace(",", "").replace("\"", "").trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return parse(node.asString());
    }
}
