export type AvatarImageLoadingStatus = "idle" | "loading" | "loaded" | "error"

export type AvatarDisplayState = "loading" | "image" | "fallback"

/**
 * Keep an existing avatar neutral until the exact current URL has loaded.
 * A stale status from a previous URL must never make the identity fallback
 * flash while the new image is still being decoded.
 */
export function getAvatarDisplayState(
  avatarUrl: string | null,
  trackedAvatarUrl: string | null,
  imageStatus: AvatarImageLoadingStatus,
): AvatarDisplayState {
  if (!avatarUrl) return "fallback"
  if (trackedAvatarUrl !== avatarUrl) return "loading"
  if (imageStatus === "loaded") return "image"
  if (imageStatus === "error") return "fallback"
  return "loading"
}
