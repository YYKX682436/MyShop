import type { Metadata, ResolvingMetadata } from "next"

/** Use the site identity already resolved by the root layout for this request. */
export async function internalPageMetadata(title: string, parent: ResolvingMetadata): Promise<Metadata> {
  const siteName = (await parent).openGraph?.siteName?.trim() || "商城"
  const fullTitle = `${title} | ${siteName}`
  return {
    title: { absolute: fullTitle },
    openGraph: { type: "website", locale: "zh_CN", siteName, title: fullTitle },
    twitter: { card: "summary_large_image", title: fullTitle },
  }
}
