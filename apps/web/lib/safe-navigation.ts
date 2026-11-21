/** Keep post-login navigation inside this shop, including its query and anchor. */
export function safeLoginRedirect(value: string | null | undefined): string {
  if (!value?.startsWith("/") || value.startsWith("//") || /[\\\u0000-\u0020\u007f]/.test(value)) {
    return "/"
  }

  const origin = "https://myshop.invalid"
  const destination = new URL(value, origin)
  return destination.origin === origin
    ? `${destination.pathname}${destination.search}${destination.hash}`
    : "/"
}

/** Only navigate to a sibling shop on the current domain. */
export function safeHomeSiteUrl(value: unknown, currentOrigin: string): string | null {
  if (typeof value !== "string") return null
  try {
    const current = new URL(currentOrigin)
    const destination = new URL(value)
    const dot = current.hostname.indexOf(".")
    if (dot < 0 || destination.protocol !== current.protocol || destination.port !== current.port || destination.username || destination.password) return null
    const suffix = current.hostname.slice(dot).toLowerCase()
    const host = destination.hostname.toLowerCase()
    const prefix = host.endsWith(suffix) ? host.slice(0, -suffix.length) : ""
    if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(prefix)) return null
    return destination.origin
  } catch { return null }
}
