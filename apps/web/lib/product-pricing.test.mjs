import assert from "node:assert/strict"
import test from "node:test"

import { getAdminProductPriceRange, getAdminProductPriceRanges } from "./product-pricing.ts"

const spec = (name, cost, retail, { visible = true, deleted = 0 } = {}) => ({
  id: name,
  name,
  cost_price: cost,
  retail_price: retail,
  is_visible: visible,
  is_deleted: deleted,
})

test("single visible spec supplies both price values", () => {
  assert.deepEqual(getAdminProductPriceRanges({
    spec_enabled: true,
    cost_price: null,
    retail_price: 999,
    specs: [spec("A", 30, 50)],
  }), { cost: { min: 30, max: 30 }, retail: { min: 50, max: 50 } })
})

test("multiple specs with equal prices collapse each range to one value", () => {
  assert.deepEqual(getAdminProductPriceRanges({
    spec_enabled: true,
    cost_price: null,
    retail_price: 999,
    specs: [spec("A", 30, 50), spec("B", 30, 50)],
  }), { cost: { min: 30, max: 30 }, retail: { min: 50, max: 50 } })
})

test("different spec costs and retail prices produce matching ranges", () => {
  assert.deepEqual(getAdminProductPriceRanges({
    spec_enabled: true,
    cost_price: null,
    retail_price: 50,
    specs: [spec("A", 30, 50), spec("B", 80, 120), spec("C", 100, 150)],
  }), { cost: { min: 30, max: 100 }, retail: { min: 50, max: 150 } })
})

test("cost and retail targets select their own display ranges", () => {
  const product = {
    spec_enabled: true,
    cost_price: null,
    retail_price: 999,
    specs: [spec("A", 17, 17), spec("B", 37, 34)],
  }
  assert.deepEqual(getAdminProductPriceRange(product, "cost"), { min: 17, max: 37 })
  assert.deepEqual(getAdminProductPriceRange(product, "retail"), { min: 17, max: 34 })
})

test("visible undeleted specs are the one shared effective set", () => {
  assert.deepEqual(getAdminProductPriceRanges({
    spec_enabled: true,
    cost_price: null,
    retail_price: 1,
    specs: [
      spec("visible", 30, 50),
      spec("hidden", 800, 900, { visible: false }),
      spec("deleted", 1, 2, { deleted: 1 }),
    ],
  }), { cost: { min: 30, max: 30 }, retail: { min: 50, max: 50 } })
})

test("when every undeleted spec is hidden, both ranges use all undeleted specs", () => {
  assert.deepEqual(getAdminProductPriceRanges({
    spec_enabled: true,
    cost_price: null,
    retail_price: 1,
    specs: [
      spec("A", 30, 50, { visible: false }),
      spec("B", 100, 150, { visible: false }),
      spec("deleted", 1000, 2000, { visible: false, deleted: 1 }),
    ],
  }), { cost: { min: 30, max: 100 }, retail: { min: 50, max: 150 } })
})

test("products without specs use root cost and retail prices", () => {
  assert.deepEqual(getAdminProductPriceRanges({
    spec_enabled: false,
    cost_price: 30,
    retail_price: 50,
    specs: [spec("ignored", 800, 900)],
  }), { cost: { min: 30, max: 30 }, retail: { min: 50, max: 50 } })
})

test("missing effective prices return no data instead of a misleading zero", () => {
  assert.deepEqual(getAdminProductPriceRanges({
    spec_enabled: true,
    cost_price: null,
    retail_price: 1,
    specs: [],
  }), { cost: null, retail: null })
})
