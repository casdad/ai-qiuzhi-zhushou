"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { BiBrain, BiChevronDown, BiRefresh, BiSave, BiUser } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { Field } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { SectionCard } from "@/components/ui/section-card"
import { Textarea } from "@/components/ui/textarea"
import PageHeader from "@/components/layout/PageHeader"
import { API_BASE } from "@/lib/api"
import { cn } from "@/lib/utils"

type ProfileDraft = {
  summary: string
  education: string
  targetRoles: string
  skills: string
  projects: string
  highlights: string
}

type PromptDraft = { template: string; tone: string; maxChars: string; rules: string }

const EMPTY_PROFILE: ProfileDraft = {
  summary: "",
  education: "",
  targetRoles: "",
  skills: "",
  projects: "",
  highlights: "",
}

/**
 * 提示词模板占位顺序：个人画像、关键词、岗位名称、岗位描述、默认招呼语。
 * 后端按固定 %s 顺序替换，数量必须保持 5 个。
 */
const REQUIRED_PLACEHOLDERS = 5

const DEFAULT_PROMPT: PromptDraft = {
  template:
    "请根据个人画像和岗位信息生成一段简洁、真诚的求职打招呼语。个人画像：%s；搜索关键词：%s；岗位名称：%s；岗位描述：%s；默认招呼语：%s。",
  tone: "专业、真诚、简洁",
  maxChars: "60",
  rules:
    "仅使用个人画像中的真实经历；突出与岗位最匹配的技术或项目；不虚构学历、公司、论文、指标和工作经历；只输出最终打招呼语。",
}

const PROFILE_FIELDS: Array<[keyof ProfileDraft, string]> = [
  ["summary", "基本概况："],
  ["education", "教育背景："],
  ["targetRoles", "目标方向："],
  ["skills", "技术栈："],
  ["projects", "代表项目："],
  ["highlights", "能力亮点："],
]

const TONE_SUGGESTIONS = ["专业、真诚、简洁", "自然、友好、技术导向", "正式、稳重、结果导向"]

function serializeProfile(profile: ProfileDraft): string {
  return PROFILE_FIELDS.filter(([key]) => profile[key].trim())
    .map(([key, label]) => `${label}${profile[key].trim()}`)
    .join("\n")
}

/** 兼容三种历史格式：JSON 对象、"标签：内容"结构化文本、整段自由文本。 */
function parseProfile(raw: string): ProfileDraft {
  if (!raw.trim()) return { ...EMPTY_PROFILE }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      const values = parsed as Record<string, unknown>
      if (PROFILE_FIELDS.some(([key]) => typeof values[key] === "string")) {
        return Object.fromEntries(
          PROFILE_FIELDS.map(([key]) => [key, typeof values[key] === "string" ? values[key] : ""])
        ) as ProfileDraft
      }
    }
  } catch {
    // 旧版自由文本格式，继续往下解析
  }
  const profile = { ...EMPTY_PROFILE }
  let current: keyof ProfileDraft = "summary"
  let structured = false
  for (const line of raw.split("\n")) {
    const field = PROFILE_FIELDS.find(([, label]) => line.startsWith(label))
    if (field) {
      current = field[0]
      profile[current] = line.slice(field[1].length)
      structured = true
    } else {
      profile[current] += `${profile[current] ? "\n" : ""}${line}`
    }
  }
  return structured ? profile : { ...EMPTY_PROFILE, summary: raw }
}

function serializePrompt(prompt: PromptDraft): string {
  return [
    prompt.template.trim(),
    `风格：${prompt.tone.trim()}`,
    `字数上限：${prompt.maxChars}`,
    `规则：${prompt.rules.trim()}`,
  ].join("\n")
}

