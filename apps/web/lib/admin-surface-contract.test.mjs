import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")
const styles = readSource("../app/globals.css")
const shared = readSource("../components/admin/shared/index.tsx")
const dashboardPanels = readSource("../components/admin/dashboard-panels.tsx")
const orders = readSource("../app/admin/orders/page.tsx")
const productForm = readSource("../components/admin/products/product-form.tsx")
const salesChannelEditor = readSource("../components/admin/products/sales-channel-editor.tsx")
const salesChannelApi = readSource("../services/api.ts")
const xianyuAccounts = readSource("../app/admin/xianyu/accounts/page.tsx")
const adminSidebar = readSource("../components/layout/admin-sidebar.tsx")
const productTabs = readSource("../components/ui/tabs.tsx")
const categoryPage = readSource("../app/admin/categories/page.tsx")

function cssRule(selector) {
  const start = styles.indexOf(`${selector} {`)
  assert.notEqual(start, -1, `missing CSS rule for ${selector}`)
  const open = styles.indexOf("{", start)
  const close = styles.indexOf("}", open)
  assert.notEqual(close, -1, `unterminated CSS rule for ${selector}`)
  return styles.slice(open + 1, close)
}

test("admin data tables share a fully enclosed surface and keep mobile record cards and state rows", () => {
  const tableRegion = cssRule(".admin-table-region")
  const shell = shared.match(/export function DataTableShell[\s\S]*?\n}/)?.[0]

  assert.ok(shell, "DataTableShell remains the shared wrapper")
  assert.match(shell, /admin-table-region/)
  assert.match(shell, /admin-table-scroll/)
  assert.match(tableRegion, /overflow:\s*hidden/)
  assert.match(tableRegion, /border:\s*1px solid hsl\(var\(--border-subtle\)\)/)
  assert.match(tableRegion, /border-radius:\s*var\(--radius-group\)/)
  assert.match(tableRegion, /background-color:\s*hsl\(var\(--surface\)\)/)
  assert.doesNotMatch(tableRegion, /border-block/)
  assert.match(styles, /tbody\s*>\s*tr:not\(\.admin-table-state-row\)[\s\S]*?border-radius:\s*var\(--radius-control\)/)
  assert.match(styles, /tr\.admin-table-state-row/)
})

test("admin FormSection restores its shared card while retaining utility overrides", () => {
  const section = cssRule(".admin-settings-section")
  assert.match(shared, /settings-section admin-settings-section/)
  assert.match(section, /border:\s*1px solid hsl\(var\(--border-subtle\)\)/)
  assert.match(section, /border-radius:\s*var\(--radius-group\)/)
  assert.match(section, /background-color:\s*hsl\(var\(--surface\)\)/)
  assert.match(section, /padding:\s*1\.25rem/)
  assert.match(section, /box-shadow:\s*var\(--shadow-xs\)/)
  assert.match(categoryPage, /FormSection[^>]*className="border-0 bg-transparent p-0 shadow-none"/)
})

test("sales channel unlimited stock accepts zero in the UI and save validation", () => {
  assert.match(salesChannelEditor, /无限库存同步数量<Input type="number" min="0" step="1"/)
  assert.match(salesChannelEditor, /isValidUnlimitedStockQuantity\(unlimitedStockQuantity\)/)
})

test("Xianyu per-SKU stock cap is optional, bounded, and explicitly channel-only", () => {
  assert.match(salesChannelEditor, /闲鱼单规格库存上限<Input type="number" min="0" max="999999" step="1"/)
  assert.match(salesChannelEditor, /isValidStockCapPerSku\(stockCapPerSku\)/)
  assert.match(salesChannelEditor, /仅限制同步到闲鱼的单个规格库存，不影响商城真实库存；留空表示完全跟随商城库存，设为 0 表示闲鱼库存同步为 0。/)
  assert.match(salesChannelEditor, /stockCapPerSku: null/)
  assert.match(salesChannelEditor, /stockCapPerSku,/)
})

test("Xianyu address and category configuration use MyShop metadata pickers", () => {
  assert.match(salesChannelEditor, /宝贝所在地/)
  assert.match(salesChannelEditor, /智能推荐分类/)
  assert.match(salesChannelEditor, /tip\.searchKeyword/)
  assert.match(salesChannelEditor, /tip\.expectedText/)
  assert.match(salesChannelEditor, /selectedCategory:\s*rawCandidate/)
  assert.match(salesChannelEditor, /分类动态属性/)
  assert.doesNotMatch(salesChannelEditor, /地址校验文字<Input/)
  assert.doesNotMatch(salesChannelEditor, /平台属性 JSON/)
  assert.doesNotMatch(salesChannelEditor, /平台分类 ID<Input/)
  assert.match(salesChannelApi, /\/admin\/sales-channels\/xianyu\/metadata\/address\/search/)
  assert.match(salesChannelApi, /\/admin\/sales-channels\/xianyu\/metadata\/category\/recommend/)
})

test("Xianyu account onboarding stays inside MyShop and cleans up QR polling", () => {
  assert.match(adminSidebar, /labelKey: "admin\.navXianyu"[\s\S]*?href: "\/admin\/xianyu\/accounts"/)
  assert.match(salesChannelApi, /\/admin\/xianyu\/accounts\/login\/qr/)
  assert.match(xianyuAccounts, /setTimeout\(\(\) => void poll\(\), 2000\)/)
  assert.match(xianyuAccounts, /clearTimeout\(timer\)/)
  for (const state of ["WAITING", "SCANNED", "VERIFICATION_REQUIRED", "SUCCESS", "EXPIRED", "CANCELLED", "FAILED"]) {
    assert.match(xianyuAccounts, new RegExp(state))
  }
  assert.match(xianyuAccounts, /闲鱼要求进行安全验证，请按手机端提示完成验证。/)
  assert.doesNotMatch(xianyuAccounts, /<iframe\b/i)
})

