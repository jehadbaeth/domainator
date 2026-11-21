export type Availability = 'AVAILABLE' | 'LIKELY_AVAILABLE' | 'TAKEN' | 'UNKNOWN'
export type Kind = 'EXACT' | 'OTHER_TLD' | 'VARIATION' | 'DOMAIN_HACK'

export interface Quote {
  providerId: string
  providerName: string
  currency: string
  registration: number
  renewal: number
  totals: Record<string, number>
  teaser: boolean
  premium: boolean
  live: boolean
  addons: AddonLine[]
  addonsMissing: boolean
  vat: 'added' | 'net' | 'not charged' | 'unknown'
  promo: string | null
  taxNote: string
  originalCurrency: string
  originalRegistration: number
  originalRenewal: number
  buyUrl: string | null
}

export interface ProviderInfo {
  id: string
  name: string
  status: 'LOADING' | 'READY' | 'ERROR' | 'INACTIVE'
  kind: string
  tldCount: number
  updatedAt: string | null
  error: string | null
  taxNote: string | null
  note: string | null
}

export interface DomainResult {
  domain: string
  tld: string
  kind: Kind
  availability: Availability
  availabilitySource: string
  minimumYears: number
  premium: boolean
  quotes: Quote[]
  cheapest: Record<string, string>
}

export interface SearchResponse {
  query: string
  currency: string
  years: number[]
  exchangeRatesDate: string | null
  providers: ProviderInfo[]
  results: DomainResult[]
}

export interface TldRow {
  tld: string
  minimumYears: number
  quotes: Quote[]
  cheapest: Record<string, string>
}

export interface TldResponse {
  currency: string
  years: number[]
  providers: ProviderInfo[]
  tlds: TldRow[]
}

export type FeatureValue = 'free' | 'paid' | 'limited' | 'none' | 'unknown'
export type FeatureKey = 'privacy' | 'ssl' | 'emailForwarding' | 'mailbox' | 'dns' | 'dnssec' | 'urlForwarding' | 'twoFactor'

export interface AddonLine {
  feature: FeatureKey
  status: 'free' | 'paid' | 'limited' | 'missing' | 'unknown'
  amount: number | null
  note: string | null
}

export interface Feature {
  value: FeatureValue
  note?: string
  source?: string
  cost?: { amount: number; currency: string; per: 'year' | 'month' | 'once'; bundle?: string }
}

export interface RegistrarProfile {
  id: string
  name: string
  providerId: string | null
  budget: 'great' | 'good' | 'average' | 'poor' | 'unknown'
  verdict: string
  verification: 'verified' | 'partial' | 'snippet' | 'pending'
  features: Record<FeatureKey, Feature>
  catches: string | null
  sources: string[]
  website: string | null
  euVat?: boolean
}

export interface RegistrarData {
  asOf: string
  note: string
  registrars: RegistrarProfile[]
}

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body.error) message = body.error
    } catch {
      /* not json */
    }
    throw new Error(message)
  }
  return res.json() as Promise<T>
}

function params(p: Record<string, string>) {
  return new URLSearchParams(p).toString()
}

export const api = {
  providers: () => get<ProviderInfo[]>('/api/providers'),
  currencies: () => get<string[]>('/api/currencies'),
  registrars: () => get<RegistrarData>('/api/registrars'),
  defaults: () => get<{ tlds: string[] }>('/api/defaults'),
  search: (
    q: string,
    providers: string[],
    currency: string,
    years: number[],
    addons: string[],
    vat: number,
    tlds: string[],
    variations: boolean,
  ) =>
    get<SearchResponse>(
      `/api/search?${params({
        q,
        providers: providers.join(','),
        currency,
        years: years.join(','),
        addons: addons.join(','),
        vat: String(vat),
        tlds: tlds.join(','),
        variations: String(variations),
      })}`,
    ),
  tlds: (providers: string[], currency: string, years: number[], limit: number, addons: string[], vat: number) =>
    get<TldResponse>(
      `/api/tlds?${params({ providers: providers.join(','), currency, years: years.join(','), limit: String(limit), addons: addons.join(','), vat: String(vat) })}`,
    ),
}
