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

    function reconcileViewport() {
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
