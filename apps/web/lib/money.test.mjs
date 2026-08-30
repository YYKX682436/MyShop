import assert from "node:assert/strict"
import test from "node:test"

import {
  MAX_BALANCE_CENTS,
  parsePositiveMoneyToCents,
  formatCents,
} from "./money.ts"

test("strict positive money parsing uses integer cents", () => {
  for (const [raw, expected] of [
    ["0.01", 1],
    ["0.07", 7],
    ["0.29", 29],
    ["0.58", 58],
    ["2.55", 255],
    ["10", 1000],
    ["10.5", 1050],
    ["10.50", 1050],
  ]) {
    assert.equal(parsePositiveMoneyToCents(raw), expected, raw)
  }
})

test("zero, negative, and more than two decimals are invalid", () => {
  for (const raw of ["0", "0.00", "-0.01", "-1", "1.234", "10.000"]) {
    assert.equal(parsePositiveMoneyToCents(raw), null, raw)
  }
})

test("maximum matches the backend balance limit", () => {
  assert.equal(parsePositiveMoneyToCents("9999999999.99"), MAX_BALANCE_CENTS)
  assert.equal(parsePositiveMoneyToCents("10000000000.00"), null)
})

test("integer cents format back to yuan without floating point", () => {
  assert.equal(formatCents(1), "0.01")
  assert.equal(formatCents(29), "0.29")
  assert.equal(formatCents(58), "0.58")
  assert.equal(formatCents(255), "2.55")
  assert.equal(formatCents(-255), "-2.55")
})
