package com.domainator.pricing.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.IDN;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.TldPrice;

/**
 * Helpers for registrars whose prices are only published on a public web page. These read server-rendered HTML
 * (or JSON embedded in the page) exactly as a visitor's browser receives it, with the app's own User-Agent,
 * at most once per refresh. They are fragile by nature: a redesign of the page breaks them, which
 * {@link com.domainator.pricing.RegistrarProvider#minExpectedTlds()} turns into a visible ERROR.
 */
public final class PricePage {

    private static final Logger log = LoggerFactory.getLogger(PricePage.class);

    /** Pause between requests when a registrar has one page per TLD. */
    public static final long PER_PAGE_PAUSE_MS = 1500;

    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?");

    private PricePage() {
    }

    public static Document fetch(RestClient http, String url) {
        String html = http.get().uri(url).header("Accept", "text/html").retrieve().body(String.class);
        if (html == null || html.isBlank()) {
            throw new IllegalStateException("empty page: " + url);
        }
        return Jsoup.parse(html, url);
    }

    /**
     * Parses European and US formatted prices: "4,17", "1.234,56", "1 248,75", "14,90 €*", "$14.89/year",
     * "C$22.70 / year". Returns null if no number is found.
     */
    public static BigDecimal number(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace(' ', ' ').replaceAll("[^0-9,.]", " ").trim();
        // first number: with thousands groups ("2,060.25", "1.234,56") or plain ("1905.00", "4,17")
        java.util.regex.Matcher m = NUMBER.matcher(s);
        if (!m.find()) {
            return null;
        }
        String n = m.group();
        int comma = n.lastIndexOf(',');
        int dot = n.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            // the later separator is the decimal one
            n = comma > dot ? n.replace(".", "").replace(',', '.') : n.replace(",", "");
        } else if (comma >= 0) {
            // "4,17" decimal, but "1,234" (three digits after) is a thousands separator
            n = n.length() - comma - 1 == 3 ? n.replace(",", "") : n.replace(',', '.');
        } else if (dot >= 0 && n.length() - dot - 1 == 3 && n.indexOf('.') == dot && n.length() > 4) {
            n = n.replace(".", ""); // "1.234" thousands in German notation
        }
        try {
            return new BigDecimal(n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Removes VAT from a gross price, e.g. net(11.90, 19) = 10.00. */
    public static BigDecimal net(BigDecimal gross, int vatPercent) {
        if (gross == null || vatPercent <= 0) {
            return gross;
        }
        return gross.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100L + vatPercent), 2, RoundingMode.HALF_UP);
    }

    public static String tld(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "").replaceAll("\\s.*$", "");
        if (t.isEmpty() || !t.matches("[\\p{L}\\p{N}.-]+")) {
            return null;
        }
        try {
            return IDN.toASCII(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Fetches one page per TLD for the given TLDs, sequentially with a pause, and keeps what parses.
     * Used for registrars without a single price list page.
     */
    public static void perTld(List<String> tlds, Map<String, TldPrice> out, Function<String, TldPrice> fetchOne)
            throws InterruptedException {
        for (String tld : tlds) {
            try {
                TldPrice p = fetchOne.apply(tld);
                if (p != null) {
                    out.put(tld, p);
                }
            } catch (RuntimeException e) {
                log.debug("price page for .{} failed: {}", tld, e.toString());
            }
            Thread.sleep(PER_PAGE_PAUSE_MS);
        }
    }
}
