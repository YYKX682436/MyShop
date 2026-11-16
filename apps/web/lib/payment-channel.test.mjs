import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { test } from "node:test"

test("the storefront selector keeps the existing SVG logos and optional display fields", () => {
  const selector = readFileSync(new URL("../components/shared/payment-selector.tsx", import.meta.url), "utf8")
  const icon = readFileSync(new URL("../components/shared/payment-icon.tsx", import.meta.url), "utf8")

  assert.match(selector, /PaymentIcon method=\{ch\.type\}/)
  assert.match(selector, /ch\.badge_text\?\.trim\(\)/)
  assert.match(selector, /ch\.payment_hint\?\.trim\(\)/)
  assert.match(icon, /ali\.svg/)
  assert.match(icon, /wechat\.svg/)
  assert.match(icon, /m === "wxpay"/)
})
