import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

import { getDictionary } from "./i18n.ts"
import { getAfterSaleWaitingLabelKey } from "./after-sale-waiting.ts"

test("after-sale waiting labels use the viewer perspective", () => {
  assert.equal(getAfterSaleWaitingLabelKey("customer", "ADMIN"), "order.afterSaleWaitingForAdmin")
  assert.equal(getAfterSaleWaitingLabelKey("customer", "CUSTOMER"), "order.afterSaleCustomerWaitingForReply")
  assert.equal(getAfterSaleWaitingLabelKey("admin", "ADMIN"), "admin.afterSaleNeedsAttention")
  assert.equal(getAfterSaleWaitingLabelKey("admin", "CUSTOMER"), "admin.afterSaleWaitingForCustomer")
})

test("after-sale waiting labels have the requested Chinese and English copy", () => {
  const cases = [
    ["customer", "ADMIN", "order.afterSaleWaitingForAdmin", "等待商家回复", "Waiting for merchant reply"],
    ["customer", "CUSTOMER", "order.afterSaleCustomerWaitingForReply", "商家已回复 · 待你回复", "Merchant replied · Waiting for your reply"],
    ["admin", "ADMIN", "admin.afterSaleNeedsAttention", "待回复", "Needs reply"],
    ["admin", "CUSTOMER", "admin.afterSaleWaitingForCustomer", "等待买家回复", "Waiting for buyer reply"],
  ]

  for (const [mode, waitingFor, key, zh, en] of cases) {
    assert.equal(getAfterSaleWaitingLabelKey(mode, waitingFor), key)
    assert.equal(getDictionary("zh")[key], zh)
    assert.equal(getDictionary("en")[key], en)
  }
})

test("history card shares the viewer-aware waiting logic between status and prompt", () => {
  const source = readFileSync(new URL("../components/shared/after-sale-history-card.tsx", import.meta.url), "utf8")

  assert.match(source, /statusLabel\(records\[0\], mode, t\)/)
  assert.match(source, /statusLabel\(item, mode, t\)/)
  assert.match(source, /const waitingLabelKey = getAfterSaleWaitingLabelKey\(mode, item\.waiting_for\)/)
  assert.match(source, /t\(waitingLabelKey\)/)
})
