import {
  getStorefrontDockPath,
  normalizeStorefrontPath,
  type StorefrontNavKey,
} from "./storefront-routes.ts"

export interface StorefrontDockAction {
  href: string | null
}

export type StorefrontDockNavigationResolution = "pending" | "succeeded" | "interrupted"

export interface StorefrontDockNavigationAttempt {
  id: number
  fromPathname: string
  targetPathname: string
  phase: "snapping" | "navigating"
}

/**
 * Async animation and navigation callbacks must prove that they still belong
 * to the latest user action before they are allowed to commit anything.
 */
export function isCurrentStorefrontDockAction(actionId: number, currentActionId: number): boolean {
  return actionId === currentActionId
}

/**
 * Resolves a navigation attempt against the pathname that actually won. The
 * source pathname remaining current is deliberately still "pending": the
 * caller's navigation timeout treats that state as a failed navigation and
 * restores the capsule to the real active item.
 */
export function resolveStorefrontDockNavigation(
  attempt: Pick<StorefrontDockNavigationAttempt, "fromPathname" | "targetPathname">,
  pathname: string,
): StorefrontDockNavigationResolution {
  const currentPathname = normalizeStorefrontPath(pathname)
  if (currentPathname === normalizeStorefrontPath(attempt.targetPathname)) return "succeeded"
  if (currentPathname !== normalizeStorefrontPath(attempt.fromPathname)) return "interrupted"
  return "pending"
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
  const targetPathname = getStorefrontDockPath(key)
  if (!targetPathname || normalizeStorefrontPath(pathname) === targetPathname) {
    return { href: null }
  }
  return { href: targetPathname }
}
