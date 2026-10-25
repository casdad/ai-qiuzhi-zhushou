"use client"

import { useCallback, useEffect, useState } from "react"
import { BiMoney, BiSearch } from "react-icons/bi"

import AnalysisContent from "@/app/51job/analysis/AnalysisContent"
import { PlatformShell } from "@/components/platform/PlatformShell"
import { usePlatformSession } from "@/components/platform/usePlatformSession"
import { Button } from "@/components/ui/button"
import { Field } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { MultiSelect } from "@/components/ui/multiselect"
import { SectionCard } from "@/components/ui/section-card"
import { Select } from "@/components/ui/select"
import { API_BASE } from "@/lib/api"

type Job51Config = {
  id?: number
  keywords?: string
  jobArea?: string
  salary?: string
}

type Option = { name: string; code: string }
type Options = { jobArea: Option[]; salary: Option[] }

/** 薪资筛选最多可选 5 个区间。 */
const MAX_SALARY_SELECTIONS = 5

/** 把库里的列表串（JSON 数组或逗号串）解析成 token 数组。 */
function parseTokens(raw?: string): string[] {
  if (!raw) return []
  const text = raw.trim()
  if (text.startsWith("[") && text.endsWith("]")) {
    try {
      const parsed = JSON.parse(text)
      if (Array.isArray(parsed)) return parsed.map((v) => String(v ?? "").trim()).filter(Boolean)
    } catch {
      // 非严格 JSON，继续按逗号拆分
    }
  }
  return text
    .replace(/，/g, ",")
    .split(",")
    .map((v) => v.trim())
    .filter(Boolean)
}

/** 关键词存库用 JSON 数组，展示用逗号分隔。 */
function parseKeywords(raw?: string): string {
  return parseTokens(raw).join(", ")
}

function serializeKeywords(display?: string): string {
  return JSON.stringify(parseTokens(display))
}

