package com.domainator.pricing;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Namecheap XML API.
 * <ul>
 *   <li>Price list: namecheap.users.getPricing, once with ActionName=REGISTER and once with RENEW.</li>
 *   <li>Premium prices: namecheap.domains.check (max 50 domains per call).</li>
 * </ul>
 * Needs API access enabled on the account (Profile, Tools, Namecheap API Access) and the caller's public IPv4
 * address whitelisted. Namecheap only grants API access to accounts with 20+ domains, $50+ balance or $50+ spent
 * in the last two years. Rate limit is around 20 to 50 calls per minute.
 * <p>
 * Response notes (from real captures, the official docs are incomplete): element names are lower case in
 * practice ("register", "com"), "Price" is the per year price for the given Duration, and "AdditionalCost" is
 * the ICANN fee that is added in the cart. We add it so the totals match what you pay.
 */
public class NamecheapProvider implements RegistrarProvider {

    private final RestClient http;
    private final String baseUrl;
    private final String apiUser;
    private final String apiKey;
    private final String userName;
    private final String clientIp;

    public NamecheapProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.baseUrl = s.flag("sandbox", false)
                ? "https://api.sandbox.namecheap.com/xml.response"
                : "https://api.namecheap.com/xml.response";
        this.apiUser = s.get("api-user");
        this.apiKey = s.get("api-key");
        this.userName = s.get("username", apiUser);
        this.clientIp = s.get("client-ip");
    }

    @Override
    public String id() {
        return "namecheap";
    }

    @Override
    public String displayName() {
        return "Namecheap";
    }

    @Override
    public String taxNote() {
        return "USD, incl. ICANN fee";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.namecheap.com/domains/registration/results/?domain=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() throws Exception {
        Map<String, Yearly> register = parsePricing(call(Map.of(
                "Command", "namecheap.users.getPricing", "ProductType", "DOMAIN", "ActionName", "REGISTER")), "register");
        Map<String, Yearly> renew = parsePricing(call(Map.of(
                "Command", "namecheap.users.getPricing", "ProductType", "DOMAIN", "ActionName", "RENEW")), "renew");
        Map<String, TldPrice> out = new HashMap<>();
        register.forEach((tld, reg) -> {
            Yearly ren = renew.get(tld);
            if (ren != null) {
                out.put(tld, new TldPrice(tld, reg.price(), ren.price(), reg.currency(),
                        reg.price().compareTo(reg.regular()) < 0 ? "first year discount (list " + reg.regular() + ")" : null));
            }
        });
        return out;
    }

    record Yearly(BigDecimal price, BigDecimal regular, String currency) {
    }

    /** Extracts the 1 year price (plus ICANN fee) per TLD from a getPricing response for one category. */
    static Map<String, Yearly> parsePricing(Document doc, String category) {
        Map<String, Yearly> out = new HashMap<>();
        for (Element cat : Xml.elements(doc, "ProductCategory")) {
            if (!category.equalsIgnoreCase(cat.getAttribute("Name"))) {
                continue;
            }
            for (Element product : Xml.elements(cat, "Product")) {
                String tld = product.getAttribute("Name").toLowerCase(Locale.ROOT);
                for (Element price : Xml.elements(product, "Price")) {
                    if (!"1".equals(price.getAttribute("Duration")) || !"YEAR".equalsIgnoreCase(price.getAttribute("DurationType"))) {
                        continue;
                    }
                    BigDecimal p = Money.parse(price.getAttribute("Price"));
                    if (p == null) {
                        continue;
                    }
                    BigDecimal fee = orZero(Money.parse(price.getAttribute("AdditionalCost")));
                    BigDecimal regular = Money.parse(price.getAttribute("RegularPrice"));
                    BigDecimal regularFee = Money.parse(price.getAttribute("RegularAdditionalCost"));
                    String currency = price.getAttribute("Currency").isBlank() ? "USD" : price.getAttribute("Currency");
                    out.put(tld, new Yearly(p.add(fee),
                            regular == null ? p.add(fee) : regular.add(regularFee == null ? fee : regularFee), currency));
                }
            }
        }
        return out;
    }

    @Override
    public boolean quotesDomains() {
        return true;
    }

    /** Only premium names are returned; normal names keep using the price list. */
    @Override
    public Map<String, DomainQuote> quoteDomains(List<String> domains) throws Exception {
        Document doc = call(Map.of("Command", "namecheap.domains.check", "DomainList", String.join(",", domains)));
        return parseCheck(doc);
    }

    static Map<String, DomainQuote> parseCheck(Document doc) {
        Map<String, DomainQuote> out = new HashMap<>();
        for (Element r : Xml.elements(doc, "DomainCheckResult")) {
            if (!"true".equalsIgnoreCase(r.getAttribute("IsPremiumName"))) {
                continue;
            }
            BigDecimal reg = Money.parse(r.getAttribute("PremiumRegistrationPrice"));
            BigDecimal ren = Money.parse(r.getAttribute("PremiumRenewalPrice"));
            BigDecimal fee = orZero(Money.parse(r.getAttribute("IcannFee")));
            if (reg != null && ren != null) {
                out.put(r.getAttribute("Domain").toLowerCase(Locale.ROOT), new DomainQuote(reg.add(fee), ren.add(fee), "USD",
                        "true".equalsIgnoreCase(r.getAttribute("Available")), true));
            }
        }
        return out;
    }

    private Document call(Map<String, String> params) throws Exception {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("ApiUser", apiUser)
                .queryParam("ApiKey", apiKey)
                .queryParam("UserName", userName)
                .queryParam("ClientIp", clientIp);
        params.forEach(uri::queryParam);
        String body = http.get().uri(uri.encode().build().toUri()).retrieve().body(String.class);
        Document doc = Xml.parse(body);
        Element root = doc.getDocumentElement();
        if (!"OK".equalsIgnoreCase(root.getAttribute("Status"))) {
            List<Element> errors = Xml.elements(doc, "Error");
            String msg = errors.isEmpty() ? "unknown error"
                    : errors.get(0).getTextContent().trim() + " [" + errors.get(0).getAttribute("Number") + "]";
            throw new IllegalStateException("Namecheap: " + msg);
        }
        return doc;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
