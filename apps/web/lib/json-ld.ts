/** JSON embedded in an HTML script must not contain a literal closing tag. */
export function serializeJsonLd(value: Record<string, unknown>): string {
  return JSON.stringify(value).replace(/</g, "\\u003c")
}
