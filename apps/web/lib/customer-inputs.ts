import type { CustomerInputField, CustomerInputValue } from "@/types"

export type CustomerInputPayload = { id: string; value: string }

/**
 * Build the API payload in the current product-field set. The returned order
 * is canonicalized by id so idempotency fingerprints do not depend on UI or
 * JSON array ordering.
 */
export function canonicalCustomerInputs(
  fields: readonly CustomerInputField[],
  values: readonly CustomerInputPayload[] | readonly CustomerInputValue[],
): CustomerInputPayload[] {
  const byId = new Map(values.map(input => [input.id, input.value.trim()]))
  return fields
    .map(field => ({ id: field.id, value: byId.get(field.id) ?? "" }))
    .sort((left, right) => left.id.localeCompare(right.id))
}

export function customerInputsFromDrafts(
  fields: readonly CustomerInputField[],
  drafts: Record<string, string>,
): CustomerInputPayload[] {
  return canonicalCustomerInputs(fields, fields.map(field => ({
    id: field.id,
    value: drafts[field.id] ?? "",
  })))
}

export function validateCustomerInputValue(value: string): string | null {
  const normalized = value.trim()
  if (!normalized) return "客户填写信息不能为空"
  if (normalized.length > 255) return "客户填写信息最多255个字符"
  if ([...normalized].some(character => /\p{C}/u.test(character))) {
    return "客户填写信息不能包含换行或控制字符"
  }
  return null
}

export function validateCustomerInputs(
  fields: readonly CustomerInputField[],
  drafts: Record<string, string>,
): { ok: true; values: CustomerInputPayload[] } | { ok: false; field?: string; message: string } {
  const values = customerInputsFromDrafts(fields, drafts)
  for (const field of fields) {
    const error = validateCustomerInputValue(drafts[field.id] ?? "")
    if (error) return { ok: false, field: field.id, message: `${field.label}：${error}` }
  }
  return { ok: true, values }
}
