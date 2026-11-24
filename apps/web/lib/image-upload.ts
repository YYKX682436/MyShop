export const ALLOWED_IMAGE_TYPES = [
  "image/jpeg",
  "image/png",
  "image/apng",
  "image/gif",
  "image/webp",
  "image/avif",
  "image/heic",
  "image/heif",
  "image/bmp",
] as const

export const ALLOWED_IMAGE_ACCEPT = ".jpg,.jpeg,.png,.apng,.gif,.webp,.avif,.heic,.heif,.bmp"
export const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024

export const AVATAR_ALLOWED_IMAGE_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/avif",
  "image/heic",
  "image/heif",
] as const

export const AVATAR_ALLOWED_IMAGE_ACCEPT = ".jpg,.jpeg,.png,.webp,.avif,.heic,.heif"
export const MAX_AVATAR_SIZE_BYTES = 5 * 1024 * 1024

export type AvatarFileValidationError = "format" | "size"

export function getAvatarFileValidationError(
  file: Pick<File, "type" | "size"> & Partial<Pick<File, "name">>,
): AvatarFileValidationError | null {
  const fileType = file.type.toLowerCase()
  if (!(AVATAR_ALLOWED_IMAGE_TYPES as readonly string[]).includes(fileType)
      && !((!fileType || fileType === "application/octet-stream") && /\.(jpe?g|png|webp|avif|heic|heif)$/i.test(file.name || ""))) {
    return "format"
  }
  if (file.size > MAX_AVATAR_SIZE_BYTES) {
    return "size"
  }
  return null
}

export function validateImageFile(file: Pick<File, "type" | "size"> & Partial<Pick<File, "name">>): string | null {
  const fileType = file.type.toLowerCase()
  if (!(ALLOWED_IMAGE_TYPES as readonly string[]).includes(fileType)
      && !((!fileType || fileType === "application/octet-stream") && /\.(jpe?g|png|apng|gif|webp|avif|heic|heif|bmp)$/i.test(file.name || ""))) {
    return "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/AVIF/HEIC/HEIF/BMP"
  }
  if (file.size > MAX_IMAGE_SIZE_BYTES) {
    return "图片大小不能超过 10MB"
  }
  return null
}
