import assert from "node:assert/strict"
import { test } from "node:test"

import {
  canonicalCustomerInputs,
  validateCustomerInputs,
} from "./customer-inputs.ts"
import { fingerprintPayload } from "./idempotency.ts"

test("customer input canonicalization is independent of submitted order", () => {
  const fields = [
    { id: "account", label: "收货账号" },
    { id: "contact", label: "联系方式" },
  ]
  assert.deepEqual(
    canonicalCustomerInputs(fields, [
      { id: "contact", value: " 123 " },
      { id: "account", value: " alice " },
    ]),
    [
      { id: "account", value: "alice" },
      { id: "contact", value: "123" },
    ],
  )
})

test("all configured customer fields are required and ordinary text is allowed", () => {
  const fields = [{ id: "note", label: "备注" }, { id: "account", label: "账号" }]
  const valid = validateCustomerInputs(fields, { note: "hello world", account: "A-01" })
  assert.equal(valid.ok, true)

  const invalid = validateCustomerInputs(fields, { note: "hello world", account: "" })
  assert.equal(invalid.ok, false)
  assert.equal(invalid.field, "account")
})

test("customer input changes rotate the direct-order intent fingerprint", () => {
  const base = { product_id: "product-1", customer_inputs: canonicalCustomerInputs(
    [{ id: "account", label: "账号" }],
    [{ id: "account", value: "111" }],
  ) }
  const changed = { ...base, customer_inputs: canonicalCustomerInputs(
    [{ id: "account", label: "账号" }],
    [{ id: "account", value: "222" }],
  ) }
  assert.notEqual(fingerprintPayload(base), fingerprintPayload(changed))
})

test("supplier recharge validates option values, numeric input and optional multiline text", () => {
  const fields = [
    { id: "account", label: "账号", type: "TEXT", required: true, regex: "^[a-z]{2,5}$" },
    { id: "count", label: "数量", type: "NUMBER", required: true },
    { id: "zone", label: "区服", type: "SELECT", required: true, options: [{ name: "一区", value: "zone-1" }] },
    { id: "note", label: "备注", type: "MULTILINE", required: false },
  ]
  assert.equal(validateCustomerInputs(fields, { account: "abc", count: "12", zone: "zone-1", note: "first\nsecond" }).ok, true)
  assert.equal(validateCustomerInputs(fields, { account: "abc", count: "12", zone: "一区", note: "" }).field, "zone")
  assert.equal(validateCustomerInputs(fields, { account: "abc", count: "abc", zone: "zone-1", note: "" }).field, "count")
  assert.equal(validateCustomerInputs(fields, { account: "ABC", count: "12", zone: "zone-1", note: "" }).field, "account")
  assert.equal(validateCustomerInputs([{ id: "image", label: "图片", type: "IMAGE" }], { image: "test" }).ok, false)
})