export default function Job51Page() {
  const session = usePlatformSession("51job")
  const [config, setConfig] = useState<Job51Config>({ keywords: "", jobArea: "", salary: "" })
  const [options, setOptions] = useState<Options>({ jobArea: [], salary: [] })
  const [selectedSalaries, setSelectedSalaries] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [backendAvailable, setBackendAvailable] = useState(false)
  const [loadError, setLoadError] = useState("")

  const [areaMode, setAreaMode] = useState<"list" | "custom">("list")

  const fetchAllData = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/api/51job/config`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      const opts: Options = data.options || { jobArea: [], salary: [] }
      const conf: Job51Config = data.config || {}

      // 地区存的是中文名列表，取第一个并映射回 code
      const rawArea = parseTokens(conf.jobArea)[0] ?? ""
      const areaCode =
        opts.jobArea.find((o) => o.code === rawArea || o.name === rawArea)?.code ??
        opts.jobArea.find((o) => o.name === "不限")?.code ??
        opts.jobArea.find((o) => o.code === "0")?.code ??
        ""

      const salaryCodes = parseTokens(conf.salary)
        .map((raw) => opts.salary.find((o) => o.code === raw || o.name === raw)?.code ?? raw)
        .filter(Boolean)
        .slice(0, MAX_SALARY_SELECTIONS)

      setOptions(opts)
      setConfig({
        ...conf,
        keywords: parseKeywords(conf.keywords),
        jobArea: areaCode,
      })
      setSelectedSalaries(salaryCodes)
      setAreaMode("list")
      setLoadError("")
      setBackendAvailable(true)
    } catch (error) {
      console.warn("[51job] 获取配置失败", error)
      setBackendAvailable(false)
      setLoadError("未能读取 51job 配置，请确认后端服务已启动。")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void fetchAllData()
  }, [fetchAllData])

  /** 地区保存为中文名列表：列表选择存 name，手动输入存原值。 */
  const serializeArea = (value: string): string => {
    const text = value.trim()
    if (!text) return "[]"
    const name = options.jobArea.find((o) => o.code === text || o.name === text)?.name ?? text
    return JSON.stringify([name])
  }

  const saveConfig = async (): Promise<boolean> => {
    if (!backendAvailable) {
      session.notify("danger", "后端服务未连接，无法保存配置。")
      return false
    }
    const salaryNames = selectedSalaries
      .map((code) => options.salary.find((o) => o.code === code)?.name)
      .filter((name): name is string => Boolean(name))

    try {
      const response = await fetch(`${API_BASE}/api/51job/config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...config,
          keywords: serializeKeywords(config.keywords),
          jobArea: serializeArea(config.jobArea ?? ""),
          salary: salaryNames.length ? JSON.stringify(salaryNames) : "[]",
        }),
      })
      if (!response.ok) {
        session.notify("danger", `保存失败：后端返回异常状态（HTTP ${response.status}）。`)
        return false
      }
      await fetchAllData()
      return true
    } catch (error) {
      console.error("[51job] 保存配置失败", error)
      session.notify("danger", "保存失败：无法连接后端服务。")
      return false
    }
  }

  return (
    <PlatformShell
      platform="51job"
      session={session}
      subtitle="配置搜索关键词、工作地区与薪资区间"
      onSave={saveConfig}
      analytics={<AnalysisContent />}
    >
      <SectionCard
        icon={<BiSearch className="brand-text" />}
        title="搜索配置"
        description="关键词按英文逗号分隔；地区与薪资用于收窄搜索结果。"
      >
        {loading ? (
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="skeleton h-16" />
            <div className="skeleton h-16" />
            <div className="skeleton h-16" />
          </div>
        ) : loadError ? (
          <div className="flex flex-col items-start gap-3 rounded-lg border border-dashed border-slate-300 p-6 text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400">
            <p>{loadError}</p>
            <Button size="sm" variant="outline" onClick={() => void fetchAllData()}>
              重新加载
            </Button>
          </div>
        ) : (
          <div className="grid gap-5 sm:grid-cols-2">
            <Field
              id="keywords"
              label="搜索关键词"
              hint="使用英文逗号分隔，例如：Java, 后端, Spring"
            >
              <Input
                id="keywords"
                value={config.keywords ?? ""}
                placeholder="如：Java, 后端, Spring"
                onChange={(e) => setConfig((c) => ({ ...c, keywords: e.target.value }))}
              />
            </Field>

            <div className="space-y-2">
              <div className="flex items-center justify-between gap-3">
                <Label>工作地区</Label>
                <button
                  type="button"
                  onClick={() => {
                    const next = areaMode === "list" ? "custom" : "list"
                    setAreaMode(next)
                    // 切到手动输入时清掉上一个 code，避免把 code 当城市名保存
                    if (next === "custom") setConfig((c) => ({ ...c, jobArea: "" }))
                  }}
                  className="text-xs text-[hsl(var(--brand))] hover:underline"
                >
                  {areaMode === "list" ? "手动输入" : "从列表选择"}
                </button>
              </div>
              {areaMode === "list" ? (
                <Select
                  value={config.jobArea ?? ""}
                  onChange={(e) => setConfig((c) => ({ ...c, jobArea: e.target.value }))}
                  aria-label="工作地区"
                >
                  <option value="">请选择地区</option>
                  {options.jobArea.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.name}
                    </option>
                  ))}
                </Select>
              ) : (
                <Input
                  value={config.jobArea ?? ""}
                  placeholder="请输入地区名称"
                  aria-label="工作地区"
                  onChange={(e) => setConfig((c) => ({ ...c, jobArea: e.target.value }))}
                />
              )}
              <p className="hint">
                {areaMode === "list"
                  ? "从列表选择地区"
                  : "手动输入地区名称，例如：北京"}
              </p>
            </div>

            <Field
              label={
                <span className="flex items-center gap-1.5">
                  <BiMoney className="text-slate-400" /> 薪资范围
                </span>
              }
              hint={`最多选择 ${MAX_SALARY_SELECTIONS} 个区间；不选表示不限。`}
            >
              <MultiSelect
                options={options.salary}
                selected={selectedSalaries}
                onChange={setSelectedSalaries}
                max={MAX_SALARY_SELECTIONS}
                placeholder="选择薪资范围"
                ariaLabel="薪资范围"
              />
            </Field>
          </div>
        )}
      </SectionCard>
    </PlatformShell>
  )
}
