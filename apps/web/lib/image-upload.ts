export const ALLOWED_IMAGE_TYPES = [
  "image/jpeg",
  "image/png",
  "image/gif",
  "image/webp",
  "image/bmp",
] as const

export const ALLOWED_IMAGE_ACCEPT = ".jpg,.jpeg,.png,.gif,.webp,.bmp"
export const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024

export const QR_ALLOWED_IMAGE_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
] as const

export const QR_ALLOWED_IMAGE_ACCEPT = ".jpg,.jpeg,.png,.webp"

export const AVATAR_ALLOWED_IMAGE_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
] as const

export const AVATAR_ALLOWED_IMAGE_ACCEPT = ".jpg,.jpeg,.png,.webp"
export const MAX_AVATAR_SIZE_BYTES = 5 * 1024 * 1024

export type AvatarFileValidationError = "format" | "size"

export function getAvatarFileValidationError(
  file: Pick<File, "type" | "size">,
): AvatarFileValidationError | null {
  const fileType = file.type.toLowerCase()
  if (!(AVATAR_ALLOWED_IMAGE_TYPES as readonly string[]).includes(fileType)) {
    return "format"
  }
  if (file.size > MAX_AVATAR_SIZE_BYTES) {
    return "size"
  }
  return null
}

export function validateImageFile(file: Pick<File, "type" | "size">): string | null {
  const fileType = file.type.toLowerCase()
  if (!(ALLOWED_IMAGE_TYPES as readonly string[]).includes(fileType)) {
    return "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP"
  }
  if (file.size > MAX_IMAGE_SIZE_BYTES) {
    return "图片大小不能超过 10MB"
  }
  return null
}

export function validateQrImageFile(file: Pick<File, "type" | "size">): string | null {
  const fileType = file.type.toLowerCase()
  if (!(QR_ALLOWED_IMAGE_TYPES as readonly string[]).includes(fileType)) {
    return "二维码仅支持 JPG/PNG/WebP 格式"
  }
  if (file.size > MAX_IMAGE_SIZE_BYTES) {
    return "图片大小不能超过 10MB"
  }
  return null
}
