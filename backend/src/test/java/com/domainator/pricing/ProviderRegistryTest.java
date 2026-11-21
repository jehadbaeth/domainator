package com.domainator.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.domainator.config.DomainatorProperties;

class ProviderRegistryTest {

    private static ProviderRegistry registry(Map<String, Map<String, String>> providers) {
        DomainatorProperties props = new DomainatorProperties("EUR", null, null, null, Map.of(), providers, List.of());
        return new ProviderRegistry(props, RestClient.create(), Clock.systemUTC());
    }

    @Test
    void keyedProvidersNeedAllCredentials() {
        ProviderRegistry r = registry(Map.of(
                "namecheap", Map.of("api-user", "me", "api-key", "", "client-ip", "1.2.3.4"),
                "namesilo", Map.of("api-key", "\"abc\""),
                "regru", Map.of()));
        List<String> active = r.active().stream().map(RegistrarProvider::id).toList();
        assertThat(active).contains("porkbun", "ovh", "inwx", "vercel", "namesilo").doesNotContain("namecheap", "regru");
        assertThat(r.inactive()).anySatisfy(i -> {
            assertThat(i.id()).isEqualTo("namecheap");
            assertThat(i.reason()).isEqualTo("set NAMECHEAP_API_KEY in .env");
        });
        assertThat(r.inactive()).anySatisfy(i -> assertThat(i.id()).isEqualTo("regru"));
    }

    @Test
    void explicitDisableWins() {
        ProviderRegistry r = registry(Map.of("porkbun", Map.of("enabled", "false")));
        assertThat(r.active().stream().map(RegistrarProvider::id)).doesNotContain("porkbun");
    }
}
