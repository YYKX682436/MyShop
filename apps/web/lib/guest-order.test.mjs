import assert from "node:assert/strict"
import test from "node:test"
import { canApplyForAfterSale } from "./guest-order.ts"

test("only registered paid or delivered orders offer in-app after-sale", () => {
  for (const status of ["PAID", "DELIVERED"]) {
    assert.equal(canApplyForAfterSale(status, false), true)
    assert.equal(canApplyForAfterSale(status, true), false)
    assert.equal(canApplyForAfterSale(status, undefined), false)
  }
  assert.equal(canApplyForAfterSale("PENDING", false), false)
})
