import assert from "node:assert/strict"
import test from "node:test"

import {
  buildRegisterRequest,
  validateRegistrationForm,
} from "./auth-validation.ts"

const validForm = {
  username: " Tom123 ",
  email: " buyer@example.com ",
  phone: " 13800138000 ",
  password: "abcdef",
  confirmPassword: "abcdef",
  captcha: "1234",
}

test("matching confirmation is the only form state that builds a register request", () => {
  assert.equal(validateRegistrationForm(validForm), null)

  const request = buildRegisterRequest(validForm, "captcha-id")
  assert.deepEqual(request, {
    username: "Tom123",
    email: "buyer@example.com",
    phone: "13800138000",
    password: "abcdef",
    captcha_id: "captcha-id",
    captcha: "1234",
  })
  assert.equal(Object.hasOwn(request, "confirmPassword"), false)
})

test("mismatched confirmation fails before a request payload can be built", () => {
  assert.equal(validateRegistrationForm({ ...validForm, confirmPassword: "abcdeg" }), "auth.passwordMismatch")
})

test("frontend validation covers the requested identifier and password examples", () => {
  for (const username of ["abc", "123", "abc123", "abc_def", "abc-def", "abcdefghijklmnopqrst"]) {
    assert.equal(validateRegistrationForm({ ...validForm, username }), null, username)
  }
  for (const username of ["ab", "abcdefghijklmnopqrstu", "中文", "ab c", "abc@def", "abc.def"]) {
    assert.equal(validateRegistrationForm({ ...validForm, username }), "auth.usernameRule", username)
  }
  for (const password of ["666666", "abcdef", "abc123", "Abc@12", "Abc@123"]) {
    assert.equal(validateRegistrationForm({ ...validForm, password, confirmPassword: password }), null, password)
  }
  for (const password of ["12345", "中文密码", "abc def", "abc😀12", "a".repeat(73)]) {
    assert.equal(validateRegistrationForm({ ...validForm, password, confirmPassword: password }), "auth.passwordRule", password)
  }
})

test("phone validation accepts repeated digits but rejects malformed or country-code values", () => {
  assert.equal(validateRegistrationForm({ ...validForm, phone: "18888888888" }), null)
  for (const phone of ["1380013800", "138001380000", "12800138000", "中文手机号", "1380013800a", "+8613800138000"]) {
    assert.equal(validateRegistrationForm({ ...validForm, phone }), "auth.phoneRule", phone)
  }
})
