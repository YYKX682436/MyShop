export function announcementPopupKey(site: string | undefined, id: string | undefined, version: number | undefined): string | null {
  if (!id || !version || version < 1) return null
  return `myshop:popup:${site || "main"}:${id}:${version}`
}
