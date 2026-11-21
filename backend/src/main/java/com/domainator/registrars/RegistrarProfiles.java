package com.domainator.registrars;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What each registrar includes for free and what its paid add-ons cost (privacy, SSL, email forwarding, ...),
 * researched from official pages and kept in src/main/resources/registrars.json with a source link per claim.
 * Edit that file to correct an entry.
 */
@Component
public class RegistrarProfiles {

    public static final List<String> FEATURES = List.of("privacy", "ssl", "emailForwarding", "mailbox", "dns",
            "dnssec", "urlForwarding", "twoFactor");

    /**
     * @param per    "year", "month" or "once"
     * @param bundle features sharing a bundle id are paid once (e.g. one product covering privacy and DNSSEC)
     */
    public record Cost(BigDecimal amount, String currency, String per, String bundle) {
    }

    /** @param value free, paid, limited, none or unknown */
    public record Feature(String value, Optional<Cost> cost) {
    }

    private final JsonNode data;
    private final Map<String, JsonNode> byProvider = new HashMap<>();

    public RegistrarProfiles() throws Exception {
        try (InputStream in = new ClassPathResource("registrars.json").getInputStream()) {
            data = JsonMapper.builder().build().readTree(in);
        }
        for (JsonNode r : data.path("registrars")) {
            String pid = r.path("providerId").asString(null);
            if (pid != null) {
                byProvider.put(pid, r);
            }
        }
    }

    public JsonNode all() {
        return data;
    }

    public Feature feature(String providerId, String key) {
        JsonNode f = byProvider.containsKey(providerId) ? byProvider.get(providerId).path("features").path(key) : null;
        if (f == null || f.isMissingNode()) {
            return new Feature("unknown", Optional.empty());
        }
        JsonNode c = f.path("cost");
        Optional<Cost> cost = c.has("amount")
                ? Optional.of(new Cost(c.path("amount").decimalValue(), c.path("currency").asString("USD"),
                        c.path("per").asString("year"), c.path("bundle").asString(null)))
                : Optional.empty();
        return new Feature(f.path("value").asString("unknown"), cost);
    }

    /** Whether the registrar adds VAT for EU consumers on top of the net price; null when unknown. */
    public Boolean euVat(String providerId) {
        JsonNode r = byProvider.get(providerId);
        return r == null || !r.has("euVat") ? null : r.path("euVat").asBoolean();
    }
}
