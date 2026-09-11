import {
  getStorefrontDockPath,
  normalizeStorefrontPath,
  type StorefrontNavKey,
} from "./storefront-routes.ts"

export interface StorefrontDockAction {
  href: string | null
}

export type StorefrontDockNavigationResolution = "pending" | "succeeded" | "interrupted" | "failed"

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
 * Resolves a navigation attempt against the pathname that actually won.
 *
 * While the React/Next navigation transition is pending, the source pathname
 * is deliberately still "pending": it only means that completion has not
 * been observed yet. Once the caller has observed that transition settle,
 * the same source pathname is a genuine navigation failure.
 */
export function resolveStorefrontDockNavigation(
  attempt: Pick<StorefrontDockNavigationAttempt, "fromPathname" | "targetPathname">,
  pathname: string,
  transitionPending = true,
): StorefrontDockNavigationResolution {
  const currentPathname = normalizeStorefrontPath(pathname)
  if (currentPathname === normalizeStorefrontPath(attempt.targetPathname)) return "succeeded"
  if (currentPathname !== normalizeStorefrontPath(attempt.fromPathname)) return "interrupted"
  return transitionPending ? "pending" : "failed"
}

/**
 * Starts one navigation and reports synchronous failures immediately. The
 * caller owns the recovery callback so it can invalidate the matching action
 * and restore the capsule to the pathname-authoritative active item.
 */
export function tryStartStorefrontDockNavigation(
  startNavigation: () => void,
  onSynchronousFailure: () => void,
): boolean {
  try {
    startNavigation()
    return true
  } catch {
    onSynchronousFailure()
    return false
  }
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
