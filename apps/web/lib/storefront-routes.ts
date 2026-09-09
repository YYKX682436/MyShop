export type StorefrontNavKey = "home" | "orders" | "me"

/**
 * Returns the storefront primary tab for a pathname.
 * Order routes are checked before /my routes so /my/orders cannot activate Me.
 */
export function getStorefrontActiveTab(pathname: string): StorefrontNavKey | null {
  const normalizedPath = pathname.replace(/\/+$/, "") || "/"

  if (normalizedPath === "/" || normalizedPath.startsWith("/product/")) {
    return "home"
  }

  if (
    normalizedPath === "/my/orders" ||
    normalizedPath.startsWith("/my/orders/") ||
    normalizedPath === "/order" ||
    normalizedPath.startsWith("/order/")
  ) {
    return "orders"
  }

  if (
    normalizedPath === "/my" ||
    normalizedPath.startsWith("/my/") ||
    normalizedPath === "/profile" ||
    normalizedPath.startsWith("/profile/")
  ) {
    return "me"
  }

  return null
}
