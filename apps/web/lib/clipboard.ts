/**
 * Copy text without coupling business actions to a browser-only API. The
 * legacy execCommand path is retained as a deliberate fallback for embedded
 * browsers and permission-restricted clipboard contexts.
 */
export async function copyText(text: string): Promise<boolean> {
  if (!text) return false

  try {
    if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // Continue to the DOM fallback.
  }

  if (typeof document === "undefined") return false

  let textarea: HTMLTextAreaElement | null = null
  try {
    textarea = document.createElement("textarea")
    textarea.value = text
    textarea.setAttribute("readonly", "")
    textarea.setAttribute("aria-hidden", "true")
    textarea.style.position = "fixed"
    textarea.style.top = "-9999px"
    textarea.style.left = "-9999px"
    textarea.style.opacity = "0"
    document.body.appendChild(textarea)
    textarea.focus()
    textarea.select()
    textarea.setSelectionRange(0, textarea.value.length)
    return document.execCommand("copy")
  } catch {
    return false
  } finally {
    if (textarea) {
      if (typeof textarea.remove === "function") textarea.remove()
      else textarea.parentNode?.removeChild(textarea)
    }
  }
}
