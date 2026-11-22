import assert from "node:assert/strict"
import test from "node:test"
import { announcementPopupKey } from "./announcement-popup.ts"

test("dismissal distinguishes site, announcement and publication version", () => {
  const first = announcementPopupKey("main", "notice-1", 1)
  assert.equal(first, announcementPopupKey("main", "notice-1", 1))
  assert.notEqual(first, announcementPopupKey("main", "notice-1", 2))
  assert.notEqual(first, announcementPopupKey("other-site", "notice-1", 1))
  assert.notEqual(first, announcementPopupKey("main", "notice-2", 1))
  assert.equal(announcementPopupKey("main", undefined, 1), null)
})
