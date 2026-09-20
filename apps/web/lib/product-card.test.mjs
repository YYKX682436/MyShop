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
  assert.match(styles, /\.product-image-stage-image\s*\{[^}]*object-fit:\s*contain;[^}]*padding:\s*9%;/s)
  assert.match(source, /line-clamp-2/)
  assert.match(source, /formatYuan\(product\.price\)/)
  assert.match(source, /product\.soldOut/)
  assert.doesNotMatch(source, /home\.instantDelivery|home\.sales|home\.stock|home\.inStock|home\.outOfStock/)
  assert.doesNotMatch(source, /product\.delivery_type|sales_count|lowStock/)
  assert.doesNotMatch(source, /<span[^>]+product\.buyNow|t\("product\.buyNow"\)/)
})

test("home intro is a compact rounded card and the category label appears once", () => {
  assert.match(homeSource, /className="store-home-hero"/)
  assert.doesNotMatch(homeSource, /store-catalog-kicker/)
  assert.match(homeSource, /<span className="relative z-10">\{t\("home\.allCategories"\)\}<\/span>/)
  assert.match(styles, /\.store-home-hero\s*\{[^}]*overflow:\s*hidden;[^}]*border-radius:\s*var\(--radius-sheet\);/s)
  assert.doesNotMatch(styles, /min-height:\s*clamp\(16rem,\s*38vw,\s*28rem\)/)
  assert.match(styles, /font-size:\s*clamp\(1\.8rem,\s*4vw,\s*3\.2rem\)/)
  assert.match(styles, /\.store-catalog-header\s*\{[^}]*justify-content:\s*flex-start;/s)
})
