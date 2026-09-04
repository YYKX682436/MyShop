import assert from "node:assert/strict"
import test from "node:test"

import { copyText } from "./clipboard.ts"

test("clipboard prefers the async browser API", async () => {
  const originalNavigator = globalThis.navigator
  const originalDocument = globalThis.document
  let written = ""
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: { clipboard: { writeText: async (value) => { written = value } } },
  })
  Object.defineProperty(globalThis, "document", { configurable: true, value: undefined })
  try {
    assert.equal(await copyText("preferred"), true)
    assert.equal(written, "preferred")
  } finally {
    if (originalNavigator === undefined) delete globalThis.navigator
    else Object.defineProperty(globalThis, "navigator", { configurable: true, value: originalNavigator })
    if (originalDocument === undefined) delete globalThis.document
    else Object.defineProperty(globalThis, "document", { configurable: true, value: originalDocument })
  }
})

test("clipboard fallback works when the async API is unavailable", async () => {
  const originalNavigator = globalThis.navigator
  const originalDocument = globalThis.document
  let copied = ""
  const textarea = {
    value: "",
    style: {},
    setAttribute() {},
    focus() {},
    select() {},
    setSelectionRange() {},
    remove() {},
  }
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: {} })
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: {
      createElement: () => textarea,
      body: { appendChild() {} },
      execCommand: (command) => { if (command === "copy") copied = textarea.value; return true },
    },
  })
  try {
    assert.equal(await copyText("fallback"), true)
    assert.equal(copied, "fallback")
  } finally {
    if (originalNavigator === undefined) delete globalThis.navigator
    else Object.defineProperty(globalThis, "navigator", { configurable: true, value: originalNavigator })
    if (originalDocument === undefined) delete globalThis.document
    else Object.defineProperty(globalThis, "document", { configurable: true, value: originalDocument })
  }
})

test("clipboard failure is reported as false instead of throwing", async () => {
  const originalNavigator = globalThis.navigator
  const originalDocument = globalThis.document
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: {} })
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    value: { createElement: () => { throw new Error("blocked") } },
  })
  try {
    assert.equal(await copyText("unavailable"), false)
  } finally {
    if (originalNavigator === undefined) delete globalThis.navigator
    else Object.defineProperty(globalThis, "navigator", { configurable: true, value: originalNavigator })
    if (originalDocument === undefined) delete globalThis.document
    else Object.defineProperty(globalThis, "document", { configurable: true, value: originalDocument })
  }
})
