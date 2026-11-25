import assert from "node:assert/strict"
import test from "node:test"
import { clampPurchaseQuantity, hasPurchasableStock, purchaseQuantityBounds, stockNoticeKey } from "./product-stock.ts"

test("sync failures and expired snapshots never become out-of-stock zeroes", () => {
  assert.equal(hasPurchasableStock(12, false, "SYNC_FAILED"), false)
  assert.equal(stockNoticeKey("SYNC_FAILED"), "product.stockCheckPending")
  assert.equal(stockNoticeKey("STALE"), "product.stockCheckPending")
  assert.equal(hasPurchasableStock(0, false, "OUT_OF_STOCK"), false)
  assert.equal(stockNoticeKey("OUT_OF_STOCK"), "product.soldOut")
})

test("quantity bounds use buyer units and preserve minimum and maximum", () => {
  const bounds = purchaseQuantityBounds(7, 2, 5, false)
  assert.deepEqual(bounds, { minimum: 2, maximum: 5, selectable: true })
  assert.equal(clampPurchaseQuantity(1, bounds.minimum, bounds.maximum), 2)
  assert.equal(clampPurchaseQuantity(99, bounds.minimum, bounds.maximum), 5)
  assert.deepEqual(purchaseQuantityBounds(1, 2, 5, false), { minimum: 2, maximum: 2, selectable: false })
})
