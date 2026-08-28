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

test("customer input changes rotate the checkout intent fingerprint", () => {
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
