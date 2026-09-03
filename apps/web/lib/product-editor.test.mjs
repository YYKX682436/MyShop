import assert from "node:assert/strict"
import test from "node:test"

import {
  buildPricingPreviewDraft,
  getCardKeyImportAvailability,
  getProductEditorTabForMessage,
  reorderSpecs,
  withCurrentSpecOrder,
} from "./product-editor.ts"

test("pricing preview draft contains only price-related data", () => {
  assert.deepEqual(buildPricingPreviewDraft({
    spec_enabled: false,
    cost_price: "12.30",
    retail_price: "",
    specs: [],
    overrides: [{ user_level_id: "level-1", spec_id: null, price: "8.5" }],
  }), {
    cost_price: 12.3,
    retail_price: null,
    spec_enabled: false,
    specs: [],
    overrides: [{ user_level_id: "level-1", spec_id: null, price: 8.5 }],
  })
})

test("partial specification prices remain independent preview targets", () => {
  assert.deepEqual(buildPricingPreviewDraft({
    spec_enabled: true,
    cost_price: "",
    retail_price: "",
    specs: [
      { id: "client-a", cost_price: "10", retail_price: "20" },
      { id: "client-b", cost_price: "15", retail_price: "" },
    ],
    overrides: [{ user_level_id: "level-1", spec_id: "client-a", price: "18" }],
  }).specs, [
    { id: "client-a", cost_price: 10, retail_price: 20 },
    { id: "client-b", cost_price: 15, retail_price: null },
  ])
})

test("spec order can move in either direction without mutating the draft", () => {
  const source = ["a", "b", "c"]
  assert.deepEqual(reorderSpecs(source, 1, -1), ["b", "a", "c"])
  assert.deepEqual(reorderSpecs(source, 1, 1), ["a", "c", "b"])
  assert.deepEqual(reorderSpecs(source, 0, -1), source)
  assert.deepEqual(source, ["a", "b", "c"])
})

test("save payload order is derived from the current array position", () => {
  assert.deepEqual(withCurrentSpecOrder([{ id: "second" }, { id: "first" }]), [
    { id: "second", sort_order: 0 },
    { id: "first", sort_order: 1 },
  ])
})

test("card-key import is only available for a saved clean card-key product", () => {
  assert.equal(getCardKeyImportAvailability(null, "CARD_KEY", false), "save-product")
  assert.equal(getCardKeyImportAvailability({ id: "product-1" }, "CARD_KEY", true), "save-changes")
  assert.equal(getCardKeyImportAvailability({ id: "product-1" }, "CARD_KEY", false), "ready")
  assert.equal(getCardKeyImportAvailability({ id: "product-1" }, "MANUAL", false), "hidden")
})

test("validation messages identify the tab that owns the error", () => {
  assert.equal(getProductEditorTabForMessage("成本价不能为空"), "pricing")
  assert.equal(getProductEditorTabForMessage("规格名称不能为空"), "specs")
  assert.equal(getProductEditorTabForMessage("低库存阈值不能小于 0"), "inventory")
  assert.equal(getProductEditorTabForMessage("商品名称不能为空"), "basic")
})
