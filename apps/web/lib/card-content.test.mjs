import assert from "node:assert/strict"
import test from "node:test"
import { recognizedCardContentCount, validateCardContentColumns } from "./card-content.ts"

test("card content validation accepts trimmed CRLF columns", () => {
  assert.equal(validateCardContentColumns(["\r\n 1 \r\n 2 \r\n", "a\r\nb"]), null)
  assert.equal(recognizedCardContentCount("\n 1 \n 2 \n"), 2)
})

test("card content validation reports unequal columns immediately", () => {
  assert.equal(
    validateCardContentColumns(["1\n2\n3\n4", "a\nb\nc"]),
    "各卡券内容数量不一致：第1列 4 条，第2列 3 条，请补齐后再导入",
  )
})

test("card content validation reports internal blank rows instead of compacting them", () => {
  assert.equal(
    validateCardContentColumns(["1\n\n3", "a\nb\nc"]),
    "第1列第2行为空，请补充内容或删除对应行",
  )
})
