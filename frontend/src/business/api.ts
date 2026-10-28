export class ApiError extends Error {
  constructor(public code: string, message: string, public status: number) { super(message) }
}
let token = ''
let generation = 0
export function setToken(value: string) { token = value; generation++ }
export async function api<T>(path: string, body?: unknown, key?: string, method?: string): Promise<T> {
  const current = generation
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 25000)
  try {
    const headers: Record<string, string> = {}
    if (token) headers.Authorization = 'Bearer ' + token
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (key) headers['Idempotency-Key'] = key
    const response = await fetch('/api/v1' + path, { method: method || (body === undefined ? 'GET' : 'POST'), headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal })
    const result = await response.json()
    if (generation !== current) throw new ApiError('STALE_SESSION', '账号已切换，请刷新页面', 409)
    if (!response.ok) throw new ApiError(result.code || 'HTTP_ERROR', result.message || '请求失败', response.status)
    return result.data as T
  } catch (error) {
    if (generation !== current) throw new ApiError('STALE_SESSION', '账号已切换', 409)
    if (error instanceof ApiError) throw error
    if (body !== undefined) throw new ApiError('WRITE_UNKNOWN', '请求结果尚未确认，请先刷新记录核对；不要重复提交。', 0)
    throw new ApiError('NETWORK_ERROR', '网络中断或响应无效，请稍后刷新。', 0)
  } finally { clearTimeout(timer) }
}
