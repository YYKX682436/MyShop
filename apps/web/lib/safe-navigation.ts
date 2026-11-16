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
