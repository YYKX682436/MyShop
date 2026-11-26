import assert from "node:assert/strict"
import test from "node:test"
import {
  createPendingSupportMessage,
  shouldFetchConversationDetail,
  SupportReadQueue,
} from "./support-message-state.ts"

test("failed read receipts remain queued and retry the same cursors", async () => {
  const queue = new SupportReadQueue()
  const controller = new AbortController()
  queue.enqueue(["14", "18"])
  const attempts = []
  await queue.flush(controller.signal, async batch => {
    attempts.push([...batch])
    if (attempts.length === 1) throw new Error("offline")
  })
  assert.deepEqual(queue.pending(), ["14", "18"])
  assert.deepEqual(queue.acknowledged(), [])
  await queue.flush(controller.signal, async batch => attempts.push([...batch]))
  assert.deepEqual(attempts, [["14", "18"], ["14", "18"]])
  assert.deepEqual(queue.pending(), [])
  assert.deepEqual(queue.acknowledged(), ["14", "18"])
})

test("in-flight receipt batches deduplicate for one identity and abort on identity change", async () => {
  const queue = new SupportReadQueue()
  const firstIdentity = new AbortController()
  queue.enqueue(["21"])
  let calls = 0
  let finish
  const pending = queue.flush(firstIdentity.signal, async () => {
    calls += 1
    await new Promise(resolve => { finish = resolve })
  })
  const duplicate = queue.flush(firstIdentity.signal, async () => { calls += 1 })
  assert.equal(calls, 1)
  firstIdentity.abort()
  finish()
  await Promise.all([pending, duplicate])
  assert.deepEqual(queue.pending(), ["21"])
  queue.clear()
  assert.deepEqual(queue.pending(), [])
})

test("retry context preserves the original sale round and notice after the editor changes", () => {
  const files = [{ name: "proof.png" }]
  const context = { conversationId: "thread-a", afterSale: true, orderId: "order-a", requestId: "sale-a", round: 2, noticeId: "notice-a" }
  const message = createPendingSupportMessage("client-a", context, "请处理", files)
  context.requestId = "sale-b"
  context.round = 3
  files.push({ name: "later.png" })
  assert.deepEqual(message.context, {
    conversationId: "thread-a", afterSale: true, orderId: "order-a", requestId: "sale-a", round: 2, noticeId: "notice-a",
  })
  assert.equal(message.files.length, 1)
  assert.equal(message.content, "请处理")
})

test("detail loading is retried after activities succeed but detail fails", () => {
  assert.equal(shouldFetchConversationDetail(false, 0), true)
  assert.equal(shouldFetchConversationDetail(false, 4), true)
  assert.equal(shouldFetchConversationDetail(true, 0), false)
  assert.equal(shouldFetchConversationDetail(true, 1), true)
})
