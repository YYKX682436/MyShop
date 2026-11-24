import { readFile } from "node:fs/promises"
import { createRequire } from "node:module"
import path from "node:path"

// Vditor requests language, parser and theme files lazily. Never accept an
// arbitrary package path here, even if a path happens to be on disk.
const resources = new Set([
  "dist/js/lute/lute.min.js",
  "dist/js/i18n/zh_CN.js",
  "dist/js/i18n/en_US.js",
  "dist/js/icons/ant.js",
  "dist/css/content-theme/light.css",
  "dist/css/content-theme/dark.css",
])
// Use Node's runtime resolution; bundlers replace static require.resolve calls
// with numeric module IDs, which are not filesystem paths.
const packageRoot = path.dirname(createRequire(path.join(process.cwd(), "server.js"))
  .resolve(["vditor", "package.json"].join("/")))

export const runtime = "nodejs"

export async function GET(_request: Request, context: { params: Promise<{ path: string[] }> }) {
  const segments = (await context.params).path
  const [version, ...parts] = segments
  const asset = parts.join("/")
  if (version !== "4.0.1" || !resources.has(asset)) return new Response(null, { status: 404 })
  try {
    const content = await readFile(path.join(packageRoot, asset))
    return new Response(content, { headers: {
      "Content-Type": asset.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8",
      "Cache-Control": "public, max-age=31536000, immutable",
      "X-Content-Type-Options": "nosniff",
    } })
  } catch {
    return new Response(null, { status: 404 })
  }
}
