import assert from "node:assert/strict"
import test from "node:test"
import { getDeviceId } from "./fingerprint.ts"

test("concurrent device requests share one collection even when browser storage is unavailable", async () => {
  const originals = Object.fromEntries(["window", "document", "sessionStorage"].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  let canvases = 0
  Object.defineProperty(globalThis, "window", { configurable: true, value: { screen: { width: 1280, height: 720, colorDepth: 24 }, devicePixelRatio: 1 } })
  Object.defineProperty(globalThis, "document", { configurable: true, value: { createElement: () => {
    canvases += 1
    return { getContext: () => null }
  } } })
  Object.defineProperty(globalThis, "sessionStorage", { configurable: true, value: {
    getItem: () => { throw new Error("storage disabled") },
    setItem: () => { throw new Error("storage disabled") },
  } })
  try {
    const [first, second] = await Promise.all([getDeviceId(), getDeviceId()])
    assert.match(first, /^[a-f0-9]{64}$/)
    assert.equal(first, second)
    assert.equal(await getDeviceId(), first)
    assert.equal(canvases, 2, "one Canvas and one WebGL probe for all calls")
  } finally {
    for (const [key, descriptor] of Object.entries(originals)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  }
})
