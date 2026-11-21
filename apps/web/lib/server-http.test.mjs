import assert from "node:assert/strict"
import { createServer } from "node:http"
import test from "node:test"
import { getServerHttpResponse } from "./server-http.ts"

test("server requests preserve the storefront host and only the supplied cookie", async (t) => {
  const server = createServer((request, response) => {
    response.setHeader("Content-Type", "application/json")
    response.end(JSON.stringify({ host: request.headers.host, cookie: request.headers.cookie ?? null }))
  })
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve))
  t.after(() => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())))

  const backend = `http://127.0.0.1:${server.address().port}/api/site/config`
  const main = await getServerHttpResponse(backend, { Host: "mc.mc520.top" })
  const subsite = await getServerHttpResponse(backend, {
    Host: "xiaoming.mc520.top",
    Cookie: "myshop_access_token=test-token",
  })

  assert.equal(main.status, 200)
  assert.deepEqual(JSON.parse(main.body), { host: "mc.mc520.top", cookie: null })
  assert.deepEqual(JSON.parse(subsite.body), {
    host: "xiaoming.mc520.top",
    cookie: "myshop_access_token=test-token",
  })
})
