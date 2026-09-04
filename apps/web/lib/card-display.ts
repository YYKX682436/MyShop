/** Keep fields within one card together; separate cards with newlines. */
export function formatCardContent(parts: string[]): string {
  return parts.join("\t")
}

export function formatCardContents(cards: { content_parts: string[] }[]): string {
  return cards.map(card => formatCardContent(card.content_parts)).join("\n")
}
