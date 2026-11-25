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
  assert.match(source, /t\(stockNoticeKey\(product\.stock_status\)\)/)
  assert.doesNotMatch(source, /home\.instantDelivery|home\.sales|home\.stock|home\.inStock|home\.outOfStock/)
  assert.doesNotMatch(source, /product\.delivery_type|sales_count|lowStock/)
  assert.doesNotMatch(source, /<span[^>]+product\.buyNow|t\("product\.buyNow"\)/)
})

test("home begins with catalog and preview stages crop without stretching", () => {
  assert.doesNotMatch(homeSource, /store-home-hero|siteSlogan|siteDescription|siteName/)
  assert.match(homeSource, /<section className="store-catalog"/)
  assert.match(styles, /\.product-image-stage--card \.product-image-stage-image,[\s\S]*?object-fit:\s*cover;[\s\S]*?object-position:\s*center top;/)
  assert.match(styles, /@media \(min-width: 1024px\)[\s\S]*?\.product-image-stage--detail\s*\{[^}]*aspect-ratio:\s*4\s*\/\s*5;/)
  assert.match(styles, /@media \(max-width: 1023px\)[\s\S]*?\.product-detail-layout\s*\{[^}]*grid-template-columns:\s*clamp\(110px, 34vw, 145px\)/)
  assert.match(styles, /@media \(max-width: 767px\)[\s\S]*?\.product-card\s*\{[^}]*border:\s*1px solid/)
})
