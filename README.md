# Domainator

A meta search for domain names that ranks registrars by what a domain costs over 1, 5 or 10 years,
not by the first year teaser price.

```
backend/   Spring Boot 4.2 (Java 17, Gradle)  ->  http://localhost:8080/api/...
frontend/  React + Vite + TypeScript          ->  http://localhost:5173 (proxies /api to 8080)
```

## Run it

```bash
cp .env.example .env                     # optional: add API keys
cd backend && ./gradlew bootRun          # terminal 1, prices load in the background (~20 s)
cd frontend && npm install && npm run dev  # terminal 2, open http://localhost:5173
```

## Registrars

Public ones work with no setup. Keyed ones switch on automatically once their variables are in `.env`
(copy `.env.example`). The UI lists inactive registrars and which variables they still need.

| Registrar | Needs | Price list | Live per-domain quotes (premium) | Notes |
|---|---|---|---|---|
| Porkbun | nothing | yes | no | USD |
| OVHcloud | nothing | yes | no | net prices, subsidiary configurable |
| INWX | nothing | yes (official CSV) | no | net EUR/USD/CHF; multi-year-minimum TLDs skipped |
| Vercel | nothing (token optional) | configured TLDs only | yes, 200 per call | anonymous limit 60 requests/min |
| REG.RU | nothing | yes | no | off by default (Russian registrar) |
| Namecheap | API user, key, whitelisted IPv4 | yes | premium only | API access needs 20+ domains, $50 balance or $50 spent |
| NameSilo | API key | yes | no | your account's prices |
| Dynadot | API key | yes | no | API3 is marked legacy by Dynadot |
| Spaceship | key + secret | yes | yes | includes ICANN fee |
| Name.com | username + token | yes | premium only | |
| Internet.bs | key + password | yes | no | `INTERNETBS_TEST=true` tries the public test env (not retail prices) |
| AWS Route 53 | IAM access key (ROUTE53_*) | yes | no | IAM `route53domains:ListPrices` |
| GoDaddy | personal access token | no | yes | excludes ICANN fee and tax |
| Gandi | personal access token | no | yes | net prices |
| Cloudflare | token + account id | no | yes | beta API, subset of TLDs, no premiums |
| Hostinger | token | yes | no | experimental: which field is the renewal price is inferred |
| Openprovider | reseller login | yes | no | reseller membership prices |

Not included, and why: Joker and Netim need reseller accounts and give no reliable price format; Infomaniak's
API has no renewal price; Regery has no public docs; IONOS, Strato, netcup, Hover and others have no price API.
Use a `static-providers` entry in `application.yml` to type in prices for any of them.

**Only Porkbun, OVHcloud, INWX, Vercel and Internet.bs (test env) were tested against the real service.**
The keyed adapters are built and unit tested against official docs and captured responses, but have not
seen your real account. If one fails, its error shows on the registrar chip in the UI and in the backend log.

### Other data sources

| What | Source |
|---|---|
| Availability | DNS NS lookup, then registry RDAP (IANA bootstrap + overrides in `application.yml`) |
| Real TLD list | IANA root zone list (filters out Porkbun's Handshake names) |
| Currency conversion | ECB daily reference rates |

Price lists refresh every 12 h. Availability and live quotes are cached for 15 min.

## How the cost is calculated

`total(N) = first year price + (N - 1) × renewal price`, using today's list prices. TLDs sold in
multi-year blocks (`tld-minimum-years`, e.g. `.ai: 2`) are billed in whole blocks, so 5 years of `.ai`
costs 6 billed years. A quote is flagged as a **teaser** when renewal is at least 1.5× the first year.

## Configure

Everything is in `backend/src/main/resources/application.yml`: display currency, which TLDs and
prefixes/suffixes the suggestions use, which registrars are enabled, OVH subsidiary and VAT handling,
RDAP overrides, and `static-providers` for registrars without a public price API (type the prices in).

## Add a registrar

Implement `RegistrarProvider`: `fetchPrices()` for a TLD price list and/or `quoteDomains()` for live
per-domain prices. Then add one line to `ProviderRegistry.descriptors`, a settings block in
`application.yml` and its variables in `.env.example`.

## Known limits

* **Premium names.** Price lists only know the standard TLD price. Premium names are only detected
  by the registrars marked "live" (Vercel works without a key; Spaceship, GoDaddy, Gandi, Namecheap
  and Name.com need keys). A name with no live quote can still turn out to be premium.
* **Renewal prices change.** A 10 year total assumes today's renewal price for 10 years.
* **Tax and fees differ per registrar.** Each column header says what is included (net, ICANN fee,
  account price level). Compare with that in mind.
* **Coupons** (Porkbun first-year codes, OVH new-customer deals) are ignored on purpose.
  OVH "standard" promotions that anyone gets are applied to the first year and labelled.
* **TLDs without RDAP** (e.g. `.eu`) fall back to DNS and show "likely available".
* DNS says "taken" for any name with nameservers, so a TLD with a wildcard DNS record would show
  every name as taken.
