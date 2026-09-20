export type StorefrontNavKey = "home" | "orders" | "me"

export interface StorefrontDockDestination {
  readonly key: StorefrontNavKey
  readonly pathname: string
}

/**
 * The exact first-level destinations owned by the storefront Dock.
 *
 * This is the single route identity source used by both visibility and the
 * active item. UI labels and icons intentionally remain in the component.
 */
export const STOREFRONT_DOCK_DESTINATIONS: readonly StorefrontDockDestination[] = [
  { key: "home", pathname: "/" },
  { key: "orders", pathname: "/my/orders" },
  { key: "me", pathname: "/my" },
]

export function normalizeStorefrontPath(pathname: string): string {
  return pathname.replace(/\/+$/, "") || "/"
}

/**
 * Product detail pages use their own breadcrumb and purchase Dock instead of
 * the shared floating storefront header. Other storefront routes keep it.
 */
export function isStorefrontHeaderVisible(pathname: string): boolean {
  return !normalizeStorefrontPath(pathname).startsWith("/product/")
}

/**
 * Returns the exact first-level Dock destination for a pathname.
 *
 * Child/detail routes intentionally do not match here. For example,
 * `/product/abc` may belong to the Home section, but it is not the Home Dock
 * destination and therefore must not render the Dock.
 */
export function getStorefrontDockDestination(pathname: string): StorefrontDockDestination | null {
  const normalizedPath = normalizeStorefrontPath(pathname)
  return STOREFRONT_DOCK_DESTINATIONS.find((destination) => destination.pathname === normalizedPath) ?? null
}

/**
 * Returns the canonical pathname for a configured first-level Dock item.
 */
export function getStorefrontDockPath(key: StorefrontNavKey): string | null {
  return STOREFRONT_DOCK_DESTINATIONS.find((destination) => destination.key === key)?.pathname ?? null
}

/**
 * The Dock is present only on an exact first-level destination. Detail,
 * account, order and auth routes keep the shared storefront shell, but never
 * render the Dock.
 */
export function isStorefrontDockVisible(pathname: string): boolean {
  return getStorefrontDockDestination(pathname) !== null
}

/**
 * Returns the active Dock item for an exact first-level destination.
 * Secondary routes return null even when they belong to a primary section.
 */
export function getStorefrontActiveTab(pathname: string): StorefrontNavKey | null {
  return getStorefrontDockDestination(pathname)?.key ?? null
}

/**
 * Returns the broader storefront section for routes that are not Dock
 * destinations. This deliberately remains separate from Dock visibility.
 */
export function getStorefrontRouteSection(pathname: string): StorefrontNavKey | null {
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
    normalizedPath.startsWith("/my/")
  ) {
    return "me"
  }

  return null
}
