const legacyPath = /^\/api\/uploads\/(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(?:jpe?g|png|webp|bmp)$/i
const variantPath = /^(\/api\/uploads\/(?:images\/|avatars\/managed-)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(?:display|thumb|clear)(\.(?:jpe?g|png|gif|webp|avif|heic|heif|bmp))$/i

/** Map only our versioned local variants, never an arbitrary external URL. */
export function managedImageVariant(path: string, variant: "display" | "thumb" | "clear"): string | null {
  const match = variantPath.exec(path)
  return match ? `${match[1]}-${variant}${match[2]}` : null
}

/** Resolve only trusted site uploads; legacy query strings do not change the stored file. */
export function managedProductImagePath(src: string, allowedOrigins: string[]): string | null {
  try {
    const absolute = /^https?:\/\//i.test(src)
    const url = new URL(src, "http://local.invalid")
    if (url.username || url.password || (!absolute && url.origin !== "http://local.invalid")) return null
    if (absolute && !allowedOrigins.includes(url.origin)) return null
    return legacyPath.test(url.pathname) || variantPath.test(url.pathname) ? url.pathname : null
  } catch {
    return null
  }
}