function parsePrompt(raw: string): PromptDraft {
  if (!raw.trim()) return { ...DEFAULT_PROMPT }
  const result = { ...DEFAULT_PROMPT, template: "", rules: "" }
  const labels: Array<[keyof PromptDraft, string]> = [
    ["tone", "风格："],
    ["maxChars", "字数上限："],
    ["rules", "规则："],
  ]
  let current: keyof PromptDraft = "template"
  let hasRules = false
  for (const line of raw.split("\n")) {
    const field = labels.find(([, label]) => line.startsWith(label))
    if (field) {
      current = field[0]
      result[current] = line.slice(field[1].length)
      if (current === "rules") hasRules = true
    } else {
      result[current] += `${result[current] ? "\n" : ""}${line}`
    }
  }
  if (!hasRules) result.rules = DEFAULT_PROMPT.rules
  return result
}

function countPlaceholders(value: string): number {
  return (value.match(/%s/g) || []).length
}

/**
 * 存量配置里的模板可能没有占位符（旧版默认值「请在此填写AI提示词模板」就是如此），
 * 这类模板无法通过保存校验，用户既看不出问题也存不进去，只能对着报错发呆。
 * 这里直接换成默认模板，让页面回到可保存的状态，并把原值留 dirty 以便点一次保存就修好数据库。
 */
function normalizeTemplate(template: string): { template: string; repaired: boolean } {
  if (countPlaceholders(template) === REQUIRED_PLACEHOLDERS) {
    return { template, repaired: false }
  }
  return { template: DEFAULT_PROMPT.template, repaired: true }
}

function validatePrompt(prompt: PromptDraft): string | null {
  if (countPlaceholders(prompt.template) !== REQUIRED_PLACEHOLDERS) {
    return `提示词模板需保留 ${REQUIRED_PLACEHOLDERS} 个 %s，占位顺序为：个人画像、关键词、岗位名称、岗位描述、默认招呼语。`
  }
  if (!prompt.tone.trim()) return "请填写生成语气。"
  const maxChars = Number(prompt.maxChars)
  if (!Number.isInteger(maxChars) || maxChars < 20 || maxChars > 200) {
    return "建议字数上限需为 20 至 200 之间的整数。"
  }
  if (countPlaceholders(serializePrompt(prompt)) !== REQUIRED_PLACEHOLDERS) {
    return "语气与生成规则中不能包含额外的 %s 占位符。"
  }
  return null
}

