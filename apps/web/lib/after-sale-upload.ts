export const AFTER_SALE_MAX_FILES = 5
export const AFTER_SALE_MAX_FILE_SIZE = 5 * 1024 * 1024

const MIME_BY_EXTENSION: Record<string, string> = {
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
}

export type AfterSaleFileValidationError = "SIZE" | "FORMAT"

/** Client-side feedback only; the API repeats every check before storage. */
export async function validateAfterSaleFile(file: File): Promise<AfterSaleFileValidationError | null> {
  if (file.size <= 0 || file.size > AFTER_SALE_MAX_FILE_SIZE) return "SIZE"
  const extension = file.name.split(".").pop()?.toLowerCase() || ""
  const expectedMime = MIME_BY_EXTENSION[extension]
  if (!expectedMime || file.type !== expectedMime) return "FORMAT"
  const header = new Uint8Array(await file.slice(0, 12).arrayBuffer())
  const magicOk = extension === "png"
    ? bytesStartWith(header, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
    : extension === "webp"
      ? bytesStartWith(header, [0x52, 0x49, 0x46, 0x46]) && bytesStartWith(header.slice(8), [0x57, 0x45, 0x42, 0x50])
      : header.length >= 3 && header[0] === 0xff && header[1] === 0xd8 && header[2] === 0xff
  return magicOk ? null : "FORMAT"
}

function bytesStartWith(value: Uint8Array, prefix: number[]) {
  return value.length >= prefix.length && prefix.every((byte, index) => value[index] === byte)
}
