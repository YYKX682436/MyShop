export type StorefrontNavKey = "home" | "orders" | "me"

function normalizeStorefrontPath(pathname: string): string {
  return pathname.replace(/\/+$/, "") || "/"
}

/**
 * The Dock is a home-page control surface. Detail, account, order and auth
 * routes keep the shared storefront shell, but never render the Dock.
 */
export function isStorefrontDockVisible(pathname: string): boolean {
  return pathname === "/"
}

/**
 * Returns the storefront primary tab for a pathname.
 * Order routes are checked before /my routes so /my/orders cannot activate Me.
 */
export function getStorefrontActiveTab(pathname: string): StorefrontNavKey | null {
  const normalizedPath = normalizeStorefrontPath(pathname)

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
