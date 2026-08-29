import assert from "node:assert/strict"
import test from "node:test"

import { formatYuan } from "./utils.ts"

test("formatYuan always renders the RMB symbol and two decimals", () => {
  assert.equal(formatYuan(0), "¥0.00")
  assert.equal(formatYuan(12), "¥12.00")
  assert.equal(formatYuan(12.3), "¥12.30")
  assert.equal(formatYuan(12.345), "¥12.35")
})
