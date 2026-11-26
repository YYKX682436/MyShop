import assert from "node:assert/strict"
import test from "node:test"
import { getAfterSaleAnchorDestination } from "./order-after-sale-anchor.ts"

test("legacy after-sale fragments resolve to the order context on the support route", () => {
  assert.equal(getAfterSaleAnchorDestination("#after-sale", "order-1"), "/messages?order=order-1")
  assert.equal(getAfterSaleAnchorDestination("#after-sale", "order&other"), "/messages?order=order%26other")
  assert.equal(getAfterSaleAnchorDestination("#customer-inputs", "order-1"), null)
})
