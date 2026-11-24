import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const source = readFileSync(new URL("../app/(store)/product/[id]/product-purchase-dock.tsx", import.meta.url), "utf8")

test("the purchase Dock is the only entry point and cannot open before auth or for a sold-out selection", () => {
  assert.match(source, /<Dialog\.Root open=\{open\} onOpenChange=\{handleOpenChange\}>/)
  assert.match(source, /<Dialog\.Trigger asChild>/)
  assert.match(source, /const \{ user, setUser, authLoaded \} = useAuth\(\)/)
  assert.match(source, /isOutOfStock \|\| !authLoaded/)
  assert.match(source, /if \(isOutOfStock \|\| !authLoaded\) return/)
  assert.match(source, /setSelectedPaymentMethod\(getDefaultPaymentMethod\(balanceAvailability\)\)/)
  assert.match(source, /t\("product\.soldOut"\)/)
})

test("the purchase panel keeps the existing customer, Turnstile, and order flow", () => {
  assert.match(source, /validateCustomerInputs\(/)
  assert.match(source, /<Turnstile /)
  assert.match(source, /orderApi\.create\(/)
  assert.match(source, /idempotency_key: intent\.key/)
  assert.match(source, /setTurnstileHeaders\(turnstileToken\)/)
  assert.equal((source.match(/expected_unit_price: Number\(currentPrice\.toFixed\(2\)\)/g) ?? []).length, 2)
  assert.match(source, /if \(submittingRef\.current\) return/)
  assert.match(source, /router\.push\(buildOrderPageHref\(result\.order\.id\)\)/)
})

test("the dialog and closed entry share one action row without keeping a closed modal mounted", () => {
  assert.match(source, /aria-hidden=\{open\}\s+inert=\{open\}/)
  assert.match(source, /open \? "hidden" : "flex"/)
  assert.equal((source.match(/<PurchaseActionBar \{\.\.\.actionBarProps\}/g) ?? []).length, 2)
  assert.match(source, /type=\{open \? "submit" : "button"\}/)
  assert.match(source, /formatYuan\(totalPrice\)/)
  assert.doesNotMatch(source, /DrawerContent|forceMount|AnimatePresence|layoutId/)
})