export default function AiConfigPage() {
  const [profile, setProfile] = useState<ProfileDraft>(EMPTY_PROFILE)
  const [prompt, setPrompt] = useState<PromptDraft>(DEFAULT_PROMPT)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [savedDraft, setSavedDraft] = useState<string | null>(null)
  const [loadError, setLoadError] = useState("")
  const [templateRepaired, setTemplateRepaired] = useState(false)
  const [feedback, setFeedback] = useState<{ success: boolean; message: string } | null>(null)

  const draft = JSON.stringify({ profile, prompt })
  const isDirty = savedDraft !== null && draft !== savedDraft
  const disabled = loading || saving || savedDraft === null
  const placeholderCount = countPlaceholders(prompt.template)

  const fetchConfig = useCallback(async (signal?: AbortSignal) => {
    try {
      const response = await fetch(`${API_BASE}/api/ai/config`, { signal, cache: "no-store" })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const result = await response.json()
      if (!result.success || (result.data != null && (typeof result.data !== "object" || Array.isArray(result.data)))) {
        throw new Error(result.message || "配置响应无效")
      }
      const storedProfile = parseProfile(String(result.data?.introduce ?? ""))
      const storedPrompt = parsePrompt(String(result.data?.prompt ?? ""))
      const repaired = normalizeTemplate(storedPrompt.template)
      // savedDraft 记录磁盘上的原值，prompt 放修复后的值：这样 isDirty 为真，
      // 保存按钮可用，用户点一次就把脏模板覆盖掉。
      setProfile(storedProfile)
      setPrompt({ ...storedPrompt, template: repaired.template })
      setSavedDraft(JSON.stringify({ profile: storedProfile, prompt: storedPrompt }))
      setTemplateRepaired(repaired.repaired)
      setLoadError("")
    } catch (error) {
      if (signal?.aborted) return
      console.error("加载 AI 配置失败:", error)
      setLoadError("未能读取已保存的 AI 配置。请检查后端连接后重试，当前禁止保存以免覆盖原配置。")
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void fetchConfig(controller.signal)
    return () => controller.abort()
  }, [fetchConfig])

  const updateProfile = (key: keyof ProfileDraft, value: string) => {
    setProfile((current) => ({ ...current, [key]: value }))
    setFeedback(null)
  }

  const updatePrompt = (key: keyof PromptDraft, value: string) => {
    // 用户一旦自己动过模板，就以他的输入为准，不再提示自动修复
    if (key === "template") setTemplateRepaired(false)
    setPrompt((current) => ({ ...current, [key]: value }))
    setFeedback(null)
  }

  const handleSave = async () => {
    if (disabled || !isDirty) return
    const validationError = validatePrompt(prompt)
    if (validationError) {
      setFeedback({ success: false, message: validationError })
      return
    }
    setSaving(true)
    setFeedback(null)
    try {
      const response = await fetch(`${API_BASE}/api/ai/config`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ introduce: serializeProfile(profile), prompt: serializePrompt(prompt) }),
      })
      const result = await response.json()
      if (!response.ok || !result.success) throw new Error(result.message || `HTTP ${response.status}`)
      setSavedDraft(draft)
      setFeedback({ success: true, message: "个人画像与生成规则已保存。" })
    } catch (error) {
      console.error("保存 AI 配置失败:", error)
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
        : templateRepaired
          ? {
              tone: "danger" as const,
              text: `已保存的提示词缺少 ${REQUIRED_PLACEHOLDERS} 个占位符，已临时改用默认模板。点击右上角「保存更改」即可写入。`,
            }
          : { tone: "muted" as const, text: isDirty ? "有未保存的更改" : "配置已同步" }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBrain />}
        title="AI 画像与规则"
        subtitle="让招呼语依据真实经历，匹配具体岗位。"
        actions={
          <Button variant="brand" onClick={handleSave} disabled={disabled || !isDirty}>
            <BiSave /> {saving ? "保存中…" : "保存更改"}
          </Button>
        }
      />

      <div className="flex min-h-7 flex-wrap items-center justify-between gap-3 text-sm" aria-live="polite">
        <p
          role={status.tone === "danger" ? "alert" : "status"}
          className={cn(
            status.tone === "danger" && "text-rose-600 dark:text-rose-400",
            status.tone === "success" && "text-emerald-700 dark:text-emerald-400",
            status.tone === "muted" && "text-slate-500 dark:text-slate-400"
          )}
        >
          {status.text}
        </p>
        {loadError && !loading && (
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
        )}
      </div>

      <fieldset disabled={disabled} className="min-w-0 space-y-6">
        <SectionCard
          icon={<BiUser className="brand-text" />}
          title="个人画像"
          description="仅填写真实信息；不适用的内容可留空，所有已填字段都会传入招呼语生成。"
        >
          <div className="grid grid-cols-1 gap-5 md:grid-cols-2">
            <Field
              id="profile-summary"
              label="当前身份与求职状态"
              hint="例如当前身份、毕业时间、到岗时间"
            >
              <Textarea
                id="profile-summary"
                value={profile.summary}
                onChange={(e) => updateProfile("summary", e.target.value)}
              />
            </Field>
            <Field id="profile-education" label="教育背景" hint="学校、专业、学位">
              <Textarea
                id="profile-education"
                value={profile.education}
                onChange={(e) => updateProfile("education", e.target.value)}
              />
            </Field>
            <Field id="profile-target" label="目标岗位" hint="期望从事的岗位方向">
              <Input
                id="profile-target"
                value={profile.targetRoles}
                placeholder="例如：计算机视觉算法工程师"
                onChange={(e) => updateProfile("targetRoles", e.target.value)}
              />
            </Field>
            <Field id="profile-skills" label="技术与技能" hint="能够实际使用的技能">
              <Input
                id="profile-skills"
                value={profile.skills}
                placeholder="例如：Python, PyTorch, OpenCV"
                onChange={(e) => updateProfile("skills", e.target.value)}
              />
            </Field>
            <Field
              id="profile-projects"
              className="md:col-span-2"
              label="项目与工作经历"
              hint="项目背景、你的职责、所用技术与实际成果"
            >
              <Textarea
                id="profile-projects"
                className="min-h-28"
                value={profile.projects}
                onChange={(e) => updateProfile("projects", e.target.value)}
              />
            </Field>
            <Field
              id="profile-highlights"
              className="md:col-span-2"
              label="补充优势"
              hint="尚未涵盖的获奖、证书或其他真实优势；避免重复上方经历"
            >
              <Textarea
                id="profile-highlights"
                value={profile.highlights}
                onChange={(e) => updateProfile("highlights", e.target.value)}
              />
            </Field>
          </div>
        </SectionCard>

        <SectionCard
          icon={<BiBrain className="brand-text" />}
          title="招呼语生成规则"
          description="语气、长度和约束会一并写入提示词，供投递流程中的 AI 生成使用。"
        >
          <div className="space-y-5">
            <div className="grid grid-cols-1 gap-5 md:grid-cols-2">
              <Field id="prompt-tone" label="表达语气" hint="可选择建议语气或直接输入">
                <Input
                  id="prompt-tone"
                  list="prompt-tones"
                  value={prompt.tone}
                  onChange={(e) => updatePrompt("tone", e.target.value)}
                />
                <datalist id="prompt-tones">
                  {TONE_SUGGESTIONS.map((tone) => (
                    <option key={tone} value={tone} />
                  ))}
                </datalist>
              </Field>
              <Field
                id="prompt-length"
                label="建议字数上限"
                hint="20–200 字。作为生成指令，不会强制截断回复。"
              >
                <Input
                  id="prompt-length"
                  type="number"
                  min={20}
                  max={200}
                  step={1}
                  value={prompt.maxChars}
                  onChange={(e) => updatePrompt("maxChars", e.target.value)}
                />
              </Field>
            </div>

            <Field
              id="prompt-rules"
              label="内容约束"
              hint="需要突出什么，以及不能编造的内容"
            >
              <Textarea
                id="prompt-rules"
                value={prompt.rules}
                onChange={(e) => updatePrompt("rules", e.target.value)}
              />
            </Field>

            {/* 模板多数用户不会改，收进折叠区避免一进来就是一整块 prompt 源码 */}
            <details className="group rounded-lg border border-slate-200 dark:border-slate-700">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-4 py-3 text-sm font-medium text-slate-700 dark:text-slate-200">
                自定义提示词模板
                <BiChevronDown className="h-5 w-5 shrink-0 text-slate-400 transition-transform group-open:rotate-180" />
              </summary>
              <div className="space-y-2 border-t border-slate-200 p-4 dark:border-slate-700">
                <Field
                  label="提示词模板"
                  hint={
                    <>
                      占位符 {placeholderCount}/{REQUIRED_PLACEHOLDERS}：按顺序对应个人画像、关键词、岗位名称、岗位描述、默认招呼语。保留 {REQUIRED_PLACEHOLDERS} 个 %s，普通百分号写为 %%。
                    </>
                  }
                >
                  <Textarea
                    className="min-h-36 font-mono text-xs"
                    value={prompt.template}
                    aria-invalid={placeholderCount !== REQUIRED_PLACEHOLDERS}
                    onChange={(e) => updatePrompt("template", e.target.value)}
                  />
                </Field>
              </div>
            </details>
          </div>
        </SectionCard>
      </fieldset>

      <p className="text-xs leading-5 text-slate-500">
        AI 启用状态与岗位画像在各平台的「平台配置」中管理；接口地址与密钥在{" "}
        <Link href="/env-config" className="text-[hsl(var(--brand))] underline-offset-4 hover:underline">
          服务与通知
        </Link>{" "}
        中配置。
      </p>
    </div>
  )
}