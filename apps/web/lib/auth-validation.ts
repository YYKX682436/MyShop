import type { RegisterRequest } from "@/types"

export interface RegistrationFormValues {
  username: string
  email: string
  phone: string
  password: string
  confirmPassword: string
  captcha: string
}

export type RegistrationField = keyof RegistrationFormValues
export type RegistrationValidationMessage =
  | "auth.usernameRequired"
  | "auth.usernameRule"
  | "auth.usernameExists"
  | "auth.usernamePhoneConflict"
  | "auth.emailRequired"
  | "auth.emailRule"
  | "auth.emailExists"
  | "auth.phoneRequired"
  | "auth.phoneRule"
  | "auth.phoneExists"
  | "auth.phoneUsernameConflict"
  | "auth.passwordRequired"
  | "auth.passwordRule"
  | "auth.confirmPasswordRequired"
  | "auth.passwordMismatch"
  | "auth.captchaRequired"
  | "auth.captchaInvalid"

export type RegistrationErrors = Partial<Record<RegistrationField, RegistrationValidationMessage>>

const USERNAME_PATTERN = /^[A-Za-z0-9_-]{3,20}$/
const EMAIL_PATTERN = /^(?!.*\.\.)[A-Za-z0-9](?:[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{0,62}[A-Za-z0-9])?@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$/
const PASSWORD_PATTERN = /^[!-~]{6,72}$/
const PHONE_PATTERN = /^1[3-9]\d{9}$/

export const REGISTRATION_ERROR_CODES = {
  badRequest: 10001,
  usernameExists: 20001,
  emailExists: 20002,
  captchaInvalid: 20003,
  phoneExists: 20008,
  identifierConflict: 20009,
} as const

export function validateRegistrationField(
  field: RegistrationField,
  form: RegistrationFormValues,
): RegistrationValidationMessage | null {
  switch (field) {
    case "username":
      if (!form.username.trim()) return "auth.usernameRequired"
      return USERNAME_PATTERN.test(form.username.trim()) ? null : "auth.usernameRule"
    case "email":
      if (!form.email.trim()) return "auth.emailRequired"
      return form.email.trim().length <= 255 && EMAIL_PATTERN.test(form.email.trim())
        ? null
        : "auth.emailRule"
    case "phone":
      if (!form.phone.trim()) return "auth.phoneRequired"
      return PHONE_PATTERN.test(form.phone.trim()) ? null : "auth.phoneRule"
    case "password":
      if (!form.password) return "auth.passwordRequired"
      return PASSWORD_PATTERN.test(form.password) ? null : "auth.passwordRule"
    case "confirmPassword":
      if (!form.confirmPassword) return "auth.confirmPasswordRequired"
      return form.password === form.confirmPassword ? null : "auth.passwordMismatch"
    case "captcha":
      return form.captcha.trim() ? null : "auth.captchaRequired"
  }
}

export function validateRegistrationForm(form: RegistrationFormValues): RegistrationErrors {
  const errors: RegistrationErrors = {}
  const fields: RegistrationField[] = [
    "username",
    "email",
    "phone",
    "password",
    "confirmPassword",
    "captcha",
  ]
  for (const field of fields) {
    const message = validateRegistrationField(field, form)
    if (message) errors[field] = message
  }
  return errors
}

function serverValidationMessage(
  field: string,
  validation: unknown,
): RegistrationValidationMessage | null {
  const required = validation === "required"
  switch (field) {
    case "username":
      return required ? "auth.usernameRequired" : "auth.usernameRule"
    case "email":
      return required ? "auth.emailRequired" : "auth.emailRule"
    case "phone":
      return required ? "auth.phoneRequired" : "auth.phoneRule"
    case "password":
      return required ? "auth.passwordRequired" : "auth.passwordRule"
    case "captcha":
      return required ? "auth.captchaRequired" : "auth.captchaInvalid"
    case "captchaId":
      return "auth.captchaRequired"
    default:
      return null
  }
}

/** Map stable API error codes and field params to inline registration feedback. */
export function mapRegistrationApiError(
  code: number,
  params?: Record<string, unknown>,
): RegistrationErrors | null {
  switch (code) {
    case REGISTRATION_ERROR_CODES.usernameExists:
      return { username: "auth.usernameExists" }
    case REGISTRATION_ERROR_CODES.emailExists:
      return { email: "auth.emailExists" }
    case REGISTRATION_ERROR_CODES.phoneExists:
      return { phone: "auth.phoneExists" }
    case REGISTRATION_ERROR_CODES.captchaInvalid:
      return { captcha: "auth.captchaInvalid" }
    case REGISTRATION_ERROR_CODES.identifierConflict: {
      const field = params?.field
      if (field === "phone") return { phone: "auth.phoneUsernameConflict" }
      if (field === "both") {
        return {
          username: "auth.usernamePhoneConflict",
          phone: "auth.phoneUsernameConflict",
        }
      }
      return { username: "auth.usernamePhoneConflict" }
    }
    case REGISTRATION_ERROR_CODES.badRequest: {
      const field = params?.field
      if (typeof field !== "string") return null
      const outputField = field === "captchaId" ? "captcha" : field
      const message = serverValidationMessage(field, params?.validation)
      return message ? { [outputField]: message } : null
    }
    default:
      return null
  }
}

/** Whether an API response confirms that registration consumed its one-time image captcha. */
export function registrationErrorConsumesCaptcha(
  code: number,
  params?: Record<string, unknown>,
): boolean {
  if (params?.captcha_consumed === true) return true
  return code === REGISTRATION_ERROR_CODES.captchaInvalid
    || code === REGISTRATION_ERROR_CODES.usernameExists
    || code === REGISTRATION_ERROR_CODES.emailExists
    || code === REGISTRATION_ERROR_CODES.phoneExists
    || code === REGISTRATION_ERROR_CODES.identifierConflict
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
