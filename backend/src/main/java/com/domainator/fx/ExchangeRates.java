package com.domainator.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Daily EUR reference rates from the European Central Bank (free, no key). */
@Component
public class ExchangeRates {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRates.class);
    private static final String ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    private static final Pattern RATE = Pattern.compile("currency='([A-Z]{3})'\\s+rate='([0-9.]+)'");
    private static final Pattern TIME = Pattern.compile("time='(\\d{4}-\\d{2}-\\d{2})'");

    private final RestClient http;
    private volatile Map<String, BigDecimal> eurRates = Map.of("EUR", BigDecimal.ONE);
    private volatile LocalDate date;

    public ExchangeRates(RestClient http) {
        this.http = http;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "PT6H")
    public void refresh() {
        try {
            String xml = http.get().uri(ECB_URL).retrieve().body(String.class);
            Map<String, BigDecimal> rates = parse(xml);
            if (rates.size() > 1) {
                eurRates = rates;
                Matcher t = TIME.matcher(xml);
                date = t.find() ? LocalDate.parse(t.group(1)) : null;
                log.info("Loaded {} ECB exchange rates for {}", rates.size(), date);
            }
        } catch (Exception e) {
            log.warn("Could not load ECB exchange rates: {}", e.toString());
        }
    }

    static Map<String, BigDecimal> parse(String xml) {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("EUR", BigDecimal.ONE);
        Matcher m = RATE.matcher(xml == null ? "" : xml);
        while (m.find()) {
            rates.put(m.group(1), new BigDecimal(m.group(2)));
        }
        return rates;
    }

    /** Converts an amount, or returns empty if one of the currencies is unknown. */
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to) {
        if (from.equalsIgnoreCase(to)) {
            return Optional.of(amount);
        }
        BigDecimal fromRate = eurRates.get(from.toUpperCase());
        BigDecimal toRate = eurRates.get(to.toUpperCase());
        if (fromRate == null || toRate == null) {
            return Optional.empty();
        }
        return Optional.of(amount.divide(fromRate, 10, RoundingMode.HALF_UP).multiply(toRate));
    }

    public LocalDate date() {
        return date;
    }

    public java.util.Set<String> currencies() {
        return new java.util.TreeSet<>(eurRates.keySet());
    }
}
