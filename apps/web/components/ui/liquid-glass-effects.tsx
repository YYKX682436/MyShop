"use client"

import { useEffect } from "react"

const SVG_NS = "http://www.w3.org/2000/svg"
const TARGET_SELECTOR = [
  ".static-material",
  ".transient-glass",
  ".large-material",
  ".content-surface",
  ".content-surface-strong",
  ".content-surface-muted",
  ".dashboard-panel",
  ".dashboard-kpi-strip",
  ".dashboard-attention-strip",
  ".dashboard-system-facts",
  ".admin-metric-strip",
  ".admin-table-scroll",
  ".store-dock-active",
  ".segmented-control",
  ".store-search-field",
  ".store-home-hero",
  ".product-card",
  ".product-summary",
  ".product-description-region",
  ".product-image-stage--detail",
  ".account-balance",
  ".auth-form-panel",
  ".liquid-glass-control",
  ".segmented-glass-indicator",
  ".myshop-dialog",
  ".myshop-alert-dialog",
  ".myshop-sheet-right",
  ".myshop-sheet-left",
  ".myshop-sheet-top",
  ".myshop-sheet-bottom",
  ".myshop-popup",
  ".myshop-toast",
  '[data-sidebar="sidebar"]',
  ".recharts-default-tooltip",
  'input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([type="file"]):not([type="submit"])',
  "textarea",
  "select",
  '[role="combobox"]',
].join(",")

