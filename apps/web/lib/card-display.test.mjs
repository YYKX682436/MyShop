import assert from "node:assert/strict"
import test from "node:test"

import { formatCardContent, formatCardContents } from "./card-display.ts"

test("one, two and three fields are copied in order on separate lines", () => {
  assert.equal(formatCardContent(["账号"]), "账号")
  assert.equal(formatCardContent(["账号", "密码"]), "账号\n密码")
  assert.equal(formatCardContent(["账号", "密码", "备注"]), "账号\n密码\n备注")
})

test("multiple cards are separated by blank lines without flattening parts", () => {
  assert.equal(
    formatCardContents([
      { content_parts: ["a", "b"] },
      { content_parts: ["c", "d"] },
    ]),
    "a\nb\n\nc\nd",
  )
})

test("many cards stay in API order with a blank line between cards and no trailing newline", () => {
  const cards = Array.from({ length: 12 }, (_, index) => ({
    content_parts: [`field-${index + 1}`, `value-${index + 1}`],
  }))
  const formatted = formatCardContents(cards)

  assert.equal(formatted, cards.map(card => card.content_parts.join("\n")).join("\n\n"))
  assert.equal(formatted.split("\n\n").length, 12)
  assert.equal(formatted.endsWith("\n"), false)
  assert.equal(formatted.includes("field-1\nvalue-1\n\nfield-2"), true)
  assert.equal(formatted.includes("field-10\nvalue-10"), true)
})

test("empty card content remains empty", () => {
  assert.equal(formatCardContent([]), "")
  assert.equal(formatCardContents([]), "")
})
