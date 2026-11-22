import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const source = readFileSync(new URL("../components/store/product-card.tsx", import.meta.url), "utf8")
const styles = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8")
const homeSource = readFileSync(new URL("../app/(store)/home-content.tsx", import.meta.url), "utf8")

test("product cards stay limited to image, title, price, and a light sold-out state", () => {
  assert.match(source, /product-image-stage product-image-stage--card/)
  assert.match(source, /className="product-image-stage-image"/)
  assert.doesNotMatch(source, /object-cover/)
  assert.match(styles, /\.product-image-stage\s*\{[^}]*aspect-ratio:\s*1;[^}]*background:\s*transparent;/s)
  assert.match(styles, /\.product-image-stage-image\s*\{[^}]*object-fit:\s*contain;/s)
  assert.doesNotMatch(styles.match(/\.product-image-stage-image\s*\{[^}]*\}/s)?.[0] ?? "", /padding/)
  assert.match(source, /line-clamp-2/)
  assert.match(source, /formatYuan\(product\.price\)/)
  assert.match(source, /product\.soldOut/)
  assert.doesNotMatch(source, /home\.instantDelivery|home\.sales|home\.stock|home\.inStock|home\.outOfStock/)
  assert.doesNotMatch(source, /product\.delivery_type|sales_count|lowStock/)
  assert.doesNotMatch(source, /<span[^>]+product\.buyNow|t\("product\.buyNow"\)/)
})

test("mobile home uses a compact intro and the category label appears once", () => {
  assert.match(homeSource, /className="store-home-hero"/)
  assert.doesNotMatch(homeSource, /store-catalog-kicker/)
  assert.match(homeSource, /<span className="relative z-10">\{t\("home\.allCategories"\)\}<\/span>/)
  assert.match(homeSource, /store-home-brand/)
  assert.match(styles, /@media \(max-width: 767px\)[\s\S]*?\.store-home-hero\s*\{[^}]*border:\s*0;[^}]*padding:\s*0;/)
  assert.match(styles, /@media \(max-width: 767px\)[\s\S]*?\.product-card\s*\{[^}]*border:\s*1px solid/)
  assert.match(styles, /\.store-catalog-header\s*\{[^}]*justify-content:\s*flex-start;/s)
})
