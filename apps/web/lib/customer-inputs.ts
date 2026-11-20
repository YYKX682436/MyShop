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

export function validateCustomerInputValue(value: string, field?: CustomerInputField): string | null {
  const normalized = value.trim()
  if (field?.type === "IMAGE" || field?.type === "CASCADE") return "此充值字段暂不支持购买"
  if (!normalized) return field?.required === false ? null : "客户填写信息不能为空"
  if (normalized.length > 255) return "客户填写信息最多255个字符"
  if ([...normalized].some(character => /\p{C}/u.test(character)
      && !(field?.type === "MULTILINE" && ["\n", "\r", "\t"].includes(character)))) {
    return "客户填写信息不能包含换行或控制字符"
  }
  if (field?.type === "NUMBER" && !/^[+-]?[0-9]+(?:\.[0-9]+)?$/.test(normalized)) return "请输入数字"
  if (field?.type === "SELECT" && !field.options?.some(option => option.value === normalized)) return "请选择有效选项"
  if (field?.regex) {
    try { if (!new RegExp(`^(?:${field.regex})$`, "u").test(normalized)) return "格式不符合要求" }
    catch { return "字段校验规则不可用" }
  }
  return null
}

export function validateCustomerInputs(
  fields: readonly CustomerInputField[],
  drafts: Record<string, string>,
): { ok: true; values: CustomerInputPayload[] } | { ok: false; field?: string; message: string } {
  const values = customerInputsFromDrafts(fields, drafts)
  for (const field of fields) {
    const error = validateCustomerInputValue(drafts[field.id] ?? "", field)
    if (error) return { ok: false, field: field.id, message: `${field.label}：${error}` }
  }
  return { ok: true, values }
}