type GlassInstance = {
  element: HTMLElement
  filter: SVGFilterElement
  image: SVGFEImageElement
  red: SVGFEDisplacementMapElement
  green: SVGFEDisplacementMapElement
  blue: SVGFEDisplacementMapElement
  resizeObserver?: ResizeObserver
  baseScale: number
  factor: number
  velocity: number
  targetFactor: number
  frame: number
  lastFrame: number
  hovered: boolean
  pressed: boolean
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

function svgElement<K extends keyof SVGElementTagNameMap>(tag: K): SVGElementTagNameMap[K] {
  return document.createElementNS(SVG_NS, tag) as SVGElementTagNameMap[K]
}

function roundedRectMap(width: number, height: number, radius: number): string | null {
  const mapScale = Math.min(1, 512 / Math.max(width, height))
  const mapWidth = Math.max(96, Math.round(width * mapScale))
  const mapHeight = Math.max(96, Math.round(height * mapScale))
  const canvas = document.createElement("canvas")
  canvas.width = mapWidth
  canvas.height = mapHeight
  const context = canvas.getContext("2d", { willReadFrequently: true })
  if (!context) return null

  const imageData = context.createImageData(mapWidth, mapHeight)
  const pixels = imageData.data
  const safeRadius = clamp(radius, 0, Math.min(width, height) / 2)
  const halfWidth = width / 2
  const halfHeight = height / 2
  const halfInnerWidth = Math.max(0, halfWidth - safeRadius)
  const halfInnerHeight = Math.max(0, halfHeight - safeRadius)
  const bandCenter = Math.max(2, safeRadius * 0.28)
  const bandWidth = Math.max(2, safeRadius * 0.28)

  for (let y = 0; y < mapHeight; y += 1) {
    const py = ((y + 0.5) / mapHeight) * height
    const dy = py - halfHeight
    for (let x = 0; x < mapWidth; x += 1) {
      const px = ((x + 0.5) / mapWidth) * width
      const dx = px - halfWidth
      const qx = Math.abs(dx) - halfInnerWidth
      const qy = Math.abs(dy) - halfInnerHeight
      const outsideX = Math.max(qx, 0)
      const outsideY = Math.max(qy, 0)
      const outsideLength = Math.hypot(outsideX, outsideY)
      const signedDistance = outsideLength + Math.min(Math.max(qx, qy), 0) - safeRadius
      let nx = 0
      let ny = 0

      if (signedDistance <= 0) {
        if (outsideLength > 0) {
          nx = Math.sign(dx) * outsideX / outsideLength
          ny = Math.sign(dy) * outsideY / outsideLength
        } else if (qx > qy) {
          nx = Math.sign(dx)
        } else {
          ny = Math.sign(dy)
        }
      }

      const inward = Math.max(0, -signedDistance)
      const opticalBand = Math.exp(-Math.pow((inward - bandCenter) / bandWidth, 2))
      const index = (y * mapWidth + x) * 4
      pixels[index] = Math.round(128 - nx * opticalBand * 126)
      pixels[index + 1] = Math.round(128 - ny * opticalBand * 126)
      pixels[index + 2] = 128
      pixels[index + 3] = 255
    }
  }

  context.putImageData(imageData, 0, 0)
  return canvas.toDataURL("image/png")
}

function elementRadius(element: HTMLElement, width: number, height: number) {
  const radiusText = getComputedStyle(element).borderTopLeftRadius
  const radiusValue = Number.parseFloat(radiusText) || 0
  const radius = radiusText.includes("%")
    ? Math.min(width, height) * radiusValue / 100
    : radiusValue
  return clamp(radius, 0, Math.min(width, height) / 2)
}

function setInteraction(instance: GlassInstance, hovered: boolean, pressed = false, reducedMotion = false) {
  instance.hovered = hovered
  instance.pressed = pressed
  instance.element.dataset.glassState = pressed ? "pressed" : hovered ? "hover" : "idle"
  instance.targetFactor = pressed ? 1.52 : hovered ? 1.2 : 1

  if (reducedMotion) {
    instance.factor = instance.targetFactor
    instance.velocity = 0
    instance.red.setAttribute("scale", String(instance.baseScale * instance.factor + instance.baseScale * 0.055))
    instance.green.setAttribute("scale", String(instance.baseScale * instance.factor))
    instance.blue.setAttribute("scale", String(instance.baseScale * instance.factor - instance.baseScale * 0.055))
    return
  }

  if (instance.frame) return
  const animate = (time: number) => {
    const delta = instance.lastFrame === 0 ? 1 / 60 : clamp((time - instance.lastFrame) / 1000, 0, 0.04)
    instance.lastFrame = time
    const displacement = instance.targetFactor - instance.factor
    const acceleration = displacement * 190 - instance.velocity * 25
    instance.velocity += acceleration * delta
    instance.factor += instance.velocity * delta
    instance.red.setAttribute("scale", String(instance.baseScale * instance.factor + instance.baseScale * 0.055))
    instance.green.setAttribute("scale", String(instance.baseScale * instance.factor))
    instance.blue.setAttribute("scale", String(instance.baseScale * instance.factor - instance.baseScale * 0.055))

    if (Math.abs(displacement) < 0.003 && Math.abs(instance.velocity) < 0.015) {
      instance.factor = instance.targetFactor
      instance.velocity = 0
      instance.red.setAttribute("scale", String(instance.baseScale * instance.factor + instance.baseScale * 0.055))
      instance.green.setAttribute("scale", String(instance.baseScale * instance.factor))
      instance.blue.setAttribute("scale", String(instance.baseScale * instance.factor - instance.baseScale * 0.055))
      instance.frame = 0
      instance.lastFrame = 0
      return
    }

    instance.frame = requestAnimationFrame(animate)
  }

  instance.frame = requestAnimationFrame(animate)
}

function makeChannelMatrix(channel: "red" | "green" | "blue") {
  if (channel === "red") return "1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0"
  if (channel === "green") return "0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 1 0"
  return "0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 1 0"
}

export function LiquidGlassEffects() {
  useEffect(() => {
    const userAgent = navigator.userAgent
    const isBlink = /Chrome|Chromium|Edg\/|OPR\//i.test(userAgent) && !/CriOS|EdgiOS|FxiOS/i.test(userAgent)
    const supportsBackdropReference = CSS.supports("backdrop-filter", 'url("#liquid-glass-probe")')
    const reducedMotionQuery = window.matchMedia("(prefers-reduced-motion: reduce)")
    const reducedTransparencyQuery = window.matchMedia("(prefers-reduced-transparency: reduce)")

    if (!isBlink || !supportsBackdropReference || reducedTransparencyQuery.matches) {
      document.documentElement.dataset.liquidGlassMode = reducedTransparencyQuery.matches ? "solid" : "frost"
      return () => {
        delete document.documentElement.dataset.liquidGlassMode
      }
    }

    document.documentElement.dataset.liquidGlassMode = "live-svg"
    const defsRoot = svgElement("svg")
    defsRoot.setAttribute("aria-hidden", "true")
    defsRoot.setAttribute("width", "0")
    defsRoot.setAttribute("height", "0")
    defsRoot.style.cssText = "position:fixed;left:-2px;top:-2px;width:0;height:0;overflow:hidden;pointer-events:none;opacity:0"
    const defs = svgElement("defs")
    defsRoot.appendChild(defs)
    document.body.appendChild(defsRoot)

    const instances = new Map<HTMLElement, GlassInstance>()
    const pending = new Set<HTMLElement>()
    const mapCache = new Map<string, string>()
    let nextId = 0
    let lastPointerTarget: HTMLElement | null = null
    let pressedTarget: HTMLElement | null = null

    const resizeInstance = (instance: GlassInstance) => {
      const rect = instance.element.getBoundingClientRect()
      if (rect.width < 2 || rect.height < 2) return

      const width = Math.round(rect.width)
      const height = Math.round(rect.height)
      const radius = elementRadius(instance.element, width, height)
      const cacheKey = `${width}:${height}:${Math.round(radius)}`
      let map = mapCache.get(cacheKey)
      if (!map) {
        map = roundedRectMap(width, height, radius) ?? undefined
        if (!map) return
        mapCache.set(cacheKey, map)
        if (mapCache.size > 64) {
          const oldestKey = mapCache.keys().next().value
          if (oldestKey) mapCache.delete(oldestKey)
        }
      }

      const padding = Math.ceil(clamp(Math.min(width, height) * 0.42, 12, 42))
      instance.filter.setAttribute("x", String(-padding))
      instance.filter.setAttribute("y", String(-padding))
      instance.filter.setAttribute("width", String(width + padding * 2))
      instance.filter.setAttribute("height", String(height + padding * 2))
      instance.image.setAttribute("width", String(width))
      instance.image.setAttribute("height", String(height))
      instance.image.setAttribute("href", map)
      instance.baseScale = clamp(Math.min(width, height) * 0.72, 18, 44)
      const scale = instance.baseScale * instance.factor
      instance.red.setAttribute("scale", String(scale + instance.baseScale * 0.055))
      instance.green.setAttribute("scale", String(scale))
      instance.blue.setAttribute("scale", String(scale - instance.baseScale * 0.055))
    }

    const register = (element: HTMLElement) => {
      if (!element.isConnected || instances.has(element)) return
      pending.delete(element)
      const filter = svgElement("filter")
      const id = `myshop-liquid-glass-${++nextId}`
      filter.id = id
      filter.setAttribute("filterUnits", "userSpaceOnUse")
      filter.setAttribute("primitiveUnits", "userSpaceOnUse")
      filter.setAttribute("color-interpolation-filters", "sRGB")

      const image = svgElement("feImage")
      image.setAttribute("x", "0")
      image.setAttribute("y", "0")
      image.setAttribute("preserveAspectRatio", "none")
      image.setAttribute("result", "lens-map")

      const makeDisplacement = (scale: number, result: string) => {
        const displacement = svgElement("feDisplacementMap")
        displacement.setAttribute("in", "SourceGraphic")
        displacement.setAttribute("in2", "lens-map")
        displacement.setAttribute("scale", String(scale))
        displacement.setAttribute("xChannelSelector", "R")
        displacement.setAttribute("yChannelSelector", "G")
        displacement.setAttribute("result", result)
        return displacement
      }

      const red = makeDisplacement(0, "red-displaced")
      const green = makeDisplacement(0, "green-displaced")
      const blue = makeDisplacement(0, "blue-displaced")
      const isolate = (input: string, channel: "red" | "green" | "blue", result: string) => {
        const matrix = svgElement("feColorMatrix")
        matrix.setAttribute("in", input)
        matrix.setAttribute("type", "matrix")
        matrix.setAttribute("values", makeChannelMatrix(channel))
        matrix.setAttribute("result", result)
        return matrix
      }
      const redOnly = isolate("red-displaced", "red", "red-only")
      const greenOnly = isolate("green-displaced", "green", "green-only")
      const blueOnly = isolate("blue-displaced", "blue", "blue-only")
      const rg = svgElement("feBlend")
      rg.setAttribute("in", "red-only")
      rg.setAttribute("in2", "green-only")
      rg.setAttribute("mode", "screen")
      rg.setAttribute("result", "red-green")
      const rgb = svgElement("feBlend")
      rgb.setAttribute("in", "red-green")
      rgb.setAttribute("in2", "blue-only")
      rgb.setAttribute("mode", "screen")
      rgb.setAttribute("result", "dispersed-glass")
      const saturation = svgElement("feColorMatrix")
      saturation.setAttribute("in", "dispersed-glass")
      saturation.setAttribute("type", "saturate")
      saturation.setAttribute("values", "1.18")

      filter.append(image, red, green, blue, redOnly, greenOnly, blueOnly, rg, rgb, saturation)
      defs.appendChild(filter)

      const instance: GlassInstance = {
        element,
        filter,
        image,
        red,
        green,
        blue,
        baseScale: 24,
        factor: 1,
        velocity: 0,
        targetFactor: 1,
        frame: 0,
        lastFrame: 0,
        hovered: false,
        pressed: false,
      }
      instances.set(element, instance)
      element.classList.add("liquid-glass-enhanced")
      element.style.setProperty("--liquid-glass-filter", `url("#${id}")`)
      resizeInstance(instance)
      if (typeof ResizeObserver !== "undefined") {
        instance.resizeObserver = new ResizeObserver(() => resizeInstance(instance))
        instance.resizeObserver.observe(element)
      }
    }

    let intersectionObserver: IntersectionObserver | undefined
    if (typeof IntersectionObserver !== "undefined") {
      intersectionObserver = new IntersectionObserver((entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          const element = entry.target as HTMLElement
          intersectionObserver?.unobserve(element)
          register(element)
        }
      }, { rootMargin: "64px" })
    }

    const observeTarget = (element: HTMLElement) => {
      if (instances.has(element) || pending.has(element)) return
      pending.add(element)
      if (intersectionObserver) intersectionObserver.observe(element)
      else register(element)
    }

    const scanNode = (node: Node) => {
      if (!(node instanceof Element)) return
      if (node.matches(TARGET_SELECTOR)) observeTarget(node as HTMLElement)
      node.querySelectorAll<HTMLElement>(TARGET_SELECTOR).forEach(observeTarget)
    }

    const removeNode = (node: Node) => {
      if (!(node instanceof Element)) return
      for (const element of pending) {
        if (element === node || node.contains(element)) {
          pending.delete(element)
          intersectionObserver?.unobserve(element)
        }
      }
      for (const [element, instance] of instances) {
        if (element !== node && !node.contains(element)) continue
        if (instance.frame) cancelAnimationFrame(instance.frame)
        instance.resizeObserver?.disconnect()
        instance.filter.remove()
        element.classList.remove("liquid-glass-enhanced")
        element.removeAttribute("data-glass-state")
        element.style.removeProperty("--liquid-glass-filter")
        element.style.removeProperty("--glass-pointer-x")
        element.style.removeProperty("--glass-pointer-y")
        instances.delete(element)
        if (lastPointerTarget === element) lastPointerTarget = null
        if (pressedTarget === element) pressedTarget = null
      }
    }

    const findTarget = (node: EventTarget | null): HTMLElement | null => {
      if (!(node instanceof Element)) return null
      const dock = node.closest<HTMLElement>(".store-dock")
      if (dock) {
        const active = dock.querySelector<HTMLElement>(".store-dock-active")
        if (active && instances.has(active)) return active
      }
      const match = node.closest<HTMLElement>(TARGET_SELECTOR)
      return match && instances.has(match) ? match : null
    }

    const onPointerMove = (event: PointerEvent) => {
      const element = findTarget(event.target)
      if (!element) return
      if (lastPointerTarget && lastPointerTarget !== element) {
        const previous = instances.get(lastPointerTarget)
        if (previous && !previous.pressed) setInteraction(previous, false, false, reducedMotionQuery.matches)
      }
      lastPointerTarget = element
      const instance = instances.get(element)
      if (!instance) return
      if (!instance.hovered) setInteraction(instance, true, instance.pressed, reducedMotionQuery.matches)
      const rect = element.getBoundingClientRect()
      element.style.setProperty("--glass-pointer-x", `${clamp(((event.clientX - rect.left) / rect.width) * 100, 0, 100)}%`)
      element.style.setProperty("--glass-pointer-y", `${clamp(((event.clientY - rect.top) / rect.height) * 100, 0, 100)}%`)
    }

    const onPointerOut = (event: PointerEvent) => {
      const element = findTarget(event.target)
      if (!element) return
      const related = findTarget(event.relatedTarget)
      if (related === element) return
      const instance = instances.get(element)
      if (instance && !instance.pressed) setInteraction(instance, false, false, reducedMotionQuery.matches)
      if (lastPointerTarget === element) lastPointerTarget = related
    }

    const onPointerDown = (event: PointerEvent) => {
      const element = findTarget(event.target)
      if (!element) return
      pressedTarget = element
      const instance = instances.get(element)
      if (instance) setInteraction(instance, instance.hovered, true, reducedMotionQuery.matches)
    }

    const onPointerUp = () => {
      if (!pressedTarget) return
      const instance = instances.get(pressedTarget)
      if (instance) setInteraction(instance, instance.hovered, false, reducedMotionQuery.matches)
      pressedTarget = null
    }

    const onFocusIn = (event: FocusEvent) => {
      const element = findTarget(event.target)
      const instance = element && instances.get(element)
      if (instance) setInteraction(instance, true, false, reducedMotionQuery.matches)
    }

    const onFocusOut = (event: FocusEvent) => {
      const element = findTarget(event.target)
      if (!element || (event.relatedTarget instanceof Node && element.contains(event.relatedTarget))) return
      const instance = instances.get(element)
      if (instance) setInteraction(instance, false, false, reducedMotionQuery.matches)
    }

    scanNode(document.body)
    const mutationObserver = new MutationObserver((records) => {
      for (const record of records) {
        record.removedNodes.forEach(removeNode)
        record.addedNodes.forEach(scanNode)
      }
    })
    mutationObserver.observe(document.body, { childList: true, subtree: true })
    document.addEventListener("pointermove", onPointerMove, { passive: true })
    document.addEventListener("pointerout", onPointerOut, { passive: true })
    document.addEventListener("pointerdown", onPointerDown, { passive: true })
    document.addEventListener("pointerup", onPointerUp, { passive: true })
    document.addEventListener("pointercancel", onPointerUp, { passive: true })
    document.addEventListener("focusin", onFocusIn)
    document.addEventListener("focusout", onFocusOut)

    return () => {
      mutationObserver.disconnect()
      intersectionObserver?.disconnect()
      document.removeEventListener("pointermove", onPointerMove)
      document.removeEventListener("pointerout", onPointerOut)
      document.removeEventListener("pointerdown", onPointerDown)
      document.removeEventListener("pointerup", onPointerUp)
      document.removeEventListener("pointercancel", onPointerUp)
      document.removeEventListener("focusin", onFocusIn)
      document.removeEventListener("focusout", onFocusOut)
      for (const [element, instance] of instances) {
        if (instance.frame) cancelAnimationFrame(instance.frame)
        instance.resizeObserver?.disconnect()
        element.classList.remove("liquid-glass-enhanced")
        element.removeAttribute("data-glass-state")
        element.style.removeProperty("--liquid-glass-filter")
        element.style.removeProperty("--glass-pointer-x")
        element.style.removeProperty("--glass-pointer-y")
      }
      defsRoot.remove()
      delete document.documentElement.dataset.liquidGlassMode
    }
  }, [])

  return null
}
