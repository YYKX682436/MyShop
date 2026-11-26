import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const read = path => readFileSync(new URL(path, import.meta.url), "utf8")
const script = read("../app/viewport-compatibility-script.tsx")
const layout = read("../app/layout.tsx")
const storefrontAccount = read("../app/(store)/my/page.tsx")
const adminHeader = read("../components/layout/admin-header.tsx")
const styles = read("../app/globals.css")

test("desktop layout follows the browser without a MyShop toggle or saved preference", () => {
  assert.match(layout, /ViewportCompatibilityScript/)
  assert.doesNotMatch(layout, /LayoutModeScript/)
  assert.doesNotMatch(storefrontAccount + adminHeader, /LayoutModePreferenceButton|useLayoutPreference|switchToDesktop|switchToAuto/)
  assert.match(script, /localStorage\.removeItem\("myshop-layout-preference"\)/)
  assert.doesNotMatch(styles, /data-layout-mode/)
})

test("viewport compatibility only fits an actual wide viewport on a narrow screen", () => {
  assert.match(script, /screenWidth < item\.width \* 0\.8/)
  assert.match(script, /visibleWidth >= width \* 0\.8/)
  assert.match(script, /screenWidth \/ width/)
  assert.match(script, /meta\.content = content/)
  assert.match(script, /attributeFilter: \["content", "name"\]/)
  assert.doesNotMatch(script, /navigator\.userAgent|maxTouchPoints|setTimeout|maximum-scale|user-scalable=no/)
})
