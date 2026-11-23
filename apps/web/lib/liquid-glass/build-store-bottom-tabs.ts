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
} from './catalog/helpers'

export interface StoreBottomTabsResult {
  elements: GlassElementConfig[]
  interactions: Record<string, ElementInteraction>
  tabWidth: number
}

/**
 * Builds the 4-tab bottom navigation bar for MyShop, faithfully reusing
 * the upstream liquid-glass-webgl architecture (Apache-2.0, commit b4e0a691).
 *
 * Layout:
 *   - Container: 64dp (or H) full-width capsule glass shape with G2 continuous curvature,
 *     refraction, rim highlight, blur, and outer shadow.
 *   - Tab indicator: 56dp (or H - 8dp) capsule glass shape with DampedDragAnimation,
 *     7-channel chromatic aberration, lens refraction, and inner shadow.
 */
export function buildStoreBottomTabs(
  W: number,
  H: number,
  selectedTab: number,
  onSelect: (index: number) => void,
  rendererRef: React.MutableRefObject<LiquidGlassRenderer | null> | null,
  palette: ThemePalette
): StoreBottomTabsResult {
  const elements: GlassElementConfig[] = []
  const interactions: Record<string, ElementInteraction> = {}

  const tabsCount = 4
  const idPrefix = 'store-dock'

  const CONTAINER_H = H
  const GLASS_PAD = 4 * DP
  const GLASS_H = Math.max(20, CONTAINER_H - 2 * GLASS_PAD)

  const containerX = 0
  const containerW = W
  const containerR = CONTAINER_H / 2

  const glassX = GLASS_PAD
  const glassW = Math.max(1, containerW - 2 * GLASS_PAD)
  const glassR = GLASS_H / 2
  const tabW = glassW / tabsCount

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
  interactions[`${idPrefix}-container`] = dragInteractions

  // === Layer 2: Sliding indicator glass capsule ===
  const indicatorEl = makeGlassShape(
    `${idPrefix}-indicator`,
    { x: glassX, y: GLASS_PAD, w: tabW, h: GLASS_H },
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
      y: GLASS_PAD,
      w: glassW + 2 * GLASS_PAD,
      h: GLASS_H,
    },
    containerCenterX: containerX + containerW / 2,
    containerCenterY: CONTAINER_H / 2,
    containerWidth: containerW,
    tabContentIds: [],
    tabContentRects: [],
  }
  indicatorEl.useContinuousSdf = true
  elements.push(indicatorEl)

  return { elements, interactions, tabWidth: tabW }
}
