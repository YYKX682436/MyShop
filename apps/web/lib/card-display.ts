/** Keep fields within one card together; separate distinct cards with a blank line. */
export function formatCardContent(parts: string[]): string {
  return parts.join("\n")
}

export function formatCardContents(cards: { content_parts: string[] }[]): string {
  return cards.map(card => formatCardContent(card.content_parts)).join("\n\n")
}
