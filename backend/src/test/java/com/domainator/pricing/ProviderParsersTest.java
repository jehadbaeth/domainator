package com.domainator.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.domainator.pricing.RegistrarProvider.DomainQuote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parser tests. Samples are taken from official docs/specs or real captured responses (noted per test),
 * because most of these APIs cannot be called without the user's credentials.
 */
class ProviderParsersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream in = ProviderParsersTest.class.getResourceAsStream("/fixtures/" + name)) {
            return JSON.readTree(in);
        }
    }

    // real sandbox capture (opscrew fixture), note lower case names, the misspelled YourAdditonalCost and xmlns
    private static final String NAMECHEAP_REGISTER = """
            <?xml version="1.0" encoding="utf-8"?>
            <ApiResponse Status="OK" xmlns="http://api.namecheap.com/xml.response">
              <Errors /><Warnings />
              <RequestedCommand>namecheap.users.getPricing</RequestedCommand>
              <CommandResponse Type="namecheap.users.getPricing">
                <UserGetPricingResult>
                  <ProductType Name="domains">
                    <ProductCategory Name="register">
                      <Product Name="com">
                        <Price Duration="1" DurationType="YEAR" Price="10.28" PricingType="MULTIPLE" AdditionalCost="0.20" RegularPrice="13.98" RegularPriceType="MULTIPLE" RegularAdditionalCost="0.20" RegularAdditionalCostType="MULTIPLE" YourPrice="10.28" YourPriceType="MULTIPLE" YourAdditonalCost="0.20" YourAdditonalCostType="MULTIPLE" PromotionPrice="0.0" Currency="USD" />
                        <Price Duration="2" DurationType="YEAR" Price="12.13" PricingType="MULTIPLE" AdditionalCost="0.20" RegularPrice="13.98" RegularPriceType="MULTIPLE" RegularAdditionalCost="0.20" RegularAdditionalCostType="MULTIPLE" YourPrice="12.13" YourPriceType="MULTIPLE" YourAdditonalCost="0.20" YourAdditonalCostType="MULTIPLE" PromotionPrice="0.0" Currency="USD" />
                      </Product>
                    </ProductCategory>
                  </ProductType>
                </UserGetPricingResult>
              </CommandResponse>
            </ApiResponse>""";

    @Test
    void namecheapPricingAddsIcannFeeAndUsesOneYearRow() throws Exception {
        Map<String, NamecheapProvider.Yearly> reg = NamecheapProvider.parsePricing(Xml.parse(NAMECHEAP_REGISTER), "REGISTER");
        assertThat(reg.get("com").price()).isEqualByComparingTo("10.48");
        assertThat(reg.get("com").regular()).isEqualByComparingTo("14.18");
        assertThat(NamecheapProvider.parsePricing(Xml.parse(NAMECHEAP_REGISTER), "renew")).isEmpty();
    }

    @Test
    void namecheapCheckReturnsOnlyPremiums() throws Exception {
        // official docs example plus a normal domain
        String xml = """
                <ApiResponse xmlns="http://api.namecheap.com/xml.response" Status="OK"><Errors/><Warnings/>
                <CommandResponse Type="namecheap.domains.check">
                <DomainCheckResult Domain="us.xyz" Available="true" ErrorNo="0" Description="" IsPremiumName="true" PremiumRegistrationPrice="13000.0000" PremiumRenewalPrice="13000.0000" PremiumRestorePrice="65.0000" PremiumTransferPrice="13000.0000" IcannFee="0.0000" EapFee="0.0000"/>
                <DomainCheckResult Domain="plain.com" Available="true" ErrorNo="0" Description="" IsPremiumName="false" PremiumRegistrationPrice="0" PremiumRenewalPrice="0" PremiumRestorePrice="0" PremiumTransferPrice="0" IcannFee="0" EapFee="0"/>
                </CommandResponse></ApiResponse>""";
        Map<String, DomainQuote> q = NamecheapProvider.parseCheck(Xml.parse(xml));
        assertThat(q).containsOnlyKeys("us.xyz");
        assertThat(q.get("us.xyz").premium()).isTrue();
        assertThat(q.get("us.xyz").renewal()).isEqualByComparingTo("13000");
    }

    @Test
    void xmlParserRejectsDoctype() {
        assertThatThrownBy(() -> Xml.parse("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void inwxSkipsMultiYearRows() {
        // real rows from the public CSV
        String csv = """
                "TLD";"New registration";"Registration (period in years)";"Provider switch";"Provider switch (period in years)";"Renewal";"Extension (period in years)";"Trade";"Trade (period in years)";"Whois Privacy";"Trustee Service";"Update";"Restoration"
                "com";"15.50";"1";"15.50";"1";"15.50";"1";"0.00";0;"4.19";"0.00";"0.00";"104.50"
                "online";"2.50";"1";"41.50";"1";"41.50";"1";"0.00";0;"4.19";"0.00";"0.00";"125.00"
                "ai";"110.19";"2";"110.19";"2";"110.19";"2";"0.00";0;"4.62";"0.00";"0.00";"198.34"
                """;
        Map<String, TldPrice> p = InwxCsvProvider.parse(csv, "EUR");
        assertThat(p).containsOnlyKeys("com", "online");
        assertThat(p.get("online").renewal()).isEqualByComparingTo("41.50");
    }

    @Test
    void namesiloIgnoresCodeAndDetail() {
        // docs sample (with the missing comma fixed) and a thousands separator as seen by NameSilo's own sync script
        Map<String, TldPrice> p = NameSiloProvider.parse(json("""
                {"request":{"operation":"getPrices","ip":"55.555.55.55"},
                 "reply":{"code":300,"detail":"success",
                   "com":{"registration":10.00,"transfer":10.40,"renew":11.95},
                   "cars":{"registration":"2,999.00","transfer":"2,999.00","renew":"2,999.00"}}}"""));
        assertThat(p).containsOnlyKeys("com", "cars");
        assertThat(p.get("com").renewal()).isEqualByComparingTo("11.95");
        assertThat(p.get("cars").registration()).isEqualByComparingTo("2999.00");
    }

    @Test
    void dynadotTldPrice() {
        // official API3 docs sample
        Map<String, TldPrice> p = DynadotProvider.parse(json("""
                {"TldPriceResponse":{"ResponseCode":0,"Status":"success","PriceLevel":"Regular Pricing","Currency":"USD",
                  "TldPrice":[{"Tld":"com","Usage":"General","IDN":"YES","Restrictions":"--","OnSale":"Yes","Privacy":"10.00",
                    "Price":{"Unit":"(Price/1 year)","Register":"15.00","Renew":"15.00","Transfer":"15.00","Restore":"10.00"},
                    "maxDuration":10,"minDuration":1}]}}"""));
        assertThat(p.get("com").registration()).isEqualByComparingTo("15.00");
        assertThat(p.get("com").currency()).isEqualTo("USD");
    }

    @Test
    void spaceshipTldAndDomainPricing() {
        // official OpenAPI examples
        TldPrice com = SpaceshipProvider.parseTld(json("""
                {"tld":"com","prices":{
                  "register":[{"period":{"value":1,"unit":"y"},"priceInDefaultCurrency":{"finalPrice":{"amount":"5.98","currency":"USD"},"regularPrice":{"amount":"10.98","currency":"USD"}}}],
                  "renew":[{"period":{"value":1,"unit":"y"},"priceInDefaultCurrency":{"finalPrice":{"amount":"10.98","currency":"USD"},"regularPrice":{"amount":"10.98","currency":"USD"}}}]}}"""));
        assertThat(com.registration()).isEqualByComparingTo("5.98");
        assertThat(com.renewal()).isEqualByComparingTo("10.98");
        assertThat(com.promo()).contains("10.98");

        Map<String, DomainQuote> q = SpaceshipProvider.parseDomains(json("""
                {"domains":[{"domain":"rare.io","priceClass":"premium","prices":{
                  "register":[{"period":{"value":1,"unit":"y"},"priceInDefaultCurrency":{"finalPrice":{"amount":"999.99","currency":"USD"},"regularPrice":{"amount":"999.99","currency":"USD"}}}],
                  "renew":[{"period":{"value":1,"unit":"y"},"priceInDefaultCurrency":{"finalPrice":{"amount":"999.99","currency":"USD"},"regularPrice":{"amount":"999.99","currency":"USD"}}}]}},
                  {"domain":"shop.example","errorCode":"tldNotSupported"}]}"""));
        assertThat(q).containsOnlyKeys("rare.io");
        assertThat(q.get("rare.io").premium()).isTrue();
    }

    @Test
    void goDaddyMinorUnitsAndMissingRenewal() {
        // official v3 OpenAPI example, second item without renewalPrice
        Map<String, DomainQuote> q = GoDaddyProvider.parse(json("""
                {"items":[{"domain":"coffee24x7x365.com","available":true,"definitive":false,"inventory":"REGISTRY",
                  "prices":[{"term":"YEAR","period":1,"price":{"currencyCode":"USD","value":1199},"renewalPrice":{"currencyCode":"USD","value":2299}},
                            {"term":"YEAR","period":2,"price":{"currencyCode":"USD","value":3098},"renewalPrice":{"currencyCode":"USD","value":4598}}]},
                  {"domain":"flat.com","available":true,"inventory":"REGISTRY_PREMIUM",
                  "prices":[{"term":"YEAR","period":1,"price":{"currencyCode":"USD","value":1999}}]}]}"""));
        assertThat(q.get("coffee24x7x365.com").registration()).isEqualByComparingTo("11.99");
        assertThat(q.get("coffee24x7x365.com").renewal()).isEqualByComparingTo("22.99");
        assertThat(q.get("flat.com").renewal()).isEqualByComparingTo("19.99");
        assertThat(q.get("flat.com").premium()).isTrue();
    }

    @Test
    void cloudflareDomainCheck() {
        // official docs example
        Map<String, DomainQuote> q = CloudflareProvider.parse(json("""
                {"errors":[],"messages":[],"result":{"domains":[
                  {"name":"xq7mz9brand.com","pricing":{"currency":"USD","registration_cost":"8.57","renewal_cost":"8.57"},"registrable":true,"tier":"standard"},
                  {"name":"taken.com","registrable":false,"reason":"domain_unavailable"}]},"success":true}"""));
        assertThat(q).containsOnlyKeys("xq7mz9brand.com");
        assertThat(q.get("xq7mz9brand.com").premium()).isFalse();
    }

    @Test
    void gandiCheckNeedsCreateAndRenew() {
        // shape from the official docs example, with a renew product added
        DomainQuote q = GandiProvider.parse(json("""
                {"currency":"EUR","grid":"A","products":[
                  {"status":"available","name":"example.com","process":"create","prices":[{"max_duration":1,"duration_unit":"y","min_duration":1,"discount":false,"price_after_taxes":878.44,"price_before_taxes":732.03,"type":"premium"}]},
                  {"status":"available","name":"example.com","process":"renew","prices":[{"max_duration":1,"duration_unit":"y","min_duration":1,"price_before_taxes":732.03}]}]}"""));
        assertThat(q.registration()).isEqualByComparingTo("732.03");
        assertThat(q.premium()).isTrue();
        assertThat(q.available()).isTrue();
        assertThat(GandiProvider.parse(json("""
                {"currency":"EUR","products":[{"status":"available","process":"create","prices":[{"duration_unit":"y","min_duration":1,"price_before_taxes":9}]}]}""")))
                .isNull();
    }

    @Test
    void vercelPriceAndSearch() {
        // live responses captured 2026-11-21
        assertThat(VercelProvider.parsePrice("xyz", json("{\"years\":1,\"purchasePrice\":1.49,\"renewalPrice\":13,\"transferPrice\":15.17}")).renewal())
                .isEqualByComparingTo("13");
        assertThat(VercelProvider.parsePrice("ai", json("{\"years\":2,\"purchasePrice\":160,\"renewalPrice\":160}")).registration())
                .isEqualByComparingTo("80");
        Map<String, DomainQuote> q = VercelProvider.parseSearch(json("""
                {"results":[{"domain":"acme-zzqx-test-123.io","available":true,"years":1,"price":14.99,"renewalPrice":46,"premium":false},
                            {"domain":"acme.io","available":false}]}"""));
        assertThat(q).containsOnlyKeys("acme-zzqx-test-123.io");
        assertThat(q.get("acme-zzqx-test-123.io").renewal()).isEqualByComparingTo("46");
    }

    @Test
    void nameComPriceListAndPremiumCheck() {
        // field names from the official Core API spec
        Map<String, TldPrice> p = NameComProvider.parsePriceList(json("""
                {"pricing":[{"tld":"com","duration":1,"registrationPrice":9.99,"registrationRetailPrice":11.99,"renewalPrice":12.99},
                            {"tld":"nope","duration":1,"registrationPrice":null,"renewalPrice":12.99}],
                 "nextPage":null,"lastPage":1,"totalCount":2,"from":1,"to":2}"""));
        assertThat(p).containsOnlyKeys("com");
        assertThat(p.get("com").promo()).contains("11.99");
        Map<String, DomainQuote> q = NameComProvider.parseCheck(json("""
                {"results":[{"domainName":"example.com","sld":"example","tld":"com","purchasable":true,"premium":true,"purchasePrice":2500,"renewalPrice":10.99,"purchaseType":"registration"},
                            {"domainName":"plain.com","purchasable":true,"premium":false,"purchasePrice":10.99,"renewalPrice":10.99}]}"""));
        assertThat(q).containsOnlyKeys("example.com");
    }

    @Test
    void internetBsVersion5() throws Exception {
        // real response from the public test environment
        Map<String, TldPrice> p = InternetBsProvider.parse(fixture("internetbs-v5.json"));
        assertThat(p).containsKeys("com", "io", "co.uk").doesNotContainKey("ai"); // .ai has minperiod 2
        assertThat(p.get("io").renewal()).isEqualByComparingTo("51.24");
    }

    @Test
    void regRuMapsRurAndSkipsNothingButInvalid() throws Exception {
        // real response, currency=EUR
        Map<String, TldPrice> p = RegRuProvider.parse(fixture("regru.json"));
        assertThat(p.get("com").renewal()).isEqualByComparingTo("32.00");
        assertThat(p.get("com").currency()).isEqualTo("EUR");
        assertThat(p.get("vg").registration()).isEqualByComparingTo("0"); // dropped later by the zero price filter
    }

    @Test
    void hostingerCents() {
        // shape from the official OpenAPI schema (domain sample not published)
        Map<String, TldPrice> p = HostingerProvider.parse(json("""
                [{"id":"hostingercom-domain-com","name":".COM","category":"DOMAIN","prices":[
                  {"id":"hostingercom-domain-com-usd-1y","name":".COM 1 year","currency":"USD","price":1799,"first_period_price":899,"period":1,"period_unit":"year"},
                  {"id":"hostingercom-domain-com-usd-2y","currency":"USD","price":3598,"first_period_price":2698,"period":2,"period_unit":"year"}]}]"""));
        assertThat(p.get("com").registration()).isEqualByComparingTo("8.99");
        assertThat(p.get("com").renewal()).isEqualByComparingTo("17.99");
    }

    @Test
    void openproviderResellerPrice() {
        // shape from the official swagger example
        Map<String, TldPrice> p = OpenproviderProvider.parse(json("""
                [{"name":"eu","min_period":1,"max_period":10,"prices":{
                   "create_price":{"product":{"currency":"EUR","price":5},"reseller":{"currency":"EUR","price":4.5}},
                   "renew_price":{"product":{"currency":"EUR","price":5},"reseller":{"currency":"EUR","price":4.75}}}}]"""));
        assertThat(p.get("eu").registration()).isEqualByComparingTo("4.5");
        assertThat(p.get("eu").renewal()).isEqualByComparingTo("4.75");
    }
}
