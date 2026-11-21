package com.domainator.pricing;

import java.math.BigDecimal;
import java.net.IDN;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53domains.Route53DomainsClient;
import software.amazon.awssdk.services.route53domains.model.DomainPrice;
import software.amazon.awssdk.services.route53domains.model.ListPricesRequest;
import software.amazon.awssdk.services.route53domains.model.PriceWithCurrency;

/**
 * Amazon Route 53 Domains ListPrices (us-east-1 only). Needs an IAM user with route53domains:ListPrices on "*".
 * Returns standard prices only, never premium. Credentials are passed explicitly because values from .env are
 * Spring properties, not real environment variables.
 */
public class AwsRoute53Provider implements RegistrarProvider {

    private final String accessKeyId;
    private final String secretAccessKey;

    public AwsRoute53Provider(ProviderSettings s) {
        this.accessKeyId = s.get("access-key-id");
        this.secretAccessKey = s.get("secret-access-key");
    }

    @Override
    public String id() {
        return "route53";
    }

    @Override
    public String displayName() {
        return "AWS Route 53";
    }

    @Override
    public String taxNote() {
        return "USD, excl. tax";
    }

    @Override
    public String buyUrl(String domain) {
        return "https://console.aws.amazon.com/route53/domains/home#/DomainSearch";
    }

    @Override
    public Map<String, TldPrice> fetchPrices() {
        try (Route53DomainsClient client = Route53DomainsClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build()) {
            Map<String, TldPrice> out = new HashMap<>();
            for (DomainPrice p : client.listPricesPaginator(ListPricesRequest.builder().maxItems(1000).build()).prices()) {
                TldPrice price = toPrice(p.name(), p.registrationPrice(), p.renewalPrice());
                if (price != null) {
                    out.put(price.tld(), price);
                }
            }
            return out;
        }
    }

    static TldPrice toPrice(String name, PriceWithCurrency reg, PriceWithCurrency ren) {
        if (name == null || reg == null || ren == null || reg.price() == null || ren.price() == null) {
            return null;
        }
        String tld = IDN.toASCII(name.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""));
        return new TldPrice(tld, BigDecimal.valueOf(reg.price()), BigDecimal.valueOf(ren.price()),
                reg.currency() == null ? "USD" : reg.currency(), null);
    }
}
