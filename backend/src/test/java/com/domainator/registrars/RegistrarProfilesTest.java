package com.domainator.registrars;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.domainator.config.DomainatorProperties;
import com.domainator.pricing.ProviderRegistry;
import com.domainator.pricing.RegistrarProvider;

import tools.jackson.databind.JsonNode;

class RegistrarProfilesTest {

    private static final Set<String> VALUES = Set.of("free", "paid", "limited", "none", "unknown");
    private static final List<String> FEATURES = List.of("privacy", "ssl", "emailForwarding", "mailbox", "dns",
            "dnssec", "urlForwarding", "twoFactor");

    @Test
    void datasetIsConsistent() throws Exception {
        RegistrarProfiles profiles = new RegistrarProfiles();
        // every provider id must be a provider the app knows (active or not)
        ProviderRegistry registry = new ProviderRegistry(new DomainatorProperties("EUR", null, null, null, Map.of(),
                Map.of("regru", Map.of("enabled", "true")), List.of()), RestClient.create(), Clock.systemUTC());
        Set<String> known = registry.active().stream().map(RegistrarProvider::id).collect(Collectors.toSet());
        registry.inactive().forEach(i -> known.add(i.id()));

        for (JsonNode r : profiles.all().path("registrars")) {
            String id = r.path("id").asString();
            String pid = r.path("providerId").asString(null);
            if (pid != null) {
                assertThat(known).as("providerId of " + id).contains(pid);
            }
            assertThat(Set.of("great", "good", "average", "poor", "unknown")).as("budget of " + id).contains(r.path("budget").asString());
            for (String f : FEATURES) {
                JsonNode feature = r.path("features").path(f);
                assertThat(VALUES).as(id + "." + f).contains(feature.path("value").asString());
            }
            for (String f : FEATURES) {
                JsonNode cost = r.path("features").path(f).path("cost");
                if (!cost.isMissingNode()) {
                    assertThat(Set.of("year", "month", "once")).as(id + "." + f + ".per").contains(cost.path("per").asString());
                    assertThat(cost.path("amount").decimalValue()).as(id + "." + f + ".amount").isPositive();
                }
            }
        }
        assertThat(profiles.feature("porkbun", "privacy").value()).isEqualTo("free");
        assertThat(profiles.feature("inwx", "privacy").cost().orElseThrow().amount()).isEqualByComparingTo("4.19");
        assertThat(profiles.feature("nope", "privacy").value()).isEqualTo("unknown");
        assertThat(profiles.euVat("inwx")).isTrue();
        assertThat(profiles.euVat("porkbun")).isNull();
    }
}
