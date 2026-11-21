package com.domainator.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.domainator.availability.AvailabilityService;
import com.domainator.config.DomainatorProperties;
import com.domainator.fx.ExchangeRates;
import com.domainator.pricing.LiveQuoteService;
import com.domainator.pricing.PriceCatalog;
import com.domainator.pricing.PriceCatalog.ProviderState;
import com.domainator.pricing.ProviderRegistry;
import com.domainator.pricing.RegistrarProvider;
import com.domainator.pricing.RegistrarProvider.DomainQuote;
import com.domainator.pricing.TldPrice;
import com.domainator.registrars.RegistrarProfiles;
import com.domainator.search.SearchModels.DomainResult;
import com.domainator.search.SearchModels.ProviderInfo;
import com.domainator.search.SearchModels.Quote;
import com.domainator.search.SearchModels.SearchResponse;
import com.domainator.search.SearchModels.TldResponse;
import com.domainator.search.SearchModels.TldRow;
import com.domainator.suggest.SuggestionGenerator;
import com.domainator.suggest.SuggestionGenerator.Candidate;

@Service
public class SearchService {

    /** Live per-domain checks cost API calls, so only this many not-taken candidates are checked. */
    private static final int MAX_LIVE_DOMAINS = 50;

    private final PriceCatalog catalog;
    private final AvailabilityService availability;
    private final SuggestionGenerator suggestions;
    private final ExchangeRates fx;
    private final DomainatorProperties props;
    private final LiveQuoteService live;
    private final ProviderRegistry registry;
    private final RegistrarProfiles profiles;

    public SearchService(PriceCatalog catalog, AvailabilityService availability, SuggestionGenerator suggestions,
                         ExchangeRates fx, DomainatorProperties props, LiveQuoteService live, ProviderRegistry registry,
                         RegistrarProfiles profiles) {
        this.profiles = profiles;
        this.catalog = catalog;
        this.availability = availability;
        this.suggestions = suggestions;
        this.fx = fx;
        this.props = props;
        this.live = live;
        this.registry = registry;
    }

    public SearchResponse search(String query, Set<String> providerIds, String currency, List<Integer> years,
                                 boolean withPrivacy) {
        List<ProviderState> states = selected(providerIds);
        Set<String> ids = states.stream().map(s -> s.provider().id()).collect(Collectors.toSet());
        Set<String> knownTlds = catalog.knownTlds(ids);
        String cur = currency(currency);

        SuggestionGenerator.Query q = suggestions.parse(query, knownTlds);
        List<Candidate> candidates = suggestions.generate(q, knownTlds);
        Map<String, AvailabilityService.Result> avail =
                availability.checkAll(candidates.stream().map(Candidate::domain).toList());

        List<String> liveDomains = candidates.stream().map(Candidate::domain)
                .filter(d -> avail.get(d).status() != AvailabilityService.Status.TAKEN)
                .limit(MAX_LIVE_DOMAINS).toList();
        Map<String, Map<String, DomainQuote>> liveQuotes =
                live.quote(states.stream().map(ProviderState::provider).toList(), liveDomains);

        List<DomainResult> results = new ArrayList<>();
        for (Candidate c : candidates) {
            int minYears = props.tldMinimumYears().getOrDefault(c.tld(), 1);
            List<Quote> quotes = quotes(c.tld(), c.domain(), states, liveQuotes, cur, years, minYears, withPrivacy);
            AvailabilityService.Result a = avail.get(c.domain());
            // A premium name is premium at every registrar, so standard price list quotes must not win "best".
            boolean premium = quotes.stream().anyMatch(Quote::premium);
            List<Quote> eligible = eligible(premium ? quotes.stream().filter(Quote::live).toList() : quotes, withPrivacy);
            results.add(new DomainResult(c.domain(), c.tld(), c.kind(), a.status(), a.source(), minYears,
                    premium, quotes, cheapest(eligible, years)));
        }
        return new SearchResponse(query, cur, years, fx.date(), providerInfo(states), results);
    }

    /** Every TLD the selected providers sell, cheapest first for the longest requested horizon. */
    public TldResponse tlds(Set<String> providerIds, String currency, List<Integer> years, int limit,
                            boolean withPrivacy) {
        List<ProviderState> states = selected(providerIds);
        Set<String> ids = states.stream().map(s -> s.provider().id()).collect(Collectors.toSet());
        String cur = currency(currency);
        int sortYears = years.get(years.size() - 1);
        List<TldRow> rows = new ArrayList<>();
        for (String tld : catalog.knownTlds(ids)) {
            int minYears = props.tldMinimumYears().getOrDefault(tld, 1);
            List<Quote> quotes = quotes(tld, null, states, Map.of(), cur, years, minYears, withPrivacy);
            if (!quotes.isEmpty()) {
                rows.add(new TldRow(tld, minYears, quotes, cheapest(eligible(quotes, withPrivacy), years)));
            }
        }
        rows.sort(Comparator.comparing((TldRow r) -> bestTotal(eligible(r.quotes(), withPrivacy), sortYears, cur)));
        return new TldResponse(cur, years, providerInfo(states), rows.stream().limit(limit).toList());
    }

