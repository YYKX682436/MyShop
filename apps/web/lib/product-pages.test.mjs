import assert from "node:assert/strict"
import test from "node:test"
import { getSitemapProducts } from "./product-pages.ts"
import { getPublicSiteUrl } from "./site-url.ts"

test("sitemap traverses all API pages without exceeding the 100 item limit", async () => {
  const calls = []
  const products = Array.from({ length: 241 }, (_, index) => ({ id: String(index + 1) }))
  const result = await getSitemapProducts(async ({ page, page_size }) => {
    calls.push({ page, page_size })
    return {
      list: products.slice((page - 1) * page_size, page * page_size),
      pagination: { page, page_size, total: products.length },
    }
  })
  assert.deepEqual(result, products)
  assert.deepEqual(calls, [1, 2, 3].map(page => ({ page, page_size: 100 })))
})

test("an empty catalog stops after one page and a failed page is not reported as a complete sitemap", async () => {
  let calls = 0
  assert.deepEqual(await getSitemapProducts(async ({ page, page_size }) => {
    calls += 1
    return { list: [], pagination: { page, page_size, total: 0 } }
  }), [])
  assert.equal(calls, 1)
  await assert.rejects(getSitemapProducts(async () => { throw new Error("backend unavailable") }), /backend unavailable/)
})

test("public URLs prefer runtime config, retain the old fallback, and refuse a production localhost default", () => {
  assert.equal(getPublicSiteUrl({ PUBLIC_SITE_URL: "https://shop.example/", NEXT_PUBLIC_BASE_URL: "https://old.example" }), "https://shop.example")
  assert.equal(getPublicSiteUrl({ NEXT_PUBLIC_BASE_URL: "https://old.example" }), "https://old.example")
  assert.equal(getPublicSiteUrl({ NODE_ENV: "development" }), "http://localhost:3000")
  assert.throws(() => getPublicSiteUrl({ NODE_ENV: "production" }), /PUBLIC_SITE_URL/)
  assert.throws(() => getPublicSiteUrl({ PUBLIC_SITE_URL: "javascript:alert(1)" }), /HTTP/)
})
