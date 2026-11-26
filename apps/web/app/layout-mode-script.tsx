const SCRIPT = `
(() => {
  try {
    if (window.__myshopLayoutBooted) return;
    window.__myshopLayoutBooted = true;
    const key = "myshop-layout-preference";
    const saved = localStorage.getItem(key);
    const ua = navigator.userAgent || "";
    const hints = navigator.userAgentData;
    const smallTouchScreen = navigator.maxTouchPoints > 0 && (screen.width || innerWidth) <= 640;
    const mobileUa = /\\biPhone\\b|\\biPod\\b|\\bAndroid\\b.*\\bMobile\\b/i.test(ua);
    const androidUa = /\\bAndroid\\b/i.test(ua);
    const desktopUa = /\\bMacintosh\\b|\\bWindows NT\\b|\\bX11\\b|\\bCrOS x86_64\\b/i.test(ua);
    const desktopHint = hints && hints.mobile === false && !androidUa;
    const desktopRequest = smallTouchScreen && !mobileUa && (desktopUa || desktopHint);
    const mode = saved === "desktop" || saved === null && desktopRequest ? "desktop" : "auto";
    document.documentElement.dataset.layoutMode = mode;
    if (mode === "desktop" && smallTouchScreen) {
      window.setTimeout(() => {
        const viewport = document.querySelector('meta[name="viewport"]');
        if (!viewport) return;
        const browserWidth = Number(viewport.content.match(/(?:^|,)\\s*width\\s*=\\s*(\\d+)/i)?.[1]);
        if (innerWidth >= 768 || browserWidth >= 768 && !viewport.dataset.myshopDesktopWidth) return;
        viewport.content = "width=1024, initial-scale=1, viewport-fit=cover";
        viewport.dataset.myshopDesktopWidth = "true";
      }, 800);
    }
  } catch {}
})();
`

export function LayoutModeScript() {
  return <script id="myshop-layout-mode" dangerouslySetInnerHTML={{ __html: SCRIPT }} />
}
