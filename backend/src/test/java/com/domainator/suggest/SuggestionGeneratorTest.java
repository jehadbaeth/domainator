package com.domainator.suggest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.domainator.config.DomainatorProperties;
import com.domainator.suggest.SuggestionGenerator.Candidate;
import com.domainator.suggest.SuggestionGenerator.Kind;

class SuggestionGeneratorTest {

    private static final Set<String> KNOWN = Set.of("com", "io", "dev", "co", "co.uk", "ly");

    private final SuggestionGenerator gen = new SuggestionGenerator(new DomainatorProperties("EUR", null, null, null,
            new DomainatorProperties.Suggestions(List.of("com", "io", "dev", "unsold"), List.of("com"),
                    List.of("get"), List.of("hq"), 50, true),
            null, Map.of(), null, null));

    @Test
    void parsesUrlsAndTlds() {
        assertThat(gen.parse("https://www.Acme.co.uk/about", KNOWN)).isEqualTo(new SuggestionGenerator.Query("acme", null, "co.uk"));
        assertThat(gen.parse("Acme Corp", KNOWN)).isEqualTo(new SuggestionGenerator.Query("acmecorp", "acme-corp", null));
        assertThatThrownBy(() -> gen.parse("!!!", KNOWN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatesTldsVariationsAndHacks() {
        List<Candidate> c = gen.generate(gen.parse("acmeco", KNOWN), KNOWN);
        List<String> domains = c.stream().map(Candidate::domain).toList();
        assertThat(c.get(0)).isEqualTo(new Candidate("acmeco.com", "acmeco", "com", Kind.EXACT));
        assertThat(domains).contains("acmeco.io", "acmeco.dev", "acme.co", "getacmeco.com", "acmecohq.com");
        // configured TLDs are always checked, even if no selected registrar sells them
        assertThat(domains).contains("acmeco.unsold");
    }

    @Test
    void variationsCanBeSwitchedOff() {
        List<Candidate> c = gen.generate(gen.parse("acmeco", KNOWN), KNOWN, List.of("de", "com"), false);
        assertThat(c.stream().map(Candidate::domain)).containsExactly("acmeco.de", "acmeco.com");
        assertThat(c.get(0).kind()).isEqualTo(Kind.EXACT);
    }

    @Test
    void typedTldComesFirst() {
        List<Candidate> c = gen.generate(gen.parse("acme.ly", KNOWN), KNOWN);
        assertThat(c.get(0).domain()).isEqualTo("acme.ly");
        assertThat(c.get(0).kind()).isEqualTo(Kind.EXACT);
        assertThat(c.stream().map(Candidate::domain)).contains("getacme.ly", "acme.com");
    }
}
