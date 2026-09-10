import { getStorefrontActiveTab, type StorefrontNavKey } from "./storefront-routes.ts"

export type StorefrontDockKey = StorefrontNavKey | "search"

export interface StorefrontDockAction {
  href: string | null
  searchOpen: boolean
}

/**
 * Resolves the visual Dock selection without changing the primary route model.
 * Search is a home-owned panel, so it only replaces Home while the home route
 * is visible and the panel is open.
 */
export function getStorefrontDockKey(pathname: string, searchOpen: boolean): StorefrontDockKey | null {
  const normalizedPath = pathname.replace(/\/+$/, "") || "/"

  if (normalizedPath === "/") {
    return searchOpen ? "search" : "home"
  }

  return getStorefrontActiveTab(pathname)
}

/**
 * Describes the side effects for a Dock action. The component owns navigation
 * and context updates; this pure result keeps the routing behavior testable.
 */
export function getStorefrontDockAction(
  key: StorefrontDockKey,
  pathname: string,
  searchOpen: boolean,
): StorefrontDockAction {
  switch (key) {
    case "home":
      return { href: pathname === "/" ? null : "/", searchOpen: false }
    case "search":
      return { href: pathname === "/" ? null : "/", searchOpen: pathname === "/" ? !searchOpen : true }
    case "orders":
      return { href: "/my/orders", searchOpen: false }
    case "me":
      return { href: "/my", searchOpen: false }
  }
}
