import { useMemo, useState } from 'react'
import type { Feature, FeatureKey, ProviderInfo, RegistrarData, RegistrarProfile, TldRow } from './api'

export const FEATURES: { key: FeatureKey; label: string; short: string }[] = [
  { key: 'privacy', label: 'WHOIS privacy', short: 'Privacy' },
  { key: 'ssl', label: 'SSL certificate', short: 'SSL' },
  { key: 'emailForwarding', label: 'Email forwarding', short: 'Email fwd' },
  { key: 'mailbox', label: 'Mailbox', short: 'Mailbox' },
  { key: 'dns', label: 'DNS hosting', short: 'DNS' },
  { key: 'dnssec', label: 'DNSSEC', short: 'DNSSEC' },
  { key: 'urlForwarding', label: 'URL forwarding', short: 'URL fwd' },
  { key: 'twoFactor', label: 'Two factor login', short: '2FA' },
]

const VALUE_LABEL = { free: 'free', paid: 'paid', limited: 'limited', none: 'none', unknown: '?' }
const BUDGET_ORDER = { great: 0, good: 1, average: 2, poor: 3, unknown: 4 }

/** A feature as a colored chip that links to the registrar page the claim comes from. */
export function FeatureChip({ feature, label }: { feature: Feature | undefined; label?: string }) {
  const f = feature ?? { value: 'unknown' as const }
  const text = label ? `${label}: ${VALUE_LABEL[f.value]}` : VALUE_LABEL[f.value]
  const title = [f.note, f.source ? 'Click for the source' : 'No source page'].filter(Boolean).join(' · ')
  return f.source ? (
    <a className={`feat ${f.value}`} href={f.source} target="_blank" rel="noreferrer" title={title}>
      {text}
    </a>
  ) : (
    <span className={`feat ${f.value}`} title={title}>
      {text}
    </span>
  )
}

export function BudgetBadge({ budget }: { budget: RegistrarProfile['budget'] }) {
  return <span className={`budget ${budget}`}>{budget === 'unknown' ? 'unrated' : budget}</span>
}

export function RegistrarsTab(props: {
  data: RegistrarData | null
  providers: ProviderInfo[]
  inactive: ProviderInfo[]
  comRow: TldRow | null
  horizon: number
  currency: string
}) {
  const { data, providers, inactive, comRow, horizon, currency } = props
  const [required, setRequired] = useState<FeatureKey[]>([])
  const [onlyPriced, setOnlyPriced] = useState(false)

  const rows = useMemo(() => {
    if (!data) return []
    return data.registrars
      .filter((r) => required.every((k) => r.features[k]?.value === 'free'))
      .filter((r) => !onlyPriced || providers.some((p) => p.id === r.providerId))
      .sort((a, b) => BUDGET_ORDER[a.budget] - BUDGET_ORDER[b.budget] || a.name.localeCompare(b.name))
  }, [data, required, onlyPriced, providers])

  if (!data) return <p className="notice">Loading registrar profiles…</p>

  const fmt = (v: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(v)
  const status = (r: RegistrarProfile) => {
    if (!r.providerId) return { text: 'not compared (no price source)', cls: 'none' }
    if (providers.some((p) => p.id === r.providerId)) return { text: 'prices compared', cls: 'on' }
    const i = inactive.find((p) => p.id === r.providerId)
    return { text: i?.error ?? 'inactive', cls: 'off' }
  }

  return (
    <section>
      <p className="sub">
        What each registrar includes for free, from their official pages (as of {data.asOf}). Every chip links to its
        source. {data.note}
      </p>
      <div className="controls">
        <div className="control">
          <span className="label">Must include for free</span>
          <div className="chips">
            {FEATURES.map((f) => (
              <label key={f.key} className={`chip ${required.includes(f.key) ? 'on' : ''}`}>
                <input
                  type="checkbox"
                  checked={required.includes(f.key)}
                  onChange={() =>
                    setRequired((cur) => (cur.includes(f.key) ? cur.filter((k) => k !== f.key) : [...cur, f.key]))
                  }
                />
                {f.label}
              </label>
            ))}
          </div>
        </div>
        <label className="control inline">
          <input type="checkbox" checked={onlyPriced} onChange={(e) => setOnlyPriced(e.target.checked)} />
          Only registrars with live prices
        </label>
      </div>
      <div className="table-wrap">
        <table className="registrars">
          <thead>
            <tr>
              <th>Registrar</th>
              <th>Budget</th>
              <th>.com over {horizon}y</th>
              {FEATURES.map((f) => (
                <th key={f.key}>{f.short}</th>
              ))}
              <th>Watch out for</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const q = comRow?.quotes.find((x) => x.providerId === r.providerId)
              const s = status(r)
              return (
                <tr key={r.id}>
                  <td className="domain">
                    <strong>
                      {r.website ? (
                        <a href={r.website} target="_blank" rel="noreferrer">
                          {r.name} ↗
                        </a>
                      ) : (
                        r.name
                      )}
                    </strong>
                    <div className={`sub src-${s.cls}`}>{s.text}</div>
                    {r.verification !== 'verified' && (
                      <div className="sub">
                        {r.verification === 'snippet'
                          ? 'site blocks checks, data from search excerpts'
                          : r.verification === 'pending'
                            ? 'not researched yet'
                            : 'partly verified'}
                      </div>
                    )}
                  </td>
                  <td>
                    <BudgetBadge budget={r.budget} />
                    <div className="sub wrap">{r.verdict}</div>
                  </td>
                  <td>
                    {q ? (
                      <>
                        <strong>{fmt(q.totals[String(horizon)])}</strong>
                        <div className="sub">
                          {fmt(q.registration)} then {fmt(q.renewal)}/yr
                        </div>
                        {q.buyUrl && (
                          <a className="offer" href={q.buyUrl} target="_blank" rel="noreferrer">
                            Offer ↗
                          </a>
                        )}
                      </>
                    ) : (
                      <span className="sub">–</span>
                    )}
                  </td>
                  {FEATURES.map((f) => (
                    <td key={f.key}>
                      <FeatureChip feature={r.features[f.key]} />
                      {f.key === 'privacy' && r.privacyCost && (
                        <div className="sub">
                          {r.privacyCost.amount} {r.privacyCost.currency}
                          {r.privacyCost.oneTime ? ' once' : '/yr'}
                        </div>
                      )}
                    </td>
                  ))}
                  <td className="sub wrap">{r.catches ?? '–'}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {rows.length === 0 && <p className="notice">No registrar matches all the selected free features.</p>}
    </section>
  )
}
