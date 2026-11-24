import { unified } from "unified"
import remarkParse from "remark-parse"
import remarkGfm from "remark-gfm"

import { createMarkdownImage } from "@/lib/markdown-image"
import { sanitizeMarkdownUrl } from "@/lib/markdown-security"

type Node = {
  type: string
  children?: Node[]
  identifier?: string
  url?: string
  alt?: string | null
  title?: string | null
  position?: { start?: { offset?: number }; end?: { offset?: number } }
}
type ImageMapping = { altToken: string; urlToken: string; alt: string; url: string; title?: string | null }
type EditorLute = {
  Md2VditorDOM: (markdown: string) => string
  VditorDOM2Md: (html: string) => string
  SpinVditorDOM: (html: string) => string
  HTML2VditorDOM: (html: string) => string
}

const processor = unified().use(remarkParse).use(remarkGfm)

function token(kind: "ALT" | "URL") {
  return `MYSHOP${kind}${globalThis.crypto.randomUUID().replaceAll("-", "")}END`
}
function destination(url: string) {
  const sample = createMarkdownImage("x", url)
  return sample.slice("![x](".length, -1)
}
function escapeAlt(alt: string) {
  return alt.replace(/\\/g, "\\\\").replace(/\[/g, "\\[").replace(/\]/g, "\\]").replace(/[\r\n]+/g, " ")
}
function needsMask(alt: string, url: string) {
  return /[\[\]\\"*_~`<>]/.test(alt) || /[()]/.test(url)
}
function walk(node: Node, visit: (node: Node) => void) {
  visit(node)
  node.children?.forEach(child => walk(child, visit))
}
function newMapping(alt: string, url: string, title?: string | null): ImageMapping {
  return { altToken:token("ALT"), urlToken:`/api/uploads/${token("URL")}.png`, alt, url, title }
}

/**
 * Lute 4.0.1 injects editing spans into image alt attributes when Markdown
 * contains escaped brackets. Its DOM serializer also emits unescaped brackets
 * and parentheses. Mask only AST image nodes while Lute converts the document,
 * then restore real values using DOM attribute APIs and Markdown escaping.
 * Each mapping exists for one synchronous conversion; it is never persisted.
 */
export function installVditorImageCompatibility(lute: EditorLute) {
  const originalFromMarkdown = lute.Md2VditorDOM.bind(lute)
  const originalToMarkdown = lute.VditorDOM2Md.bind(lute)
  const originalSpin = lute.SpinVditorDOM.bind(lute)
  const originalFromHtml = lute.HTML2VditorDOM.bind(lute)

  const restoreDom = (html: string, mappings: ImageMapping[]) => {
    if (!mappings.length) return html
    const template = document.createElement("template")
    template.innerHTML = html
    const byAlt = new Map(mappings.map(mapping => [mapping.altToken, mapping]))
    template.content.querySelectorAll("img").forEach(image => {
      const mapping = byAlt.get(image.getAttribute("alt") || "")
      if (!mapping) return
      const safe = sanitizeMarkdownUrl(mapping.url, "image")
      if (!safe) { image.remove(); return }
      image.setAttribute("alt", mapping.alt)
      image.setAttribute("src", safe)
      if (mapping.title != null) image.setAttribute("title", mapping.title)
      else image.removeAttribute("title")
    })
    // Some Lute contexts return an image as literal Markdown text instead of
    // an img element. Restore it in that location while the per-call mapping
    // still exists, so no temporary marker can enter the document.
    for (const mapping of mappings) {
      const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_TEXT)
      const textNodes: Text[] = []
      while (walker.nextNode()) textNodes.push(walker.currentNode as Text)
      const safe = sanitizeMarkdownUrl(mapping.url, "image")
      for (const node of textNodes) {
        const patterns = [`![${mapping.altToken}](<${mapping.urlToken}>)`, `![${mapping.altToken}](${mapping.urlToken})`]
        const pattern = patterns.find(candidate => node.data.includes(candidate))
        if (pattern && safe) {
          const at = node.data.indexOf(pattern)
          const fragment = document.createDocumentFragment()
          fragment.append(document.createTextNode(node.data.slice(0, at)))
          const image = document.createElement("img")
          image.setAttribute("alt", mapping.alt); image.setAttribute("src", safe)
          if (mapping.title != null) image.setAttribute("title", mapping.title)
          fragment.append(image, document.createTextNode(node.data.slice(at + pattern.length)))
          node.replaceWith(fragment)
        } else {
          node.data = node.data.replaceAll(mapping.altToken, escapeAlt(mapping.alt)).replaceAll(mapping.urlToken, safe || "")
        }
      }
      template.content.querySelectorAll("*").forEach(element => {
        for (const attribute of [...element.attributes]) {
          if (attribute.value.includes(mapping.altToken) || attribute.value.includes(mapping.urlToken)) {
            element.setAttribute(attribute.name, attribute.value.replaceAll(mapping.altToken, mapping.alt).replaceAll(mapping.urlToken, safe || ""))
          }
        }
      })
    }
    return template.innerHTML
  }

  const maskDom = (html: string) => {
    const template = document.createElement("template")
    template.innerHTML = html
    const mappings: ImageMapping[] = []
    template.content.querySelectorAll("img").forEach(image => {
      const url = image.getAttribute("src") || ""
      const safe = sanitizeMarkdownUrl(url, "image")
      if (!safe) { image.remove(); return }
      if (!needsMask(image.getAttribute("alt") || "", safe)) return
      const mapping = newMapping(image.getAttribute("alt") || "", safe, image.getAttribute("title"))
      mappings.push(mapping)
      image.setAttribute("alt", mapping.altToken)
      image.setAttribute("src", mapping.urlToken)
    })
    return { html:template.innerHTML, mappings }
  }

  lute.Md2VditorDOM = markdown => {
    const tree = processor.parse(markdown) as unknown as Node
    const definitions = new Map<string, Node>()
    walk(tree, node => {
      if (node.type === "definition" && node.identifier && !definitions.has(node.identifier.toLowerCase())) definitions.set(node.identifier.toLowerCase(), node)
    })
    const replacements: { start: number; end: number; mapping: ImageMapping }[] = []
    walk(tree, node => {
      if (node.type !== "image" && node.type !== "imageReference") return
      const definition = node.type === "imageReference" && node.identifier ? definitions.get(node.identifier.toLowerCase()) : undefined
      const url = node.url || definition?.url
      const start = node.position?.start?.offset
      const end = node.position?.end?.offset
      if (url == null || start == null || end == null) return
      if (!needsMask(node.alt || "", url)) return
      replacements.push({ start, end, mapping:newMapping(node.alt || "", url, node.title ?? definition?.title) })
    })
    if (!replacements.length) return originalFromMarkdown(markdown)
    let masked = markdown
    for (const item of [...replacements].sort((a, b) => b.start - a.start)) {
      masked = masked.slice(0, item.start) + createMarkdownImage(item.mapping.altToken, item.mapping.urlToken) + masked.slice(item.end)
    }
    return restoreDom(originalFromMarkdown(masked), replacements.map(item => item.mapping))
  }

  lute.VditorDOM2Md = html => {
    const masked = maskDom(html)
    let result = originalToMarkdown(masked.html)
    for (const mapping of masked.mappings) {
      result = result.replaceAll(mapping.altToken, escapeAlt(mapping.alt))
        .replaceAll(mapping.urlToken, destination(mapping.url))
    }
    return result
  }
  lute.SpinVditorDOM = html => {
    const masked = maskDom(html)
    return restoreDom(originalSpin(masked.html), masked.mappings)
  }
  lute.HTML2VditorDOM = html => {
    const masked = maskDom(html)
    return restoreDom(originalFromHtml(masked.html), masked.mappings)
  }
}
