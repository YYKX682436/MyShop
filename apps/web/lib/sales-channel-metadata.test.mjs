import assert from "node:assert/strict"
import test from "node:test"

import {
  normalizeXianyuAddressTip,
  normalizeXianyuCategoryCandidate,
  normalizeXianyuCategoryProperty,
} from "./sales-channel-metadata.ts"

test("address metadata accepts the Agent snake_case response and preserves picker values", () => {
  assert.deepEqual(normalizeXianyuAddressTip({
    id: "poi-1",
    name: "示例商圈",
    district: "朝阳区",
    address: "示例路 1 号",
    search_keyword: "示例商圈",
    expected_text: "示例路",
  }), {
    id: "poi-1",
    name: "示例商圈",
    district: "朝阳区",
    adcode: null,
    location: null,
    address: "示例路 1 号",
    city: null,
    searchKeyword: "示例商圈",
    expectedText: "示例路",
  })
})

test("category candidates normalize both naming styles and require the real publish fields", () => {
  const snake = normalizeXianyuCategoryCandidate({
    cat_id: "1001",
    cat_name: "软件服务",
    channel_cat_id: "2001",
    channel_cat_name: "软件服务",
    tb_cat_id: "3001",
    leaf_id: null,
    is_selected: true,
    path: [{ id: "100", name: "数码" }, { id: "1001", name: "软件服务" }],
  })
  assert.equal(snake.canPublish, true)
  assert.equal(snake.pathLabel, "数码 › 软件服务")
  assert.equal(snake.isSelected, true)
  assert.deepEqual(snake.incompleteFields, [])

  const incomplete = normalizeXianyuCategoryCandidate({ catId: "1001", channelCatId: "2001" })
  assert.equal(incomplete.canPublish, false)
  assert.deepEqual(incomplete.incompleteFields, ["channel_cat_name", "tb_cat_id"])
})

test("dynamic properties normalize snake_case options and multiselect metadata", () => {
  const property = normalizeXianyuCategoryProperty({
    property_id: "brand",
    property_name: "品牌",
    input_word: "搜索或输入品牌",
    is_multiple: true,
    options: [{ property_id: "brand", property_name: "品牌", value_id: "acme", value_name: "ACME" }],
  })
  assert.equal(property.propertyId, "brand")
  assert.equal(property.inputWord, "搜索或输入品牌")
  assert.equal(property.isMultiple, true)
  assert.equal(property.options?.[0]?.valueId, "acme")
  assert.equal(property.options?.[0]?.valueName, "ACME")
})
