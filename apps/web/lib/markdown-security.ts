export type MarkdownUrlKind = "link" | "image"

/**
 * Markdown is intentionally rendered without rehype-raw. URLs still need an
 * explicit allowlist because a markdown link or image can otherwise become a
 * script/navigation sink.
 */
export function sanitizeMarkdownUrl(rawUrl: string | undefined, kind: MarkdownUrlKind): string | null {
  const value = rawUrl?.trim() || ""
  if (!value) return null
  // Browsers treat backslashes and control characters specially while
  // resolving URLs. Reject them before the relative-path branch so a value
  // such as /\\evil.example cannot become a protocol-relative navigation.
  if (value.includes("\\") || /[\u0000-\u001f\u007f]/.test(value)) return null

  if (value.startsWith("#")) {
    return kind === "link" ? value : null
  }

  if (value.startsWith("/") && !value.startsWith("//")) {
    if (kind === "image" && !isUploadPath(value)) return null
    return value
  }

  try {
    const parsed = new URL(value)
    if (parsed.protocol === "http:" || parsed.protocol === "https:") return value
    if (kind === "link" && parsed.protocol === "mailto:") return value
  } catch {
    return null
  }
  return null
}

function isUploadPath(value: string) {
  if (!value.startsWith("/api/uploads/")) return false

  const path = value.split(/[?#]/, 1)[0]
  const segments = path.split("/")
  return segments.slice(3).every((segment) => {
    let decoded: string
    try {
      decoded = decodeURIComponent(segment)
    } catch {
      return false
    }
    return decoded !== "" && decoded !== "." && decoded !== ".." && !decoded.includes("/") && !decoded.includes("\\")
  })
}
