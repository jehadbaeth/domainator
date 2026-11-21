import { useState } from 'react'
import type { FeatureKey, ProviderInfo, RegistrarProfile } from './api'
import { BudgetBadge, FEATURES } from './Registrars'

export interface PriceSettings {
  addons: FeatureKey[]
  vatOn: boolean
  vatRate: number
  /** suggest prefixes, suffixes, hyphens and domain hacks besides the searched name */
  variations: boolean
  /** TLDs the searched name is checked on; null = the server's default list */
  tlds: string[] | null
}

/** Defaults give the lowest numbers: net prices, no add-ons. */
export const LOWEST: PriceSettings = { addons: [], vatOn: false, vatRate: 19, variations: true, tlds: null }

export function summary(settings: PriceSettings, used: number, total: number) {
  const parts = [`Comparing ${used} of ${total} registrars`]
  parts.push(settings.vatOn ? `incl. ${settings.vatRate}% VAT where charged` : 'prices excl. VAT')
  parts.push(
    settings.addons.length
      ? `with ${settings.addons.map((a) => FEATURES.find((f) => f.key === a)?.label.toLowerCase()).join(', ')}`
      : 'no add-ons',
  )
  if (!settings.variations) parts.push('no variations')
  if (settings.tlds) parts.push(`${settings.tlds.length} chosen TLDs`)
  return parts.join(' · ')
}

/** Edits the list of TLDs a search checks. */
function TldEditor(props: { tlds: string[]; onChange: (t: string[]) => void; onReset: () => void; custom: boolean }) {
  const { tlds, onChange, onReset, custom } = props
  const [draft, setDraft] = useState('')
  const addDraft = () => {
    const add = draft
      .split(/[\s,]+/)
      .map((t) => t.trim().toLowerCase().replace(/^\./, ''))
      .filter((t) => /^[a-z0-9-]{2,63}(\.[a-z0-9-]{2,63})?$/.test(t) && !tlds.includes(t))
    if (add.length) onChange([...tlds, ...add])
    setDraft('')
  }
  return (
    <div className="tld-editor">
      <div className="chips">
        {tlds.map((t) => (
          <span key={t} className="chip on">
            .{t}
            <button aria-label={`remove .${t}`} onClick={() => onChange(tlds.filter((x) => x !== t))}>
              ×
            </button>
          </span>
        ))}
      </div>
      <div className="row">
        <input
          value={draft}
          placeholder="add e.g. de, ch, shop"
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && addDraft()}
        />
        <button onClick={addDraft}>Add</button>
        {custom && <button onClick={onReset}>Default list</button>}
      </div>
    </div>
  )
}

export function freeAddons(profile: RegistrarProfile | undefined) {
  if (!profile) return []
  return FEATURES.filter((f) => profile.features[f.key]?.value === 'free')
}

export function SettingsPanel(props: {
  providers: ProviderInfo[]
  inactive: ProviderInfo[]
  profiles: Map<string, RegistrarProfile>
  selected: string[]
  onSelected: (ids: string[]) => void
  settings: PriceSettings
  onSettings: (s: PriceSettings) => void
  defaultTlds: string[]
}) {
  const { providers, inactive, profiles, selected, onSelected, settings, onSettings, defaultTlds } = props
  const all = providers.map((p) => p.id)
  const toggle = (id: string) =>
    onSelected(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id])
  const byBudget = all.filter((id) => ['great', 'good'].includes(profiles.get(id)?.budget ?? ''))
  const withFree = (key: FeatureKey) => all.filter((id) => profiles.get(id)?.features[key]?.value === 'free')

  return (
    <div className="settings">
      <div className="settings-col">
        <h3>Registrars to compare</h3>
        <div className="quick">
          <button onClick={() => onSelected(all)}>All</button>
          <button onClick={() => onSelected([])}>None</button>
          <button onClick={() => onSelected(byBudget)} title="Budget verdict great or good">
            Great &amp; good budget
          </button>
          <button onClick={() => onSelected(withFree('privacy'))}>Free privacy</button>
        </div>
        <ul className="provider-list">
          {providers.map((p) => {
            const prof = profiles.get(p.id)
            const free = freeAddons(prof)
            return (
              <li key={p.id}>
                <label>
                  <input type="checkbox" checked={selected.includes(p.id)} onChange={() => toggle(p.id)} />
                  <span className="pname">{p.name}</span>
                  <span className={`dot ${p.status.toLowerCase()}`} title={p.error ?? p.status} />
                  {prof && <BudgetBadge budget={prof.budget} />}
                </label>
                <div className="sub">
                  {free.length ? `free: ${free.map((f) => f.short.toLowerCase()).join(', ')}` : 'no free add-ons known'}
                  {p.kind === 'price page' && ' · read from price page'}
                  {p.kind.includes('live') && ' · live quotes'}
                </div>
              </li>
            )
          })}
        </ul>
        {inactive.length > 0 && (
          <details className="inactive">
            <summary>{inactive.length} more need API keys in .env</summary>
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
      </div>

      <div className="settings-col">
        <h3>Search</h3>
        <label className="row">
          <input
            type="checkbox"
            checked={settings.variations}
            onChange={(e) => onSettings({ ...settings, variations: e.target.checked })}
          />
          Suggest name variations (prefixes, suffixes, hyphens, domain hacks)
        </label>
        <p className="sub wrap">Your name is always checked on every TLD below, taken or not.</p>
        <TldEditor
          tlds={settings.tlds ?? defaultTlds}
          custom={settings.tlds !== null}
          onChange={(t) => onSettings({ ...settings, tlds: t })}
          onReset={() => onSettings({ ...settings, tlds: null })}
        />

        <h3>VAT</h3>
        <label className="row">
          <input
            type="checkbox"
            checked={settings.vatOn}
            onChange={(e) => onSettings({ ...settings, vatOn: e.target.checked })}
          />
          Show prices including VAT
        </label>
        <label className="row">
          Rate
          <input
            type="number"
            min={0}
            max={50}
            value={settings.vatRate}
            disabled={!settings.vatOn}
            onChange={(e) => onSettings({ ...settings, vatRate: Math.max(0, Math.min(50, Number(e.target.value) || 0)) })}
          />
          %
        </label>
        <p className="sub wrap">
          Off shows net prices, the lowest. On adds the rate for registrars known to charge EU VAT. For the others
          (mostly US registrars) it's unknown whether VAT is charged, and their cells say so.
        </p>

        <h3>Add-ons I need</h3>
        <p className="sub wrap">
          Free ones cost nothing. Paid ones are added to every year of that registrar's price. A registrar that
          doesn't offer one, or offers only GDPR redaction instead of privacy, can't be "Best".
        </p>
        <ul className="addon-list">
          {FEATURES.map((f) => (
            <li key={f.key}>
              <label>
                <input
                  type="checkbox"
                  checked={settings.addons.includes(f.key)}
                  onChange={() =>
                    onSettings({
                      ...settings,
                      addons: settings.addons.includes(f.key)
                        ? settings.addons.filter((a) => a !== f.key)
                        : [...settings.addons, f.key],
                    })
                  }
                />
                {f.label}
                <span className="sub"> · free at {withFree(f.key).length} of {all.length}</span>
              </label>
            </li>
          ))}
        </ul>
        <button className="reset" onClick={() => onSettings(LOWEST)}>
          Reset to lowest prices
        </button>
      </div>
    </div>
  )
}
