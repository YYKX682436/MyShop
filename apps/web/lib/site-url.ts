type SiteEnvironment = {
  PUBLIC_SITE_URL?: string
  NEXT_PUBLIC_BASE_URL?: string
  NODE_ENV?: string
}

/** Resolve the storefront origin at request time, including container runtime config. */
export function getPublicSiteUrl(env: SiteEnvironment = process.env): string {
  const configured = env.PUBLIC_SITE_URL || env.NEXT_PUBLIC_BASE_URL
  if (!configured && env.NODE_ENV === "production") {
    throw new Error("PUBLIC_SITE_URL must be configured for the production storefront")
  }
  const url = new URL(configured || "http://localhost:3000")
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) {
    throw new Error("PUBLIC_SITE_URL must be an HTTP(S) storefront URL")
  }
  return url.origin
}

/** Validate the request Host against the configured storefront and its sibling sites. */
export function getRequestSiteUrl(host: string | null, env: SiteEnvironment = process.env): string {
  const main = new URL(getPublicSiteUrl(env))
  if (!host || !/^[a-z0-9.-]+(?::[0-9]{1,5})?$/i.test(host)) {
    throw new Error("Invalid storefront Host")
  }
  const request = new URL(`${main.protocol}//${host}`)
  const hostname = request.hostname.toLowerCase()
  const mainHost = main.hostname.toLowerCase()
  const suffix = mainHost.includes(".") ? mainHost.slice(mainHost.indexOf(".")) : ""
  const prefix = suffix && hostname.endsWith(suffix) ? hostname.slice(0, -suffix.length) : ""
  const isSibling = !!prefix && /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(prefix)
  if ((hostname !== mainHost && !isSibling) || request.port !== main.port) {
    throw new Error("Invalid storefront Host")
  }
  return request.origin
}
