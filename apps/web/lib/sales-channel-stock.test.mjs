import assert from "node:assert/strict"
import test from "node:test"
import { isValidStockCapPerSku, isValidUnlimitedStockQuantity } from "./sales-channel-stock.ts"

test("unlimited stock accepts zero and positive integers", () => {
  assert.equal(isValidUnlimitedStockQuantity(0), true)
  assert.equal(isValidUnlimitedStockQuantity(999), true)
})

test("unlimited stock rejects negative, fractional, and non-finite numbers", () => {
  assert.equal(isValidUnlimitedStockQuantity(-1), false)
  assert.equal(isValidUnlimitedStockQuantity(0.5), false)
  assert.equal(isValidUnlimitedStockQuantity(Number.NaN), false)
  assert.equal(isValidUnlimitedStockQuantity(Number.POSITIVE_INFINITY), false)
})

test("an empty optional field remains valid", () => {
  assert.equal(isValidUnlimitedStockQuantity(null), true)
})

test("per-SKU channel stock cap accepts blank, zero, and the supported integer range", () => {
  assert.equal(isValidStockCapPerSku(null), true)
  assert.equal(isValidStockCapPerSku(0), true)
  assert.equal(isValidStockCapPerSku(1), true)
  assert.equal(isValidStockCapPerSku(999999), true)
})

test("per-SKU channel stock cap rejects values outside 0..999999", () => {
  assert.equal(isValidStockCapPerSku(-1), false)
  assert.equal(isValidStockCapPerSku(1000000), false)
  assert.equal(isValidStockCapPerSku(0.5), false)
  assert.equal(isValidStockCapPerSku(Number.NaN), false)
  assert.equal(isValidStockCapPerSku(Number.POSITIVE_INFINITY), false)
})
