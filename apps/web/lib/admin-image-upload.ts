import { toast } from "sonner"
import { adminProductApi } from "@/services/api"

/** Only privileged callers inject this capability into the shared editor. */
export async function uploadAdminImage(file: File, locale: "zh" | "en") {
  const uploaded = await adminProductApi.uploadImage(file)
  if (uploaded.reason === "color_unspecified") {
    toast.warning(locale === "zh"
      ? "图片色彩信息无法识别，已按 sRGB 处理，显示颜色可能略有差异"
      : "The image color profile was not recognized; it was processed as sRGB and colors may differ slightly")
  }
  return uploaded
}
