package com.domainator.registrars;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What each registrar includes for free (privacy, SSL, email forwarding, ...), researched from official pages and
 * kept in src/main/resources/registrars.json with a source link per claim. Edit that file to correct an entry.
 */
@Component
public class RegistrarProfiles {

    /** @param oneTime paid once at registration instead of every year (Namespro) */
    public record PrivacyCost(BigDecimal amount, String currency, boolean oneTime) {
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

    /** "free", "paid", "limited" (redaction only / conditional), "none" or "unknown". */
    public String privacy(String providerId) {
        JsonNode r = byProvider.get(providerId);
        return r == null ? "unknown" : r.path("features").path("privacy").path("value").asString("unknown");
    }

    public Optional<PrivacyCost> privacyCost(String providerId) {
        JsonNode c = byProvider.getOrDefault(providerId, JsonMapper.builder().build().createObjectNode()).path("privacyCost");
        if (c.isMissingNode() || !c.has("amount")) {
            return Optional.empty();
        }
        return Optional.of(new PrivacyCost(c.path("amount").decimalValue(), c.path("currency").asString("USD"),
                c.path("oneTime").asBoolean(false)));
    }
}
