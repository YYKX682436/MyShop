import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

import {
  USER_MANAGEMENT_LIST_COLUMNS,
  buildPasswordResetResultText,
  formatBalanceAdjustmentResult,
  isOptionalReasonValid,
  normalizeIpLocationDisplay,
  normalizeOptionalReason,
} from "./user-management.ts"

const detailSource = readFileSync(new URL("../app/admin/users/[id]/page.tsx", import.meta.url), "utf8")
const detailRouteSource = readFileSync(new URL("../components/admin/users/user-detail-route.tsx", import.meta.url), "utf8")

test("the user list has operations columns but no email presentation column", () => {
  assert.deepEqual(USER_MANAGEMENT_LIST_COLUMNS, [
    "user_no", "username", "user_level", "balance", "order_count", "total_spent", "status", "created_at", "actions",
  ])
  assert.equal(USER_MANAGEMENT_LIST_COLUMNS.includes("email"), false)
})

test("the detail keeps email and uses the wide workspace route", () => {
  assert.match(detailSource, /value=\{user\.email\}/)
  assert.match(detailSource, /adminUserApi\.updateProfile/)
  assert.match(detailRouteSource, /WorkspaceSheet/)
  assert.doesNotMatch(detailRouteSource, /InspectorSheet/)
})

test("balance result uses authoritative signed delta and fixed cents", () => {
  assert.equal(formatBalanceAdjustmentResult(10001, "buyer", {
    delta: 10,
    balance_after: 123.45,
  }), "客户ID：10001\n客户账号：buyer\n余额调整：+10.00元\n当前余额：123.45元")
  assert.equal(formatBalanceAdjustmentResult(10001, "buyer", {
    delta: -10,
    balance_after: "113.40",
  }), "客户ID：10001\n客户账号：buyer\n余额调整：-10.00元\n当前余额：113.40元")
})

test("password result keeps a manual-copyable full notification", () => {
  const temporaryPassword = `AIYUN${Array.from({ length: 8 }, (_, index) => index).join("")}`
  assert.equal(buildPasswordResetResultText(temporaryPassword), `登录密码已重置\n${temporaryPassword}\n请尽快登录修改`)
  assert.match(detailSource, /temporary_password/)
  assert.match(detailSource, /value=\{resetResult\?\.password \|\| ""\}/)
})

test("blank reasons become null while non-empty reasons stay capped", () => {
  assert.equal(normalizeOptionalReason("  "), null)
  assert.equal(normalizeOptionalReason("  manual credit  "), "manual credit")
  assert.equal(isOptionalReasonValid(null), true)
  assert.equal(isOptionalReasonValid("x".repeat(500)), true)
  assert.equal(isOptionalReasonValid("x".repeat(501)), false)
})

test("IP display normalizes municipalities and preserves real foreign labels", () => {
  assert.equal(normalizeIpLocationDisplay({ resolved: true, province: "北京市", city: "北京市" }), "北京市")
  assert.equal(normalizeIpLocationDisplay({ resolved: true, province: "广东省", city: "深圳市" }), "广东省 · 深圳市")
  assert.equal(normalizeIpLocationDisplay({ resolved: true, country: "美国", display_name: "美国 · 加利福尼亚州" }), "美国 · 加利福尼亚州")
  assert.equal(normalizeIpLocationDisplay({ resolved: false, display_name: "内网/保留地址" }), "内网/保留地址")
})
