import assert from "node:assert/strict"
import { existsSync } from "node:fs"
import { chromium } from "playwright"

// User mutations are fully intercepted; this exercises intercepted-route
// invalidation without changing a real user, balance, or account status.
const base = new URL(process.env.BASE_URL ?? "http://localhost:3000")
if (!["localhost", "127.0.0.1"].includes(base.hostname)) throw new Error("An isolated loopback test service is required")
if (!process.env.MYSHOP_TEST_ADMIN || !process.env.MYSHOP_TEST_PASSWORD) throw new Error("Set MYSHOP_TEST_ADMIN and MYSHOP_TEST_PASSWORD for the isolated test service")
const macChrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ?? (existsSync(macChrome) ? macChrome : undefined)
const browser = await chromium.launch({ headless: true, executablePath })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const id = "ca4d0000-0000-4000-8000-000000000066"
const levels = [
  { id: "level-a", name: "普通等级", pricing_type: "RETAIL", markup_rate: null, is_default: true, enabled: true, sort_order: 0, user_count: 1 },
  { id: "level-b", name: "测试等级", pricing_type: "RETAIL", markup_rate: null, is_default: false, enabled: true, sort_order: 1, user_count: 0 },
]
const user = { id, user_no: 99991, username: "用户同步回归", email: "old@example.test", user_level_id: "level-a", user_level_name: "普通等级", balance: 20, order_count: 0, successful_order_count: 0, total_spent: 0, is_deleted: 0, created_at: "2026-01-01T00:00:00", phone: null, last_login_ip: null, last_login_at: null, last_login_ip_location: null }
const envelope = data => ({ status: 200, contentType: "application/json", body: JSON.stringify({ code: 0, data, message: "Success" }) })
const paginated = list => ({ list, pagination: { page: 1, page_size: 20, total: list.length } })
const waitForUserList = predicate => page.waitForResponse(async response => {
  if (new URL(response.url()).pathname !== "/api/admin/users" || response.request().method() !== "GET") return false
  return predicate((await response.json()).data.list[0])
})

try {
  await page.goto(new URL("/login", base).href)
  await page.getByRole("textbox", { name: "用户名 / 邮箱 / 手机号" }).fill(process.env.MYSHOP_TEST_ADMIN)
  await page.getByRole("textbox", { name: "密码" }).fill(process.env.MYSHOP_TEST_PASSWORD)
  await page.getByRole("button", { name: "登录", exact: true }).click()
  await page.waitForURL(url => url.pathname !== "/login")
  await page.waitForTimeout(1300)
  await page.route("**/api/admin/user-levels", route => route.fulfill(envelope(levels)))
  await page.route("**/api/admin/users?*", route => route.fulfill(envelope(paginated([{ ...user }]))))
  await page.route(`**/api/admin/users/${id}`, route => route.fulfill(envelope({ ...user })))
  await page.route(`**/api/admin/users/${id}/operation-logs?*`, route => route.fulfill(envelope(paginated([]))))
  await page.route(`**/api/admin/users/${id}/balance-transactions?*`, route => route.fulfill(envelope(paginated([]))))
  await page.route(`**/api/admin/users/${id}/profile`, async route => {
    user.email = route.request().postDataJSON().email
    await route.fulfill(envelope({ ...user }))
  })
  await page.route(`**/api/admin/users/${id}/level`, async route => {
    const level = levels.find(item => item.id === route.request().postDataJSON().user_level_id)
    user.user_level_id = level.id
    user.user_level_name = level.name
    await route.fulfill(envelope(level))
  })
  await page.route(`**/api/admin/users/${id}/balance`, async route => {
    user.balance = 25
    await route.fulfill(envelope({ id: "transaction", delta: 5, balance_after: 25, balance_before: 20, reason: null, created_at: "2026-01-01T00:00:00" }))
  })
  await page.route(`**/api/admin/users/${id}/toggle`, async route => {
    user.is_deleted = route.request().postDataJSON().is_deleted
    await route.fulfill(envelope(null))
  })

  await page.goto(new URL("/admin/users", base).href)
  await page.getByRole("link", { name: "查看", exact: true }).click()
  const workspace = page.getByRole("dialog", { name: "用户详情" })
  await workspace.getByRole("button", { name: "编辑资料" }).click()
  const profile = page.getByRole("dialog", { name: "编辑资料" })
  await profile.getByLabel("邮箱").fill("new@example.test")
  const emailList = waitForUserList(item => item?.email === "new@example.test")
  await profile.getByRole("button", { name: "保存", exact: true }).click()
  await emailList
  await profile.waitFor({ state: "hidden" })

  await workspace.getByRole("button", { name: "编辑等级: 用户同步回归" }).click()
  await page.getByRole("combobox", { name: "用户等级", exact: true }).selectOption("level-b")
  const levelList = waitForUserList(item => item?.user_level_id === "level-b")
  await page.getByRole("button", { name: "保存", exact: true }).click()
  await levelList

  await workspace.getByRole("button", { name: "调整余额", exact: true }).click()
  const balance = page.getByRole("dialog", { name: "调整余额" })
  await balance.getByLabel("调整金额").fill("5")
  const balanceList = waitForUserList(item => item?.balance === 25)
  await balance.getByRole("button", { name: "确认调整" }).click()
  await balanceList
  const result = page.getByRole("dialog", { name: "余额调整成功" })
  await result.getByRole("button", { name: "关闭", exact: true }).click()

  await workspace.getByRole("button", { name: "编辑用户状态: 用户同步回归" }).click()
  await page.getByRole("button", { name: "禁用", exact: true }).click()
  const confirmation = page.getByRole("alertdialog", { name: "确认禁用用户" })
  const statusList = waitForUserList(item => item?.is_deleted === 1)
  await confirmation.getByRole("button", { name: "禁用", exact: true }).click()
  await statusList
  await confirmation.waitFor({ state: "hidden" })
  await page.keyboard.press("Escape")
  await page.waitForURL(url => url.pathname === "/admin/users")
  const row = page.locator("tr").filter({ hasText: "用户同步回归" })
  await row.getByText("测试等级", { exact: true }).waitFor()
  await row.getByText("已禁用", { exact: true }).waitFor()
  assert.match(await row.textContent(), /25\.00/)
  console.log("User detail email, level, balance and status changes refreshed the underlying list")
} finally {
  await context.close()
  await browser.close()
}
