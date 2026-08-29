import type { ContactItem } from "@/types"

export const MAX_CONTACT_ITEMS = 3

const CONTACT_ITEM_FIELDS = new Set(["name", "url", "qr_url"])

function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return (url.protocol === "http:" || url.protocol === "https:") && url.hostname.length > 0
  } catch {
    return false
  }
}

function isSafeQrUrl(value: string): boolean {
  return isHttpUrl(value) || (value.startsWith("/") && !value.startsWith("//") && !/[\u0000-\u001f\u007f]/u.test(value))
}

function readOptionalText(value: unknown, field: string, index: number): string | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value !== "string") {
    throw new Error(`联系方式第 ${index + 1} 项的 ${field} 必须是文本`)
  }
  const normalized = value.trim()
  return normalized || undefined
}

/**
 * Validate and normalize the public contact-item shape. Empty optional fields
 * are removed so the serialized value stays canonical and stable.
 */
export function normalizeContactItems(value: unknown): ContactItem[] {
  if (!Array.isArray(value)) {
    throw new Error("联系方式必须是 JSON 数组")
  }
  if (value.length > MAX_CONTACT_ITEMS) {
    throw new Error(`联系方式最多配置 ${MAX_CONTACT_ITEMS} 项`)
  }

  return value.map((rawItem, index) => {
    if (!rawItem || typeof rawItem !== "object" || Array.isArray(rawItem)) {
      throw new Error(`联系方式第 ${index + 1} 项必须是对象`)
    }

    const item = rawItem as Record<string, unknown>
    const unsupportedField = Object.keys(item).find(field => !CONTACT_ITEM_FIELDS.has(field))
    if (unsupportedField) {
      throw new Error(`联系方式第 ${index + 1} 项包含不支持的字段 ${unsupportedField}`)
    }

    if (typeof item.name !== "string") {
      throw new Error(`联系方式第 ${index + 1} 项名称必须是文本`)
    }
    const name = item.name.trim()
    if (!name) throw new Error(`联系方式第 ${index + 1} 项名称不能为空`)
    if ([...name].length > 30) throw new Error(`联系方式第 ${index + 1} 项名称不能超过 30 个字符`)

    const url = readOptionalText(item.url, "链接", index)
    if (url && !isHttpUrl(url)) {
      throw new Error(`联系方式第 ${index + 1} 项链接必须是合法的 http/https URL`)
    }

    const qrUrl = readOptionalText(item.qr_url, "二维码地址", index)
    if (qrUrl && !isSafeQrUrl(qrUrl)) {
      throw new Error(`联系方式第 ${index + 1} 项二维码地址必须是合法的 http/https URL 或站内路径`)
    }
    if (!url && !qrUrl) {
      throw new Error(`联系方式第 ${index + 1} 项必须填写链接或二维码`)
    }

    return {
      name,
      ...(url ? { url } : {}),
      ...(qrUrl ? { qr_url: qrUrl } : {}),
    }
  })
}

export function parseContactItems(raw: string | null | undefined): ContactItem[] {
  if (raw === undefined || raw === null || raw.trim() === "") return []
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    throw new Error("联系方式必须是有效 JSON 数组")
  }
  return normalizeContactItems(parsed)
}

export function serializeContactItems(items: readonly ContactItem[]): string {
  return JSON.stringify(normalizeContactItems(items))
}
