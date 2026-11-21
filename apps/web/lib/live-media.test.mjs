import { test } from "node:test"
import assert from "node:assert/strict"
import { liveMediaUrl } from "./live-media.ts"

test("only local managed images use the uncached route", () => {
  const name = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png"
  const origins = ["https://mc.mc520.top"]
  assert.equal(liveMediaUrl(`/api/uploads/${name}?v=1`, origins), `/image-source/${name}?live=1`)
  assert.equal(liveMediaUrl(`https://mc.mc520.top/api/uploads/${name}`, origins), `/image-source/${name}?live=1`)
  assert.equal(liveMediaUrl(`https://other.example/api/uploads/${name}`, origins), null)
  assert.equal(liveMediaUrl("/api/uploads/avatars/avatar.png", origins), null)
  assert.equal(liveMediaUrl(`/api/uploads/../${name}`, origins), null)
})
