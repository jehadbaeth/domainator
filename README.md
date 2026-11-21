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

## Run with Docker

```bash
cp .env.example .env            # optional: API keys
docker compose up -d --build    # http://localhost:8095 (DOMAINATOR_PORT to change)
```

Two containers: `backend` (Spring Boot, not exposed) and `frontend` (nginx serving the React build and
proxying `/api` to the backend).

## CI/CD

* **CI** (`.github/workflows/ci.yml`) runs on every push and pull request: backend build and tests, frontend
  lint and build, then both container images are built.
* **CD** is pull-based, so a home server never has to be reachable from GitHub:
  `deploy/homelab-update.sh` runs every 5 minutes from a systemd user timer, and when `main` has a new commit
  whose CI checks all passed it fast-forwards the checkout and runs `docker compose up -d --build`.

  ```bash
  git clone https://github.com/jehadbaeth/domainator.git ~/deployments/domainator
  cd ~/deployments/domainator && docker compose up -d --build
  mkdir -p ~/.config/systemd/user && cp deploy/domainator-deploy.{service,timer} ~/.config/systemd/user/
  loginctl enable-linger "$USER"            # run user timers without a login session
  systemctl --user daemon-reload && systemctl --user enable --now domainator-deploy.timer
  journalctl --user -u domainator-deploy    # deployment log
  ```

## Registrars

**15 work with no setup**, 13 more switch on once their keys are in `.env` (copy `.env.example`). The UI
lists inactive registrars and which variables they still need.

| Registrar | Source | Live per-domain quotes | Notes |
|---|---|---|---|
| Porkbun | public API | no | USD |
| OVHcloud | public API | no | net prices, subsidiary configurable |
| INWX | official CSV | no | net; multi-year-minimum TLDs skipped |
| Vercel | public API | yes (premium) | 60 requests/min anonymously |
| NearlyFreeSpeech | price page | no | 65 TLDs |
| NameBright | price page | no | |
| DomainFactory | price page | no | monthly gross prices, shown yearly and net |
| one.com | price page | no | currency/VAT depend on the backend's country |
| ALL-INKL.COM | price page | no | new gTLDs only with hosting |
| Loopia | price page | no | SEK |
| Namespro | price page | no | CAD |
| EuroDNS, 101domain, Gname | one page per TLD | no | only your suggestion TLDs are read |
| united-domains | price page | no | promo TLDs need one extra page each |
| REG.RU | public API | no | off by default |
| Namecheap | API key + whitelisted IP | premium only | API needs 20+ domains, $50 balance or $50 spent |
| NameSilo, Dynadot | API key | no | |
| Spaceship | key + secret | yes | |
| Name.com | username + token | premium only | |
| Internet.bs | key + password | no | `INTERNETBS_TEST=true` for the test env |
| AWS Route 53 | `ROUTE53_*` access key | no | |
| GoDaddy | free personal access token | yes | official API, any account |
| Gandi, Cloudflare | token | yes | |
| Hostinger | token | no | experimental field mapping |
| Openprovider | reseller login | no | |

"Price page" sources read the registrar's public price page (server-rendered HTML) once per refresh with the
app's own User-Agent. They break when a site is redesigned; a refresh that finds too few prices shows ERROR on
that registrar's chip instead of silently serving a half empty list. Internal website APIs (for example the
ones behind GoDaddy's or Namecheap's search pages) are deliberately not used.

**Only the 15 public sources and Internet.bs (test env) were tested against the real service.** The keyed
adapters are unit tested against official docs and captured responses but have not seen a real account.

### Free extras and budget verdicts

`backend/src/main/resources/registrars.json` holds, for 37 registrars, whether WHOIS privacy, SSL, email
forwarding, a mailbox, DNS, DNSSEC, URL forwarding and 2FA are free, paid, limited or missing, each with a link
to the official page it comes from, plus a budget verdict and the usual catches. The "Registrars & free extras"
tab shows it with filters ("must include free privacy and SSL"), and price table headers show the key ones.
Entries marked *snippet* could only be read from search excerpts because the site blocks automated checks.

### Settings (saved in your browser)

The defaults give the lowest prices: every public registrar, net prices, no add-ons.

* **Search:** "Suggest name variations" (prefixes, suffixes, hyphens, domain hacks) can be switched off, and
  the TLD list your name is checked on can be edited. Your name always shows on every listed TLD, taken or
  not; a TLD none of your selected registrars sells says so instead of disappearing.
* **Registrars to compare:** tick any combination, or use All, None, "Great & good budget" or "Free privacy".
  Only ticked registrars are compared and shown as columns.
* **VAT:** off shows net prices. On adds your rate (default 19%) for registrars that charge EU VAT;
  for registrars where that is unknown (mostly US ones) nothing is added and the cell says "VAT?".
* **Add-ons I need:** privacy, SSL, email forwarding, mailbox, DNS, DNSSEC, URL forwarding, 2FA. Free ones
  cost nothing; paid ones are added to every year (monthly prices x12, one-time fees in year one, a bundle
  such as united-domains' Domain Tresor counted once). A registrar that does not offer one, offers only GDPR
  redaction instead of privacy, or has no known price for it is marked and cannot be "Best".

Paid add-on prices and VAT behaviour live in `registrars.json` next to the feature data.

### Other data sources

| What | Source |
|---|---|
| Availability | DNS NS lookup, then registry RDAP (IANA bootstrap + overrides in `application.yml`) |
| Real TLD list | IANA root zone list (filters out Porkbun's Handshake names) |
| Currency conversion | ECB daily reference rates |

### Caching (to keep request volume low)

* **Price lists** are kept on disk (`cache-dir`, a Docker volume) and only refetched when older than 12 h,
  checked every 30 min. A restart or redeploy serves the cached lists without contacting any registrar. A
  registrar whose refresh failed is retried after 2 h, and its last good list keeps being served.
* **Availability** (DNS + RDAP): registered names are cached for 24 h, free names for 1 h, failures not at all.
  At most 2 parallel requests go to any single RDAP server.
* **Live quotes**: 15 min per registrar and domain, including "no quote".
* One-page-per-TLD sources and Vercel are fetched sequentially with a pause between requests.

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
