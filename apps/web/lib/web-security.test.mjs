import assert from "node:assert/strict"
import test from "node:test"
import { safeLoginRedirect } from "./safe-navigation.ts"
import { serializeJsonLd } from "./json-ld.ts"

test("login returns to a local order or admin page with its query and anchor", () => {
  assert.equal(safeLoginRedirect("/order/123?paymentReturn=1#after-sale"), "/order/123?paymentReturn=1#after-sale")
  assert.equal(safeLoginRedirect("/admin/orders"), "/admin/orders")
  assert.equal(safeLoginRedirect("/product/%E5%95%86%E5%93%81"), "/product/%E5%95%86%E5%93%81")
})

test("login refuses external origins, executable schemes and browser URL normalization tricks", () => {
  for (const value of [null, undefined, "", "https://example.com", "//example.com", "javascript:alert(1)",
    "/\\example.com", "/\t/example.com", "/\n/example.com", " /admin", "/\u0000/admin"]) {
    assert.equal(safeLoginRedirect(value), "/", String(value))
  }
})

test("JSON-LD preserves content without letting a product name close its script element", () => {
  const value = {
    name: '</script><script>document.title="changed"</script>',
    description: "<b>商品 & 说明</b>",
    items: [{ name: "</ScRiPt><p>injected</p>" }],
  }
  const serialized = serializeJsonLd(value)
  assert.doesNotMatch(serialized, /</)
  assert.deepEqual(JSON.parse(serialized), value)
})
