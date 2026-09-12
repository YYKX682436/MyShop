import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const source = readFileSync(new URL("../components/store/product-card.tsx", import.meta.url), "utf8")

test("product cards stay limited to image, title, price, and a light sold-out state", () => {
  assert.match(source, /aspect-square/)
  assert.match(source, /line-clamp-2/)
  assert.match(source, /formatYuan\(product\.price\)/)
  assert.match(source, /product\.soldOut/)
  assert.doesNotMatch(source, /home\.instantDelivery|home\.sales|home\.stock|home\.inStock|home\.outOfStock/)
  assert.doesNotMatch(source, /product\.delivery_type|sales_count|lowStock/)
  assert.doesNotMatch(source, /<span[^>]+product\.buyNow|t\("product\.buyNow"\)/)
})
