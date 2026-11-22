import assert from "node:assert/strict"
import test from "node:test"
import { internalPageMetadata } from "./page-metadata.ts"

test("internal page titles use the site name resolved for each request", async () => {
  const main = await internalPageMetadata("购买记录", Promise.resolve({ openGraph: { siteName: "主站" } }))
  const subsite = await internalPageMetadata("购买记录", Promise.resolve({ openGraph: { siteName: "分站" } }))
  assert.equal(main.title.absolute, "购买记录 | 主站")
  assert.equal(subsite.title.absolute, "购买记录 | 分站")
  assert.equal(subsite.openGraph.siteName, "分站")
  assert.equal(subsite.openGraph.title, "购买记录 | 分站")
  assert.equal(subsite.twitter.title, "购买记录 | 分站")
})

test("internal page titles have a neutral fallback when site metadata is unavailable", async () => {
  const page = await internalPageMetadata("消息中心", Promise.resolve({ openGraph: null }))
  assert.equal(page.title.absolute, "消息中心 | 商城")
})
