import type { ReactNode } from "react"

const UUID_PATTERN = /\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b/gi

/** Keep technical identifiers masked when a semantic identifier is unavailable. */
export function maskTechnicalId(value: string | null | undefined) {
  if (!value) return "—"
  return value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value
}

/** Mask UUIDs embedded in historical or user-authored operation-log text. */
export function maskTechnicalIdsInText(value: string | null | undefined) {
  if (!value) return value || ""
  return value.replace(UUID_PATTERN, maskTechnicalId)
}

export function TechnicalId({ value, label }: { value: string | null | undefined; label?: ReactNode }) {
  if (!value) return <span>—</span>
  return <span className="font-mono" aria-label={label ? `${String(label)}（已掩码）` : undefined}>{maskTechnicalId(value)}</span>
}
