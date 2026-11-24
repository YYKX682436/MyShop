import http from "node:http"
import https from "node:https"
import { NextResponse, type NextRequest } from "next/server"

// Next's optimizer can serve its disk cache without touching the source URL.
// Check legacy public assets on every optimizer request before allowing a hit.
// Only the existing generated root filenames are accepted; neither the host
// nor an arbitrary upstream URL can be supplied by the client.
const legacySource = /^\/image-source\/([^/?#]+)$/
const legacyFilename = /^(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(?:jpe?g|png|gif|webp|bmp)$/i

export const config = { matcher: "/_next/image" }

async function sourceStatus(filename: string): Promise<number> {
  const backend = new URL(process.env.BACKEND_URL || "http://localhost:8083")
  const publicSite = new URL(process.env.PUBLIC_SITE_URL || "http://localhost:3000")
  if (!(["http:", "https:"].includes(backend.protocol)) || backend.username || backend.password || !publicSite.host) {
    throw new Error("Invalid media backend configuration")
  }
  const source = new URL(`/api/uploads/${filename}`, backend)
  return new Promise((resolve, reject) => {
    const client = source.protocol === "https:" ? https : http
    const check = client.request(source, { method: "HEAD", headers: { host: publicSite.host }, timeout: 10_000 }, response => {
      response.resume()
      resolve(response.statusCode || 502)
    })
    check.on("timeout", () => check.destroy(new Error("Media visibility check timed out")))
    check.on("error", reject)
    check.end()
  })
}

export async function proxy(request: NextRequest) {
  const urls = request.nextUrl.searchParams.getAll("url")
  const match = urls.length === 1 ? legacySource.exec(urls[0]) : null
  if (!match || !legacyFilename.test(match[1])) return new Response(null, { status: 400, headers: { "Cache-Control": "no-store" } })

  try {
    const status = await sourceStatus(match[1])
    if (status === 200) return NextResponse.next()
    return new Response(null, { status: status === 404 || status === 410 ? 404 : 502, headers: { "Cache-Control": "no-store" } })
  } catch {
    return new Response(null, { status: 502, headers: { "Cache-Control": "no-store" } })
  }
}