test("dashboard content groups use tokenized surfaces and one responsive KPI separator system", () => {
  const panel = cssRule(".dashboard-panel")
  const kpis = cssRule(".dashboard-kpi-strip")
  const attention = cssRule(".dashboard-attention-strip")
  const facts = cssRule(".dashboard-system-facts")
  const metrics = cssRule(".admin-metric-strip")

  for (const rule of [panel, kpis, attention]) {
    assert.match(rule, /border:\s*1px solid hsl\(var\(--border-subtle\)\)/)
    assert.match(rule, /border-radius:\s*var\(--radius-group\)/)
    assert.doesNotMatch(rule, /backdrop-filter/)
  }
  assert.match(panel, /background-color:\s*hsl\(var\(--surface\)\)/)
  assert.match(panel, /padding:\s*1rem/)
  assert.match(kpis, /background-color:\s*hsl\(var\(--surface\)\)/)
  assert.match(kpis, /overflow:\s*hidden/)
  assert.match(attention, /overflow:\s*hidden/)
  assert.doesNotMatch(attention, /border-block/)
  assert.match(facts, /border-radius:\s*var\(--radius-control\)/)
  assert.match(facts, /background-color:\s*hsl\(var\(--surface-muted\)\)/)
  assert.match(styles, /\.dashboard-system-facts > \* \+ \*\s*\{[^}]*border-top:\s*1px solid hsl\(var\(--separator\)\)/)
  assert.match(styles, /\.dashboard-system-facts > :nth-child\(2\)\s*\{[^}]*border-left:\s*1px solid hsl\(var\(--separator\)\)/)
  assert.match(metrics, /border:\s*1px solid hsl\(var\(--border-subtle\)\)/)
  assert.match(metrics, /border-radius:\s*var\(--radius-group\)/)
  assert.doesNotMatch(cssRule(".dashboard-table-region"), /border-block/)
  assert.match(dashboardPanels, /dashboard-kpi-strip grid grid-cols-1 min-\[420px\]:grid-cols-2 xl:grid-cols-4/)
  assert.doesNotMatch(dashboardPanels, /dashboard-kpi-strip[^\n]*divide-[xy]/)
})

test("dashboard list panels use one clipped grouped surface with item separators", () => {
  const group = cssRule(".dashboard-list-group")
  const warningGroup = cssRule(".dashboard-list-group--warning")
  const item = cssRule(".dashboard-list-group > .dashboard-list-item")
  const separator = cssRule(".dashboard-list-group > .dashboard-list-item + .dashboard-list-item")
  const panelSource = (name) => dashboardPanels.match(new RegExp(`export function ${name}[\\s\\S]*?(?=\\nexport function|$)`))?.[0]

  for (const property of [
    /border:\s*1px solid hsl\(var\(--border-subtle\)\)/,
    /border-radius:\s*var\(--radius-control\)/,
    /overflow:\s*hidden/,
  ]) {
    assert.match(group, property)
  }
  assert.match(group, /background-color:\s*hsl\(var\(--surface-muted\)\)/)
  assert.match(warningGroup, /background-color:\s*hsl\(var\(--warning\)\s*\/\s*0\.08\)/)
  assert.match(item, /border:\s*0/)
  assert.match(item, /border-radius:\s*0/)
  assert.match(item, /background-color:\s*transparent/)
  assert.match(separator, /border-top:\s*1px solid hsl\(var\(--separator\)\)/)

  for (const name of ["LowStockPanel", "TopProductsPanel", "RecentUsersPanel"]) {
    const source = panelSource(name)
    assert.ok(source, `${name} source exists`)
    assert.match(source, /className="dashboard-list-group(?: dashboard-list-group--warning)?"/)
    assert.match(source, /className="dashboard-list-item /)
    assert.doesNotMatch(source, /space-y-2|dashboard-list-item[^\n]*\bbg-/)
  }
  assert.match(panelSource("LowStockPanel"), /dashboard-list-group dashboard-list-group--warning/)
})

test("order status filters use the shared segmented control without changing selection state", () => {
  const statusControls = orders.match(/<div className="segmented-control[^\"]*"[\s\S]*?<\/div>/)?.[0]
  assert.ok(statusControls, "order status filters use the shared segmented container")
  assert.match(statusControls, /segmented-control-item/)
  assert.match(statusControls, /data-active=\{statusFilter === tab\.value\}/)
  assert.match(statusControls, /aria-pressed=\{statusFilter === tab\.value\}/)
  assert.match(statusControls, /setStatusFilter\(tab\.value\)/)
  assert.match(statusControls, /admin-after-sale-tab-badge/)
  assert.doesNotMatch(statusControls, /border-b/)
})

test("product editor keeps segmented tabs without the extra underline", () => {
  assert.doesNotMatch(productForm, /className="admin-editor-tabs[^\"]*border-b/)
  assert.match(productForm, /<TabsList className="flex h-auto w-full max-w-full justify-start overflow-x-auto p-1"/)
  assert.match(productTabs, /segmented-control-item/)
})
