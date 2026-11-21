package com.domainator.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.IDN;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClient;

/**
 * OVHcloud exposes its full order catalog without authentication:
 * GET https://eu.api.ovh.com/1.0/order/catalog/public/domain?ovhSubsidiary=DE
 * <p>
 * The response is about 30 MB. Prices are integers in units of 10^-8 of the currency and exclude VAT
 * (the VAT amount is given separately in "tax").
 */
public class OvhProvider implements RegistrarProvider {

    private static final BigDecimal UNIT = new BigDecimal("100000000");

    private final RestClient http;
    private final String subsidiary;
    private final boolean includeVat;
    private final Clock clock;

    public OvhProvider(RestClient http, String subsidiary, boolean includeVat, Clock clock) {
        this.http = http;
        this.subsidiary = subsidiary;
        this.includeVat = includeVat;
        this.clock = clock;
    }

    record Catalog(Locale locale, List<Plan> plans) {
    }

    record Locale(String currencyCode, String subsidiary, Integer taxRate) {
    }

    record Plan(String planCode, List<Pricing> pricings) {
    }

    record Pricing(int phase, String mode, int interval, String intervalUnit, long price, long tax,
                   List<Promotion> promotions) {
    }

    record Promotion(String description, String context, OffsetDateTime startDate, OffsetDateTime endDate,
                     Amount total) {
    }

    record Amount(long value, long tax) {
    }

    @Override
    public String id() {
        return "ovh";
    }

    @Override
    public String displayName() {
        return "OVHcloud (" + subsidiary + ")";
    }

    @Override
    public String taxNote() {
        return includeVat ? "incl. VAT" : "excl. VAT";
    }

    @Override
    public String buyUrl(String domain) {
        return null; // OVH's search runs in JavaScript; link the TLD page instead
    }

    @Override
    public String tldUrl(String tld) {
        return "https://www.ovhcloud.com/en/domains/tld/" + tld + "/";
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        Catalog catalog = http.get()
                .uri("https://eu.api.ovh.com/1.0/order/catalog/public/domain?ovhSubsidiary={s}", subsidiary)
                .retrieve()
                .body(Catalog.class);
        return parse(catalog, includeVat, OffsetDateTime.now(clock));
    }

    static Map<String, TldPrice> parse(Catalog catalog, boolean includeVat, OffsetDateTime now) {
        if (catalog == null || catalog.plans() == null || catalog.locale() == null) {
            throw new IllegalStateException("OVH catalog response was empty");
        }
        String currency = catalog.locale().currencyCode();
        Map<String, TldPrice> out = new HashMap<>();
        for (Plan plan : catalog.plans()) {
            if (plan.pricings() == null) {
                continue;
            }
            Pricing create = null;
            Pricing renew = null;
            for (Pricing p : plan.pricings()) {
                // "create-premium" rows carry a 999999 sentinel price, only the default mode is the standard price.
                if (!"create-default".equals(p.mode()) || !isOneYear(p)) {
                    continue;
                }
                if (p.phase() == 0 && create == null) {
                    create = p;
                } else if (p.phase() == 1 && renew == null) {
                    renew = p;
                }
            }
            if (create == null || renew == null) {
                continue;
            }
            BigDecimal registration = amount(create.price(), create.tax(), includeVat);
            String promo = null;
            if (create.promotions() != null) {
                for (Promotion promotion : create.promotions()) {
                    // "discover" promotions are limited quantity or new customer only, so they are ignored.
                    if (!"standard".equals(promotion.context()) || promotion.total() == null || !isActive(promotion, now)) {
                        continue;
                    }
                    BigDecimal promoted = amount(promotion.total().value(), promotion.total().tax(), includeVat);
                    if (promoted.compareTo(registration) < 0) {
                        registration = promoted;
                        promo = promotion.description() + (promotion.endDate() != null
                                ? " (until " + promotion.endDate().toLocalDate() + ")" : "");
                    }
                }
            }
            String tld = IDN.toASCII(plan.planCode().toLowerCase());
            out.put(tld, new TldPrice(tld, registration, amount(renew.price(), renew.tax(), includeVat), currency, promo));
        }
        return out;
    }

    private static boolean isOneYear(Pricing p) {
        return ("month".equals(p.intervalUnit()) && p.interval() == 12) || ("year".equals(p.intervalUnit()) && p.interval() == 1);
    }

    private static boolean isActive(Promotion p, OffsetDateTime now) {
        return (p.startDate() == null || !now.isBefore(p.startDate())) && (p.endDate() == null || now.isBefore(p.endDate()));
    }

    private static BigDecimal amount(long value, long tax, boolean includeVat) {
        long total = includeVat ? value + tax : value;
        return BigDecimal.valueOf(total).divide(UNIT, 2, RoundingMode.HALF_UP);
    }
}
