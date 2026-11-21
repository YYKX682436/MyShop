const managedPath = /^\/api\/uploads\/((?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(?:jpe?g|png|gif|webp|bmp))$/i

/** Resolve only local, public managed files through the uncached live route. */
export function liveMediaUrl(src: string, allowedOrigins: string[]): string | null {
  try {
    const absolute = /^https?:\/\//i.test(src)
    const url = new URL(src, "http://local.invalid")
    if (url.username || url.password || (!absolute && url.origin !== "http://local.invalid")) return null
    if (absolute && !allowedOrigins.includes(url.origin)) return null
    const match = url.pathname.match(managedPath)
    return match ? `/image-source/${match[1]}?live=1` : null
  } catch { return null }
}
