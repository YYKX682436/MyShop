import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"

import { getAfterSaleAnchorAction } from "./order-after-sale-anchor.ts"

const readSource = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), "utf8")

test("after-sale fragment waits for dynamic data and then scrolls once", () => {
  assert.equal(getAfterSaleAnchorAction("#after-sale", false, null), "wait")
  assert.equal(getAfterSaleAnchorAction("#after-sale", true, null), "scroll")
  assert.equal(getAfterSaleAnchorAction("#after-sale", true, "#after-sale"), "ignore")
})

test("other fragments and already handled navigation are ignored", () => {
  assert.equal(getAfterSaleAnchorAction("#customer-inputs", true, null), "ignore")
  assert.equal(getAfterSaleAnchorAction("", true, null), "ignore")
  assert.equal(getAfterSaleAnchorAction("#after-sale", false, "#after-sale"), "ignore")
})

test("order detail handles the fragment after dynamic after-sale data renders", () => {
  const source = readSource("../app/(store)/order/[orderId]/page.tsx")
  const cardSource = readSource("../components/shared/after-sale-history-card.tsx")

  assert.match(source, /window\.location\.hash/)
  assert.match(source, /hash !== "#after-sale"/)
  assert.match(source, /view\?\.order\.after_sale/)
  assert.match(source, /after_sale_history/)
  assert.match(source, /getAfterSaleAnchorAction/)
  assert.match(source, /window\.requestAnimationFrame/)
  assert.match(source, /document\.getElementById\("after-sale"\)/)
  assert.match(source, /scrollIntoView\(\{ behavior: "smooth", block: "start" \}\)/)
  assert.match(source, /afterSaleAnchorHandledRef/)
  assert.match(cardSource, /id="after-sale" className="content-surface scroll-mt-24/)
})
