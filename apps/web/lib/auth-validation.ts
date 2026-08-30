import type { RegisterRequest } from "@/types"

export interface RegistrationFormValues {
  username: string
  email: string
  phone: string
  password: string
  confirmPassword: string
  captcha: string
}

export type RegistrationValidationMessage =
  | "auth.usernameRule"
  | "auth.passwordRule"
  | "auth.passwordMismatch"
  | "auth.phoneRule"
  | "auth.requiredFields"

const USERNAME_PATTERN = /^[A-Za-z0-9_-]{3,20}$/
const PASSWORD_PATTERN = /^[!-~]{6,72}$/
const PHONE_PATTERN = /^1[3-9]\d{9}$/

export function validateRegistrationForm(
  form: RegistrationFormValues,
): RegistrationValidationMessage | null {
  const username = form.username.trim()
  const email = form.email.trim()
  const phone = form.phone.trim()

  if (!username || !email || !phone || !form.password || !form.confirmPassword || !form.captcha.trim()) {
    return "auth.requiredFields"
  }
  if (!USERNAME_PATTERN.test(username)) return "auth.usernameRule"
  if (!PASSWORD_PATTERN.test(form.password)) return "auth.passwordRule"
  if (form.password !== form.confirmPassword) return "auth.passwordMismatch"
  if (!PHONE_PATTERN.test(phone)) return "auth.phoneRule"
  return null
}

/** Build the wire payload explicitly so confirmPassword can never reach the API. */
export function buildRegisterRequest(form: RegistrationFormValues, captchaId: string): RegisterRequest {
  return {
    username: form.username.trim(),
    email: form.email.trim(),
    phone: form.phone.trim(),
    password: form.password,
    captcha_id: captchaId,
    captcha: form.captcha.trim(),
  }
}
