/** Browser-side counterpart of CardContentParser's line validation. */
export function validateCardContentColumns(rawColumns: string[]): string | null {
  if (rawColumns.length === 0) return "至少需要一列卡券内容"

  const counts: number[] = []
  for (let columnIndex = 0; columnIndex < rawColumns.length; columnIndex++) {
    const normalized = (rawColumns[columnIndex] ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n")
    const rawLines = normalized.split("\n")
    let start = 0
    let end = rawLines.length
    while (start < end && !rawLines[start].trim()) start++
    while (end > start && !rawLines[end - 1].trim()) end--
    if (start === end) return `第${columnIndex + 1}列第1行为空，请补充内容或删除对应行`

    for (let lineIndex = start; lineIndex < end; lineIndex++) {
      if (!rawLines[lineIndex].trim()) {
        return `第${columnIndex + 1}列第${lineIndex - start + 1}行为空，请补充内容或删除对应行`
      }
    }
    counts.push(end - start)
  }

  const expected = counts[0]
  for (let index = 1; index < counts.length; index++) {
    if (counts[index] !== expected) {
      return `各卡券内容数量不一致：第1列 ${expected} 条，第${index + 1}列 ${counts[index]} 条，请补齐后再导入`
    }
  }
  return null
}

export function recognizedCardContentCount(value: string): number {
  const normalized = (value ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n")
  const lines = normalized.split("\n")
  while (lines.length > 0 && !lines[0].trim()) lines.shift()
  while (lines.length > 0 && !lines[lines.length - 1].trim()) lines.pop()
  return lines.length
}
