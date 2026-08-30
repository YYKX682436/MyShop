import assert from "node:assert/strict"
import { test } from "node:test"

import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from "./idempotency.ts"

class MemoryStorage {
  constructor() {
    this.values = new Map()
  }

  getItem(key) {
    return this.values.get(key) ?? null
  }

  setItem(key, value) {
    this.values.set(key, value)
  }

  removeItem(key) {
    this.values.delete(key)
  }
}

test("reuses a direct-order key for the same intent and rotates after a material change", () => {
  const storage = new MemoryStorage()
  const first = getOrCreateIdempotencyKey("direct-order", { productId: "p1", quantity: 1 }, storage)
  const replay = getOrCreateIdempotencyKey("direct-order", { quantity: 1, productId: "p1" }, storage)
  const changed = getOrCreateIdempotencyKey("direct-order", { productId: "p1", quantity: 2 }, storage)

  assert.deepEqual(replay, first)
  assert.notEqual(changed.key, first.key)
  assert.notEqual(changed.fingerprint, first.fingerprint)
})

test("only a successful request clears the matching persisted key", () => {
  const storage = new MemoryStorage()
  const intent = getOrCreateIdempotencyKey("direct-order", { productId: "p1" }, storage)

  // A network error does not call clearIdempotencyKey, so a retry sees the same key.
  assert.deepEqual(
    getOrCreateIdempotencyKey("direct-order", { productId: "p1" }, storage),
    intent,
  )

  clearIdempotencyKey("direct-order", "different-key", storage)
  assert.deepEqual(
    getOrCreateIdempotencyKey("direct-order", { productId: "p1" }, storage),
    intent,
  )

  clearIdempotencyKey("direct-order", intent.key, storage)
  const next = getOrCreateIdempotencyKey("direct-order", { productId: "p1" }, storage)
  assert.notEqual(next.key, intent.key)
})
