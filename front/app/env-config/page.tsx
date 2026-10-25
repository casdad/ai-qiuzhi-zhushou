"use client"

import { useCallback, useEffect, useState } from "react"
import { BiBell, BiCodeAlt, BiRefresh, BiSave } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { Field } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SectionCard } from "@/components/ui/section-card"
import PageHeader from "@/components/layout/PageHeader"
import { API_BASE } from "@/lib/api"
import { cn } from "@/lib/utils"

type EnvDraft = {
  hookUrl: string
  baseUrl: string
  apiKey: string
  model: string
  botIsSend: number
}

const EMPTY_CONFIG: EnvDraft = { hookUrl: "", baseUrl: "", apiKey: "", model: "", botIsSend: 0 }

function isHttpUrl(value: string) {
  try {
    return ["http:", "https:"].includes(new URL(value).protocol)
  } catch {
    return false
  }
}

/** 加载/保存状态提示条。原样式用 text-slate-500 表示"已同步"，与"读取中"几乎同色。 */
function StatusLine({
  tone,
  children,
  action,
}: {
  tone: "muted" | "success" | "danger"
  children: React.ReactNode
  action?: React.ReactNode
}) {
  return (
    <div className="flex min-h-7 flex-wrap items-center justify-between gap-3 text-sm" aria-live="polite">
      <p
        role={tone === "danger" ? "alert" : "status"}
        className={cn(
          tone === "danger" && "text-rose-600 dark:text-rose-400",
          tone === "success" && "text-emerald-700 dark:text-emerald-400",
          tone === "muted" && "text-slate-500 dark:text-slate-400"
        )}
      >
        {children}
      </p>
      {action}
    </div>
  )
}

