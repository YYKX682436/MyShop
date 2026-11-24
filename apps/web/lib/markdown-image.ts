export interface TextSelection {
  start: number
  end: number
}

export interface MarkdownInsertionResult {
  value: string
  cursorPosition: number
}

export function createMarkdownImage(fileName: string, url: string): string {
  // Angle-delimited destinations allow parentheses in signed or external
  // URLs. Escaping the label prevents brackets in a filename from ending it.
  const label = fileName.replace(/\\/g, "\\\\").replace(/\[/g, "\\[").replace(/\]/g, "\\]").replace(/[\r\n]+/g, " ")
  const destination = url.replace(/[&<>\s\\]/g, char => char === "&" ? "&amp;" : encodeURIComponent(char))
  return `![${label}](<${destination}>)`
}

export function insertMarkdownImage(
  text: string,
  markdownImage: string,
  selection?: TextSelection | null,
): MarkdownInsertionResult {
  const hasValidSelection = Boolean(
    selection &&
      Number.isInteger(selection.start) &&
      Number.isInteger(selection.end) &&
      selection.start >= 0 &&
      selection.end >= selection.start &&
      selection.end <= text.length,
  )
  const start = hasValidSelection ? selection!.start : text.length
  const end = hasValidSelection ? selection!.end : text.length
  const before = text.slice(0, start)
  const after = text.slice(end)
  const prefix = before.length > 0 && !before.endsWith("\n") ? "\n" : ""
  const suffix = "\n"

  return {
    value: before + prefix + markdownImage + suffix + after,
    cursorPosition: start + prefix.length + markdownImage.length + suffix.length,
  }
}
