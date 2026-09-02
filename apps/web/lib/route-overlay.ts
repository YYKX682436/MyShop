export interface RouteOverlayCloseOptions {
  skipDirtyCheck?: boolean
}

export function shouldConfirmRouteOverlayClose(dirty: boolean, options: RouteOverlayCloseOptions = {}) {
  return dirty && options.skipDirtyCheck !== true
}
