import {
  getStorefrontDockPath,
  normalizeStorefrontPath,
  type StorefrontNavKey,
} from "./storefront-routes.ts"

export interface StorefrontDockAction {
  href: string | null
}

/**
 * Computes the target navigation action for a primary Dock slot.
 *
 * Checks against the currently displayed or in-flight destination pathname.
 * If the current destination already matches the target, returns `{ href: null }`.
 */
export function getStorefrontDockAction(
  key: StorefrontNavKey,
  pathname: string,
): StorefrontDockAction {
  const targetPathname = getStorefrontDockPath(key)
  if (!targetPathname || normalizeStorefrontPath(pathname) === targetPathname) {
    return { href: null }
  }
  return { href: targetPathname }
}
