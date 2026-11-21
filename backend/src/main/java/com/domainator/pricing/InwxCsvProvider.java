package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.client.RestClient;

/**
 * INWX (Germany) publishes its full price list as a semicolon separated CSV without authentication:
 * https://www.inwx.de/en/domain/pricelist/vat/1/file/csv (inwx.com for USD, inwx.ch for CHF).
 * <p>
 * The prices are net (the download page says "all prices quoted are net prices"). Rows with a registration or
 * renewal period other than 1 year are skipped because the file does not say whether the price is per year or
 * for the whole period.
 */
public class InwxCsvProvider implements RegistrarProvider {

    private final RestClient http;
    private final String site;
    private final String currency;

    public InwxCsvProvider(RestClient http, ProviderSettings s) {
        this.http = http;
        this.site = s.get("site", "de").toLowerCase(Locale.ROOT);
        this.currency = switch (site) {
            case "com" -> "USD";
            case "ch" -> "CHF";
            default -> "EUR";
        };
    }

    @Override
    public String id() {
        return "inwx";
    }

    @Override
    public String displayName() {
        return "INWX";
    }

    @Override
    public String taxNote() {
        return "excl. VAT";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://www.inwx." + site + "/en/domain/check#search=" + domain;
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        String csv = http.get().uri("https://www.inwx." + site + "/en/domain/pricelist/vat/1/file/csv")
                .retrieve().body(String.class);
        return parse(csv, currency);
    }

    static Map<String, TldPrice> parse(String csv, String currency) {
        Map<String, TldPrice> out = new HashMap<>();
        if (csv == null) {
            throw new IllegalStateException("INWX price list was empty");
        }
        boolean header = true;
        for (String line : csv.split("\\r?\\n")) {
            if (header) {
                header = false;
                if (!line.contains("TLD")) {
                    throw new IllegalStateException("INWX price list has an unexpected header: " + line);
                }
                continue;
            }
            String[] c = line.split(";", -1);
            if (c.length < 7) {
                continue;
            }
            String tld = unquote(c[0]).toLowerCase(Locale.ROOT);
            // columns: TLD; New registration; period; Provider switch; period; Renewal; period; ...
            if (tld.isEmpty() || !"1".equals(unquote(c[2])) || !"1".equals(unquote(c[6]))) {
                continue;
            }
            BigDecimal reg = Money.parse(unquote(c[1]));
            BigDecimal ren = Money.parse(unquote(c[5]));
            if (reg != null && ren != null) {
                String key = IDN.toASCII(tld);
                out.put(key, new TldPrice(key, reg, ren, currency, null));
            }
        }
        return out;
    }

    private static String unquote(String s) {
        return s.replace("\"", "").trim();
    }
}