export default function EnvConfig() {
  const [config, setConfig] = useState<EnvDraft>(EMPTY_CONFIG)
  const [saved, setSaved] = useState<EnvDraft | null>(null)
  const [showApiKey, setShowApiKey] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [loadError, setLoadError] = useState("")
  const [feedback, setFeedback] = useState<{ success: boolean; message: string } | null>(null)

  const isDirty = saved !== null && JSON.stringify(config) !== JSON.stringify(saved)
  const disabled = loading || saving || saved === null

  const fetchConfig = useCallback(async (signal?: AbortSignal) => {
    try {
      const response = await fetch(`${API_BASE}/api/config`, { signal, cache: "no-store" })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const result = await response.json()
      if (!result.success || !result.data || typeof result.data !== "object" || Array.isArray(result.data)) {
        throw new Error(result.message || "配置响应无效")
      }
      const data = result.data
      const flag = String(data.BOT_IS_SEND ?? "").trim().toLowerCase()
      const next: EnvDraft = {
        hookUrl: String(data.HOOK_URL ?? ""),
        baseUrl: String(data.BASE_URL ?? ""),
        apiKey: String(data.API_KEY ?? ""),
        model: String(data.MODEL ?? ""),
        botIsSend: flag === "1" || flag === "true" ? 1 : 0,
      }
      setConfig(next)
      setSaved(next)
      setLoadError("")
    } catch (error) {
      if (signal?.aborted) return
      console.error("获取服务配置失败:", error)
      setLoadError("未能读取已保存的配置。请检查后端连接后重试，当前禁止保存以免覆盖原配置。")
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void fetchConfig(controller.signal)
    return () => controller.abort()
  }, [fetchConfig])

  const update = <K extends keyof EnvDraft>(key: K, value: EnvDraft[K]) => {
    setConfig((current) => ({ ...current, [key]: value }))
    setFeedback(null)
  }

  const handleSave = async () => {
    if (disabled || !isDirty) return
    const draft: EnvDraft = {
      ...config,
      hookUrl: config.hookUrl.trim(),
      baseUrl: config.baseUrl.trim(),
      apiKey: config.apiKey.trim(),
      model: config.model.trim(),
    }
    if (draft.baseUrl && !isHttpUrl(draft.baseUrl)) {
      setFeedback({ success: false, message: "API 地址需为有效的 http:// 或 https:// URL。" })
      return
    }
    if (draft.botIsSend && !isHttpUrl(draft.hookUrl)) {
      setFeedback({ success: false, message: "启用通知前，请填写有效的 Webhook URL。" })
      return
    }
    setSaving(true)
    setFeedback(null)
    try {
      const response = await fetch(`${API_BASE}/api/config`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          HOOK_URL: draft.hookUrl,
          BASE_URL: draft.baseUrl,
          API_KEY: draft.apiKey,
          MODEL: draft.model,
          BOT_IS_SEND: String(draft.botIsSend),
        }),
      })
      const result = await response.json()
      if (!response.ok || !result.success) throw new Error(result.message || `HTTP ${response.status}`)
      if (typeof result.updateCount === "number" && result.updateCount !== 5) {
        throw new Error("部分配置未写入，请重试")
      }
      setConfig(draft)
      setSaved(draft)
      setFeedback({ success: true, message: "服务与通知配置已保存。" })
    } catch (error) {
      console.error("保存服务配置失败:", error)
      setFeedback({ success: false, message: "保存失败，修改内容已保留，请检查后端连接后重试。" })
    } finally {
      setSaving(false)
    }
  }

  const status = loading
    ? { tone: "muted" as const, text: "正在读取配置…" }
    : loadError
      ? { tone: "danger" as const, text: loadError }
      : feedback
        ? { tone: feedback.success ? ("success" as const) : ("danger" as const), text: feedback.message }
        : { tone: "muted" as const, text: isDirty ? "有未保存的更改" : "配置已同步" }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiCodeAlt />}
        title="服务与通知"
        subtitle="连接 AI 服务，按需接收任务通知。"
        actions={
          <Button variant="brand" onClick={handleSave} disabled={disabled || !isDirty}>
            <BiSave /> {saving ? "保存中…" : "保存更改"}
          </Button>
        }
      />

      <StatusLine
        tone={status.tone}
        action={
          loadError && !loading ? (
            <Button
              variant="outline"
              size="sm"
              className="shrink-0"
              onClick={() => {
                setLoading(true)
                void fetchConfig()
              }}
            >
              <BiRefresh /> 重新加载
            </Button>
          ) : null
        }
      >
        {status.text}
      </StatusLine>

      <fieldset disabled={disabled} className="grid min-w-0 grid-cols-1 gap-6 lg:grid-cols-5">
        <SectionCard
          className="lg:col-span-3"
          icon={<BiCodeAlt className="brand-text" />}
          title="AI 服务连接"
          description="供岗位分析与招呼语生成使用，支持 OpenAI 兼容接口"
        >
          <div className="space-y-5">
            <Field id="baseUrl" label="API 地址">
              <Input
                id="baseUrl"
                type="url"
                value={config.baseUrl}
                placeholder="https://api.example.com"
                autoComplete="url"
                onChange={(e) => update("baseUrl", e.target.value)}
              />
            </Field>

            <Field id="model" label="模型名称" hint="填写服务商支持的模型 ID">
              <Input
                id="model"
                value={config.model}
                placeholder="例如：gpt-4o-mini"
                autoComplete="off"
                onChange={(e) => update("model", e.target.value)}
              />
            </Field>

            <Field
              id="apiKey"
              label="API 密钥"
              hint="密钥保存到后端配置数据库，仅用于服务请求；请勿分享或提交到版本控制。"
            >
              <div className="relative">
                <Input
                  id="apiKey"
                  type={showApiKey ? "text" : "password"}
                  value={config.apiKey}
                  placeholder="填写 API Key"
                  autoComplete="off"
                  spellCheck={false}
                  className="pr-16"
                  onChange={(e) => update("apiKey", e.target.value)}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={showApiKey ? "隐藏 API 密钥" : "显示 API 密钥"}
                  aria-pressed={showApiKey}
                  onClick={() => setShowApiKey((current) => !current)}
                  className="absolute right-1 top-1/2 h-8 -translate-y-1/2 rounded-md text-xs"
                >
                  {showApiKey ? "隐藏" : "显示"}
                </Button>
              </div>
            </Field>
          </div>
        </SectionCard>

        <SectionCard
          className="self-start lg:col-span-2"
          icon={<BiBell className="brand-text" />}
          title="企业微信通知"
          description="通过群机器人接收投递任务的运行消息"
        >
          <div className="space-y-5">
            <div className="flex items-center justify-between gap-4">
              <div className="space-y-1">
                <Label htmlFor="notification-enabled">任务消息推送</Label>
                <p className="text-xs text-slate-500">
                  {config.botIsSend ? "保存后启用通知" : "已关闭通知，保留 Webhook 地址"}
                </p>
              </div>
              <button
                id="notification-enabled"
                type="button"
                role="switch"
                aria-checked={Boolean(config.botIsSend)}
                onClick={() => update("botIsSend", config.botIsSend ? 0 : 1)}
                className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 ${config.botIsSend ? "bg-[hsl(var(--brand))]" : "bg-slate-300 dark:bg-slate-700"}`}
              >
                <span
                  className={`h-4 w-4 rounded-full bg-white shadow-sm transition-transform ${config.botIsSend ? "translate-x-6" : "translate-x-1"}`}
                />
              </button>
            </div>

            <Field
              id="hookUrl"
              label="Webhook URL"
              hint="从企业微信群「群机器人」设置中获取；仅在启用通知时使用。"
            >
              <Input
                id="hookUrl"
                type="url"
                value={config.hookUrl}
                placeholder="https://qyapi.weixin.qq.com/..."
                autoComplete="off"
                spellCheck={false}
                onChange={(e) => update("hookUrl", e.target.value)}
              />
            </Field>
          </div>
        </SectionCard>
      </fieldset>
    </div>
  )
}