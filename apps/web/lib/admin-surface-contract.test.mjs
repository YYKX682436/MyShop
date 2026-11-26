import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")
const styles = readSource("../app/globals.css")
const shared = readSource("../components/admin/shared/index.tsx")
const dashboardPanels = readSource("../components/admin/dashboard-panels.tsx")
const orders = readSource("../app/admin/orders/page.tsx")
const products = readSource("../app/admin/products/page.tsx")
const productForm = readSource("../components/admin/products/product-form.tsx")
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

test("admin tables keep accessible scrolling while key lists use purpose-built mobile records", () => {
  const tableRegion = cssRule(".admin-table-region")
  const shell = shared.match(/export function DataTableShell[\s\S]*?\n}/)?.[0]

  assert.ok(shell, "DataTableShell remains the shared wrapper")
  assert.match(shell, /admin-table-region/)
  assert.match(shell, /admin-table-scroll/)
  assert.match(shell, /role="region"/)
  assert.match(shell, /tabIndex={0}/)
  assert.match(tableRegion, /overflow:\s*hidden/)
  assert.match(tableRegion, /border:\s*1px solid hsl\(var\(--border-subtle\)\)/)
  assert.match(tableRegion, /border-radius:\s*var\(--radius-group\)/)
  assert.match(tableRegion, /background-color:\s*hsl\(var\(--surface\)\)/)
  assert.doesNotMatch(tableRegion, /border-block/)
  assert.match(styles, /@container admin-table/)
  assert.match(orders, /admin-order-mobile-list/)
  assert.match(products, /admin-product-mobile-list/)
  assert.match(shared, /admin-table-state-row/)
  assert.doesNotMatch(styles, /admin-table-scroll[\s\S]{0,800}thead[\s\S]{0,300}display:\s*none/)
})

test("compact table visibility and record styles share the same container breakpoint", () => {
  const start = styles.indexOf("@container admin-table (max-width: 767px)")
  const end = styles.indexOf("/* Keep one keyboard-scrollable region", start)
  assert.notEqual(start, -1, "compact list rules are container driven")
  assert.notEqual(end, -1, "compact list rules end before shared scroll rules")
  const compactStyles = styles.slice(start, end)

  for (const selector of [
    ".admin-orders-table .admin-table-scroll > table { display: none; }",
    ".admin-order-mobile-list { display: grid; }",
    ".admin-order-mobile-record { display: grid;",
    ".admin-products-table .admin-table-scroll table { display: none; }",
    ".admin-product-mobile-list { display: grid; }",
    ".admin-product-mobile-record {",
    ".admin-users-table .admin-table-scroll table { display: none; }",
    ".admin-user-mobile-list { display: grid; }",
    ".admin-user-mobile-record { display: grid;",
    ".admin-recharges-table .admin-table-scroll table { display: none; }",
    ".admin-recharge-mobile-list { display: grid; }",
    ".admin-recharge-mobile-record { display: grid;",
    ":root[data-layout-mode=\"desktop\"] .admin-orders-table .admin-table-scroll > table { display: table; }",
    ":root[data-layout-mode=\"desktop\"] .admin-order-mobile-list { display: none; }",
    ":root[data-layout-mode=\"desktop\"] .admin-products-table .admin-table-scroll table { display: table; }",
    ":root[data-layout-mode=\"desktop\"] .admin-product-mobile-list { display: none; }",
    ":root[data-layout-mode=\"desktop\"] .admin-users-table .admin-table-scroll table { display: table; }",
    ":root[data-layout-mode=\"desktop\"] .admin-user-mobile-list { display: none; }",
    ":root[data-layout-mode=\"desktop\"] .admin-recharges-table .admin-table-scroll table { display: table; }",
    ":root[data-layout-mode=\"desktop\"] .admin-recharge-mobile-list { display: none; }",
    ":root[data-layout-mode=\"desktop\"] .admin-orders-table table { width: 1180px; min-width: 1180px; table-layout: fixed; }",
    ":root[data-layout-mode=\"desktop\"] .admin-products-table table { width: 1180px; min-width: 1180px; table-layout: fixed; }",
    ":root[data-layout-mode=\"desktop\"] .admin-users-table table { width: 1040px; min-width: 1040px; table-layout: fixed; }",
    ":root[data-layout-mode=\"desktop\"] .admin-recharges-table table { width: 1050px; min-width: 1050px; table-layout: fixed; }",
  ]) {
    assert.ok(compactStyles.includes(selector), `missing compact style: ${selector}`)
  }
  assert.doesNotMatch(styles, /@container admin-table \(max-width: 720px\)/)
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
  assert.match(dashboardPanels, /dashboard-kpi-strip grid grid-cols-2 xl:grid-cols-4/)
  assert.doesNotMatch(styles, /admin-table-scroll[^{}]*\{[^}]*display:\s*block/)
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
