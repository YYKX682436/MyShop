import assert from "node:assert/strict"
import test from "node:test"

import { deriveOrderDisplayStatus } from "./order-status.ts"

const manualWaiting = { delivery_type: "MANUAL", stock_mode: "MANUAL", fulfillment_status: "WAITING_MANUAL" }
const autoWaiting = { delivery_type: "AUTO", stock_mode: "CARD_KEY", fulfillment_status: "PENDING" }
const delivered = { delivery_type: "MANUAL", stock_mode: "MANUAL", fulfillment_status: "DELIVERED" }

test("derives payment and fulfillment display states from order items", () => {
  assert.equal(deriveOrderDisplayStatus("PENDING", [manualWaiting]), "PENDING")
  assert.equal(deriveOrderDisplayStatus("PAID", [manualWaiting]), "WAITING_MANUAL")
  assert.equal(deriveOrderDisplayStatus("PAID", [autoWaiting]), "WAITING_AUTO")
  assert.equal(deriveOrderDisplayStatus("PAID", [manualWaiting, autoWaiting]), "PROCESSING")
  assert.equal(deriveOrderDisplayStatus("DELIVERED", [delivered]), "DELIVERED")
  assert.equal(deriveOrderDisplayStatus("EXPIRED", [manualWaiting]), "EXPIRED")
  assert.equal(deriveOrderDisplayStatus("REFUNDED", [manualWaiting]), "REFUNDED")
  assert.equal(deriveOrderDisplayStatus("AFTER_SALE", [manualWaiting]), "AFTER_SALE")
})

test("does not classify an incomplete paid order as completed", () => {
  assert.equal(deriveOrderDisplayStatus("PAID", [delivered]), "PROCESSING")
  assert.equal(deriveOrderDisplayStatus("PAID"), "PROCESSING")
})
