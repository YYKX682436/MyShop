import type * as React from 'react'
import type { ElementInteraction } from './context'
import type { GlassElementConfig, LiquidGlassRenderer } from './renderer'
import {
  DEFAULT_HIGHLIGHT,
  DEFAULT_SHADOW,
  DP,
  type ThemePalette,
} from './catalog/types'
import {
  makeGlassShape,
  makeTabDragInteractions,
  makeText,
} from './catalog/helpers'

export interface StoreBottomTabsResult {
  elements: GlassElementConfig[]
  interactions: Record<string, ElementInteraction>
  tabWidth: number
}

// 24x24 standard filled SVG paths
export const STORE_DOCK_ICONS = {
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  orders: 'M18 17H6v-2h12v2zm0-4H6v-2h12v2zm0-4H6V7h12v2zM3 22l1.5-1.5L6 22l1.5-1.5L9 22l1.5-1.5L12 22l1.5-1.5L15 22l1.5-1.5L18 22l1.5-1.5L21 22V2l-1.5 1.5L18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2 4.5 3.5 3 2v20z',
  messages: 'M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z',
  me: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
}

/**
 * Builds the 4-tab bottom navigation bar for MyShop, faithfully reusing
 * the upstream liquid-glass-webgl architecture (Apache-2.0, commit b4e0a691).
 *
 * Layout:
 *   - Container: 64dp full-width capsule glass shape with G2 continuous curvature,
 *     refraction, rim highlight, blur, and outer shadow.
 *   - Tab contents: 4 text/icon items with continuous curvature scaling,
 *     rendered into the scene FBO before indicator pass.
 *   - Tab indicator: 56dp capsule glass shape with DampedDragAnimation,
 *     7-channel chromatic aberration, lens refraction, and inner shadow.
 */
