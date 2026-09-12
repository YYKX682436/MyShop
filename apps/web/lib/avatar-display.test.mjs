import assert from "node:assert/strict"
import test from "node:test"
import { getAvatarDisplayState } from "./avatar-display.ts"

test("avatar display uses a neutral placeholder while the current URL is idle or loading", () => {
  for (const status of ["idle", "loading"]) {
    assert.equal(
      getAvatarDisplayState("/api/uploads/avatars/current.png", "/api/uploads/avatars/current.png", status),
      "loading",
    )
  }
})

test("avatar display never reuses a previous URL status", () => {
  assert.equal(getAvatarDisplayState("/current.png", "/previous.png", "loaded"), "loading")
})

test("avatar display shows the image only after it loads and falls back only on a real error", () => {
  assert.equal(getAvatarDisplayState("/current.png", "/current.png", "loaded"), "image")
  assert.equal(getAvatarDisplayState("/current.png", "/current.png", "error"), "fallback")
  assert.equal(getAvatarDisplayState(null, null, "error"), "fallback")
})
