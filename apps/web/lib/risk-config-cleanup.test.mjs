import { readFile } from "node:fs/promises"
import assert from "node:assert/strict"
import test from "node:test"

const [riskPage, riskTypes, translations] = await Promise.all([
  readFile(new URL("../app/admin/risk/page.tsx", import.meta.url), "utf8"),
  readFile(new URL("../types/index.ts", import.meta.url), "utf8"),
  readFile(new URL("./i18n.ts", import.meta.url), "utf8"),
])

const retiredConfigKeys = ["ip", "user"].map((dimension) => ["max", "pending", "orders", "per", dimension].join("_"))
const retiredTranslationKeys = [
  ["pending", "Order", "Limit"].join(""),
  ["max", "Pending", "Per", "Ip"].join(""),
  ["max", "Pending", "Per", "User"].join(""),
].map((key) => ["admin", key].join("."))
const retiredErrorKey = ["unpaid", "Order", "Exists"].join("")

function sourcePattern(value) {
  return new RegExp(value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
}

test("retired pending-order configuration is absent from the risk UI contract", () => {
  for (const key of retiredConfigKeys) {
    assert.doesNotMatch(riskPage, sourcePattern(key), `${key} must not be rendered by the risk page`)
    assert.doesNotMatch(riskTypes, sourcePattern(key), `${key} must not be present in RiskConfig`)
  }
  for (const key of retiredTranslationKeys) {
    assert.doesNotMatch(riskPage, sourcePattern(key), `${key} must not be requested by the risk page`)
    assert.doesNotMatch(translations, sourcePattern(key), `${key} must not be present in i18n`)
  }
  assert.doesNotMatch(translations, sourcePattern(["error", retiredErrorKey].join(".")), "retired unpaid-order error text must not be present in i18n")
})
