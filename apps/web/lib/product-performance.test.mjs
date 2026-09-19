import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const read = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")
const dock = read("../components/layout/store-bottom-nav.tsx")
const form = read("../components/admin/products/product-form.tsx")
const editorPage = read("../components/admin/products/product-editor-page.tsx")
const styles = read("../app/globals.css")
const perfScript = read("../scripts/perf-smoke.mjs")
const webPackage = JSON.parse(read("../package.json"))
const persistentSurfaces = [
  read("../components/layout/store-header.tsx"),
  read("../components/layout/admin-header.tsx"),
  read("../components/admin/shared/index.tsx"),
  read("../components/ui/sheet.tsx"),
  read("../components/ui/dialog.tsx"),
  read("../components/ui/alert-dialog.tsx"),
  read("../components/ui/drawer.tsx"),
  read("../app/(store)/product/[id]/product-purchase-dock.tsx"),
]

test("the storefront Dock stays full size without scroll-direction work", () => {
  assert.doesNotMatch(dock, /dockCompact|lastScrollYRef|scrollFrameRef|data-compact|addEventListener\(["']scroll/)
  assert.match(dock, /className="store-dock-viewport"/)
  assert.match(dock, /className="store-dock static-material"/)
  assert.match(dock, /useReducedMotion/)
})

test("persistent surfaces use static materials and touch devices skip backdrop sampling", () => {
  const staticMaterial = styles.match(/\.static-material\s*\{[^}]*\}/s)?.[0]
  const largeMaterial = styles.match(/\.large-material\s*\{[^}]*\}/s)?.[0]
  assert.ok(staticMaterial)
  assert.ok(largeMaterial)
  assert.doesNotMatch(staticMaterial, /backdrop-filter/i)
  assert.doesNotMatch(largeMaterial, /backdrop-filter/i)
  assert.match(styles, /\.transient-glass\s*\{[^}]*backdrop-filter:\s*blur\(4px\)/s)
  assert.match(styles, /@media\s*\(pointer:\s*coarse\),\s*\(any-pointer:\s*coarse\)[\s\S]*?backdrop-filter:\s*none/)
  assert.doesNotMatch(styles, /store-dock-viewport\[data-compact/)
  assert.match(styles, /padding:\s*calc\(env\(safe-area-inset-top,\s*0px\)\s*\+\s*0\.25rem\)/)
  for (const source of persistentSurfaces) {
    assert.match(source, /static-material|large-material/)
    assert.doesNotMatch(source, /bar-glass|sheet-glass/)
  }
  const activeBlurDeclarations = [...styles.matchAll(/^[ \t]*(?:-webkit-)?backdrop-filter:[ \t]*([^;\r\n]+);/gm)]
    .map((match) => match[1].trim())
    .filter((value) => value !== "none")
  assert.deepEqual(activeBlurDeclarations, ["blur(4px) saturate(120%)", "blur(4px) saturate(120%)", "blur(3px) saturate(115%)", "blur(3px) saturate(115%)"])
})

test("WorkspaceSheet animation only changes opacity and transform", () => {
  const sheetAnimations = styles.slice(styles.indexOf("@keyframes myshop-sheet-right-in"), styles.indexOf("@keyframes myshop-sheet-left-in"))
  assert.match(sheetAnimations, /transform/)
  assert.match(sheetAnimations, /opacity/)
  assert.doesNotMatch(sheetAnimations, /filter|background|box-shadow/)
})

test("ProductForm mounts one tab at a time and defers editor-only modules", () => {
  assert.doesNotMatch(form, /forceMount/)
  assert.match(form, /lazy\(\(\) => import\("@\/components\/admin\/markdown-editor"\)/)
  assert.match(form, /lazy\(\(\) => import\("@\/components\/admin\/products\/card-key-import-dialog"\)/)
  assert.match(form, /cardKeyImportOpen && cardKeyImportAvailability === "ready"/)
  for (const tab of ["basic", "specs", "pricing", "inventory", "details"]) {
    assert.match(form, new RegExp(`<TabsContent value="${tab}" data-testid="product-tab-panel-${tab}"`))
  }
})

test("product pricing data is loaded only while pricing is active", () => {
  assert.match(form, /if \(activeTab !== "pricing" \|\| levelsLoaded \|\| levelsRequestRef\.current\) return/)
  assert.match(form, /const request = adminUserLevelApi\.getList\(\)/)
  assert.match(form, /if \(activeTab !== "pricing" \|\| levelsLoading \|\| !levelsLoaded\)/)
  assert.match(form, /controller\.abort\(\)/)
  assert.match(perfScript, /initialUserLevelCalls, 0/)
  assert.match(perfScript, /initialPreviewCalls, 0/)
  assert.match(perfScript, /userLevelCalls, 1/)
  assert.match(perfScript, /noUnrelatedPreview/)
})

test("new product workspaces do not wait for category data", () => {
  assert.match(editorPage, /useState<"loading" \| "ready" \| "error">\("loading"\)/)
  assert.match(editorPage, /if \(isWorkspace && loadingProduct && !productError\)/)
  assert.match(editorPage, /categoryStatus=\{categoryStatus\}/)
  assert.match(editorPage, /onRetryCategories=\{\(\) => \{ void loadCategories\(\) \}\}/)
  assert.match(form, /data-testid="product-category-select"/)
  assert.match(form, /disabled=\{categoryStatus !== "ready"/)
  assert.match(perfScript, /categoryDelayProof/)
  assert.match(perfScript, /Tracing\.start/)
  assert.equal(webPackage.scripts["perf:install"], "playwright install chromium")
  assert.equal(webPackage.scripts["perf:smoke"], "node scripts/perf-smoke.mjs")
})
