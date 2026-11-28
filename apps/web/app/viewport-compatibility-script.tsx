import Script from "next/script"

const SCRIPT = String.raw`
(() => {
  try {
    try { localStorage.removeItem("myshop-layout-preference") } catch {}

    let scheduled = false
    const viewportWidth = content => {
      const match = content.match(/(?:^|,)\s*width\s*=\s*(\d+)/i)
      return Number(match?.[1] || 0)
    }

    function appDisplay() {
      const state = window.__AIKAYUN_DISPLAY__
      return state && state.version === 1 && state.origin === location.origin &&
        typeof state.desktop === "boolean" && Number.isFinite(state.topBarDp) && state.topBarDp >= 0 && state.topBarDp <= 100 &&
        Number.isFinite(state.containerWidthDp) && state.containerWidthDp > 0 ? state : null
    }

    function reconcileViewport() {
      const state = appDisplay()
      const root = document.documentElement
      if (state) {
        root.dataset.aikayunShell = "1"
        const width = Number(window.visualViewport?.width || window.innerWidth || state.containerWidthDp)
        const gap = Number((state.topBarDp * width / state.containerWidthDp).toFixed(3)) + "px"
        if (root.style.getPropertyValue("--aikayun-topbar-gap") !== gap) root.style.setProperty("--aikayun-topbar-gap", gap)
        const content = state.desktop
          ? "width=1280, initial-scale=" + Math.min(1, state.containerWidthDp / 1280) + ", viewport-fit=cover"
          : "width=device-width, initial-scale=1, viewport-fit=cover"
        const metas = Array.from(document.querySelectorAll('meta[name="viewport"]'))
        const meta = metas[0] || document.head.appendChild(Object.assign(document.createElement("meta"), { name: "viewport" }))
        for (const extra of metas.slice(1)) extra.remove()
        if (meta.content !== content || metas.length > 1) meta.content = content
        return
      }
      delete root.dataset.aikayunShell
      root.style.removeProperty("--aikayun-topbar-gap")
      const viewports = Array.from(document.querySelectorAll('meta[name="viewport"]'))
      const screenWidth = Number(window.screen?.width || 0)
      const wide = viewports
        .map(meta => ({ meta, width: viewportWidth(meta.content) }))
        .filter(item => item.width >= 768 && screenWidth > 0 && screenWidth < item.width * 0.8)
        .sort((a, b) => b.width - a.width)[0]

      if (!wide) {
        for (const meta of viewports) delete meta.dataset.myshopViewportCompatContent
        return
      }

      for (const meta of viewports) {
        if (meta !== wide.meta) meta.remove()
      }

      const { meta, width } = wide
      if (meta.dataset.myshopViewportCompatContent === meta.content) return

      const visibleWidth = Number(window.visualViewport?.width || window.innerWidth || 0)
      if (!visibleWidth || visibleWidth >= width * 0.8) return

      const scale = Math.min(1, Math.max(0.1, screenWidth / width))
      const initialScale = Number(scale.toFixed(4))
      const content = /(^|,)\s*initial-scale\s*=/i.test(meta.content)
        ? meta.content.replace(/(^|,)\s*initial-scale\s*=\s*[^,]*/i, (_, prefix) => prefix + " initial-scale=" + initialScale)
        : meta.content + ", initial-scale=" + initialScale

      meta.dataset.myshopViewportCompatContent = content
      meta.content = content
    }

    function scheduleReconcile() {
      if (scheduled) return
      scheduled = true
      window.requestAnimationFrame(() => {
        scheduled = false
        reconcileViewport()
      })
    }

    const observer = new MutationObserver(scheduleReconcile)
    observer.observe(document.head, {
      attributes: true,
      attributeFilter: ["content", "name"],
      childList: true,
      subtree: true,
    })
    window.addEventListener("aikayun:display", scheduleReconcile)
    window.addEventListener("resize", scheduleReconcile, { passive: true })
    window.addEventListener("pageshow", scheduleReconcile, { passive: true })
    window.visualViewport?.addEventListener("resize", scheduleReconcile, { passive: true })
    scheduleReconcile()
  } catch {}
})();
`

export function ViewportCompatibilityScript() {
  return <Script id="myshop-viewport-compatibility" strategy="beforeInteractive">{SCRIPT}</Script>
}
