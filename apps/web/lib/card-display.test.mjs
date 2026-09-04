import assert from "node:assert/strict"
import test from "node:test"

import { formatCardContent, formatCardContents } from "./card-display.ts"

test("a card keeps its content parts on one tab-separated line", () => {
  assert.equal(formatCardContent(["账号", "密码", "备注"]), "账号\t密码\t备注")
})

test("multiple cards are separated by newlines without flattening parts", () => {
  assert.equal(
    formatCardContents([
      { content_parts: ["a", "b"] },
      { content_parts: ["c", "d"] },
    ]),
    "a\tb\nc\td",
  )
})

test("empty card content remains empty", () => {
  assert.equal(formatCardContent([]), "")
  assert.equal(formatCardContents([]), "")
})
