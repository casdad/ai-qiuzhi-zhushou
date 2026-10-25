"use client"

import { useCallback, useEffect, useState } from "react"
import { BiMap, BiMoney, BiSearch } from "react-icons/bi"

import AnalysisContent from "@/app/zhilian/analysis/AnalysisContent"
import { PlatformShell } from "@/components/platform/PlatformShell"
import { usePlatformSession } from "@/components/platform/usePlatformSession"
import { Button } from "@/components/ui/button"
import { Field } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { SectionCard } from "@/components/ui/section-card"
import { Select } from "@/components/ui/select"
import { API_BASE } from "@/lib/api"

type ZhilianConfig = {
  id?: number
  keywords?: string
  cityCode?: string
  salary?: string
}

type Option = { name: string; code: string }

function parseKeywordsFromDb(raw?: string): string {
  if (!raw) return ""
  const text = raw.trim()
  if (text.startsWith("[") && text.endsWith("]")) {
    try {
      const parsed = JSON.parse(text)
      if (Array.isArray(parsed)) return parsed.filter(Boolean).join(", ")
    } catch {
      // 非严格 JSON，落回逗号拆分
    }
  }
  return text.replace(/，/g, ",")
}

function serializeKeywordsForDb(display?: string): string {
  const tokens = (display || "")
    .trim()
    .replace(/，/g, ",")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
  return JSON.stringify(tokens)
}

export default function ZhilianPage() {
  const session = usePlatformSession("zhilian")
  const [config, setConfig] = useState<ZhilianConfig>({ keywords: "", cityCode: "", salary: "" })
  const [cities, setCities] = useState<Option[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState("")

  const fetchAllData = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/api/zhilian/config`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      if (data.config) {
        setConfig({ ...data.config, keywords: parseKeywordsFromDb(data.config.keywords) })
      }
      setCities(data.options?.city ?? [])
      setLoadError("")
    } catch (error) {
      console.error("[智联] 获取配置失败", error)
      setLoadError("未能读取智联招聘配置，请确认后端服务已启动。")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void fetchAllData()
  }, [fetchAllData])

  const saveConfig = async (): Promise<boolean> => {
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...config, keywords: serializeKeywordsForDb(config.keywords) }),
      })
      if (!response.ok) {
        session.notify("danger", `保存失败：后端返回异常状态（HTTP ${response.status}）。`)
        return false
      }
      await fetchAllData()
      return true
    } catch (error) {
      console.error("[智联] 保存配置失败", error)
      session.notify("danger", "保存失败：无法连接后端服务。")
      return false
    }
  }

  return (
    <PlatformShell
      platform="zhilian"
      session={session}
      subtitle="配置搜索关键词、目标城市与薪资区间"
      onSave={saveConfig}
      analytics={<AnalysisContent />}
    >
      <SectionCard
        icon={<BiSearch className="brand-text" />}
        title="搜索配置"
        description="关键词按英文逗号分隔，用于智联招聘站内搜索。"
      >
        {loading ? (
          <div className="grid gap-5 sm:grid-cols-2">
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
              hint="支持多个关键词，使用英文逗号分隔"
            >
              <Input
                id="keywords"
                value={config.keywords ?? ""}
                placeholder="如：Java, 后端, Spring"
                onChange={(e) => setConfig((c) => ({ ...c, keywords: e.target.value }))}
              />
            </Field>

            <Field
              label={
                <span className="flex items-center gap-1.5">
                  <BiMap className="text-slate-400" />
                  工作城市
                </span>
              }
              hint="不选表示全国不限"
            >
              <Select
                value={config.cityCode ?? ""}
                onChange={(e) => setConfig((c) => ({ ...c, cityCode: e.target.value }))}
                aria-label="工作城市"
              >
                <option value="">请选择城市</option>
                {cities.map((city) => (
                  <option key={city.code} value={city.code}>
                    {city.name}
                  </option>
                ))}
              </Select>
            </Field>

            <Field
              className="sm:col-span-2"
              label={
                <span className="flex items-center gap-1.5">
                  <BiMoney className="text-slate-400" />
                  薪资范围
                </span>
              }
              hint="填写「最低, 最高」，单位元/月，例如 12000, 20000；留空表示不限。"
            >
              <Input
                value={config.salary ?? ""}
                placeholder="如：12000, 20000"
                onChange={(e) => setConfig((c) => ({ ...c, salary: e.target.value }))}
              />
            </Field>
          </div>
        )}
      </SectionCard>
    </PlatformShell>
  )
}
