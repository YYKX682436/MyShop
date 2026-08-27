import assert from "node:assert/strict"
import { test } from "node:test"

import {
  buildDashboardAttention,
  formatBytes,
  formatChangePct,
  formatPercent,
  getSystemStatusLevel,
  getUsageSeverity,
  mergeAndSortAttention,
} from "./dashboard.ts"

test("usage severity keeps 70 and 85 in warning and values above 85 critical", () => {
  assert.equal(getUsageSeverity(69.99), "normal")
  assert.equal(getUsageSeverity(70), "warning")
  assert.equal(getUsageSeverity(85), "warning")
  assert.equal(getUsageSeverity(85.01), "critical")
  assert.equal(getUsageSeverity(null), null)
})

test("formatBytes uses readable binary units and preserves unavailable values", () => {
  assert.equal(formatBytes(0), "0 B")
  assert.equal(formatBytes(1024), "1 KB")
  assert.equal(formatBytes(1536), "1.5 KB")
  assert.equal(formatBytes(1024 ** 3), "1 GB")
  assert.equal(formatBytes(null), "—")
})

test("null change percentages display an em dash instead of a fabricated rate", () => {
  assert.equal(formatChangePct(null), "—")
  assert.equal(formatPercent(null), "—")
  assert.equal(formatChangePct(0), "0%")
  assert.equal(formatChangePct(12.345), "+12.3%")
  assert.equal(formatChangePct(-4.2), "-4.2%")
})

test("attention items merge by id and sort critical before warning", () => {
  const items = mergeAndSortAttention([
    { id: "stock", severity: "warning", message: "stock" },
    { id: "web", severity: "critical", message: "web" },
    { id: "stock", severity: "critical", message: "stock-critical" },
    { id: "disk", severity: "warning", message: "disk" },
    { id: "api", severity: "critical", message: "api" },
  ], 3)

  assert.deepEqual(items.map(item => item.message), ["stock-critical", "web", "api"])
})

test("unavailable exporter never hides a critical service failure", () => {
  const system = {
    available: false,
    services: {
      api: { status: "UP" },
      web: { status: "UP" },
      database: { status: "DOWN" },
      caddy: { status: "UP" },
    },
    cpu: null,
    memory: null,
    disk: null,
    uptime_seconds: null,
    load_1m: null,
    sampled_at: "2026-08-27T00:00:00Z",
  }
  const messages = {
    paidWaitingDelivery: (count) => `${count} paid`,
    lowStock: (count) => `${count} low`,
    zeroStock: (count) => `${count} zero`,
    paymentSuccessRate: (rate) => `${rate} payment`,
    monitorUnavailable: () => "monitor unavailable",
    resource: (resource, usage) => `${resource} ${usage}`,
    serviceUnavailable: (service) => `${service} down`,
    serviceUnknown: (service) => `${service} unknown`,
  }

  assert.equal(getSystemStatusLevel(system), "critical")
  assert.deepEqual(buildDashboardAttention(null, system, messages).map(item => item.message), [
    "database down",
    "monitor unavailable",
  ])
})
