import assert from "node:assert/strict"
import test from "node:test"
import { balanceTransactionLabel } from "./balance-transaction.ts"

test("subsite balance entries have distinct labels and unknown types do not become refunds", () => {
  const translate = key => key
  assert.equal(balanceTransactionLabel("SUBSITE_OPENING", translate), "admin.balanceSubsiteOpening")
  assert.equal(balanceTransactionLabel("SUBSITE_PROFIT", translate), "admin.balanceSubsiteProfit")
  assert.equal(balanceTransactionLabel("SUBSITE_PROFIT_REVERSAL", translate), "admin.balanceSubsiteProfitReversal")
  assert.equal(balanceTransactionLabel("FUTURE_TYPE", translate), "FUTURE_TYPE")
})
