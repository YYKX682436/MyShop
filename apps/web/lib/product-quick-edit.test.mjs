import assert from "node:assert/strict"
import test from "node:test"

import {
  buildProductPricesUpdatePayload,
  createProductPriceDraft,
  getProductStockQuickAction,
  isValidMoneyDraft,
  parseIntegerDraft,
  validateProductTitleDraft,
  validateProductPriceDraft,
} from "./product-quick-edit.ts"
import { getAdminProductPriceFocusTarget } from "./product-pricing.ts"

const product = (overrides = {}) => ({
  spec_enabled: false,
  cost_price: 10,
  retail_price: 20,
  stock_mode: "CARD_KEY",
  specs: [],
  ...overrides,
})

test("title drafts share trim, required, and length validation", () => {
  assert.equal(validateProductTitleDraft("  Product  "), null)
  assert.equal(validateProductTitleDraft("   "), "required")
  assert.equal(validateProductTitleDraft("x".repeat(256)), "length")
})

test("ordinary product price draft keeps root cost and retail independent", () => {
  const draft = createProductPriceDraft(product())
  assert.deepEqual(draft, { spec_enabled: false, cost_price: "10", retail_price: "20" })
  assert.deepEqual(buildProductPricesUpdatePayload({ ...draft, cost_price: "12.30", retail_price: "25.00" }), {
    cost_price: 12.3,
    retail_price: 25,
    specs: [],
  })
  assert.deepEqual(validateProductPriceDraft({ ...draft, cost_price: "12.30", retail_price: "25.00" }), { valid: true })
})

test("multi-spec price draft preserves each specification target", () => {
  const draft = createProductPriceDraft(product({
    spec_enabled: true,
    cost_price: null,
    retail_price: 30,
    specs: [
      { id: "spec-a", name: "A", cost_price: 10, retail_price: 20 },
      { id: "spec-b", name: "B", cost_price: 15, retail_price: 30 },
    ],
  }))
  assert.deepEqual(buildProductPricesUpdatePayload({
    ...draft,
    specs: draft.specs.map((spec, index) => ({ ...spec, cost_price: String(index + 11), retail_price: String(index + 21) })),
  }), {
    cost_price: null,
    retail_price: null,
    specs: [
      { spec_id: "spec-a", cost_price: 11, retail_price: 21 },
      { spec_id: "spec-b", cost_price: 12, retail_price: 22 },
    ],
  })
})

test("price target focus points to the root or first specification field", () => {
  assert.deepEqual(getAdminProductPriceFocusTarget(product(), "cost"), { target: "cost", key: "root" })
  assert.deepEqual(getAdminProductPriceFocusTarget(product({
    spec_enabled: true,
    specs: [{ id: "spec-first" }, { id: "spec-second" }],
  }), "retail"), { target: "retail", key: "spec-first" })
})

test("illegal money drafts are rejected before a request is built", () => {
  assert.equal(isValidMoneyDraft("10.123", false), false)
  assert.equal(isValidMoneyDraft("0", true), false)
  assert.deepEqual(validateProductPriceDraft({ spec_enabled: false, cost_price: "-1", retail_price: "20" }), {
    valid: false,
    field: "cost_price",
  })
})

test("weight parsing accepts safe integers only", () => {
  assert.equal(parseIntegerDraft(" -12 "), -12)
  assert.equal(parseIntegerDraft("12.0"), null)
  assert.equal(parseIntegerDraft("1e2"), null)
  assert.equal(parseIntegerDraft("2", 3), null)
})

test("only card-key stock exposes the card-key import quick action", () => {
  assert.equal(getProductStockQuickAction(product({ stock_mode: "CARD_KEY" })), "import-card-keys")
  assert.equal(getProductStockQuickAction(product({ stock_mode: "MANUAL" })), "display-only")
  assert.equal(getProductStockQuickAction(product({ stock_mode: "UNLIMITED" })), "display-only")
})
