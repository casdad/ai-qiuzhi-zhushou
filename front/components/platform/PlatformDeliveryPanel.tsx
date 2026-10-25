"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { BiSend } from "react-icons/bi"
import { Button } from "@/components/ui/button"
import { Dialog } from "@/components/ui/dialog"
import { SectionCard } from "@/components/ui/section-card"
import { API_BASE, ApiResponseError, requestJson } from "@/lib/api"
import { type PlatformKey, type usePlatformSession } from "@/components/platform/usePlatformSession"

type Platform = Exclude<PlatformKey, "boss">
type Draft = { id: string; platform: string; status: string; reason?: string; job: { title: string; company: string; salary: string; url: string } }
type Result = { success: boolean; message?: string; status?: string }
const labels: Record<string, string> = { PENDING: "待确认", IN_FLIGHT: "执行中", SENT: "已确认成功", UNKNOWN: "结果待核对", FAILED: "失败", RESTRICTED: "平台限制", SKIPPED: "已跳过", DISMISSED: "已忽略" }

export function PlatformDeliveryPanel({ platform, session }: { platform: Platform; session: ReturnType<typeof usePlatformSession> }) {
  const [mode, setMode] = useState<"CONFIRM" | "AUTO">("CONFIRM")
  const [savedMode, setSavedMode] = useState<"CONFIRM" | "AUTO" | null>(null)
  const [items, setItems] = useState<Draft[]>([])
  const [ready, setReady] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  const [notice, setNotice] = useState("")
  const [uncertain, setUncertain] = useState<Record<string, boolean>>({})
  const [selected, setSelected] = useState<Draft | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const acting = useRef(false)
  const mounted = useRef(true)
  const refreshController = useRef<AbortController | null>(null)
  const base = `${API_BASE}/api/${platform}`

  const refresh = useCallback(async (clearError = true) => {
    if (acting.current) return
    refreshController.current?.abort()
    const controller = new AbortController()
    refreshController.current = controller
    try {
      const settings = await requestJson<{ success: boolean; mode: "CONFIRM" | "AUTO" }>(`${base}/delivery-settings`, { signal: controller.signal })
      const queue = await requestJson<{ success: boolean; items: Draft[] }>(`${base}/deliveries`, { signal: controller.signal, cache: "no-store" })
      if (!settings.success || !["CONFIRM", "AUTO"].includes(settings.mode) || !queue.success || !Array.isArray(queue.items)
        || queue.items.some((row) => !row || row.platform !== platform || typeof row.id !== "string" || !row.id || !row.job
          || typeof row.job.title !== "string" || typeof row.job.company !== "string" || !Object.hasOwn(labels, row.status))) throw new Error("队列响应不完整")
      if (!mounted.current || controller.signal.aborted) return
      setMode(settings.mode)
      setSavedMode(settings.mode)
      setItems(queue.items)
      setUncertain((current) => Object.fromEntries(Object.entries(current).filter(([id]) =>
        !queue.items.some((item) => item.id === id && item.status !== "PENDING" && item.status !== "IN_FLIGHT")
      )))
      setReady(true)
      if (clearError) setError("")
    } catch (failure) {
      if (!mounted.current || controller.signal.aborted) return
      setReady(false)
      setError(failure instanceof Error ? failure.message : "读取队列失败")
    }
  }, [base, platform])

  useEffect(() => {
    mounted.current = true
    void refresh()
    return () => { mounted.current = false; refreshController.current?.abort() }
  }, [refresh])

  const operate = async (path: string, body?: object, id?: string, method = "POST") => {
    if (acting.current) return
    acting.current = true
    refreshController.current?.abort()
    setBusy(true)
    setError("")
    setNotice("")
    try {
      const result = await requestJson<Result>(`${base}${path}`, {
        method, headers: { "Content-Type": "application/json" }, ...(body ? { body: JSON.stringify(body) } : {}),
      }, id ? 240_000 : 10_000)
      if (result.success !== true) throw new Error(result.message || "服务未确认操作成功")
      if (mounted.current) {
        setNotice(result.message || "已保存")
        if (path === "/resume") setAcknowledged(false)
      }
    } catch (failure) {
      if (mounted.current) {
        setError(failure instanceof Error ? failure.message : "操作未确认，不自动重试")
        const response = failure instanceof ApiResponseError ? failure.data as Result | null : null
        const rejectedBeforeSend = failure instanceof ApiResponseError && failure.status === 409
          && response?.success === false && ["busy", "session_changed", "PENDING"].includes(response.status || "")
        if (id && !rejectedBeforeSend) setUncertain((current) => ({ ...current, [id]: true }))
      }
    } finally {
      acting.current = false
      if (mounted.current) { setBusy(false); setSelected(null); await refresh(false); await session.syncTaskStatus() }
    }
  }

  const blocked = busy || !ready || !session.taskStatusKnown || session.isDelivering || !!session.safetyHoldReason || !session.isLoggedIn

  return <SectionCard icon={<BiSend className="brand-text" />} title="发送模式与待确认投递" description="确认模式不点击申请或聊天；自动模式只处理新准备的岗位，不自动发送旧草稿。">
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <label><input type="radio" name={`mode-${platform}`} checked={mode === "CONFIRM"} disabled={busy || !ready} onChange={() => setMode("CONFIRM")} /> 逐条确认（默认）</label>
        <label><input type="radio" name={`mode-${platform}`} checked={mode === "AUTO"} disabled={busy || !ready} onChange={() => setMode("AUTO")} /> 自动投递</label>
        <Button size="sm" disabled={busy || !ready || session.isDelivering || mode === savedMode} onClick={() => void operate("/delivery-settings", { mode }, undefined, "PUT")}>保存发送模式</Button>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => { void refresh(); void session.syncTaskStatus() }}>刷新队列</Button>
      </div>
      <p className="text-xs text-slate-500">先保存搜索配置和发送模式，再点击上方“开始任务”。每次最多检查 20 岗；投递至少间隔 120 秒，主动浏览至少间隔 15 秒。猎聘按平台默认方式发起沟通；51job、智联投递平台已保存的简历，不额外投递相似岗位。</p>
      {mode !== savedMode && ready && <p className="text-sm text-amber-700">模式尚未保存；启动仍按已保存的 {savedMode === "AUTO" ? "自动投递" : "逐条确认"} 模式执行。</p>}
      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      {notice && <p role="status" className="text-sm text-emerald-700">{notice}</p>}
      {session.safetyHoldReason && <div className="space-y-2 rounded-lg border border-amber-300 p-3 text-sm">
        <p>任务已暂停：{session.safetyHoldReason}</p>
        <label className="block"><input type="checkbox" checked={acknowledged} onChange={(e) => setAcknowledged(e.target.checked)} /> 我已在官方界面核对未知结果并处理限制</label>
        <Button size="sm" disabled={busy || !acknowledged || session.isDelivering} onClick={() => void operate("/resume", { acknowledged: true })}>确认恢复（不自动启动）</Button>
      </div>}
      {!items.length && <p className="text-sm text-slate-500">暂无投递记录。确认模式运行后，岗位会出现在这里。</p>}
      <div className="max-h-[36rem] space-y-3 overflow-auto">
        {items.map((item) => <div key={item.id} className="rounded-lg border border-slate-200 p-3 dark:border-slate-700">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div><p className="font-medium">{item.job.title}</p><p className="text-sm text-slate-500">{item.job.company} · {item.job.salary}</p></div>
            <span className="text-sm">{labels[item.status]}</span>
          </div>
          {item.reason && <p className="mt-2 text-sm text-slate-500">{item.reason}</p>}
          {uncertain[item.id] && <p className="text-sm text-amber-700">请求结果未确认，刷新核对后再操作，不重复发送。</p>}
          {item.status === "PENDING" && <div className="mt-2 flex gap-2">
            <Button size="sm" disabled={blocked || uncertain[item.id]} onClick={() => setSelected(item)}>确认投递</Button>
            <Button size="sm" variant="outline" disabled={busy || uncertain[item.id]} onClick={() => void operate(`/deliveries/${item.id}/dismiss`)}>忽略</Button>
          </div>}
        </div>)}
      </div>
      <Dialog open={!!selected} onClose={() => { if (!busy) setSelected(null) }} title="确认投递此岗位"
        description={selected ? `${selected.job.company} · ${selected.job.title}。将使用平台默认简历/沟通方式；操作结果不明时不会重发。` : ""}
        actions={<Button disabled={blocked || !selected} onClick={() => { if (selected) void operate(`/deliveries/${selected.id}/confirm`, undefined, selected.id) }}>{busy ? "正在执行…" : "确认并投递"}</Button>} />
    </div>
  </SectionCard>
}
