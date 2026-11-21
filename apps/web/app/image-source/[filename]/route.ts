import http, { type IncomingMessage } from "node:http"
import https from "node:https"
import { Readable } from "node:stream"

// Next's internal image fetch cannot follow an external rewrite to Spring.
// This narrow route serves only generated, public top-level media and never
// forwards client headers or accepts a client-supplied destination.
const managedImage = /^(?:[0-9a-f]{64}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(?:jpe?g|png|webp|bmp)$/i

export const dynamic = "force-dynamic"

export async function GET(_request: Request, { params }: { params: Promise<{ filename: string }> }) {
  const { filename } = await params
  if (!managedImage.test(filename)) return new Response(null, { status: 404 })

  try {
    const backend = new URL(process.env.BACKEND_URL || "http://localhost:8083")
    const publicSite = new URL(process.env.PUBLIC_SITE_URL || "http://localhost:3000")
    const source = new URL(`/api/uploads/${filename}`, backend)
    // Node fetch rewrites Host to the connection authority. Use the Node HTTP
    // client so the API's storefront-host guard sees the configured public host.
    const response = await new Promise<IncomingMessage>((resolve, reject) => {
      const client = source.protocol === "https:" ? https : http
      const request = client.get(source, { headers: { host: publicSite.host }, timeout: 10_000 }, resolve)
      request.on("timeout", () => request.destroy(new Error("Image source timed out")))
      request.on("error", reject)
    })
    if (response.statusCode !== 200) {
      response.resume()
      return new Response(null, { status: 404 })
    }
    return new Response(Readable.toWeb(response) as ReadableStream<Uint8Array>, {
      headers: {
        "Content-Type": response.headers["content-type"] || "application/octet-stream",
        "Cache-Control": response.headers["cache-control"] || "no-store",
        "X-Content-Type-Options": "nosniff",
      },
    })
  } catch {
    return new Response(null, { status: 502 })
  }
}
