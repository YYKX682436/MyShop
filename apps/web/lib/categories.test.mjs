import assert from "node:assert/strict"
import test from "node:test"

import {
  findCategory,
  flattenCategories,
  getCategoryPath,
  getCategorySelection,
  getProductCategoryPathLabel,
  matchesCategory,
} from "./categories.ts"

const categories = [
  {
    id: "root-a",
    name: "软件",
    sort_order: 1,
    parent_id: null,
    children: [
      { id: "child-a1", name: "AI 工具", sort_order: 1, parent_id: "root-a", children: [] },
      { id: "child-a2", name: "设计工具", sort_order: 2, parent_id: "root-a", children: [] },
    ],
  },
  {
    id: "root-b",
    name: "账号",
    sort_order: 2,
    parent_id: null,
    children: [
      { id: "child-b1", name: "AI 工具", sort_order: 1, parent_id: "root-b", children: [] },
    ],
  },
]

test("finds root and child nodes and derives their selection", () => {
  assert.equal(findCategory(categories, "root-a").name, "软件")
  assert.equal(findCategory(categories, "child-a1").name, "AI 工具")
  assert.deepEqual(getCategorySelection(categories, "root-a"), { primaryId: "root-a", secondaryId: "" })
  assert.deepEqual(getCategorySelection(categories, "child-a1"), { primaryId: "root-a", secondaryId: "child-a1" })
})

test("root and child products have the correct category path", () => {
  assert.deepEqual(getCategoryPath(categories, "root-a").map((category) => category.name), ["软件"])
  assert.deepEqual(getCategoryPath(categories, "child-a1").map((category) => category.name), ["软件", "AI 工具"])
  assert.equal(getProductCategoryPathLabel({ category_id: "child-a1" }, categories), "软件 / AI 工具")
})

test("root matching includes direct products and every direct child", () => {
  assert.equal(matchesCategory("root-a", "root-a", categories), true)
  assert.equal(matchesCategory("child-a1", "root-a", categories), true)
  assert.equal(matchesCategory("child-a2", "root-a", categories), true)
  assert.equal(matchesCategory("child-b1", "root-a", categories), false)
})

test("child matching is exact and an old child is invalid after switching roots", () => {
  assert.equal(matchesCategory("child-a1", "child-a1", categories), true)
  assert.equal(matchesCategory("child-a2", "child-a1", categories), false)
  assert.equal(matchesCategory("child-a1", "root-b", categories), false)
})

test("flatten preserves root and child grouping order", () => {
  assert.deepEqual(flattenCategories(categories).map(({ category, depth }) => [category.id, depth]), [
    ["root-a", 0], ["child-a1", 1], ["child-a2", 1], ["root-b", 0], ["child-b1", 1],
  ])
})
