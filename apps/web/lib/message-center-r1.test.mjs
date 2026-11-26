import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

const read = path => readFileSync(new URL(path, import.meta.url), "utf8")

test("/messages opens the shared customer conversation and links directly to notices", () => {
  const page = read("../app/(store)/messages/page.tsx")
  assert.match(page, /<SupportThread[\s\S]*showHeader=\{false\}/)
  assert.match(page, /商城客服/)
  assert.match(page, /href="\/messages\/announcements"/)
  assert.doesNotMatch(page, /function Entry\(/)
  assert.doesNotMatch(page, /<SupportThread key=/)
})

test("legacy customer service routes preserve only supported context parameters", () => {
  const oldSupport = read("../app/(store)/messages/support/page.tsx")
  const oldNew = read("../app/(store)/messages/support/new/page.tsx")
  const oldId = read("../app/(store)/messages/support/[id]/page.tsx")
  for (const source of [oldSupport, oldNew]) {
    assert.match(source, /\["order", "request_id", "round", "notice"\]/)
    assert.match(source, /redirect\("\/messages"/)
  }
  assert.match(oldId, /router\.replace\("\/messages"/)
})

test("customer bubbles use fit content, distinct theme colors, and one native transcript scroller", () => {
  const thread = read("../components/shared/support-thread.tsx")
  const primitives = read("../components/shared/chat-primitives.tsx")
  const navigation = read("../components/store/liquid-tabs/store-bottom-navigation.tsx")
  const styles = read("../app/globals.css")
  assert.match(primitives, /w-fit max-w-\[min\(86%,42rem\)\]/)
  assert.match(primitives, /bg-primary text-white/)
  assert.match(primitives, /bg-muted\/70 text-foreground dark:bg-muted\/55/)
  assert.equal((thread.match(/overflow-y-auto/g) ?? []).length, 1)
  assert.match(thread, /data-cursor=\{item\.cursor\}/)
  assert.match(thread, /scrollIntoView|preserveAnchor/)
  assert.doesNotMatch(thread, /new ResizeObserver|window\.scrollBy/)
  assert.match(navigation, /baselineViewportHeight - viewportHeight > 140/)
  assert.match(styles, /\.store-shell\[data-chat-keyboard-open\] \+ \.store-liquid-surface/)
})

test("read receipts and submitted retries keep their exact identity-bound batches", () => {
  const state = read("./support-message-state.ts")
  const thread = read("../components/shared/support-thread.tsx")
  assert.match(state, /class SupportReadQueue/)
  assert.match(state, /this\.queued\.delete\(cursor\)/)
  assert.match(state, /Object\.freeze\(\{ \.\.\.context \}\)/)
  assert.match(thread, /message\.context\.requestId/)
  assert.match(thread, /message\.context\.round/)
  assert.match(thread, /shouldFetchConversationDetail/)
  assert.match(thread, /data-cursor/)
})

test("notice polling refreshes loaded IDs and read-all uses server receipts", () => {
  const page = read("../app/(store)/messages/announcements/page.tsx")
  assert.match(page, /announcementApi\.refresh\(/)
  assert.match(page, /announcementApi\.readAll\(/)
  assert.match(page, /first_unread_id/)
  assert.match(page, /located\.current === locationKey/)
  assert.match(page, /readVersion === undefined \? item/)
  assert.match(page, /MarkdownContent value=\{item\.body\}/)
  assert.match(page, /href=\{`\/messages\?notice=/)
})

test("admin support keeps search and quick filters in the database-backed query", () => {
  const page = read("../app/admin/support/page.tsx")
  const service = read("../../api/src/main/java/com/myshop/service/SupportTimelineService.java")
  assert.match(page, /姓名、用户编号或订单号/)
  assert.match(page, /value === "unread"/)
  assert.match(page, /quickFilter === "pending"/)
  assert.match(page, /supportApi\.list\(true, query/)
  assert.match(service, /buyer\.username ILIKE \? OR buyer\.email ILIKE \? OR buyer\.user_no::text ILIKE/)
  assert.match(service, /JOIN support_messages message ON activity\.source_type='SUPPORT_MESSAGE'/)
})

test("manual fulfillment results use a structured marker and keep role-specific order links", () => {
  const thread = read("../components/shared/support-thread.tsx")
  const types = read("../services/api.ts")
  assert.match(thread, /item\.event_type === "ORDER_DELIVERED" && item\.manual_delivery/)
  assert.match(thread, /您的订单[\s\S]*已完成人工发货，请查看订单详情。/)
  assert.match(thread, /admin \? "\/admin\/orders\/" : "\/order\/"/)
  assert.match(types, /manual_delivery\?: boolean/)
})
