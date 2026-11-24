import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { createServer, request as httpRequest } from "node:http"
import { cpSync, mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join, resolve } from "node:path"
import { setTimeout as delay } from "node:timers/promises"
import { fileURLToPath } from "node:url"

// Build the real proxy and source route in an isolated production Next server.
// The tiny local upstream lets us control visibility without touching any DB.
const project = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const work = mkdtempSync(join(tmpdir(), "myshop-optimizer-test-"))
const filename = "12345678-1234-4234-8234-123456789abc.png"
const image = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAAEUlEQVR4nGP4z8DQAMIMMAYAOOgF/REzMMkAAAAASUVORK5CYII=", "base64")
let available = true
let upstreamFailed = false
const requests = { head: 0, get: 0, unexpected: 0 }
const backend = createServer((req, res) => {
  if (req.url !== `/api/uploads/${filename}`) { requests.unexpected++; res.writeHead(404); res.end(); return }
  if (req.method === "HEAD") requests.head++
  else if (req.method === "GET") requests.get++
  else { requests.unexpected++; res.writeHead(405); res.end(); return }
  if (upstreamFailed) { res.writeHead(503); res.end(); return }
  if (!available) { res.writeHead(404, { "Cache-Control": "no-store" }); res.end(); return }
  res.writeHead(200, { "Content-Type": "image/png", "Cache-Control": "public, max-age=31536000, immutable" })
  res.end(req.method === "HEAD" ? undefined : image)
})
const listen = server => new Promise(resolve => server.listen(0, "127.0.0.1", resolve))
let next

