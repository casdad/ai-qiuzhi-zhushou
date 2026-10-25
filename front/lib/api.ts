/**
 * 后端 API 根地址 —— 全站唯一读取入口。
 *
 * 之前这个地址是逐处硬编码的：39 处 http://localhost:8888 散在 11 个文件里，
 * 改一次后端端口就得全项目搜索替换，漏一处就是页面上某个按钮静默失效。
 *
 * 取值优先级：
 *   1. NEXT_PUBLIC_API_BASE —— 临时覆盖用（NEXT_PUBLIC_ 前缀是 Next.js 注入浏览器端的要求）
 *   2. API_BASE_URL         —— next.config.ts 从 front/server.config.js 的 api.baseUrl 注入
 *   3. 兜底字面量           —— 上面都没有时才用，需与后端 application.yaml 的 server.port 一致
 *
 * 也就是说：平时改端口只改 front/server.config.js 和后端 application.yaml 两处。
 */
export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE ||
  process.env.API_BASE_URL ||
  'http://localhost:9527'

export class ApiResponseError extends Error {
  constructor(message: string, public readonly status: number, public readonly data: unknown) {
    super(message)
    this.name = "ApiResponseError"
  }
}

/** The deadline covers both the connection queue and reading the response body. Never retries writes. */
export async function requestJson<T = Record<string, unknown>>(
  url: string,
  options: RequestInit = {},
  timeoutMs = 10_000,
): Promise<T> {
  const controller = new AbortController()
  const forwardAbort = () => controller.abort(options.signal?.reason)
  options.signal?.addEventListener("abort", forwardAbort, { once: true })
  if (options.signal?.aborted) forwardAbort()
  let timer: ReturnType<typeof setTimeout> | undefined
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      reject(new Error("请求超时，请检查服务连接。操作结果可能尚未确认，请刷新核对，不会自动重试。"))
      controller.abort()
    }, timeoutMs)
  })
  try {
    return await Promise.race([
      (async () => {
        const response = await fetch(url, { ...options, signal: controller.signal })
        const data = await response.json()
        if (!response.ok) {
          throw new ApiResponseError(data?.message || data?.error || `服务返回异常（HTTP ${response.status}）`, response.status, data)
        }
        return data as T
      })(),
      deadline,
    ])
  } finally {
    clearTimeout(timer)
    options.signal?.removeEventListener("abort", forwardAbort)
  }
}
