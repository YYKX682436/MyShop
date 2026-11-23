const managedPath = /^\/api\/uploads\/(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(?:jpe?g|png|webp|bmp)$/i

/** Convert only an exact, trusted site upload URL into the existing local optimizer path. */
export function managedProductImagePath(src: string, allowedOrigins: string[]): string | null {
  if (managedPath.test(src)) return src
  try {
    const url = new URL(src)
    if (!["http:", "https:"].includes(url.protocol) || !allowedOrigins.includes(url.origin) || url.username || url.password || url.search || url.hash) return null
    return managedPath.test(url.pathname) ? url.pathname : null
  } catch {
    return null
  }
}
