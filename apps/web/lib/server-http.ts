import { request as httpRequest } from "node:http"
import { request as httpsRequest } from "node:https"

type ServerHttpResponse = {
  status: number
  statusText: string
  body: string
}

export class ServerApiError extends Error {
  readonly status: number

  constructor(status: number, statusText: string, path: string) {
    super(`Server API error: ${status} ${statusText} [${path}]`)
    this.status = status
  }
}

/** Send the validated storefront Host to the internal API without rewriting it. */
export function getServerHttpResponse(url: string, headers: Record<string, string>): Promise<ServerHttpResponse> {
  const target = new URL(url)
  const request = target.protocol === "http:" ? httpRequest : target.protocol === "https:" ? httpsRequest : null
  if (!request) return Promise.reject(new Error("Unsupported backend URL protocol"))

  return new Promise((resolve, reject) => {
    const outgoing = request(target, { method: "GET", headers }, (response) => {
      let body = ""
      response.setEncoding("utf8")
      response.on("data", chunk => { body += chunk })
      response.on("error", reject)
      response.on("aborted", () => reject(new Error("Backend response was aborted")))
      response.on("end", () => resolve({
        status: response.statusCode ?? 0,
        statusText: response.statusMessage ?? "",
        body,
      }))
    })
    outgoing.on("error", reject)
    outgoing.end()
  })
}
