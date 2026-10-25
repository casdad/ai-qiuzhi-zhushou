"use client"

import { useRef, useState } from "react"
import { BiLoaderAlt, BiSend, BiStar, BiUserCircle } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { SectionCard } from "@/components/ui/section-card"
import { Textarea } from "@/components/ui/textarea"
import { API_BASE } from "@/lib/api"
import { BOSS_DELIVERY_TIMEOUT_MS, bossJobUrl, type DeliveryMode } from "@/lib/boss-delivery"

type RecommendedJob = {
  id: string
  companyName: string
  jobName: string
  salary: string
  location: string
  experience: string
  degree: string
  jobDescription: string
  jobUrl: string
  score: number
  reason: string
  applied: boolean
  queued: boolean
  uncertain: boolean
}

/** 单岗投递要开页面、生成招呼语并降速等待，给足时间。 */
const APPLY_TIMEOUT_MS = BOSS_DELIVERY_TIMEOUT_MS

/**
 * 对话式岗位推荐。
 *
 * 旧版抓取把输入直接当 Boss 搜索关键词，关键词没命中的岗位在搜索层就消失了，
 * 无法理解「职责相近、技能可迁移」这类意图。这里改成让模型对已抓取候选
 * 结合岗位描述做语义打分排序，黑名单等硬过滤仍然生效。
 */
