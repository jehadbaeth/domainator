package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

/**
 * Name.com Core API (https://docs.name.com), HTTP Basic auth with username and API token.
 * <ul>
 *   <li>Price list: GET /core/v1/tldpricing?duration=1&amp;perPage=1000&amp;page=N. Your account price in USD.</li>
 *   <li>Premium: POST /core/v1/domains:checkAvailability, max 50 domains. Only premium names are returned.
 *       Name.com notes the renewal price for premiums is more exact from getPricing; we use the check value.</li>
 * </ul>
 * Rate limit: 20 requests per second, 3000 per hour.
 */
public class NameComProvider implements RegistrarProvider {

    private final RestClient http;
    private final String base;
    private final String username;
    private final String token;

    public NameComProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        boolean sandbox = s.flag("sandbox", false);
        this.base = sandbox ? "https://api.dev.name.com" : "https://api.name.com";
        String user = s.get("username");
        this.username = sandbox && user != null && !user.endsWith("-test") ? user + "-test" : user;
        this.token = s.get("api-token");
    }

    @Override
    public String id() {
        return "namecom";
    }

    @Override
    public String displayName() {
        return "Name.com";
    }

    @Override
    public String taxNote() {
        return "USD";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.name.com/domain/search/" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        Map<String, TldPrice> out = new HashMap<>();
        int page = 1;
        for (int guard = 0; guard < 50; guard++) {
            JsonNode body = Json.parse(http.get().uri(base + "/core/v1/tldpricing?duration=1&perPage=1000&page={p}", page)
                    .headers(h -> h.setBasicAuth(username, token))
                    .retrieve().body(String.class));
            out.putAll(parsePriceList(body));
            JsonNode next = body.path("nextPage");
            if (next.isNull() || next.isMissingNode() || next.asInt(0) <= page) {
                break;
            }
            page = next.asInt();
        }
        return out;
    }

    static Map<String, TldPrice> parsePriceList(JsonNode body) {
        Map<String, TldPrice> out = new HashMap<>();
        for (JsonNode e : body.path("pricing")) {
            if (e.path("duration").asInt(1) != 1) {
                continue;
            }
            BigDecimal reg = Money.parse(e.get("registrationPrice"));
            BigDecimal ren = Money.parse(e.get("renewalPrice"));
            String tld = e.path("tld").asString("").toLowerCase(Locale.ROOT);
            if (reg != null && ren != null && !tld.isEmpty()) {
                String key = IDN.toASCII(tld);
                BigDecimal retail = Money.parse(e.get("registrationRetailPrice"));
                out.put(key, new TldPrice(key, reg, ren, "USD",
                        retail != null && reg.compareTo(retail) < 0 ? "account price (retail " + retail + ")" : null));
            }
        }
        return out;
    }

    @Override
    public boolean quotesDomains() {
        return true;
    }

    @Override
    public Map<String, DomainQuote> quoteDomains(List<String> domains) {
        JsonNode body = Json.parse(http.post().uri(base + "/core/v1/domains:checkAvailability")
                .headers(h -> h.setBasicAuth(username, token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("domainNames", domains, "purchaseType", "registration"))
                .retrieve().body(String.class));
        return parseCheck(body);
    }

    static Map<String, DomainQuote> parseCheck(JsonNode body) {
        Map<String, DomainQuote> out = new HashMap<>();
        for (JsonNode r : body.path("results")) {
            if (!r.path("premium").asBoolean(false)) {
                continue;
            }
            BigDecimal reg = Money.parse(r.get("purchasePrice"));
            BigDecimal ren = Money.parse(r.get("renewalPrice"));
            if (reg != null && ren != null) {
                out.put(r.path("domainName").asString().toLowerCase(Locale.ROOT),
                        new DomainQuote(reg, ren, "USD", r.path("purchasable").asBoolean(false), true));
            }
        }
        return out;
    }
}
