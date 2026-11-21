package com.domainator.pricing;

import java.util.List;
import java.util.Map;

/** The settings block of one provider in application.yml, e.g. domainator.providers.namecheap.* */
public record ProviderSettings(String id, Map<String, String> values) {

    public ProviderSettings {
        values = values == null ? Map.of() : values;
    }

    public String get(String key) {
        String v = values.get(key);
        if (v == null) {
            return null;
        }
        v = v.trim();
        // tolerate KEY="value" in .env, which the properties format would otherwise keep literally
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v.isEmpty() ? null : v;
    }

    public String get(String key, String fallback) {
        String v = get(key);
        return v == null ? fallback : v;
    }

    public boolean flag(String key, boolean fallback) {
        String v = get(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    public List<String> missing(List<String> requiredKeys) {
        return requiredKeys.stream().filter(k -> get(k) == null).toList();
    }
}
