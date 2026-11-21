package com.domainator.pricing;

import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The official list of TLDs in the DNS root zone. Used to drop names registrars sell that are not real
 * internet TLDs (Porkbun's feed contains ~270 Handshake names such as ".80proof").
 */
@Component
public class RootZone {

    private static final Logger log = LoggerFactory.getLogger(RootZone.class);
    private static final String URL = "https://data.iana.org/TLD/tlds-alpha-by-domain.txt";

    private final RestClient http;
    private volatile Set<String> tlds = Set.of();

    public RootZone(RestClient http) {
        this.http = http;
    }

    /** Fetches the list once. Called by {@link PriceCatalog} before each price refresh. */
    public void refresh() {
        try {
            String body = http.get().uri(URL).retrieve().body(String.class);
            Set<String> set = body == null ? Set.of() : body.lines()
                    .filter(l -> !l.startsWith("#") && !l.isBlank())
                    .map(l -> l.trim().toLowerCase())
                    .collect(Collectors.toUnmodifiableSet());
            if (!set.isEmpty()) {
                tlds = set;
                log.info("Loaded {} root zone TLDs", set.size());
            }
        } catch (Exception e) {
            // keep the previous list; if there is none we fail open until the next refresh
            log.warn("Could not load the IANA TLD list ({}), {}", e.toString(),
                    tlds.isEmpty() ? "not filtering TLDs" : "keeping the previous list");
        }
    }

    /** True if the last label of {@code tld} (e.g. "uk" for "co.uk") is in the root zone, or if no list is loaded. */
    public boolean contains(String tld) {
        Set<String> set = tlds;
        return set.isEmpty() || set.contains(tld.substring(tld.lastIndexOf('.') + 1));
    }
}
