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
  search: (q: string, providers: string[], currency: string, years: number[]) =>
    get<SearchResponse>(
      `/api/search?${params({ q, providers: providers.join(','), currency, years: years.join(',') })}`,
    ),
  tlds: (providers: string[], currency: string, years: number[], limit: number) =>
    get<TldResponse>(
      `/api/tlds?${params({ providers: providers.join(','), currency, years: years.join(','), limit: String(limit) })}`,
    ),
}
