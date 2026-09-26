import { useId, useRef, useState, type DragEvent, type ReactNode } from 'react'
import { LuCheck, LuImage } from 'react-icons/lu'
import { CharCount } from './controls'

/*
 * The campaign builder's shared form parts: a labelled field, a card, the banner drop
 * zone. One set, so every step's fields look, label and report errors the same way.
 */

export const BANNER_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
export const BANNER_MAX_BYTES = 2 * 1024 * 1024

/** The id a field's control carries, so errors can move focus to it. */
export const fieldId = (name: string) => `campaign-${name}`

export function Required() {
  return (
    <>
      <span aria-hidden="true" className="text-admin-red-text">*</span>
      <span className="sr-only">(required)</span>
    </>
  )
}

export function Card({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id}
             className="rounded-admin-card border border-admin-line bg-white p-3 shadow-admin-card">
      <h3 id={id} className="mb-2 text-[12.5px] font-bold text-admin-ink">{title}</h3>
      {children}
    </section>
  )
}

export const controlClass =
  'w-full rounded-admin-control border border-[#E3E5E9] bg-white px-3 text-[13px] text-admin-ink ' +
  'placeholder:text-[#9CA0A8] transition-shadow focus:border-admin-red focus:outline-none ' +
  'focus:ring-2 focus:ring-admin-red/20 aria-[invalid=true]:border-admin-red-text'

/**
 * A labelled field with its helper, counter and error wired to the control.
 *
 * <p>The label is a real {@code <label for>}, the helper and counter are attached with
 * aria-describedby, and an error sets aria-invalid and is read out with the field, so a
 * screen reader hears "Email Subject, required, invalid, Keep the subject to 100
 * characters" rather than only seeing a red border.
 */
export function TextField({
  name, label, value, onChange, required = false, helper, error, placeholder,
  counter, counterBelow = false, multiline = false, type = 'text', min,
}: {
  name: string
  label: string
  value: string
  onChange: (value: string) => void
  required?: boolean
  helper?: string
  error?: string
  placeholder?: string
  counter?: number
  counterBelow?: boolean
  multiline?: boolean
  type?: 'text' | 'date'
  min?: string
}) {
  const id = fieldId(name)
  const described = [
    helper && `${id}-helper`,
    counter && `${id}-count`,
    error && `${id}-error`,
  ].filter(Boolean).join(' ') || undefined
  const count = counter ? <CharCount id={`${id}-count`} value={value} limit={counter} /> : null

  return (
    <div>
      <div className="mb-1 flex items-baseline justify-between gap-3">
        <label htmlFor={id} className="text-[13px] font-medium text-admin-ink">
          {label} {required && <Required />}
        </label>
        {!counterBelow && count}
      </div>
      {multiline ? (
        <textarea
          id={id}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          rows={3}
          aria-required={required || undefined}
          aria-invalid={Boolean(error) || undefined}
          aria-describedby={described}
          className={`${controlClass} min-h-[68px] resize-y py-2 leading-relaxed`}
        />
      ) : (
        <input
          id={id}
          type={type}
          value={value}
          min={min}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          aria-required={required || undefined}
          aria-invalid={Boolean(error) || undefined}
          aria-describedby={described}
          className={`${controlClass} h-[31px]`}
        />
      )}
      {(helper || (counterBelow && count)) && (
        <div className="mt-1 flex items-start justify-between gap-3">
          {helper ? <p id={`${id}-helper`} className="text-[11px] text-admin-muted">{helper}</p> : <span />}
          {counterBelow && count}
        </div>
      )}
      {error && (
        <p id={`${id}-error`} className="mt-1 text-[12px] font-medium text-admin-red-text">{error}</p>
      )}
    </div>
  )
}

/**
 * The banner drop zone. A real file input, visually hidden, inside the label that is the
 * drop zone, so it is reachable by keyboard and opens the picker on Enter or Space.
 */
export function BannerDrop({ busy, hasBanner, onFile }: {
  busy: boolean
  hasBanner: boolean
  onFile: (file: File) => void
}) {
  const [over, setOver] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  const drop = (event: DragEvent) => {
    event.preventDefault()
    setOver(false)
    const file = event.dataTransfer.files[0]
    if (file) onFile(file)
  }

  return (
    <label
      onDragOver={(e) => { e.preventDefault(); setOver(true) }}
      onDragLeave={() => setOver(false)}
      onDrop={drop}
      className={`flex min-h-[92px] cursor-pointer flex-col items-center justify-center rounded-admin-control
                  border border-dashed px-2 py-3 text-center transition-colors focus-within:ring-2
                  focus-within:ring-admin-red focus-within:ring-offset-2
                  ${over ? 'border-admin-red bg-admin-pink' : 'border-[#CBD0D8] hover:bg-admin-page'}`}
    >
      <input
        ref={input}
        type="file"
        accept={BANNER_TYPES.join(',')}
        className="sr-only"
        disabled={busy}
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) onFile(file)
          // Choosing the same file twice should upload twice.
          e.target.value = ''
        }}
      />
      {hasBanner && !busy
        ? <LuCheck aria-hidden="true" className="h-5 w-5 text-[#12692F]" />
        : <LuImage aria-hidden="true" className="h-5 w-5 text-admin-ink" />}
      <span className="mt-1.5 text-[12px] font-semibold text-admin-ink">
        {busy ? 'Uploading…' : hasBanner ? 'Banner added — click to replace' : 'Click to upload banner'}
      </span>
      <span className="mt-0.5 text-[10.5px] text-admin-muted">Recommended size: 1200 x 600 px (JPG/PNG)</span>
    </label>
  )
}
