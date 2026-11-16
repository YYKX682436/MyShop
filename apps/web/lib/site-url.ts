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
