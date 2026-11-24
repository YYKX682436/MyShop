import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8")

test("removing storefront search leaves category requests and pagination intact", () => {
  const shell = read("../app/(store)/store-shell-client.tsx")
  const home = read("../app/(store)/home-content.tsx")
  const api = read("../services/api.ts")
  assert.doesNotMatch(shell, /StoreSearchPanel|StoreHeaderSearch|StoreFooter|StoreHeader/)
  assert.match(home, /category_id: selectedChildCategory \?\? selectedRootCategory/)
  assert.match(home, /productApi\.getList\(params/)
  assert.match(home, /<Pagination[\s\S]*onChange=/)
  assert.match(api, /export const productApi/)
})

test("admin navigation search is gone while business search remains available", () => {
  const header = read("../components/layout/admin-header.tsx")
  assert.doesNotMatch(header, /CommandDialog|commandOpen|keydown|commandBar/)
  for (const [page, expected] of [
    ["products", /admin\.searchProduct/],
    ["orders", /admin\.searchOrder/],
    ["users", /admin\.searchUser/],
    ["suppliers", /searchGoods/],
  ]) assert.match(read(`../app/admin/${page}/page.tsx`), expected, page)
  assert.match(read("../app/admin/products/page.tsx"), /product_no/)
  const suppliers = read("../app/admin/suppliers/page.tsx")
  for (const label of ["上游商品 / 规格", "MyShop 商品 / 规格", "成本", "库存", "状态", "操作"]) {
    assert.ok(suppliers.includes(`data-label="${label}"`), label)
  }
})

test("product and markdown previews share an on-demand original viewer", () => {
  const page = read("../app/(store)/product/[id]/page.tsx")
  const productViewer = read("../components/store/product-image-viewer.tsx")
  const viewer = read("../components/shared/image-viewer.tsx")
  const markdown = read("../components/shared/markdown-content.tsx")
  assert.match(page, /ProductImageViewer src=\{product\.cover_url\}/)
  assert.match(productViewer, /<ImageViewer src=\{managedImageVariant\(previewSrc, "clear"\) \|\| src\}/)
  assert.match(viewer, /<DialogTrigger asChild>/)
  assert.match(viewer, /<DialogPrimitive\.Close/)
  assert.match(viewer, /onCloseAutoFocus=[\s\S]*?focus\(\{ preventScroll: true \}\)/)
  assert.match(viewer, /scrollPosition\.current = \{ x: window\.scrollX, y: window\.scrollY \}/)
  assert.match(viewer, /requestAnimationFrame\([\s\S]*?triggerRef\.current\?\.isConnected[\s\S]*?window\.scrollTo\(/)
  assert.match(viewer, /product-image-viewer-scroll/)
  assert.match(viewer, /<img src=\{src\}/)
  assert.match(markdown, /<ImageViewer src=\{clearPath \|\| src\}/)
})

test("the registration captcha input can shrink beside its image on narrow screens", () => {
  const registration = read("../app/(store)/register/page.tsx")
  assert.match(registration, /id="reg-captcha"[\s\S]*?className="[^"]*min-w-0 flex-1/)
})