    private List<Quote> quotes(String tld, String domain, List<ProviderState> states,
                               Map<String, Map<String, DomainQuote>> liveQuotes, String cur,
                               List<Integer> years, int minYears, boolean withPrivacy) {
        List<Quote> quotes = new ArrayList<>();
        for (ProviderState s : states) {
            RegistrarProvider provider = s.provider();
            DomainQuote dq = domain == null ? null : liveQuotes.getOrDefault(provider.id(), Map.of()).get(domain);
            TldPrice p = s.prices().get(tld);
            if (dq == null && p == null) {
                continue;
            }
            BigDecimal origReg = dq != null ? dq.registration() : p.registration();
            BigDecimal origRen = dq != null ? dq.renewal() : p.renewal();
            String origCur = dq != null ? dq.currency() : p.currency();
            String promo = dq != null ? null : p.promo();

            Optional<BigDecimal> reg = fx.convert(origReg, origCur, cur);
            Optional<BigDecimal> ren = fx.convert(origRen, origCur, cur);
            boolean converted = reg.isPresent() && ren.isPresent();
            // round the yearly prices first so the totals can be checked by hand from the displayed numbers
            BigDecimal r1 = (converted ? reg.get() : origReg).setScale(2, RoundingMode.HALF_UP);
            BigDecimal rn = (converted ? ren.get() : origRen).setScale(2, RoundingMode.HALF_UP);
            String privacy = profiles.privacy(provider.id());
            BigDecimal privacyAdded = null;
            if (withPrivacy && "paid".equals(privacy)) {
                String priceCur = converted ? cur : origCur;
                var cost = profiles.privacyCost(provider.id());
                privacyAdded = cost.flatMap(pc -> fx.convert(pc.amount(), pc.currency(), priceCur))
                        .map(v -> v.setScale(2, RoundingMode.HALF_UP)).orElse(null);
                if (privacyAdded != null) {
                    r1 = r1.add(privacyAdded);
                    if (!cost.get().oneTime()) {
                        rn = rn.add(privacyAdded); // yearly fee
                    }
                }
            }
            Map<Integer, BigDecimal> totals = new LinkedHashMap<>();
            for (int y : years) {
                totals.put(y, CostCalculator.total(r1, rn, y, minYears));
            }
            quotes.add(new Quote(provider.id(), provider.displayName(), converted ? cur : origCur, r1, rn, totals,
                    CostCalculator.isTeaser(origReg, origRen), dq != null && dq.premium(), dq != null, privacy, privacyAdded, promo,
                    provider.taxNote(), origCur, origReg, origRen,
                    link(provider, domain, tld)));
        }
        return quotes;
    }

    /**
     * With "include privacy" on, registrars whose privacy is missing, redaction only or a paid add-on we have no
     * price for cannot win "best".
     */
    private static List<Quote> eligible(List<Quote> quotes, boolean withPrivacy) {
        if (!withPrivacy) {
            return quotes;
        }
        return quotes.stream().filter(q -> "free".equals(q.privacy()) || q.privacyAdded() != null
                || "unknown".equals(q.privacy())).toList();
    }

    /** The registrar's search for the exact domain if it has one, otherwise its offer page for the TLD. */
    private static String link(RegistrarProvider provider, String domain, String tld) {
        String url = domain == null ? null : provider.buyUrl(domain);
        return url != null ? url : provider.tldUrl(tld);
    }

    private static Map<Integer, String> cheapest(List<Quote> quotes, List<Integer> years) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (quotes.isEmpty()) {
            return out;
        }
        String cur = quotes.get(0).currency();
        for (int y : years) {
            quotes.stream()
                    .filter(q -> q.currency().equals(cur)) // only compare like with like if FX failed
                    .min(Comparator.comparing((Quote q) -> q.totals().get(y)))
                    .ifPresent(q -> out.put(y, q.providerId()));
        }
        return out;
    }

    private static BigDecimal bestTotal(List<Quote> quotes, int years, String cur) {
        return quotes.stream().filter(q -> q.currency().equals(cur)).map(q -> q.totals().get(years))
                .min(Comparator.naturalOrder()).orElse(new BigDecimal(Long.MAX_VALUE));
    }

    private List<ProviderState> selected(Set<String> providerIds) {
        return catalog.states().stream()
                .filter(s -> providerIds == null || providerIds.isEmpty() || providerIds.contains(s.provider().id()))
                .toList();
    }

    private String currency(String requested) {
        String c = requested == null || requested.isBlank() ? props.displayCurrency() : requested.toUpperCase();
        return fx.currencies().contains(c) ? c : props.displayCurrency();
    }

    private List<ProviderInfo> providerInfo(List<ProviderState> states) {
        return states.stream().map(s -> {
            RegistrarProvider p = s.provider();
            boolean list = !s.prices().isEmpty() || s.status() != PriceCatalog.Status.READY;
            String kind = p.quotesDomains() ? (list ? "price list + live quotes" : "live quotes") : "price list";
            String error = s.error() != null ? s.error() : live.lastError(p.id());
            return new ProviderInfo(p.id(), p.displayName(), s.status().name(), kind, s.prices().size(),
                    s.updatedAt(), error, p.taxNote(), null);
        }).toList();
    }

    /** Active providers first, then the ones that could be enabled with the reason they are not. */
    public List<ProviderInfo> allProviders() {
        List<ProviderInfo> out = new ArrayList<>(providerInfo(catalog.states()));
        registry.inactive().forEach(i -> out.add(new ProviderInfo(i.id(), i.name(), "INACTIVE", i.kind(), 0, null,
                i.reason(), null, i.note())));
        return out;
    }

    public Set<String> currencies() {
        return new LinkedHashSet<>(fx.currencies());
    }
}
