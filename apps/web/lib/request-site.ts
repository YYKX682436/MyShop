import { headers } from "next/headers"
import { getRequestSiteUrl } from "@/lib/site-url"

/** Only Host is used; forwarded headers are not trusted for site selection. */
export async function requestSiteUrl(): Promise<string> {
  return getRequestSiteUrl((await headers()).get("host"))
}
