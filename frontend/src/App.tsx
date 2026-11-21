import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { api, type Availability, type DomainResult, type ProviderInfo, type Quote, type TldRow } from './api'

const HORIZONS = [1, 3, 5, 10]
const KIND_LABEL = { EXACT: 'exact', OTHER_TLD: 'other TLD', VARIATION: 'variation', DOMAIN_HACK: 'domain hack' }
const STATUS_LABEL: Record<Availability, string> = {
  AVAILABLE: 'available',
  LIKELY_AVAILABLE: 'likely available',
  TAKEN: 'taken',
  UNKNOWN: 'unknown',
}

type Tab = 'search' | 'tlds'
type Sort = 'relevance' | 'price'

function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function save(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* storage unavailable */
  }
}

function money(amount: number | undefined, currency: string) {
  if (amount === undefined || amount === null) return '–'
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)
}

function bestTotal(quotes: Quote[], years: number, currency: string) {
  const totals = quotes.filter((q) => q.currency === currency).map((q) => q.totals[String(years)])
  return totals.length ? Math.min(...totals) : Number.POSITIVE_INFINITY
}

export default function App() {
  const [tab, setTab] = useState<Tab>('search')
  const [query, setQuery] = useState('')
  const [providers, setProviders] = useState<ProviderInfo[]>([])
  const [inactive, setInactive] = useState<ProviderInfo[]>([])
  const [selected, setSelected] = useState<string[]>(() => load('selectedProviders', []))
  const [currencies, setCurrencies] = useState<string[]>(['EUR', 'USD'])
  const [currency, setCurrency] = useState<string>(() => load('currency', 'EUR'))
  const [horizon, setHorizon] = useState<number>(() => load('horizon', 10))
  const [showTaken, setShowTaken] = useState(false)
  const [sort, setSort] = useState<Sort>('relevance')
  const [results, setResults] = useState<DomainResult[] | null>(null)
  const [tldRows, setTldRows] = useState<TldRow[] | null>(null)
  const [resultCurrency, setResultCurrency] = useState(currency)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // poll provider status until every price list is loaded
  useEffect(() => {
    let timer: number | undefined
    const refresh = () =>
      api
        .providers()
        .then((list) => {
          setProviders(list.filter((p) => p.status !== 'INACTIVE'))
          setInactive(list.filter((p) => p.status === 'INACTIVE'))
          if (list.some((p) => p.status === 'LOADING')) timer = window.setTimeout(refresh, 2000)
        })
        .catch(() => (timer = window.setTimeout(refresh, 3000)))
    refresh()
    api.currencies().then(setCurrencies).catch(() => undefined)
    return () => window.clearTimeout(timer)
  }, [])

  useEffect(() => save('selectedProviders', selected), [selected])
  useEffect(() => save('currency', currency), [currency])
  useEffect(() => save('horizon', horizon), [horizon])

  // empty selection means "all"
  const activeIds = selected.filter((id) => providers.some((p) => p.id === id))
  const providerIds = activeIds.length ? activeIds : providers.map((p) => p.id)
  const shownProviders = providers.filter((p) => providerIds.includes(p.id))
  const years = useMemo(() => [...new Set([1, 5, 10, horizon])].sort((a, b) => a - b), [horizon])

  async function runSearch(e?: FormEvent) {
    e?.preventDefault()
    if (!query.trim()) return
    setLoading(true)
    setError(null)
    try {
      const res = await api.search(query, providerIds, currency, years)
      setResults(res.results)
      setResultCurrency(res.currency)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  async function loadTlds() {
    setLoading(true)
    setError(null)
    try {
      const res = await api.tlds(providerIds, currency, years, 300)
      setTldRows(res.tlds)
      setResultCurrency(res.currency)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  // re-run the current view when settings change
  useEffect(() => {
    if (tab === 'search' && results) runSearch()
    if (tab === 'tlds') loadTlds()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, currency, horizon, selected.join(','), providers.filter((p) => p.status === 'READY').length])

  function toggleProvider(id: string) {
    const current = activeIds.length ? activeIds : providers.map((p) => p.id)
    const next = current.includes(id) ? current.filter((x) => x !== id) : [...current, id]
    setSelected(next.length ? next : [id])
  }

  const visible = useMemo(() => {
    if (!results) return []
    let list = showTaken ? results : results.filter((r) => r.availability !== 'TAKEN')
    if (sort === 'price') {
      list = [...list].sort(
        (a, b) => bestTotal(a.quotes, horizon, resultCurrency) - bestTotal(b.quotes, horizon, resultCurrency),
      )
    }
    return list
  }, [results, showTaken, sort, horizon, resultCurrency])

  const takenCount = results ? results.filter((r) => r.availability === 'TAKEN').length : 0
  const loadingProviders = providers.filter((p) => p.status === 'LOADING')

  return (
    <div className="page">
      <header>
        <h1>Domainator</h1>
        <p className="tagline">What a domain really costs over {horizon} years, not what the first year costs.</p>
      </header>

      <nav className="tabs">
        <button className={tab === 'search' ? 'active' : ''} onClick={() => setTab('search')}>
          Search a name
        </button>
        <button className={tab === 'tlds' ? 'active' : ''} onClick={() => setTab('tlds')}>
          Cheapest TLDs
        </button>
      </nav>

      {tab === 'search' && (
        <form className="search" onSubmit={runSearch}>
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Name or domain, e.g. acme or acme.io"
            aria-label="Domain search"
          />
          <button type="submit" disabled={loading || !query.trim()}>
            {loading ? 'Checking…' : 'Search'}
          </button>
        </form>
      )}

      <section className="controls">
        <div className="control">
          <span className="label">Own it for</span>
          <div className="segmented">
            {HORIZONS.map((h) => (
              <button key={h} className={h === horizon ? 'active' : ''} onClick={() => setHorizon(h)}>
                {h} {h === 1 ? 'year' : 'years'}
              </button>
            ))}
          </div>
        </div>
        <label className="control">
          <span className="label">Currency</span>
          <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
            {currencies.map((c) => (
              <option key={c}>{c}</option>
            ))}
          </select>
        </label>
        <div className="control">
          <span className="label">Registrars</span>
          <div className="chips">
            {providers.map((p) => (
              <label key={p.id} className={`chip ${providerIds.includes(p.id) ? 'on' : ''}`} title={p.error ?? ''}>
                <input type="checkbox" checked={providerIds.includes(p.id)} onChange={() => toggleProvider(p.id)} />
                {p.name}
                {p.kind !== 'price list' && <span className="live-mark" title={p.kind}>live</span>}
                <span className={`dot ${p.status.toLowerCase()}`} />
              </label>
            ))}
          </div>
        </div>
        {tab === 'search' && (
          <>
            <label className="control">
              <span className="label">Sort</span>
              <select value={sort} onChange={(e) => setSort(e.target.value as Sort)}>
                <option value="relevance">Relevance</option>
                <option value="price">Cheapest over {horizon}y</option>
              </select>
            </label>
            <label className="control inline">
              <input type="checkbox" checked={showTaken} onChange={(e) => setShowTaken(e.target.checked)} />
              Show taken ({takenCount})
            </label>
          </>
        )}
      </section>

      {inactive.length > 0 && (
        <details className="inactive">
          <summary>
            Showing {providers.length} registrars that publish prices without an account. {inactive.length} more
            need your API keys in <code>.env</code>, click to see which
          </summary>
          <ul>
            {inactive.map((p) => (
              <li key={p.id}>
                <strong>{p.name}</strong>: {p.error}
                {p.note && <span className="sub"> ({p.note})</span>}
              </li>
            ))}
          </ul>
        </details>
      )}

      {loadingProviders.length > 0 && (
        <p className="notice">Still loading prices from {loadingProviders.map((p) => p.name).join(', ')}…</p>
      )}
      {error && <p className="notice error">{error}</p>}

      {tab === 'search' && results && (
        <PriceTable
          rows={visible.map((r) => ({
            key: r.domain,
            title: r.domain,
            badge: KIND_LABEL[r.kind],
            status: r.availability,
            statusTitle: `source: ${r.availabilitySource}`,
            minimumYears: r.minimumYears,
            premium: r.premium,
            quotes: r.quotes,
            cheapest: r.cheapest,
          }))}
          providers={shownProviders}
          horizon={horizon}
          currency={resultCurrency}
          empty={results.length ? 'Everything we checked is taken. Tick "Show taken" to see them.' : 'No candidates.'}
        />
      )}

      {tab === 'tlds' && tldRows && (
        <PriceTable
          rows={[...tldRows]
            .sort((a, b) => bestTotal(a.quotes, horizon, resultCurrency) - bestTotal(b.quotes, horizon, resultCurrency))
            .map((r) => ({
            key: r.tld,
            title: '.' + r.tld,
            minimumYears: r.minimumYears,
            quotes: r.quotes,
            cheapest: r.cheapest,
          }))}
          providers={shownProviders}
          horizon={horizon}
          currency={resultCurrency}
          empty="No prices loaded yet."
        />
      )}

      <footer>
        <p>
          Totals are first year price plus renewals at today's list prices, converted with ECB daily rates. Renewal
          prices can change at any time, so a 10 year figure is an estimate.
        </p>
        <p>
          "Available" means the registry has no record of the name. Premium or reserved names can still cost far more
          than the standard price shown here. "Likely available" means the TLD has no RDAP server and only DNS was
          checked.
        </p>
        <p>
          Registrars marked "live" are asked about each exact domain during the search, which is the only way to see
          premium prices. The others use their TLD price list, which can't see premium names.
        </p>
      </footer>
    </div>
  )
}

interface Row {
  key: string
  title: string
  badge?: string
  status?: Availability
  statusTitle?: string
  minimumYears: number
  premium?: boolean
  quotes: Quote[]
  cheapest: Record<string, string>
}

function PriceTable(props: {
  rows: Row[]
  providers: ProviderInfo[]
  horizon: number
  currency: string
  empty: string
}) {
  const { rows, providers, horizon, currency, empty } = props
  if (!rows.length) return <p className="notice">{empty}</p>
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Domain</th>
            <th>Best over {horizon}y</th>
            {providers.map((p) => (
              <th key={p.id}>
                {p.name}
                <small>{p.taxNote}</small>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            // a taken name cannot be bought at any of these prices, so nothing is "best" and nothing links to a cart
            const taken = r.status === 'TAKEN'
            const bestId = taken ? undefined : r.cheapest[String(horizon)]
            const best = r.quotes.find((q) => q.providerId === bestId)
            return (
              <tr key={r.key} className={r.status === 'TAKEN' ? 'taken' : ''}>
                <td className="domain">
                  <strong>{r.title}</strong>
                  <div className="meta">
                    {r.status && (
                      <span className={`status ${r.status.toLowerCase()}`} title={r.statusTitle}>
                        {STATUS_LABEL[r.status]}
                      </span>
                    )}
                    {r.badge && <span className="badge">{r.badge}</span>}
                    {r.premium && (
                      <span className="badge bait" title="A registrar checked this exact name live and reports registry premium pricing">
                        premium name
                      </span>
                    )}
                    {r.minimumYears > 1 && (
                      <span className="badge warn" title="Registry sells this TLD in multi-year blocks only">
                        {r.minimumYears}y blocks
                      </span>
                    )}
                  </div>
                </td>
                <td className="best">
                  {taken ? (
                    <>
                      <strong>Registered</strong>
                      <div className="sub">Someone owns this name. The prices are for the TLD in general, not this name.</div>
                    </>
                  ) : best ? (
                    <>
                      <strong>{money(best.totals[String(horizon)], best.currency)}</strong>
                      <div className="sub">
                        {best.providerName} · {money(best.totals[String(horizon)] / horizon, best.currency)}/yr avg
                      </div>
                    </>
                  ) : (
                    '–'
                  )}
                </td>
                {providers.map((p) => {
                  const q = r.quotes.find((x) => x.providerId === p.id)
                  if (!q) return <td key={p.id} className="na">{p.kind === 'live quotes' ? 'no quote' : 'not sold'}</td>
                  const total = q.totals[String(horizon)]
                  const stale = r.premium && !q.live
                  return (
                    <td key={p.id} className={q.providerId === bestId ? 'cheapest' : stale ? 'stale' : ''}>
                      <div className="total">
                        {q.buyUrl && !taken ? (
                          <a href={q.buyUrl} target="_blank" rel="noreferrer">
                            {money(total, q.currency)}
                          </a>
                        ) : (
                          money(total, q.currency)
                        )}
                      </div>
                      <div className="sub">
                        1st yr {money(q.registration, q.currency)}, then {money(q.renewal, q.currency)}/yr
                      </div>
                      {q.teaser && (
                        <span className="badge bait" title="Renewal costs at least 1.5x the first year">
                          teaser price
                        </span>
                      )}
                      {q.premium && (
                        <span className="badge bait" title="The registry prices this exact name above the normal TLD price">
                          premium
                        </span>
                      )}
                      {q.live && (
                        <span className="badge" title="Price checked live for this exact domain">
                          live
                        </span>
                      )}
                      {q.promo && (
                        <span className="badge promo" title={q.promo}>
                          promo
                        </span>
                      )}
                      {q.currency !== currency && <span className="badge warn">no FX rate</span>}
                      {stale && (
                        <span className="badge warn" title="This is the standard TLD price; the name is premium, so the real price is higher">
                          standard price, won't apply
                        </span>
                      )}
                    </td>
                  )
                })}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
