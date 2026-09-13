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

test("ten or more cards stay in API order with one line per card and no trailing newline", () => {
  const cards = Array.from({ length: 12 }, (_, index) => ({
    content_parts: [`field-${index + 1}`, `value-${index + 1}`],
  }))
  const formatted = formatCardContents(cards)

  assert.equal(formatted, cards.map(card => card.content_parts.join("\t")).join("\n"))
  assert.equal(formatted.split("\n").length, 12)
  assert.equal(formatted.endsWith("\n"), false)
  assert.equal(formatted.includes("field-1\tvalue-1\nfield-2"), true)
  assert.equal(formatted.includes("field-10\tvalue-10"), true)
})

test("empty card content remains empty", () => {
  assert.equal(formatCardContent([]), "")
  assert.equal(formatCardContents([]), "")
})
