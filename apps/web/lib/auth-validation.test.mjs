import assert from "node:assert/strict"
import test from "node:test"

import {
  buildRegisterRequest,
  mapRegistrationApiError,
  registrationErrorConsumesCaptcha,
  REGISTRATION_ERROR_CODES,
  validateRegistrationField,
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
  assert.deepEqual(validateRegistrationForm(validForm), {})

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

test("required fields are reported independently", () => {
  assert.deepEqual(validateRegistrationForm({
    username: "",
    email: "",
    phone: "",
    password: "",
    confirmPassword: "",
    captcha: "",
  }), {
    username: "auth.usernameRequired",
    email: "auth.emailRequired",
    phone: "auth.phoneRequired",
    password: "auth.passwordRequired",
    confirmPassword: "auth.confirmPasswordRequired",
    captcha: "auth.captchaRequired",
  })
})

test("username and email validation match the backend's canonical formats", () => {
  for (const username of ["abc", "123", "abc123", "abc_def", "abc-def", "abcdefghijklmnopqrst"]) {
    assert.equal(validateRegistrationField("username", { ...validForm, username }), null, username)
  }
  for (const username of ["ab", "abcdefghijklmnopqrstu", "中文", "ab c", "abc@def", "abc.def"]) {
    assert.equal(validateRegistrationField("username", { ...validForm, username }), "auth.usernameRule", username)
  }

  for (const email of ["a@b.co", "buyer+tag@example.co.uk", "buyer_name@example.com", "name.surname@example.com"]) {
    assert.equal(validateRegistrationField("email", { ...validForm, email }), null, email)
  }
  for (const email of ["buyer", "buyer@localhost", "two@@example.com", "a..b@example.com", "buyer @example.com", "a".repeat(250) + "@example.com"]) {
    assert.equal(validateRegistrationField("email", { ...validForm, email }), "auth.emailRule", email)
  }
})

test("password, confirmation, and phone validation are field-specific", () => {
  for (const password of ["666666", "abcdef", "abc123", "Abc@12", "Abc@123"]) {
    assert.equal(validateRegistrationField("password", { ...validForm, password }), null, password)
  }
  for (const password of ["12345", "中文密码", "abc def", "abc😀12", "a".repeat(73)]) {
    assert.equal(validateRegistrationField("password", { ...validForm, password }), "auth.passwordRule", password)
  }
  assert.equal(
    validateRegistrationField("confirmPassword", { ...validForm, confirmPassword: "abcdeg" }),
    "auth.passwordMismatch",
  )

  assert.equal(validateRegistrationField("phone", { ...validForm, phone: "18888888888" }), null)
  for (const phone of ["1380013800", "138001380000", "12800138000", "中文手机号", "1380013800a", "+8613800138000"]) {
    assert.equal(validateRegistrationField("phone", { ...validForm, phone }), "auth.phoneRule", phone)
  }
})

test("duplicate registration error codes map to the owning fields without message parsing", () => {
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.usernameExists), {
    username: "auth.usernameExists",
  })
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.emailExists), {
    email: "auth.emailExists",
  })
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.phoneExists), {
    phone: "auth.phoneExists",
  })
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.identifierConflict, { field: "username" }), {
    username: "auth.usernamePhoneConflict",
  })
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.identifierConflict, { field: "phone" }), {
    phone: "auth.phoneUsernameConflict",
  })
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.captchaInvalid), {
    captcha: "auth.captchaInvalid",
  })
  assert.equal(mapRegistrationApiError(60001), null)
})

test("server-side request validation maps through stable field parameters", () => {
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.badRequest, {
    field: "email",
    validation: "format",
  }), { email: "auth.emailRule" })
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.badRequest, {
    field: "username",
    validation: "required",
  }), { username: "auth.usernameRequired" })
  assert.deepEqual(mapRegistrationApiError(REGISTRATION_ERROR_CODES.badRequest, {
    field: "captchaId",
    validation: "required",
  }), { captcha: "auth.captchaRequired" })
  assert.equal(mapRegistrationApiError(REGISTRATION_ERROR_CODES.badRequest), null)
})

test("captcha refresh is reserved for consumed captcha responses", () => {
  assert.equal(registrationErrorConsumesCaptcha(REGISTRATION_ERROR_CODES.captchaInvalid), true)
  assert.equal(registrationErrorConsumesCaptcha(REGISTRATION_ERROR_CODES.usernameExists), true)
  assert.equal(registrationErrorConsumesCaptcha(REGISTRATION_ERROR_CODES.badRequest, {
    field: "email",
    validation: "format",
  }), false)
  assert.equal(registrationErrorConsumesCaptcha(10005), false)
  assert.equal(registrationErrorConsumesCaptcha(10006), false)
  assert.equal(registrationErrorConsumesCaptcha(10001, { captcha_consumed: true }), true)
})