export function RecommendationPanel({
  jobIntent,
  profileId,
  aiRelevanceEnabled,
  onJobIntentChange,
  deliveryMode,
  deliveryDisabled,
  onDeliveryChanged,
}: {
  jobIntent: string
  profileId?: number
  aiRelevanceEnabled: boolean
  onJobIntentChange: (value: string) => void
  deliveryMode: DeliveryMode | null
  deliveryDisabled: boolean
  onDeliveryChanged: () => void
}) {
  const [jobs, setJobs] = useState<RecommendedJob[]>([])
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState("")
  const [applyingJobId, setApplyingJobId] = useState<string | null>(null)
  const applyBusy = useRef(false)
  const recommendationBusy = useRef(false)
  const recommendationVersion = useRef(0)
  const localOutcomes = useRef<Record<string, { applied: boolean; queued: boolean; uncertain: boolean }>>({})

  const requestRecommendations = async () => {
    if (recommendationBusy.current) return
    const query = jobIntent.trim()
    if (!query) {
      setMessage("请先描述你想投递的岗位、城市、经验和薪资等要求。")
      return
    }
    recommendationBusy.current = true
    const version = ++recommendationVersion.current
    setLoading(true)
    setMessage("正在让模型理解岗位意图并计算匹配分…")
    try {
      const response = await fetch(`${API_BASE}/api/boss/recommendations`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query, profileId, limit: 50, sort: "score_desc" }),
      })
      const payload = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(payload.message || `HTTP ${response.status}`)
      if (version !== recommendationVersion.current) return

      // 后端可能返回数组、{jobs}、{recommendations}、{results} 或 {data}
      const source: unknown = Array.isArray(payload)
        ? payload
        : payload?.jobs || payload?.recommendations || payload?.results || payload?.data || []
      const rows: unknown[] = Array.isArray(source) ? source : []
      const normalized = rows
        .map((raw, index): RecommendedJob => {
          const row = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>
          const str = (...keys: string[]) => {
            for (const key of keys) {
              const value = row[key]
              if (value != null && value !== "") return String(value)
            }
            return ""
          }
          const rawScore = row.score ?? row.aiScore ?? row.matchScore ?? row.relevanceScore ?? 0
          const id = String(row.id ?? row.jobId ?? row.job_id ?? row.jobUrl ?? row.job_url ?? `recommendation-${index}`)
          const local = localOutcomes.current[id]
          return {
            id,
            companyName: str("companyName", "company_name", "company"),
            jobName: str("jobName", "job_name", "title"),
            salary: str("salary"),
            location: str("location", "city"),
            experience: str("experience"),
            degree: str("degree"),
            jobDescription: str("jobDescription", "job_description", "description"),
            jobUrl: str("jobUrl", "job_url", "url"),
            score: Math.max(0, Math.min(100, Number(rawScore) || 0)),
            reason: str("reason", "relevanceReason", "relevance_reason"),
            applied: Boolean(row.applied || local?.applied),
            queued: Boolean(row.queued || local?.queued),
            uncertain: Boolean(local?.uncertain),
          }
        })
        .sort((a, b) => b.score - a.score)

      setJobs(normalized)
      setMessage(
        normalized.length
          ? `已找到 ${normalized.length} 个岗位，按匹配分从高到低排序。`
          : "暂无已抓取岗位可供匹配；请先运行一次 Boss 投递流程建立候选库，再回到这里做语义推荐。"
      )
    } catch (error) {
      if (version !== recommendationVersion.current) return
      console.error("获取语义岗位推荐失败", error)
      setJobs([])
      setMessage(`岗位推荐失败：${error instanceof Error ? error.message : "请确认后端 API 已启动。"}`)
    } finally {
      setLoading(false)
      recommendationBusy.current = false
    }
  }

  const applyJob = async (job: RecommendedJob) => {
    if (applyBusy.current || deliveryDisabled || job.applied || job.queued || job.uncertain) return
    applyBusy.current = true
    setApplyingJobId(job.id)
    setMessage(`正在${deliveryMode === "CONFIRM" ? "生成草稿" : "投递"}${job.companyName ? `“${job.companyName}”` : ""}${job.jobName ? `的“${job.jobName}”` : "该岗位"}…`)
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), APPLY_TIMEOUT_MS)
    let definitelyNotAttempted = false
    try {
      const response = await fetch(
        `${API_BASE}/api/boss/recommendations/${encodeURIComponent(job.id)}/apply`,
        { method: "POST", headers: { "Content-Type": "application/json" }, signal: controller.signal }
      )
      const payload = await response.json().catch(() => ({}))
      definitelyNotAttempted = payload.attempted === false
      if (!response.ok || payload.success === false) {
        throw new Error(payload.message || `HTTP ${response.status}`)
      }
      const status = typeof payload.status === "string" ? payload.status.toUpperCase() : ""
      const queued = payload.queued === true || status === "PENDING" || status === "QUEUED"
      const sent = status === "SENT" || payload.applied === true
      localOutcomes.current[job.id] = { applied: sent, queued, uncertain: !queued && !sent }
      setJobs((current) => current.map((item) => (item.id === job.id ? {
        ...item,
        queued,
        applied: sent,
        uncertain: !queued && !sent,
      } : item)))
      setMessage(payload.message || (queued ? "已加入待确认，请在下方逐条核对并确认发送。" : sent ? "招呼语已确认发送。" : "请求已处理，请在下方队列核对结果，不要重复提交。"))
      onDeliveryChanged()
    } catch (error) {
      console.error("单岗投递失败", error)
      const detail =
        error instanceof DOMException && error.name === "AbortError"
          ? "投递超时，后台可能仍在执行，请稍后刷新列表确认状态"
          : error instanceof Error
            ? error.message
            : "请检查登录状态和后端服务。"
      setMessage(`投递失败：${detail}`)
      localOutcomes.current[job.id] = { applied: false, queued: false, uncertain: !definitelyNotAttempted }
      setJobs((current) => current.map((item) => item.id === job.id ? { ...item, uncertain: !definitelyNotAttempted } : item))
      onDeliveryChanged()
    } finally {
      clearTimeout(timeout)
      setApplyingJobId(null)
      applyBusy.current = false
    }
  }

  return (
    <SectionCard
      icon={<BiUserCircle className="brand-text" />}
      title="告诉模型你想投递什么岗位"
      description="模型会结合意向岗位描述、已填写的技术栈与项目经历理解求职方向，将其纳入岗位匹配评分；黑名单等硬过滤仍然生效。"
    >
      <div className="space-y-2">
        <label htmlFor="job-intent" className="sr-only">求职意图</label>
        <Textarea
          id="job-intent"
          value={jobIntent}
          rows={3}
          maxLength={4000}
          placeholder="例如：我想投递深圳或广州的计算机视觉算法实习，偏深度学习和目标检测，接受应届生，期望 300-500 元/天；不要纯销售和培训机构。"
          onChange={(e) => {
            recommendationVersion.current += 1
            onJobIntentChange(e.target.value)
            setJobs([])
            setMessage("")
          }}
          onKeyDown={(e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === "Enter" && !e.nativeEvent.isComposing) {
              e.preventDefault()
              void requestRecommendations()
            }
          }}
        />
        <p className="hint">随当前画像保存，点击右上角「保存配置」后参与投递前评分，无需单独找岗位。</p>
        <p className="hint">{loading ? "正在匹配已有候选岗位…" : "可选：Ctrl / ⌘ + Enter 对已抓取岗位重新评分排序。"}</p>
        {!aiRelevanceEnabled && <p className="text-xs text-amber-700 dark:text-amber-400">投递前自动评分需开启下方的「AI 岗位匹配门禁」。</p>}
      </div>

      <p className="mt-3 text-xs text-slate-500">
        {deliveryDisabled ? "发送配置尚未读取或岗位描述有未保存修改，请先保存配置。" : deliveryMode === "CONFIRM" ? "当前为确认模式：选择岗位只生成待确认草稿，不直接发送。" : "当前为自动模式：选择岗位将直接执行发送。"}
      </p>
      {message && <p role="status" className="mt-3 text-sm text-slate-500 dark:text-slate-400">{message}</p>}

      {jobs.length > 0 && (
        <div className="mt-4 space-y-3">
          {jobs.map((job, index) => (
            <div
              key={job.id}
              className="rounded-xl border border-slate-200 p-4 dark:border-slate-800"
            >
              <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                <div className="min-w-0 flex-1 space-y-1">
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <span className="text-xs font-semibold text-slate-400">#{index + 1}</span>
                    <span className="font-semibold text-slate-900 dark:text-white">
                      {job.jobName || "未命名岗位"}
                    </span>
                    {job.companyName && (
                      <span className="text-sm text-slate-500 dark:text-slate-400">{job.companyName}</span>
                    )}
                  </div>
                  <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-500 dark:text-slate-400">
                    {[job.salary, job.location, job.experience, job.degree].filter(Boolean).map((v) => (
                      <span key={v}>{v}</span>
                    ))}
                  </div>
                  {job.reason && (
                    <p className="pt-1 text-sm leading-6 text-slate-600 dark:text-slate-400">
                      匹配理由：{job.reason}
                    </p>
                  )}
                  {job.jobDescription && (
                    <p className="line-clamp-2 pt-1 text-xs leading-5 text-slate-400">{job.jobDescription}</p>
                  )}
                </div>

                <div className="flex shrink-0 items-center gap-3 lg:flex-col lg:items-end">
                  <div className="brand-text flex items-center gap-1 text-lg font-bold">
                    <BiStar />
                    {job.score.toFixed(0)}
                    <span className="text-xs font-normal text-slate-400">分</span>
                  </div>
                  <Button
                    size="sm"
                    variant={job.applied ? "success" : "brand"}
                    disabled={applyingJobId !== null || deliveryDisabled || job.applied || job.queued || job.uncertain}
                    onClick={() => void applyJob(job)}
                  >
                    {applyingJobId === job.id ? (
                      <>
                        <BiLoaderAlt className="animate-spin" /> {deliveryMode === "CONFIRM" ? "生成中…" : "投递中…"}
                      </>
                    ) : job.applied ? (
                      "招呼语已发送"
                    ) : job.queued ? (
                      "已加入待确认"
                    ) : job.uncertain ? (
                      "结果待核对"
                    ) : (
                      <>
                        <BiSend /> {deliveryMode === "CONFIRM" ? "加入待确认" : "自动投递该岗位"}
                      </>
                    )}
                  </Button>
                  {bossJobUrl(job.jobUrl) && (
                    <a
                      href={bossJobUrl(job.jobUrl)}
                      target="_blank"
                      rel="noreferrer"
                      className="text-xs text-slate-500 hover:underline"
                    >
                      查看原岗位
                    </a>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </SectionCard>
  )
}
