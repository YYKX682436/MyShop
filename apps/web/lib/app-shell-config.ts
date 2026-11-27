export type AppShellSiteType = "MYSHOP" | "EXTERNAL"

export interface AppShellDomainRule {
  domain_pattern: string
  site_type: AppShellSiteType
  enabled: boolean
  note: string
}

export interface AppShellConfig {
  enabled: boolean
  rules: AppShellDomainRule[]
}

function normalizeHostname(value: string): string | null {
  const candidate = value.trim()
  if (!candidate || /[\s/@:\\%?#]/.test(candidate)) return null
  try {
    const url = new URL(`https://${candidate}/`)
    if (url.username || url.password || url.port || url.pathname !== "/" || url.search || url.hash) return null
    const host = url.hostname.toLowerCase().replace(/\.$/, "")
    const labels = host.split(".")
    if (labels.length < 2 || host.length > 253 || host === "localhost" || host.endsWith(".localhost")) return null
    if (labels.some((label) => !label || label.length > 63 || !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label))) return null
    if (labels.every((label) => /^(?:[0-9]+|0x[0-9a-f]+|0[0-7]+)$/i.test(label))) return null
    return host
  } catch {
    return null
  }
}

export function normalizeAppShellPattern(value: string): string | null {
  const candidate = value.trim()
  const wildcard = candidate.startsWith("*.")
  const host = normalizeHostname(wildcard ? candidate.slice(2) : candidate)
  return host ? `${wildcard ? "*." : ""}${host}` : null
}

export function selectAppShellPattern(patterns: string[], host: string): string | null {
  const exact = patterns.find((pattern) => !pattern.startsWith("*.") && pattern === host)
  if (exact) return exact
  return patterns
    .filter((pattern) => pattern.startsWith("*."))
    .filter((pattern) => {
      const suffix = pattern.slice(1)
      if (!host.endsWith(suffix)) return false
      const label = host.slice(0, -suffix.length)
      return Boolean(label) && !label.includes(".")
    })
    .sort((left, right) => right.length - left.length)[0] ?? null
}

export function normalizeAppShellConfig(value: AppShellConfig): AppShellConfig {
  if (!Array.isArray(value.rules) || value.rules.length > 500) throw new Error("域名规则数量无效")
  const seen = new Set<string>()
  const rules = value.rules.map((rule) => {
    const domain_pattern = normalizeAppShellPattern(rule.domain_pattern)
    if (!domain_pattern || seen.has(domain_pattern)) throw new Error("域名格式无效或重复")
    if (rule.site_type !== "MYSHOP" && rule.site_type !== "EXTERNAL") throw new Error("站点类型无效")
    const note = (rule.note ?? "").trim()
    if (note.length > 100) throw new Error("备注不能超过 100 个字符")
    seen.add(domain_pattern)
    return { ...rule, domain_pattern, note }
  })
  return { enabled: Boolean(value.enabled), rules }
}
