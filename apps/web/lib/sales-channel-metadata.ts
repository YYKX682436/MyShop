import type {
  XianyuAddressTip,
  XianyuCategoryCandidate,
  XianyuCategoryProperty,
  XianyuCategoryPropertyOption,
} from "@/services/api"

type RawObject = Record<string, unknown>

function asObject(value: unknown): RawObject {
  return value && typeof value === "object" && !Array.isArray(value) ? value as RawObject : {}
}

function firstText(record: RawObject, ...keys: string[]): string | null {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === "string" && value.trim()) return value.trim()
    if (typeof value === "number" && Number.isFinite(value)) return String(value)
  }
  return null
}

function firstBoolean(record: RawObject, ...keys: string[]): boolean {
  return keys.some((key) => record[key] === true || record[key] === 1 || record[key] === "true")
}

export function normalizeXianyuAddressTip(value: unknown): XianyuAddressTip {
  const raw = asObject(value)
  return {
    id: firstText(raw, "id"),
    name: firstText(raw, "name"),
    district: firstText(raw, "district"),
    adcode: firstText(raw, "adcode"),
    location: firstText(raw, "location"),
    address: firstText(raw, "address"),
    city: firstText(raw, "city"),
    searchKeyword: firstText(raw, "searchKeyword", "search_keyword"),
    expectedText: firstText(raw, "expectedText", "expected_text"),
  }
}

export function normalizeXianyuCategoryCandidate(value: unknown, index = 0) {
  const raw = asObject(value)
  const pathValue = Array.isArray(raw.path) ? raw.path : Array.isArray(raw.categoryPath) ? raw.categoryPath : []
  const path = pathValue.map((part) => {
    const item = asObject(part)
    return {
      id: firstText(item, "id"),
      name: firstText(item, "name"),
    }
  }).filter((part) => part.name)
  const candidate = {
    raw: value as XianyuCategoryCandidate,
    catId: firstText(raw, "catId", "cat_id"),
    catName: firstText(raw, "catName", "cat_name"),
    channelCatId: firstText(raw, "channelCatId", "channel_cat_id"),
    channelCatName: firstText(raw, "channelCatName", "channel_cat_name"),
    leafId: firstText(raw, "leafId", "leaf_id"),
    tbCatId: firstText(raw, "tbCatId", "tb_cat_id"),
    path,
    isSelected: firstBoolean(raw, "isSelected", "is_selected"),
  }
  const incompleteFields = [
    !candidate.catId && "cat_id",
    !candidate.channelCatId && "channel_cat_id",
    !candidate.channelCatName && "channel_cat_name",
    !candidate.tbCatId && "tb_cat_id",
  ].filter((field): field is string => Boolean(field))
  const pathLabel = path.map((part) => part.name).join(" › ")
    || candidate.catName
    || candidate.channelCatName
    || `分类候选 ${index + 1}`

  return {
    ...candidate,
    incompleteFields,
    canPublish: incompleteFields.length === 0,
    pathLabel,
    key: candidate.catId || candidate.channelCatId || candidate.tbCatId || `${pathLabel}-${index}`,
  }
}

export function normalizeXianyuCategoryProperty(value: unknown): XianyuCategoryProperty {
  const raw = asObject(value)
  const optionsValue = Array.isArray(raw.options) ? raw.options : []
  const options: XianyuCategoryPropertyOption[] = optionsValue.map((optionValue) => {
    const option = asObject(optionValue)
    return {
      ...option,
      propertyId: firstText(option, "propertyId", "property_id"),
      propertyName: firstText(option, "propertyName", "property_name"),
      valueId: firstText(option, "valueId", "value_id"),
      valueName: firstText(option, "valueName", "value_name"),
      channelCatId: firstText(option, "channelCatId", "channel_cat_id"),
      tbCatId: firstText(option, "tbCatId", "tb_cat_id"),
    }
  })

  return {
    ...raw,
    propertyId: firstText(raw, "propertyId", "property_id"),
    propertyName: firstText(raw, "propertyName", "property_name"),
    inputWord: firstText(raw, "inputWord", "input_word"),
    isMultiple: firstBoolean(raw, "isMultiple", "is_multiple"),
    options,
  }
}
