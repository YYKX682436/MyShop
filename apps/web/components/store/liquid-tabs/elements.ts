import type { GlassElementConfig, GlassHighlight } from "@/lib/vendor/liquid-glass/renderer"

export const GROUP = "store-tabs"
export const DRAWING_HEIGHT = 160
export const HORIZONTAL_BLEED = 60
export const BAR_TOP = 48
export const BAR_HEIGHT = 64
export const LABELS = ["首页", "订单", "消息", "我的"] as const
export const PATHS = ["/", "/my/orders", "/messages", "/my"] as const

// Filled paths keep the same upstream Canvas2D icon/label texture pipeline.
const ICONS = [
  "M12 2 2 10.5l1.5 1.8L5 11v9h5v-6h4v6h5v-9l1.5 1.3 1.5-1.8L12 2Z",
  "M5 3h14a1 1 0 0 1 1 1v17l-3-1.5L14.5 21 12 19.5 9.5 21 7 19.5 4 21V4a1 1 0 0 1 1-1Zm2 4v2h10V7H7Zm0 4v2h10v-2H7Zm0 4v2h7v-2H7Z",
  "M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-5l-4 3v-3H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Zm1 4v2h14V8H5Zm0 4v2h10v-2H5Z",
  "M12 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10ZM3 21a9 9 0 0 1 18 0H3Z",
]

const highlight: GlassHighlight = { mode: 0, color: [1, 1, 1], angle: Math.PI / 4, falloff: 1, alpha: 0.5, widthDp: 0.5 }
const shadow = { radius: 24, alpha: 0.1, offsetX: 0, offsetY: 4, color: [0, 0, 0] as [number, number, number] }

type Color = [number, number, number, number]
function shape(id: string, rect: { x: number; y: number; w: number; h: number }): GlassElementConfig {
  return {
    id, kind: "glass-shape", rect, cornerRadius: rect.h / 2,
    refractionHeight: 24, refractionAmount: -24, depthEffect: true, chromaticAberration: false,
    blurRadius: 8, saturation: 1.5, brightness: 0, contrast: 1,
    tintColor: [0, 0, 0, 0], surfaceColor: [0, 0, 0, 0], highlight: { ...highlight },
    outerShadow: { ...shadow }, label: "", labelColor: [0, 0, 0, 1], showChevron: false,
    isInteractive: false, scroll: false,
  }
}

/** Recreates the locked upstream four-tab catalog geometry, with MyShop labels/icons. */
export function buildElements(width: number, dark: boolean, bleed = HORIZONTAL_BLEED): GlassElementConfig[] {
  const x = bleed
  const y = BAR_TOP
  const tabWidth = (width - 8) / 4
  const contentColor: Color = dark ? [1, 1, 1, 1] : [0, 0, 0, 1]
  const container = shape(`${GROUP}-container`, { x, y, w: width, h: BAR_HEIGHT })
  container.surfaceColor = dark ? [18 / 255, 18 / 255, 18 / 255, 0.4] : [250 / 255, 250 / 255, 250 / 255, 0.4]
  container.isBottomTabContainer = { groupId: GROUP, tabsCount: 4 }
  container.independentBackdrop = true

  const tabs = LABELS.map((label, i): GlassElementConfig => ({
    ...shape(`${GROUP}-tab-${i}`, { x: x + 4 + i * tabWidth, y: y + 4, w: tabWidth, h: 56 }),
    kind: "text", cornerRadius: 0, refractionHeight: 0, refractionAmount: 0,
    depthEffect: false, blurRadius: 0, saturation: 1, highlight: null, outerShadow: null,
    text: { content: label, color: contentColor, fontSizePx: 12, fontWeight: 400,
      align: "center", wrap: false, paddingPx: 0, halo: "none",
      icon: { path: ICONS[i], size: 24, layoutSize: 28, color: contentColor, viewport: 24 } },
    isBottomTabContent: { groupId: GROUP, containerCenterX: x + width / 2, containerCenterY: y + 32, containerWidth: width },
  }))

  const indicator = shape(`${GROUP}-indicator`, { x: x + 4, y: y + 4, w: tabWidth, h: 56 })
  indicator.refractionHeight = 10
  indicator.refractionAmount = -14
  indicator.depthEffect = false
  indicator.chromaticAberration = true
  indicator.blurRadius = 0
  indicator.saturation = 1
  indicator.innerShadow = { radius: 8, alpha: 0.3, offsetX: 0, offsetY: 8 }
  indicator.independentBackdrop = false
  indicator.isBottomTabIndicator = {
    groupId: GROUP, dragWidth: tabWidth,
    dimColor: dark ? [1, 1, 1, 1] : [0, 0, 0, 1],
    accentColor: dark ? [0, 145 / 255, 1] : [0, 136 / 255, 1],
    containerRect: { x, y: y + 4, w: width, h: 56 },
    containerCenterX: x + width / 2, containerCenterY: y + 32, containerWidth: width,
    tabContentIds: tabs.map(tab => tab.id), tabContentRects: tabs.map(tab => tab.rect),
  }
  return [container, ...tabs, indicator]
}