try {
  await listen(backend)
  const portProbe = createServer(); await listen(portProbe)
  const port = portProbe.address().port
  await new Promise(resolve => portProbe.close(resolve))
  mkdirSync(join(work, "app", "image-source", "[filename]"), { recursive: true })
  symlinkSync(join(project, "node_modules"), join(work, "node_modules"), "dir")
  writeFileSync(join(work, "package.json"), JSON.stringify({ private: true }))
  const config = readFileSync(join(project, "next.config.mjs"), "utf8")
    .replace("output: 'standalone',", "// The isolated fixture uses next start with the same optimizer settings.")
  writeFileSync(join(work, "next.config.mjs"), config)
  cpSync(join(project, "proxy.ts"), join(work, "proxy.ts"))
  cpSync(join(project, "app", "image-source", "[filename]", "route.ts"), join(work, "app", "image-source", "[filename]", "route.ts"))
  writeFileSync(join(work, "app", "layout.js"), "export default function Layout({children}) { return <html><body>{children}</body></html> }")
  writeFileSync(join(work, "app", "page.js"), "export default function Home() { return <p>optimizer test</p> }")
  const nextBin = join(project, "node_modules", "next", "dist", "bin", "next")
  const environment = { ...process.env, NEXT_TELEMETRY_DISABLED: "1", BACKEND_URL: `http://127.0.0.1:${backend.address().port}`, PUBLIC_SITE_URL: "http://localhost:3000" }
  const build = spawn(process.execPath, [nextBin, "build", "--webpack"], { cwd: work, env: environment, stdio: ["ignore", "pipe", "pipe"] })
  let output = ""
  build.stdout.on("data", chunk => { output = (output + chunk).slice(-8000) })
  build.stderr.on("data", chunk => { output = (output + chunk).slice(-8000) })
  assert.equal(await new Promise(resolve => build.once("exit", resolve)), 0, `Next production build failed:\n${output}`)
  next = spawn(process.execPath, [nextBin, "start", "-p", String(port), "-H", "127.0.0.1"], { cwd: work, env: environment, stdio: ["ignore", "ignore", "ignore"] })
  let ready = false
  for (let attempt = 0; attempt < 120; attempt++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/`)).ok) { ready = true; break } } catch { /* Wait for startup. */ }
    if (next.exitCode !== null) break
    await delay(100)
  }
  assert.ok(ready, "Next production server did not start")
  const origin = `http://127.0.0.1:${port}`
  const source = `/image-source/${filename}`
  const optimized = `${origin}/_next/image?url=${encodeURIComponent(source)}&w=384&q=75`
  const first = await fetch(optimized, { headers: { Accept: "image/webp" } })
  const firstBytes = (await first.arrayBuffer()).byteLength
  assert.equal(first.status, 200); assert.equal(first.headers.get("x-nextjs-cache"), "MISS"); assert.ok(firstBytes > 0)
  const second = await fetch(optimized, { headers: { Accept: "image/webp" } })
  assert.equal(second.status, 200); assert.equal(second.headers.get("x-nextjs-cache"), "HIT")
  assert.equal(requests.head, 2, "A cached optimizer hit must still check the backend")
  assert.equal(requests.get, 1, "The second request must reuse optimized bytes")
  const beforeInvalid = { ...requests }
  for (const invalid of [
    `${optimized}&url=${encodeURIComponent("https://outside.example/image.png")}`,
    `${origin}/_next/image?url=${encodeURIComponent("/image-source/%252e%252e/private.png")}&w=384&q=75`,
    `${origin}/_next/image?url=${encodeURIComponent("/image-source/../" + filename)}&w=384&q=75`,
    `${origin}/_next/image?url=${encodeURIComponent(source + "?live=1")}&w=384&q=75`,
    `${origin}/_next/image?url=${encodeURIComponent("/IMAGE-SOURCE/" + filename)}&w=384&q=75`,
    `${origin}/_next/image?url=${encodeURIComponent("https://outside.example/" + filename)}&w=384&q=75`,
  ]) {
    assert.equal((await fetch(invalid)).status, 400, `Invalid optimizer source was accepted: ${invalid}`)
  }
  assert.deepEqual(requests, beforeInvalid, "Invalid URLs must not trigger a backend fetch")
  // Filename case is checked against the actual backend file, not normalized.
  assert.equal((await fetch(`${origin}/_next/image?url=${encodeURIComponent("/image-source/" + filename.toUpperCase())}&w=384&q=75`)).status, 404)
  assert.equal(requests.unexpected, 1)
  const beforeUnrelated = { ...requests }
  assert.equal((await fetch(`${origin}/`)).status, 200)
  assert.deepEqual(requests, beforeUnrelated, "Unrelated routes must not call the media backend")
  available = false
  const removed = await fetch(optimized, { headers: { Accept: "image/webp" } })
  assert.equal(removed.status, 404); assert.equal(removed.headers.get("cache-control"), "no-store")
  assert.equal((await fetch(optimized, { method: "HEAD" })).status, 404)
  const query = `?url=${encodeURIComponent(source)}&w=384&q=75`
  const rawStatus = (path, headers = {}) => new Promise((resolve, reject) => {
    const req = httpRequest({ hostname: "127.0.0.1", port, path, headers: { Accept: "image/webp", ...headers } }, response => {
      response.resume(); resolve(response.statusCode)
    })
    req.on("error", reject); req.end()
  })
  for (const path of [
    `/_next/image/${query}`, `/_next/%69mage${query}`, `/%5Fnext/image${query}`,
    `/_next//image${query}`, `/_next/image%2F${query}`, `/a/../_next/image${query}`,
    `/_next\\image${query}`, `/_next%5cimage${query}`, `/_NEXT/image${query}`,
  ]) assert.notEqual(await rawStatus(path), 200, `A noncanonical URL served deleted cached bytes: ${path}`)
  for (const headers of [{ "x-middleware-subrequest": "middleware:middleware:middleware:middleware:middleware" }, { "x-middleware-next": "1" }])
    assert.notEqual(await rawStatus(`/_next/image${query}`, headers), 200, "Internal headers bypassed the visibility check")
  assert.equal((await fetch(`${origin}${source}`)).status, 404)
  assert.equal(requests.get, 2, "The stale optimizer cache must never re-fetch or serve the deleted image")
  available = true; upstreamFailed = true
  assert.equal((await fetch(optimized)).status, 502, "Backend failures must not expose cached content")
  console.log(JSON.stringify({ first: first.status, cache: second.headers.get("x-nextjs-cache"), removed: removed.status, firstBytes, requests }))
} finally {
  if (next) {
    next.kill("SIGTERM")
    await Promise.race([new Promise(resolve => next.once("exit", resolve)), delay(1500)])
  }
  await new Promise(resolve => backend.close(resolve))
  rmSync(work, { recursive: true, force: true })
}
