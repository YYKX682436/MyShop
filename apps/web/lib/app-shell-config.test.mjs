import assert from "node:assert/strict"
import test from "node:test"

import { normalizeAppShellConfig, normalizeAppShellPattern, selectAppShellPattern } from "./app-shell-config.ts"

test("normalizes exact and wildcard rules with URL hostname semantics", () => {
  assert.equal(normalizeAppShellPattern("*.Bücher.Example."), "*.xn--bcher-kva.example")
  assert.equal(normalizeAppShellPattern("*.faß.de"), "*.xn--fa-hia.de")
  assert.equal(normalizeAppShellPattern("Shop.MC520.TOP."), "shop.mc520.top")
})

test("rejects unsafe and suffix-spoofed rules", () => {
  for (const value of ["https://shop.example.com", "user@example.com", "shop.example.com:8443",
    "mc520.top.evil.com@attacker.test", "127.0.0.1", "localhost"]) {
    assert.equal(normalizeAppShellPattern(value), null, value)
  }
})

test("selects exact rules first and the most specific one-label wildcard", () => {
  assert.equal(selectAppShellPattern(["*.mc520.top", "shop.mc520.top"], "shop.mc520.top"), "shop.mc520.top")
  assert.equal(selectAppShellPattern(["*.mc520.top", "*.branch.mc520.top"], "store.branch.mc520.top"), "*.branch.mc520.top")
  assert.equal(selectAppShellPattern(["*.mc520.top"], "deep.store.mc520.top"), null)
})

test("normalizes once and refuses duplicate canonical domains", () => {
  const normalized = normalizeAppShellConfig({ enabled: true, rules: [
    { domain_pattern: "Shop.MC520.TOP.", site_type: "MYSHOP", enabled: true, note: " Store " },
  ] })
  assert.deepEqual(normalized.rules[0], {
    domain_pattern: "shop.mc520.top", site_type: "MYSHOP", enabled: true, note: "Store",
  })
  assert.throws(() => normalizeAppShellConfig({ enabled: true, rules: [
    { domain_pattern: "shop.mc520.top", site_type: "MYSHOP", enabled: true, note: "" },
    { domain_pattern: "SHOP.MC520.TOP.", site_type: "EXTERNAL", enabled: true, note: "" },
  ] }), /duplicate|invalid|域名/i)
})
