import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const source = readFileSync(new URL("../app/(store)/product/[id]/product-purchase-dock.tsx", import.meta.url), "utf8")

test("the purchase Dock is the only entry point and cannot open for a sold-out selection", () => {
  assert.match(source, /<Drawer[\s\S]*<DrawerTrigger asChild>/)
  assert.match(source, /disabled=\{isOutOfStock\}/)
  assert.match(source, /if \(isOutOfStock\) return/)
  assert.match(source, /t\("product\.soldOut"\)/)
})

test("the purchase panel keeps the existing customer, Turnstile, and order flow", () => {
  assert.match(source, /validateCustomerInputs\(/)
  assert.match(source, /<Turnstile /)
  assert.match(source, /orderApi\.create\(/)
  assert.match(source, /idempotency_key: intent\.key/)
  assert.match(source, /setTurnstileHeaders\(turnstileToken\)/)
})
