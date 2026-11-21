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
 * Spaceship API (https://docs.spaceship.dev), headers X-API-Key and X-API-Secret, scope domains:read.
 * <ul>
 *   <li>Price list: GET /v1/tlds/pricing?take=25&amp;skip=N (25 per page).</li>
 *   <li>Live quotes incl. premium: POST /v1/domains/pricing/query, max 20 domains.</li>
 * </ul>
 * Amounts are strings in USD and include the ICANN fee. "finalPrice" includes promotions, "regularPrice" does not.
 * Rate limit: 25 pricing requests per 5 seconds.
 */
public class SpaceshipProvider implements RegistrarProvider {

    private static final String BASE = "https://spaceship.dev/api";

    private final RestClient http;
    private final String key;
    private final String secret;

    public SpaceshipProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.key = s.get("api-key");
        this.secret = s.get("api-secret");
    }

    @Override
    public String id() {
        return "spaceship";
    }

    @Override
    public String displayName() {
        return "Spaceship";
    }

    @Override
    public String taxNote() {
        return "USD, incl. ICANN fee";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.spaceship.com/domain-search/?query=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() throws Exception {
        Map<String, TldPrice> out = new HashMap<>();
        int skip = 0;
        while (true) {
            JsonNode page = Json.parse(http.get().uri(BASE + "/v1/tlds/pricing?take=25&skip={skip}", skip)
                    .header("X-API-Key", key).header("X-API-Secret", secret)
                    .retrieve().body(String.class));
            JsonNode items = page.path("items");
            for (JsonNode item : items) {
                TldPrice p = parseTld(item);
                if (p != null) {
                    out.put(p.tld(), p);
                }
            }
            skip += items.size();
            if (items.isEmpty() || skip >= page.path("total").asInt(0)) {
                return out;
            }
            Thread.sleep(250); // stay under 25 requests per 5 s
        }
    }

    static TldPrice parseTld(JsonNode item) {
        String tld = item.path("tld").asString("").toLowerCase(Locale.ROOT);
        Price reg = oneYear(item.path("prices").path("register"));
        Price ren = oneYear(item.path("prices").path("renew"));
        if (tld.isEmpty() || reg == null || ren == null) {
            return null;
        }
        String key = IDN.toASCII(tld);
        String promo = reg.regular() != null && reg.amount().compareTo(reg.regular()) < 0
                ? "first year promotion (regular " + reg.regular() + ")" : null;
        return new TldPrice(key, reg.amount(), ren.amount(), reg.currency(), promo);
    }

    @Override
    public boolean quotesDomains() {
        return true;
    }

    @Override
    public int maxDomainsPerQuote() {
        return 20;
    }

    @Override
    public Map<String, DomainQuote> quoteDomains(List<String> domains) {
        JsonNode body = Json.parse(http.post().uri(BASE + "/v1/domains/pricing/query")
                .header("X-API-Key", key).header("X-API-Secret", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("domains", domains))
                .retrieve().body(String.class));
        return parseDomains(body);
    }

    static Map<String, DomainQuote> parseDomains(JsonNode body) {
        Map<String, DomainQuote> out = new HashMap<>();
        for (JsonNode d : body.path("domains")) {
            Price reg = oneYear(d.path("prices").path("register"));
            Price ren = oneYear(d.path("prices").path("renew"));
            if (reg != null && ren != null) {
                out.put(d.path("domain").asString().toLowerCase(Locale.ROOT), new DomainQuote(reg.amount(), ren.amount(),
                        reg.currency(), null, "premium".equalsIgnoreCase(d.path("priceClass").asString(""))));
            }
        }
        return out;
    }

    private record Price(BigDecimal amount, BigDecimal regular, String currency) {
    }

    private static Price oneYear(JsonNode list) {
        for (JsonNode p : list) {
            JsonNode period = p.path("period");
            if (period.path("value").asInt(0) == 1 && "y".equals(period.path("unit").asString(""))) {
                JsonNode money = p.path("priceInDefaultCurrency");
                BigDecimal amount = Money.parse(money.path("finalPrice").get("amount"));
                if (amount != null) {
                    return new Price(amount, Money.parse(money.path("regularPrice").get("amount")),
                            money.path("finalPrice").path("currency").asString("USD"));
                }
            }
        }
        return null;
    }
}
