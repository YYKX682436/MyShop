import Script from "next/script"

const SCRIPT = String.raw`
(() => {
  try {
    if (window.__myshopLayoutBooted) return;
    window.__myshopLayoutBooted = true;
    const root = document.documentElement;
    const storageKey = "myshop-layout-preference";
    const requestEvent = "myshop:layout-preference-request";
    const changeEvent = "myshop:layout-preference-change";
    const deviceViewport = "width=device-width, initial-scale=1, viewport-fit=cover";
    const desktopWidth = 1024;
    let fallbackTimer = 0;
    let fallbackVersion = 0;
    let resizeTimer = 0;

    function viewportMeta() {
      return document.querySelector('meta[name="viewport"]');
    }

    function declaredViewportWidth(viewport) {
      const match = viewport?.content.match(/(?:^|,)\s*width\s*=\s*(\d+)/i);
      return Number(match?.[1] || 0);
    }

    function ownsViewport(viewport) {
      return viewport?.dataset.myshopDesktopWidth === "true"
        && viewport.content === viewport.dataset.myshopDesktopContent;
    }

    function clearViewportOwnership(viewport) {
      if (!viewport) return;
      delete viewport.dataset.myshopDesktopWidth;
      delete viewport.dataset.myshopDesktopContent;
    }

    function hasNativeDesktopViewport(viewport) {
      const owned = ownsViewport(viewport);
      if (viewport?.dataset.myshopDesktopWidth === "true" && !owned) {
        clearViewportOwnership(viewport);
      }
      const currentWidth = declaredViewportWidth(viewport);
      return !ownsViewport(viewport) && (window.innerWidth >= 768 || currentWidth >= 768);
    }

    function isSmallTouchScreen() {
      const screenWidth = Number(window.screen?.width || window.innerWidth);
      const screenHeight = Number(window.screen?.height || window.innerHeight);
      return navigator.maxTouchPoints > 0 && Math.min(screenWidth, screenHeight) <= 640;
    }

    function isNativeDesktopRequest(viewport) {
      const physicalWidth = Number(window.screen?.width || window.innerWidth);
      const layoutWidth = Math.max(declaredViewportWidth(viewport), window.innerWidth);
      return isSmallTouchScreen()
        && hasNativeDesktopViewport(viewport)
        && layoutWidth >= 768
        && layoutWidth > physicalWidth * 1.1;
    }

    function isBrowserDesktopRequest() {
      if (!isSmallTouchScreen()) return false;
      const ua = navigator.userAgent || "";
      const hints = navigator.userAgentData;
      const mobileUa = /\biPhone\b|\biPod\b|\bAndroid\b.*\bMobile\b/i.test(ua);
      const androidUa = /\bAndroid\b/i.test(ua);
      const desktopUa = /\bMacintosh\b|\bWindows NT\b|\bX11\b|\bCrOS x86_64\b/i.test(ua);
      const desktopHint = hints && hints.mobile === false && !androidUa;
      return !mobileUa && (desktopUa || desktopHint);
    }

    function readPreference() {
      try {
        return localStorage.getItem(storageKey) === "desktop" ? "desktop" : "auto";
      } catch {
        return "auto";
      }
    }

    function resolveSource(preference) {
      const viewport = viewportMeta();
      const nativeViewport = hasNativeDesktopViewport(viewport);
      if (preference === "desktop" && (isSmallTouchScreen() || ownsViewport(viewport) || nativeViewport)) return "user";
      if (isBrowserDesktopRequest()) return nativeViewport ? "native" : "browser";
      if (isNativeDesktopRequest(viewport)) return "native";
      return "auto";
    }

    function isNarrowTouchViewport() {
      return isSmallTouchScreen() && window.innerWidth < 768;
    }

    function publishState(preference, source) {
      const viewport = viewportMeta();
      const canToggle = preference === "desktop"
        ? source === "user" && (ownsViewport(viewport) || isNarrowTouchViewport())
        : source === "auto" && isNarrowTouchViewport();
      const canToggleValue = String(canToggle);
      const mode = source === "auto" ? "auto" : "desktop";
      if (root.dataset.layoutPreference === preference
        && root.dataset.layoutSource === source
        && root.dataset.layoutMode === mode
        && root.dataset.layoutCanToggle === canToggleValue) return;
      root.dataset.layoutPreference = preference;
      root.dataset.layoutSource = source;
      root.dataset.layoutMode = mode;
      root.dataset.layoutCanToggle = canToggleValue;
      window.dispatchEvent(new Event(changeEvent));
    }

    function cancelFallback() {
      fallbackVersion += 1;
      if (fallbackTimer) window.clearTimeout(fallbackTimer);
      fallbackTimer = 0;
    }

    function restoreDeviceViewport(viewport) {
      if (!ownsViewport(viewport)) return false;
      viewport.content = deviceViewport;
      clearViewportOwnership(viewport);
      return true;
    }

    function setDesktopViewport(viewport) {
      if (!viewport || hasNativeDesktopViewport(viewport)) return false;
      const screenWidth = Number(window.screen?.width || window.innerWidth || 390);
      const scale = Math.min(1, Math.max(0.1, screenWidth / desktopWidth));
      const content = "width=" + desktopWidth
        + ", initial-scale=" + Number(scale.toFixed(4))
        + ", user-scalable=yes, viewport-fit=cover";
      viewport.dataset.myshopDesktopWidth = "true";
      viewport.dataset.myshopDesktopContent = content;
      if (viewport.content !== content) viewport.content = content;
      return true;
    }

    function scheduleFallback() {
      if (fallbackTimer) return;
      const version = ++fallbackVersion;
      fallbackTimer = window.setTimeout(() => {
        fallbackTimer = 0;
        if (version !== fallbackVersion) return;

        const latestPreference = readPreference();
        const latestSource = resolveSource(latestPreference);
        publishState(latestPreference, latestSource);

        const viewport = viewportMeta();
        if ((latestSource === "browser" || latestSource === "user")
          && !hasNativeDesktopViewport(viewport)
          && !ownsViewport(viewport)) {
          setDesktopViewport(viewport);
          publishState(latestPreference, resolveSource(latestPreference));
        }
      }, 800);
    }

    function reconcile(reason) {
      cancelFallback();
      const preference = readPreference();
      let source = resolveSource(preference);
      const viewport = viewportMeta();

      if (preference === "auto" && source === "auto" && ownsViewport(viewport)) {
        restoreDeviceViewport(viewport);
        source = resolveSource(preference);
      } else if ((reason === "request" || reason === "storage") && preference === "auto") {
        restoreDeviceViewport(viewport);
        source = resolveSource(preference);
      }

      publishState(preference, source);
      if (source === "auto" || source === "native") return;
      if (hasNativeDesktopViewport(viewport)) return;
      if (source === "user" && reason === "request") {
        setDesktopViewport(viewport);
        publishState(preference, resolveSource(preference));
        return;
      }
      if (ownsViewport(viewport)) return;
      scheduleFallback();
    }

    window.addEventListener(requestEvent, event => {
      const preference = event.detail?.preference;
      if (preference !== "auto" && preference !== "desktop") return;
      if (preference === "desktop" && !isSmallTouchScreen()
        && !hasNativeDesktopViewport(viewportMeta())) return;
      try {
        localStorage.setItem(storageKey, preference);
      } catch {}
      reconcile("request");
    });

    window.addEventListener("storage", event => {
      if (event.key === storageKey) reconcile("storage");
    });
    window.addEventListener("pageshow", () => reconcile("navigation"));
    window.addEventListener("popstate", () => reconcile("navigation"));
    window.addEventListener("orientationchange", () => reconcile("viewport"));
    window.screen?.orientation?.addEventListener?.("change", () => reconcile("viewport"));
    window.addEventListener("resize", () => {
      if (resizeTimer) window.clearTimeout(resizeTimer);
      resizeTimer = window.setTimeout(() => reconcile("viewport"), 80);
    });

    reconcile("boot");
  } catch {}
})();
`

export function LayoutModeScript() {
  return <Script id="myshop-layout-mode" strategy="beforeInteractive">{SCRIPT}</Script>
}
