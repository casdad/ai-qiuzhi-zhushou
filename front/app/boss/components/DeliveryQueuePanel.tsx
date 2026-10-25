"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { BiCheck, BiLoaderAlt, BiRefresh, BiSend, BiX } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { Dialog } from "@/components/ui/dialog"
import { SectionCard } from "@/components/ui/section-card"
import { Textarea } from "@/components/ui/textarea"
import { API_BASE } from "@/lib/api"
import {
  bossJobUrl,
  BOSS_DELIVERY_TIMEOUT_MS,
  DELIVERY_STATUS_LABEL,
  isDeliveryStatus,
  normalizeDelivery,
  type BossDelivery,
} from "@/lib/boss-delivery"

const CONFIRM_TIMEOUT_MS = BOSS_DELIVERY_TIMEOUT_MS
const REFRESH_INTERVAL_MS = 30_000
type SafetyState = { reason: string; taskActive: boolean }

export function DeliveryQueuePanel({ refreshKey }: { refreshKey: number }) {
  const [items, setItems] = useState<BossDelivery[]>([])
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [uncertain, setUncertain] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [notice, setNotice] = useState("")
  const [error, setError] = useState("")
  const [loadError, setLoadError] = useState("")
  const [safety, setSafety] = useState<SafetyState | null>(null)
  const [resumeDialog, setResumeDialog] = useState(false)
  const [resumeAcknowledged, setResumeAcknowledged] = useState(false)
  const [resuming, setResuming] = useState(false)
  const mounted = useRef(true)
  const actionBusy = useRef(false)
  const refreshController = useRef<AbortController | null>(null)

  const refresh = useCallback(async () => {
    if (actionBusy.current) return
    refreshController.current?.abort()
    const controller = new AbortController()
    refreshController.current = controller
    const timeout = setTimeout(() => controller.abort(), 15_000)
    setLoading(true)
    try {
      const response = await fetch(`${API_BASE}/api/boss/deliveries`, {
        cache: "no-store",
        signal: controller.signal,
      })
      const payload = await response.json()
      if (!response.ok || payload.success !== true || !Array.isArray(payload.items)) {
        throw new Error(payload.message || `读取队列失败（HTTP ${response.status}）`)
      }
      const rows = (payload.items as unknown[]).map(normalizeDelivery).filter((row): row is BossDelivery => row !== null)
      const statusResponse = await fetch(`${API_BASE}/api/boss/status`, { cache: "no-store", signal: controller.signal })
      if (!statusResponse.ok) throw new Error(`读取账号暂停状态失败（HTTP ${statusResponse.status}）`)
      const taskStatus = await statusResponse.json()
      if (typeof taskStatus.isRunning !== "boolean") throw new Error("任务状态响应不完整，暂不可确认发送。")
      if (!mounted.current || controller.signal.aborted) return
      setSafety({
        reason: typeof taskStatus.safetyHoldReason === "string" ? taskStatus.safetyHoldReason : "",
        taskActive: taskStatus.isRunning === true || taskStatus.taskState === "RUNNING" || taskStatus.taskState === "STOPPING",
      })
      setItems(rows)
      setLoadError("")
      setLoaded(true)
      // 未收到发送响应时，PENDING 也不能证明旧请求不会继续执行。
      setUncertain((current) => Object.fromEntries(Object.entries(current).filter(([id]) =>
        !rows.some((item) => item.id === id && item.status !== "PENDING" && item.status !== "IN_FLIGHT")
      )))
    } catch (failure) {
      if (!mounted.current || refreshController.current !== controller) return
      setSafety(null)
      setLoadError(controller.signal.aborted ? "读取队列超时，请手动刷新。" : failure instanceof Error ? failure.message : "无法读取待确认队列。")
    } finally {
      clearTimeout(timeout)
      if (mounted.current && refreshController.current === controller) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void refresh()
    return () => {
      mounted.current = false
      refreshController.current?.abort()
    }
  }, [refresh, refreshKey])

  useEffect(() => {
    if (!autoRefresh) return
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") void refresh()
    }, REFRESH_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [autoRefresh, refresh])

  const act = async (item: BossDelivery, action: "confirm" | "dismiss") => {
    if (actionBusy.current || item.status !== "PENDING" || uncertain[item.id]) return
    if (action === "confirm" && (!safety || safety.reason || safety.taskActive)) return
    const message = (drafts[item.id] ?? item.message).trim()
    if (action === "confirm" && (!message || message.length > 2000)) {
      setError("招呼语需为 1-2000 个字符，请编辑后再确认。")
      return
    }
    actionBusy.current = true
    refreshController.current?.abort()
    setBusyId(item.id)
    setLoading(false)
    setError("")
    setNotice(action === "confirm" ? "正在确认发送，请勿重复操作。关闭页面不会取消已经提交的请求。" : "正在跳过该草稿…")
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), CONFIRM_TIMEOUT_MS)
    let definitiveResponse = false
    try {
      const response = await fetch(`${API_BASE}/api/boss/deliveries/${encodeURIComponent(item.id)}/${action}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: action === "confirm" ? JSON.stringify({ message }) : undefined,
        signal: controller.signal,
      })
      const payload = await response.json()
      if (!mounted.current) return
      definitiveResponse = payload.attempted === false || (isDeliveryStatus(payload.status) && payload.success === false)
      if (isDeliveryStatus(payload.status)) {
        setItems((current) => current.map((row) => row.id === item.id ? {
          ...row,
          status: payload.status,
          reason: payload.message || row.reason,
        } : row))
      }
      if (!response.ok || payload.success !== true) {
        throw new Error(payload.message || `操作未完成（HTTP ${response.status}）`)
      }
      if (action === "confirm" && !isDeliveryStatus(payload.status)) throw new Error("服务未返回明确的发送状态")
      setNotice(payload.message || (action === "confirm" ? "确认操作已完成，请核对最新状态。" : "已跳过该草稿。"))
    } catch (failure) {
      if (!mounted.current) return
      const lockForReview = action === "confirm" && !definitiveResponse
      if (lockForReview) setUncertain((current) => ({ ...current, [item.id]: true }))
      setNotice("")
      setError(`${failure instanceof Error && failure.name !== "AbortError" ? failure.message : "未能取得操作结果"}。${lockForReview ? "该条已锁定，不自动重发；请刷新状态并到官方界面核对。编辑内容已保留。" : "请刷新状态后核对，编辑内容已保留。"}`)
    } finally {
      clearTimeout(timeout)
      actionBusy.current = false
      if (mounted.current) {
        setBusyId(null)
        void refresh()
      }
    }
  }

  const resume = async () => {
    if (actionBusy.current || !resumeAcknowledged || !safety?.reason || safety.taskActive) return
    actionBusy.current = true
    refreshController.current?.abort()
    setLoading(false)
    setResuming(true)
    setError("")
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 15_000)
    try {
      const response = await fetch(`${API_BASE}/api/boss/resume`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ acknowledged: true }),
        signal: controller.signal,
      })
      const payload = await response.json()
      if (!response.ok || payload.success !== true) throw new Error(payload.message || `恢复失败（HTTP ${response.status}）`)
      if (mounted.current) {
        setResumeDialog(false)
        setResumeAcknowledged(false)
        setNotice(payload.message || "已解除安全暂停。不会自动重发结果不确定的草稿，请另行启动新任务。")
      }
    } catch (failure) {
      if (mounted.current) setError(failure instanceof Error && failure.name !== "AbortError" ? failure.message : "未能取得恢复结果，请刷新本地状态核对。")
    } finally {
      clearTimeout(timeout)
      actionBusy.current = false
      if (mounted.current) {
        setResuming(false)
        void refresh()
      }
    }
  }

  const pendingCount = items.filter((item) => item.status === "PENDING").length
  return (
    <SectionCard
      icon={<BiSend className="brand-text" />}
      title={`待确认与发送记录${loaded ? ` · ${pendingCount} 条待确认` : ""}`}
      description="只支持逐条确认，不提供批量确认。已发送指招呼语确认成功，不等于附件简历已发送。"
      actions={
        <Button size="sm" variant="outline" disabled={loading || busyId !== null || resuming} onClick={() => void refresh()}>
          <BiRefresh className={loading ? "animate-spin" : ""} /> 刷新
        </Button>
      }
    >
      <div className="mb-4 space-y-2">
        <label className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
          <input type="checkbox" checked={autoRefresh} onChange={(event) => setAutoRefresh(event.target.checked)} />
          每 30 秒刷新本地队列（页面隐藏时暂停，不访问平台）
        </label>
        <p className="text-xs leading-5 text-slate-500">生成草稿或任务结束后可点击刷新。UNKNOWN 结果不确定，不可直接重发；RESTRICTED 需本人处理登录、验证或平台限制。</p>
        {safety?.reason && (
          <div className="space-y-2 rounded-lg border border-amber-200 bg-amber-50 p-3 dark:border-amber-900 dark:bg-amber-950/40">
            <p className="text-sm text-amber-800 dark:text-amber-300">账号级暂停：{safety.reason}</p>
            <Button size="sm" variant="outline" disabled={safety.taskActive || busyId !== null || resuming} onClick={() => { setResumeAcknowledged(false); setResumeDialog(true) }}>
              已人工处理，恢复任务
            </Button>
            {safety.taskActive && <p className="text-xs text-amber-700 dark:text-amber-400">任务仍在运行或停止中，请等待完全退出后刷新，再恢复。</p>}
          </div>
        )}
        {safety?.taskActive && !safety.reason && <p className="text-xs text-amber-700 dark:text-amber-400">任务正在运行或停止中，确认发送暂不可用。请等待任务结束后刷新。</p>}
        {notice && <p role="status" className="text-sm text-slate-600 dark:text-slate-300">{notice}</p>}
        {error && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{error}</p>}
        {loadError && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{loadError}</p>}
      </div>
      {!loaded && loading && <p className="text-sm text-slate-500">正在读取队列…</p>}
      {loaded && items.length === 0 && <p className="text-sm text-slate-500">暂无记录。确认模式下启动任务或选择单个推荐岗位后，会在此生成草稿。</p>}
      <div className="max-h-[48rem] space-y-4 overflow-y-auto">
        {items.map((item) => {
          const editable = item.status === "PENDING" && !uncertain[item.id]
          const jobUrl = bossJobUrl(item.jobUrl)
          return (
            <article key={item.id} className="space-y-3 rounded-xl border border-slate-200 p-4 dark:border-slate-800">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <h3 className="text-sm font-semibold text-slate-900 dark:text-white">{item.jobName || "未命名岗位"}</h3>
                  <p className="mt-1 text-xs text-slate-500">{item.companyName || "公司信息未提供"}</p>
                </div>
                <span className="rounded bg-slate-100 px-2 py-1 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                  {uncertain[item.id] ? "响应不确定 · 已锁定待核对" : DELIVERY_STATUS_LABEL[item.status]}
                </span>
              </div>
              <label htmlFor={`delivery-message-${item.id}`} className="block text-xs font-medium text-slate-600 dark:text-slate-300">确认发送的招呼语</label>
              <Textarea
                id={`delivery-message-${item.id}`}
                value={drafts[item.id] ?? item.message}
                rows={4}
                maxLength={2000}
                disabled={!editable || busyId !== null}
                onChange={(event) => setDrafts((current) => ({ ...current, [item.id]: event.target.value }))}
              />
              {item.sendImgResume && <p className="text-xs leading-5 text-amber-700 dark:text-amber-400">确认后将尝试发送配置中的图片简历；附件状态需在官方界面核对。招呼语成功不代表图片简历成功。</p>}
              {item.reason && <p className="break-words text-xs leading-5 text-slate-500">结果说明：{item.reason}</p>}
              <div className="flex flex-wrap items-center gap-2">
                {editable && <>
                  <Button size="sm" variant="brand" disabled={busyId !== null || resuming || !safety || !!safety.reason || safety.taskActive || !(drafts[item.id] ?? item.message).trim()} onClick={() => void act(item, "confirm")}>
                    {busyId === item.id ? <BiLoaderAlt className="animate-spin" /> : <BiCheck />} 核对无误，确认发送
                  </Button>
                  <Button size="sm" variant="outline" disabled={busyId !== null || resuming} onClick={() => void act(item, "dismiss")}>
                    <BiX /> 跳过
                  </Button>
                </>}
                {jobUrl && <a href={jobUrl} target="_blank" rel="noopener noreferrer" className="text-xs text-slate-500 hover:underline">打开官方岗位核对</a>}
                {item.updatedAt && <span className="text-xs text-slate-400">更新：{item.updatedAt}</span>}
              </div>
            </article>
          )
        })}
      </div>
      <Dialog
        open={resumeDialog}
        onClose={() => { if (!resuming) setResumeDialog(false) }}
        title="确认已完成人工处理"
        description="只有完成官方界面的验证、重新登录或限制处理，并核对结果不确定的发送后，才应解除暂停。恢复不会重发 UNKNOWN 记录，也不会自动启动投递。"
        actions={
          <>
            <Button size="sm" variant="ghost" disabled={resuming} onClick={() => setResumeDialog(false)}>取消</Button>
            <Button size="sm" variant="brand" disabled={resuming || !resumeAcknowledged || !safety?.reason || safety.taskActive} onClick={() => void resume()}>
              {resuming ? "恢复中…" : "确认解除暂停"}
            </Button>
          </>
        }
      >
        <label className="mt-4 flex items-start gap-2 text-xs leading-5 text-slate-600 dark:text-slate-300">
          <input type="checkbox" className="mt-1" checked={resumeAcknowledged} disabled={resuming} onChange={(event) => setResumeAcknowledged(event.target.checked)} />
          我已完成上述人工处理，并已核对未知发送结果。
        </label>
      </Dialog>
    </SectionCard>
  )
}
