import type { StorefrontNavKey } from "./storefront-routes.ts"

export interface StorefrontDockAction {
  href: string | null
}

/**
 * Describes the navigation performed by one of the configured primary Dock
 * slots. Search is intentionally absent: it belongs to the header and is not
 * a navigation destination.
 */
export function getStorefrontDockAction(
  key: StorefrontNavKey,
  pathname: string,
): StorefrontDockAction {
  switch (key) {
    case "home":
      return { href: pathname === "/" ? null : "/" }
    case "orders":
      return { href: "/my/orders" }
    case "me":
      return { href: "/my" }
  }
}