export function buildStoreBottomTabs(
  W: number,
  H: number,
  selectedTab: number,
  onSelect: (index: number) => void,
  rendererRef: React.MutableRefObject<LiquidGlassRenderer | null> | null,
  palette: ThemePalette,
  labels: readonly [string, string, string, string] = ['首页', '订单', '消息', '我的'],
  unread = 0,
): StoreBottomTabsResult {
  const elements: GlassElementConfig[] = []
  const interactions: Record<string, ElementInteraction> = {}

  const tabsCount = 4
  const idPrefix = 'store-dock'

  // Faithful to upstream LiquidBottomTabs.kt and build-bottom-tabs.ts
  const CONTAINER_H = 64 * DP
  const GLASS_H = 56 * DP
  const GLASS_PAD = 4 * DP

  const containerX = 0
  const containerW = W
  const containerR = CONTAINER_H / 2

  const glassX = GLASS_PAD
  const glassW = Math.max(1, containerW - 2 * GLASS_PAD)
  const glassR = GLASS_H / 2
  const tabW = glassW / tabsCount
  const glassY = GLASS_PAD

  // === Layer 1: Container glass capsule ===
  const containerEl = makeGlassShape(
    `${idPrefix}-container`,
    { x: containerX, y: 0, w: containerW, h: CONTAINER_H },
    {
      cornerRadius: containerR,
      refractionHeight: 24 * DP,
      refractionAmount: -24 * DP,
      blurRadius: 8 * DP,
      saturation: 1.5,
      surfaceColor: palette.tabsContainer,
      highlight: { ...DEFAULT_HIGHLIGHT, alpha: 0.5 },
      depthEffect: true,
    }
  )
  containerEl.isBottomTabContainer = { groupId: idPrefix, tabsCount }
  containerEl.independentBackdrop = true
  containerEl.useContinuousSdf = true
  elements.push(containerEl)

  // Drag interactions for the tab bar
  const dragInteractions = makeTabDragInteractions(
    idPrefix,
    tabW,
    tabsCount,
    onSelect,
    rendererRef
  )

  const tabDefs = [
    { label: labels[0] || '首页', iconPath: STORE_DOCK_ICONS.home, badge: undefined },
    { label: labels[1] || '订单', iconPath: STORE_DOCK_ICONS.orders, badge: undefined },
    {
      label: labels[2] || '消息',
      iconPath: STORE_DOCK_ICONS.messages,
      badge: unread > 0 ? (unread > 99 ? '99+' : String(unread)) : undefined,
    },
    { label: labels[3] || '我的', iconPath: STORE_DOCK_ICONS.me, badge: undefined },
  ]

  // === Layer 2: 4 Tab contents ===
  for (let i = 0; i < tabsCount; i++) {
    const id = `${idPrefix}-tab-${i}`
    const def = tabDefs[i]
    const tabEl = makeText(
      id,
      { x: glassX + tabW * i, y: glassY, w: tabW, h: GLASS_H },
      def.label,
      {
        color: palette.tabsContentColor,
        fontSizePx: 12,
        fontWeight: 400,
        align: 'center',
        paddingPx: 0,
        halo: palette.tabsTextHalo,
        icon: {
          path: def.iconPath,
          size: 22,
          layoutSize: 26,
          color: palette.tabsContentColor,
          viewport: 24,
        },
        badge: def.badge ? { text: def.badge } : undefined,
      }
    )
    tabEl.isBottomTabContent = {
      groupId: idPrefix,
      containerCenterX: containerX + containerW / 2,
      containerCenterY: CONTAINER_H / 2,
      containerWidth: containerW,
    }
    elements.push(tabEl)

    interactions[id] = {
      onTap: () => {
        const r = rendererRef?.current
        if (r) {
          r.setTabSelected(idPrefix, i, tabsCount)
        }
        onSelect(i)
      },
      onDragStart: dragInteractions.onDragStart,
      onDrag: dragInteractions.onDrag,
      onDragEnd: dragInteractions.onDragEnd,
      onPointerDown: () => {
        rendererRef?.current?.setTabPressed(idPrefix, true, tabsCount)
      },
      onPointerUp: () => {
        rendererRef?.current?.setTabPressed(idPrefix, false, tabsCount)
      },
      onPointerCancel: () => {
        rendererRef?.current?.setTabPressed(idPrefix, false, tabsCount)
      },
    }
  }

  // Container interactions
  interactions[`${idPrefix}-container`] = {
    ...dragInteractions,
    onPointerDown: () => {
      rendererRef?.current?.setTabPressed(idPrefix, true, tabsCount)
    },
    onPointerUp: () => {
      rendererRef?.current?.setTabPressed(idPrefix, false, tabsCount)
    },
    onPointerCancel: () => {
      rendererRef?.current?.setTabPressed(idPrefix, false, tabsCount)
    },
  }

  // === Layer 3: Sliding indicator glass capsule ===
  const indicatorEl = makeGlassShape(
    `${idPrefix}-indicator`,
    { x: glassX, y: glassY, w: tabW, h: GLASS_H },
    {
      cornerRadius: glassR,
      refractionHeight: 10 * DP,
      refractionAmount: -14 * DP,
      blurRadius: 0,
      saturation: 1.0,
      tintColor: [0, 0, 0, 0],
      surfaceColor: [0, 0, 0, 0],
      highlight: { ...DEFAULT_HIGHLIGHT, alpha: 0.5 },
      outerShadow: { ...DEFAULT_SHADOW },
      innerShadow: { radius: 8 * DP, alpha: 0.3, offsetX: 0, offsetY: 8 * DP },
      chromaticAberration: true,
    }
  )
  indicatorEl.independentBackdrop = false
  indicatorEl.isBottomTabIndicator = {
    groupId: idPrefix,
    dragWidth: tabW,
    dimColor: palette.backIconColor,
    accentColor: [...palette.tabsAccent] as [number, number, number],
    containerRect: {
      x: glassX - GLASS_PAD,
      y: glassY,
      w: glassW + 2 * GLASS_PAD,
      h: GLASS_H,
    },
    containerCenterX: containerX + containerW / 2,
    containerCenterY: CONTAINER_H / 2,
    containerWidth: containerW,
    tabContentIds: Array.from({ length: tabsCount }, (_, i) => `${idPrefix}-tab-${i}`),
    tabContentRects: Array.from({ length: tabsCount }, (_, i) => ({
      x: glassX + tabW * i,
      y: glassY,
      w: tabW,
      h: GLASS_H,
    })),
  }
  indicatorEl.useContinuousSdf = true
  elements.push(indicatorEl)

  return { elements, interactions, tabWidth: tabW }
}
