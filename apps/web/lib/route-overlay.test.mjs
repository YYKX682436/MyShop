import assert from "node:assert/strict"
import test from "node:test"
import { shouldConfirmRouteOverlayClose } from "./route-overlay.ts"

test("route overlay confirms a normal close only while dirty", () => {
  assert.equal(shouldConfirmRouteOverlayClose(true), true)
  assert.equal(shouldConfirmRouteOverlayClose(false), false)
})

test("route overlay skips the dirty check only for an explicit clean close", () => {
  assert.equal(shouldConfirmRouteOverlayClose(true, { skipDirtyCheck: true }), false)
  assert.equal(shouldConfirmRouteOverlayClose(false, { skipDirtyCheck: true }), false)
  assert.equal(shouldConfirmRouteOverlayClose(true, { skipDirtyCheck: false }), true)
})
