import assert from "node:assert/strict"
import test from "node:test"

import { formatAdminOrderNumber, formatAdminOrderProductMeta, limitAdminOrderProductItems } from "./admin-order-display.ts"
import { buildAdminOrderSearchBody } from "./admin-order-search.ts"

test("admin order search sends defaults and all filters in the JSON body", () => {
  assert.deepEqual(
    JSON.parse(JSON.stringify(buildAdminOrderSearchBody({
      page: 3,
      page_size: 20,
      keyword: "  card-content  ",
      status: " PAID ",
      created_from: "2026-09-01",
      created_to: "2026-09-02",
      sort_direction: "asc",
    }))),
    {
      page: 3,
      page_size: 20,
      keyword: "card-content",
      status: "PAID",
      created_from: "2026-09-01",
      created_to: "2026-09-02",
      sort_direction: "asc",
    },
  )
  assert.equal(buildAdminOrderSearchBody().sort_direction, "desc")
})

test("admin order display keeps the numeric order number and compactly limits products", () => {
  assert.equal(formatAdminOrderNumber(1000000001), "1000000001")
  assert.notEqual(formatAdminOrderNumber(1000000001), "11111111-1111-1111-1111-111111111111")

  const items = [
    { product_title: "商品 A", product_no: 12, spec_name: "标准", quantity: 2 },
    { product_title: "商品 B", product_no: 13, spec_name: null, quantity: 1 },
    { product_title: "商品 C", product_no: 14, spec_name: "高级", quantity: 3 },
  ]
  const summary = limitAdminOrderProductItems(items)
  assert.equal(summary.visible.length, 2)
  assert.equal(summary.additionalCount, 1)
  assert.equal(formatAdminOrderProductMeta(items[0]), "商品 #12 · 标准 · ×2")
  assert.equal(formatAdminOrderProductMeta(items[1]), "商品 #13 · 无规格 · ×1")
})
