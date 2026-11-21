package com.domainator.pricing;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.domainator.pricing.web.AllInklProvider;
import com.domainator.pricing.web.DomainFactoryProvider;
import com.domainator.pricing.web.EuroDnsProvider;
import com.domainator.pricing.web.GnameProvider;
import com.domainator.pricing.web.LoopiaProvider;
import com.domainator.pricing.web.NameBrightProvider;
import com.domainator.pricing.web.NamesproProvider;
import com.domainator.pricing.web.NearlyFreeSpeechProvider;
import com.domainator.pricing.web.OneComProvider;
import com.domainator.pricing.web.OneOhOneDomainProvider;
import com.domainator.pricing.web.UnitedDomainsProvider;

import com.domainator.config.DomainatorProperties;

/**
 * Every registrar the app knows about. A provider is active when it is enabled (default true for public ones,
 * true for keyed ones) and all its required settings are filled in, usually from .env.
 * To add a registrar: implement {@link RegistrarProvider}, add a line to {@link #descriptors}, add its settings
 * block to application.yml and its keys to .env.example.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    /**
     * @param required settings keys that must be non blank
     * @param envVars  names of the .env variables behind those keys, shown in the UI when missing
     * @param kind     "public" (no key), "api key"
     */
    public record Descriptor(String id, String name, String kind, List<String> required, List<String> envVars,
                             boolean enabledByDefault, String note,
                             Function<ProviderSettings, RegistrarProvider> factory) {
    }

    /** A provider that exists but is not active, with the reason. */
    public record Inactive(String id, String name, String kind, String reason, String note) {
    }

    private final List<RegistrarProvider> active = new ArrayList<>();
    private final List<Inactive> inactive = new ArrayList<>();

    public ProviderRegistry(DomainatorProperties props, RestClient http, Clock clock) {
        for (Descriptor d : descriptors(http, clock, props.suggestions().tlds())) {
            ProviderSettings s = new ProviderSettings(d.id(), props.providers().get(d.id()));
            if (!s.flag("enabled", d.enabledByDefault())) {
                inactive.add(new Inactive(d.id(), d.name(), d.kind(), "disabled in application.yml", d.note()));
                continue;
            }
            // "test: true" (Internet.bs) uses public test credentials, so nothing else is required
            List<String> missing = s.flag("test", false) ? List.of() : s.missing(d.required());
            if (!missing.isEmpty()) {
                List<String> env = new ArrayList<>();
                for (int i = 0; i < d.required().size(); i++) {
                    if (missing.contains(d.required().get(i))) {
                        env.add(d.envVars().get(i));
                    }
                }
                inactive.add(new Inactive(d.id(), d.name(), d.kind(), "set " + String.join(", ", env) + " in .env", d.note()));
                continue;
            }
            try {
                active.add(d.factory().apply(s));
            } catch (RuntimeException e) {
                inactive.add(new Inactive(d.id(), d.name(), d.kind(), "configuration error: " + e.getMessage(), d.note()));
            }
        }
        props.staticProviders().stream()
                .filter(DomainatorProperties.StaticProvider::enabled)
                .map(StaticPriceProvider::new)
                .forEach(active::add);
        log.info("Active registrars: {}", active.stream().map(RegistrarProvider::id).toList());
    }

    static List<Descriptor> descriptors(RestClient http, Clock clock, List<String> suggestionTlds) {
        return List.of(
                // public price lists, no account needed
                new Descriptor("porkbun", "Porkbun", "public", List.of(), List.of(), true, null,
                        s -> new PorkbunProvider(http)),
                new Descriptor("ovh", "OVHcloud", "public", List.of(), List.of(), true, null,
                        s -> new OvhProvider(http, s.get("subsidiary", "DE"), s.flag("include-vat", false), clock)),
                new Descriptor("inwx", "INWX", "public", List.of(), List.of(), true, null,
                        s -> new InwxCsvProvider(http, s)),
                new Descriptor("vercel", "Vercel", "public", List.of(), List.of(), true,
                        "anonymous limit 60 requests/min; set VERCEL_TOKEN in case keyless access stops",
                        s -> new VercelProvider(http, s, suggestionTlds)),
                // public price pages (server-rendered HTML read once per refresh; can break when a site changes)
                new Descriptor("nfs", "NearlyFreeSpeech", "price page", List.of(), List.of(), true,
                        "needs a NearlyFreeSpeech account to register", s -> new NearlyFreeSpeechProvider(http)),
                new Descriptor("namebright", "NameBright", "price page", List.of(), List.of(), true, null,
                        s -> new NameBrightProvider(http)),
                new Descriptor("domainfactory", "DomainFactory", "price page", List.of(), List.of(), true,
                        "monthly prices on the page, shown here per year and without VAT",
                        s -> new DomainFactoryProvider(http)),
                new Descriptor("onecom", "one.com", "price page", List.of(), List.of(), true,
                        "currency and VAT depend on the country the backend runs in",
                        s -> new OneComProvider(http)),
                new Descriptor("allinkl", "ALL-INKL.COM", "price page", List.of(), List.of(), true,
                        "classic TLDs can be bought alone, new gTLDs only with hosting", s -> new AllInklProvider(http)),
                new Descriptor("loopia", "Loopia", "price page", List.of(), List.of(), true, "SEK, 39 TLDs",
                        s -> new LoopiaProvider(http)),
                new Descriptor("namespro", "Namespro", "price page", List.of(), List.of(), true, "CAD, 25 TLDs",
                        s -> new NamesproProvider(http)),
                new Descriptor("eurodns", "EuroDNS", "price page", List.of(), List.of(), true,
                        "one page per TLD, only your suggestion TLDs are read", s -> new EuroDnsProvider(http, suggestionTlds)),
                new Descriptor("101domain", "101domain", "price page", List.of(), List.of(), true,
                        "one page per TLD, only your suggestion TLDs are read", s -> new OneOhOneDomainProvider(http, suggestionTlds)),
                new Descriptor("gname", "Gname", "price page", List.of(), List.of(), true,
                        "one page per TLD, only your suggestion TLDs are read", s -> new GnameProvider(http, suggestionTlds)),
                new Descriptor("uniteddomains", "united-domains", "price page", List.of(), List.of(), true,
                        "promotion TLDs need one extra page each; only your suggestion TLDs get those",
                        s -> new UnitedDomainsProvider(http, suggestionTlds)),
                new Descriptor("regru", "REG.RU", "public", List.of(), List.of(), false,
                        "Russian registrar, paying from the EU may not be possible",
                        s -> new RegRuProvider(http, s)),
                // need an account and API credentials
                new Descriptor("namecheap", "Namecheap", "api key",
                        List.of("api-user", "api-key", "client-ip"),
                        List.of("NAMECHEAP_API_USER", "NAMECHEAP_API_KEY", "NAMECHEAP_CLIENT_IP"), true,
                        "API access needs 20+ domains, $50 balance or $50 spent; whitelist your IPv4",
                        s -> new NamecheapProvider(http, s)),
                new Descriptor("namesilo", "NameSilo", "api key", List.of("api-key"), List.of("NAMESILO_API_KEY"), true,
                        null, s -> new NameSiloProvider(http, s)),
                new Descriptor("dynadot", "Dynadot", "api key", List.of("api-key"), List.of("DYNADOT_API_KEY"), true,
                        null, s -> new DynadotProvider(http, s)),
                new Descriptor("spaceship", "Spaceship", "api key", List.of("api-key", "api-secret"),
                        List.of("SPACESHIP_API_KEY", "SPACESHIP_API_SECRET"), true, null,
                        s -> new SpaceshipProvider(http, s)),
                new Descriptor("namecom", "Name.com", "api key", List.of("username", "api-token"),
                        List.of("NAMECOM_USERNAME", "NAMECOM_API_TOKEN"), true, null,
                        s -> new NameComProvider(http, s)),
                new Descriptor("internetbs", "Internet.bs", "api key", List.of("api-key", "password"),
                        List.of("INTERNETBS_API_KEY", "INTERNETBS_PASSWORD"), true, null,
                        s -> new InternetBsProvider(http, s)),
                new Descriptor("route53", "AWS Route 53", "api key", List.of("access-key-id", "secret-access-key"),
                        List.of("ROUTE53_ACCESS_KEY_ID", "ROUTE53_SECRET_ACCESS_KEY"), true,
                        "IAM permission route53domains:ListPrices", AwsRoute53Provider::new),
                new Descriptor("godaddy", "GoDaddy", "api key", List.of("token"), List.of("GODADDY_TOKEN"), true,
                        "live quotes only", s -> new GoDaddyProvider(http, s)),
                new Descriptor("gandi", "Gandi", "api key", List.of("token"), List.of("GANDI_TOKEN"), true,
                        "live quotes only", s -> new GandiProvider(http, s)),
                new Descriptor("cloudflare", "Cloudflare", "api key", List.of("api-token", "account-id"),
                        List.of("CLOUDFLARE_API_TOKEN", "CLOUDFLARE_ACCOUNT_ID"), true,
                        "live quotes only, registrar API is in beta", s -> new CloudflareProvider(http, s)),
                new Descriptor("hostinger", "Hostinger", "api key", List.of("token"), List.of("HOSTINGER_TOKEN"), true,
                        "experimental price mapping", s -> new HostingerProvider(http, s)),
                new Descriptor("openprovider", "Openprovider", "api key", List.of("username", "password"),
                        List.of("OPENPROVIDER_USERNAME", "OPENPROVIDER_PASSWORD"), true,
                        "reseller membership prices", s -> new OpenproviderProvider(http, s)));
    }

    public List<RegistrarProvider> active() {
        return active;
    }

    public List<Inactive> inactive() {
        return inactive;
    }
}
