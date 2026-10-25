import { API_BASE, requestJson } from "@/lib/api"

export type LoginSnapshot = {
  bossLoggedIn: boolean
  liepinLoggedIn: boolean
  job51LoggedIn: boolean
  zhilianLoggedIn: boolean
}

type Listener = (snapshot: LoginSnapshot | null) => void
const listeners = new Set<Listener>()
let snapshot: LoginSnapshot | null = null
let controller: AbortController | null = null
let timer: ReturnType<typeof setTimeout> | undefined
const isVisible = () => document.visibilityState !== "hidden"

// One short cached-status request per tab, not one permanent HTTP/1 connection per hook.
async function poll() {
  if (!listeners.size || !isVisible() || controller) return
  const current = new AbortController()
  controller = current
  try {
    const data = await requestJson<LoginSnapshot>(`${API_BASE}/api/jobs/login-status`, {
      cache: "no-store", signal: current.signal,
    }, 5_000)
    if (![data.bossLoggedIn, data.liepinLoggedIn, data.job51LoggedIn, data.zhilianLoggedIn]
      .every((value) => typeof value === "boolean")) throw new Error("登录状态响应不完整")
    if (controller !== current || current.signal.aborted) return
    snapshot = data
    listeners.forEach((listener) => listener(snapshot))
  } catch {
    if (controller !== current || current.signal.aborted) return
    snapshot = null
    listeners.forEach((listener) => listener(null))
  } finally {
    if (controller === current) {
      controller = null
      if (listeners.size && isVisible()) timer = setTimeout(poll, 3_000)
    }
  }
}

export function refreshLoginStatus() {
  clearTimeout(timer)
  controller?.abort()
  controller = null
  void poll()
}

export function subscribeLoginStatus(listener: Listener) {
  listeners.add(listener)
  if (snapshot) listener(snapshot)
  if (listeners.size === 1) {
    window.addEventListener("focus", refreshLoginStatus)
    document.addEventListener("visibilitychange", refreshLoginStatus)
    void poll()
  }
  return () => {
    listeners.delete(listener)
    if (listeners.size) return
    clearTimeout(timer)
    controller?.abort()
    controller = null
    snapshot = null
    window.removeEventListener("focus", refreshLoginStatus)
    document.removeEventListener("visibilitychange", refreshLoginStatus)
  }
}
