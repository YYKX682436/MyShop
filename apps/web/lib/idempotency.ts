export interface IdempotencyStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export interface StoredIdempotencyKey {
  key: string
  fingerprint: string
}

/** Stable JSON-like serialization used for checkout intent fingerprints. */
export function stableSerialize(value: unknown): string {
  if (value === undefined) return "undefined"
  if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "undefined"
  if (Array.isArray(value)) return `[${value.map(stableSerialize).join(",")}]`
  const record = value as Record<string, unknown>
  return `{${Object.keys(record).sort().map((key) => `${JSON.stringify(key)}:${stableSerialize(record[key])}`).join(",")}}`
}

/** Small deterministic non-secret fingerprint; the idempotency key remains random. */
export function fingerprintPayload(value: unknown): string {
  const serialized = stableSerialize(value)
  let hash = 2166136261
  for (let index = 0; index < serialized.length; index += 1) {
    hash ^= serialized.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(16).padStart(8, "0")
}

function newKey(): string {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID()
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

/**
 * Return the existing key for the same intent, or persist a new key before a
 * request is sent. A network failure must leave this record untouched.
 */
export function getOrCreateIdempotencyKey(
  storageKey: string,
  payload: unknown,
  storage?: IdempotencyStorage,
): StoredIdempotencyKey {
  const target = storage || (typeof window !== "undefined" ? window.sessionStorage : undefined)
  if (!target) return { key: newKey(), fingerprint: fingerprintPayload(payload) }

  const fingerprint = fingerprintPayload(payload)
  const raw = target.getItem(storageKey)
  if (raw) {
    try {
      const existing = JSON.parse(raw) as StoredIdempotencyKey
      if (existing.key && existing.fingerprint === fingerprint) return existing
    } catch {
      // Replace corrupt client state with a fresh intent record.
    }
  }

  const value = { key: newKey(), fingerprint }
  target.setItem(storageKey, JSON.stringify(value))
  return value
}

/** Clear only the successful request's record; a later intent can start fresh. */
export function clearIdempotencyKey(
  storageKey: string,
  key: string,
  storage?: IdempotencyStorage,
): void {
  const target = storage || (typeof window !== "undefined" ? window.sessionStorage : undefined)
  if (!target) return
  const raw = target.getItem(storageKey)
  if (!raw) return
  try {
    const existing = JSON.parse(raw) as StoredIdempotencyKey
    if (existing.key === key) target.removeItem(storageKey)
  } catch {
    target.removeItem(storageKey)
  }
}
