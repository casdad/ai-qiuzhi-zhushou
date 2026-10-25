"use client"

import { useCallback, useEffect, useRef, useState } from "react"

import { API_BASE, requestJson } from "@/lib/api"
import { refreshLoginStatus, subscribeLoginStatus, type LoginSnapshot } from "@/lib/login-status"

export type PlatformKey = "boss" | "liepin" | "51job" | "zhilian"

/** 后端登录快照里各平台的字段名。 */
const LOGIN_FLAG: Record<PlatformKey, keyof LoginSnapshot> = {
  boss: "bossLoggedIn",
  liepin: "liepinLoggedIn",
  "51job": "job51LoggedIn",
  zhilian: "zhilianLoggedIn",
}

export const PLATFORM_LABEL: Record<PlatformKey, string> = {
  boss: "Boss 直聘",
  liepin: "猎聘",
  "51job": "前程无忧",
  zhilian: "智联招聘",
}

type Result = { success: boolean; message?: string }

/** 招聘平台本次运行的登录态与投递开关；不保存登录凭证。 */
export function usePlatformSession(platform: PlatformKey, { trackTask = true }: { trackTask?: boolean } = {}) {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [checking, setChecking] = useState(true)
  const [isDelivering, setIsDelivering] = useState(false)
  const [isStopping, setIsStopping] = useState(false)
  const [safetyHoldReason, setSafetyHoldReason] = useState("")
  const [taskStatusKnown, setTaskStatusKnown] = useState(false)
  const [notice, setNotice] = useState<{ tone: "success" | "danger"; message: string } | null>(
    null
  )
  const taskOperationPending = useRef(false)
  const taskStatusController = useRef<AbortController | null>(null)

  // 关闭时不要把提示写进已卸载的组件
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    return subscribeLoginStatus((snapshot) => {
      if (!mounted.current) return
      setIsLoggedIn(snapshot?.[LOGIN_FLAG[platform]] === true)
      setChecking(false)
    })
  }, [platform])

  const syncTaskStatus = useCallback(async () => {
    if (!trackTask || taskOperationPending.current) return
    taskStatusController.current?.abort()
    const controller = new AbortController()
    taskStatusController.current = controller
    const timeout = setTimeout(() => controller.abort(), 10_000)
    try {
      const response = await fetch(`${API_BASE}/api/${platform}/status`, { cache: "no-store", signal: controller.signal })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const data = await response.json()
      if (!mounted.current || controller.signal.aborted || taskOperationPending.current) return
      if (typeof data.isRunning !== "boolean") throw new Error("任务状态响应不完整")
      const stopping = data.taskState === "STOPPING"
      setIsStopping(stopping)
      setIsDelivering(data.isRunning || stopping || data.taskState === "RUNNING")
      setSafetyHoldReason(typeof data.safetyHoldReason === "string" ? data.safetyHoldReason : "")
      setTaskStatusKnown(true)
    } catch {
      if (mounted.current && taskStatusController.current === controller && !taskOperationPending.current) setTaskStatusKnown(false)
    } finally {
      clearTimeout(timeout)
    }
  }, [platform, trackTask])

  useEffect(() => {
    if (!trackTask) return
    void syncTaskStatus()
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") void syncTaskStatus()
    }, 30_000)
    const onFocus = () => void syncTaskStatus()
    window.addEventListener("focus", onFocus)
    return () => {
      clearInterval(timer)
      taskStatusController.current?.abort()
      window.removeEventListener("focus", onFocus)
    }
  }, [platform, syncTaskStatus, trackTask])

  const post = useCallback(async (path: string): Promise<Result> => {
    try {
      const data = await requestJson<Result>(`${API_BASE}${path}`, { method: "POST" })
      if (data.success !== true) {
        return { success: false, message: data.message || "服务未确认操作成功" }
      }
      return { success: true, message: data.message }
    } catch (error) {
      console.error(`[${platform}] 请求 ${path} 失败`, error)
      return { success: false, message: error instanceof Error ? error.message : "无法连接后端服务，请确认服务已启动" }
    }
  }, [platform])

  const start = useCallback(async () => {
    if (taskOperationPending.current) return
    taskOperationPending.current = true
    taskStatusController.current?.abort()
    setIsDelivering(true)
    const result = await post(`/api/${platform}/start`)
    taskOperationPending.current = false
    if (!result.success) {
      setIsDelivering(false)
      setNotice({ tone: "danger", message: result.message || "启动投递失败，请查看后端日志" })
    } else {
      setNotice({ tone: "success", message: result.message || "任务已启动，发送方式以已保存的配置为准。" })
    }
    await syncTaskStatus()
  }, [platform, post, syncTaskStatus])

  const stop = useCallback(async () => {
    if (taskOperationPending.current) return
    taskOperationPending.current = true
    taskStatusController.current?.abort()
    const result = await post(`/api/${platform}/stop`)
    taskOperationPending.current = false
    if (result.success) setIsStopping(true)
    await syncTaskStatus()
    setNotice(
      result.success
        ? { tone: "success", message: result.message || "已请求停止；完全退出前不能启动新任务。" }
        : { tone: "danger", message: result.message || "停止失败，任务可能已结束。" }
    )
  }, [platform, post, syncTaskStatus])

  const logout = useCallback(async () => {
    if (taskOperationPending.current) return
    taskOperationPending.current = true
    taskStatusController.current?.abort()
    const result = await post(`/api/${platform}/logout`)
    taskOperationPending.current = false
    if (result.success) {
      setIsLoggedIn(false)
      setIsDelivering(false)
    }
    refreshLoginStatus()
    void syncTaskStatus()
    setNotice(
      result.success
        ? { tone: "success", message: "已退出登录，本次登录态已清除。" }
        : { tone: "danger", message: `退出登录失败：${result.message || "服务返回异常。"}` }
    )
  }, [platform, post, syncTaskStatus])

  const openLogin = useCallback(async () => {
    if (taskOperationPending.current) return
    taskOperationPending.current = true
    taskStatusController.current?.abort()
    const result = await post(`/api/${platform}/session/open`)
    taskOperationPending.current = false
    setNotice({ tone: result.success ? "success" : "danger", message: result.message || "请在平台浏览器完成登录。" })
    refreshLoginStatus()
    await syncTaskStatus()
  }, [platform, post, syncTaskStatus])

  /** 兼容旧调用方；登录态仅在本次运行有效，不再保存 Cookie。 */
  const saveCookie = useCallback(async () => {}, [])

  const notify = useCallback((tone: "success" | "danger", message: string) => {
    setNotice({ tone, message })
  }, [])

  return {
    isLoggedIn,
    checking,
    isDelivering,
    isStopping,
    safetyHoldReason,
    taskStatusKnown,
    notice,
    clearNotice: useCallback(() => setNotice(null), []),
    start,
    stop,
    logout,
    openLogin,
    syncTaskStatus,
    saveCookie,
    notify,
  }
}
