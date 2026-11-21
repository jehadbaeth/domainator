package com.domainator.suggest;

import java.net.IDN;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.domainator.config.DomainatorProperties;

/**
 * Turns a search query into a list of candidate domains, roughly like registrar search pages do:
 * the exact name, the same name on other TLDs, prefix and suffix variations and "domain hacks"
 * (e.g. "acmeco" becomes "acme.co").
 */
@Component
public class SuggestionGenerator {

    public enum Kind { EXACT, OTHER_TLD, VARIATION, DOMAIN_HACK }

    public record Candidate(String domain, String label, String tld, Kind kind) {
    }

    /** The parsed search input. {@code tld} is null when the user only typed a name. */
    public record Query(String label, String hyphenated, String tld) {
    }

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private final DomainatorProperties.Suggestions config;

    public SuggestionGenerator(DomainatorProperties props) {
        this.config = props.suggestions();
    }

    /**
     * Parses free text like "Acme Corp", "acme.io" or "https://www.acme.io/about".
     *
     * @param knownTlds used to split "acme.co.uk" into label "acme" and TLD "co.uk"
     */
    public Query parse(String raw, Set<String> knownTlds) {
        if (raw == null) {
            throw new IllegalArgumentException("Empty query");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^[a-z]+://", "")
                .replaceFirst("^www\\.", "")
                .replaceFirst("[/?#].*$", "");
        String tld = null;
        int firstDot = s.indexOf('.');
        if (firstDot > 0) {
            String rest = ascii(s.substring(firstDot + 1));
            String head = s.substring(0, firstDot);
            if (rest != null && (knownTlds.contains(rest) || rest.matches("[a-z0-9-]{2,63}"))) {
                tld = rest;
                s = head;
            } else {
                s = s.replace('.', ' ');
            }
        }
        String words = s.replaceAll("[^\\p{L}\\p{N}\\s-]", " ").trim().replaceAll("\\s+", " ");
        String label = ascii(words.replace(" ", ""));
        String hyphenated = words.contains(" ") ? ascii(words.replace(" ", "-")) : null;
        if (label == null || !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("'" + raw + "' is not a usable domain name");
        }
        if (hyphenated != null && !LABEL.matcher(hyphenated).matches()) {
            hyphenated = null;
        }
        return new Query(label, hyphenated, tld);
    }

    /**
     * @param knownTlds TLDs that at least one selected registrar sells; candidates on other TLDs are dropped
     *                  (except the TLD the user typed explicitly)
     */
    public List<Candidate> generate(Query q, Set<String> knownTlds) {
        Map<String, Candidate> out = new LinkedHashMap<>();
        int max = config.maxCandidates();

        if (q.tld() != null) {
            add(out, q.label(), q.tld(), Kind.EXACT, max);
        }
        for (String tld : config.tlds()) {
            if (knownTlds.contains(tld)) {
                add(out, q.label(), tld, q.tld() == null && tld.equals(config.tlds().get(0)) ? Kind.EXACT : Kind.OTHER_TLD, max);
            }
        }
        // TLDs to use for variations: the one the user typed first, then the configured ones
        List<String> variationTlds = new ArrayList<>();
        if (q.tld() != null && knownTlds.contains(q.tld())) {
            variationTlds.add(q.tld());
        }
        config.modifierTlds().stream().filter(knownTlds::contains).filter(t -> !variationTlds.contains(t)).forEach(variationTlds::add);

        if (q.hyphenated() != null) {
            for (String tld : variationTlds) {
                add(out, q.hyphenated(), tld, Kind.VARIATION, max);
            }
        }
        if (config.domainHacks()) {
            knownTlds.stream()
                    .filter(t -> !t.contains(".") && t.length() >= 2)
                    .filter(t -> q.label().endsWith(t) && q.label().length() > t.length() + 1)
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .forEach(t -> add(out, q.label().substring(0, q.label().length() - t.length()), t, Kind.DOMAIN_HACK, max));
        }
        // interleave prefixes and suffixes so both kinds survive the cap
        List<String> variants = new ArrayList<>();
        int n = Math.max(config.prefixes().size(), config.suffixes().size());
        for (int i = 0; i < n; i++) {
            if (i < config.prefixes().size()) {
                variants.add(config.prefixes().get(i) + q.label());
            }
            if (i < config.suffixes().size()) {
                variants.add(q.label() + config.suffixes().get(i));
            }
        }
        for (String v : variants) {
            for (String tld : variationTlds) {
                add(out, v, tld, Kind.VARIATION, max);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, Candidate> out, String label, String tld, Kind kind, int max) {
        if (out.size() >= max || label.length() > 63 || !LABEL.matcher(label).matches()) {
            return;
        }
        String domain = label + "." + tld;
        out.putIfAbsent(domain, new Candidate(domain, label, tld, kind));
    }

    private static String ascii(String s) {
        try {
            return IDN.toASCII(s, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
