"use client"

import { useCallback, useEffect, useState } from "react"
import { BiMap, BiMoney, BiSearch } from "react-icons/bi"

import AnalysisContent from "@/app/liepin/analysis/AnalysisContent"
import { PlatformShell } from "@/components/platform/PlatformShell"
import { usePlatformSession } from "@/components/platform/usePlatformSession"
import { Button } from "@/components/ui/button"
import { Field } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SectionCard } from "@/components/ui/section-card"
import { Select } from "@/components/ui/select"
import { API_BASE } from "@/lib/api"

type LiepinConfig = {
  id?: number
  keywords?: string
  city?: string
  salaryCode?: string
}

type CityOption = { id: number; type: string; name: string; code: string }

/**
 * 关键词在库里是 JSON 数组，界面上是逗号分隔文本。
 * 四个平台各写了一份相同的解析/序列化，这里保持行为不变但只写一次。
 */
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

export default function LiepinPage() {
  const session = usePlatformSession("liepin")
  const [config, setConfig] = useState<LiepinConfig>({ keywords: "", city: "", salaryCode: "" })
  const [cities, setCities] = useState<CityOption[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState("")
  const [customCity, setCustomCity] = useState(false)

  const fetchAllData = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/api/liepin/config`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      const nextCities: CityOption[] = data.options?.city ?? []
      setCities(nextCities)
      if (data.config) {
        const next = { ...data.config, keywords: parseKeywordsFromDb(data.config.keywords) }
        setConfig(next)
        // 库里存的城市名若不在字典里，就切到手动输入模式，否则下拉会显示空白
        const known = nextCities.some((c) => c.name === next.city || c.code === next.city)
        setCustomCity(Boolean(next.city) && !known)
      }
      setLoadError("")
    } catch (error) {
      console.error("[猎聘] 获取配置失败", error)
      setLoadError("未能读取猎聘配置，请确认后端服务已启动。")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void fetchAllData()
  }, [fetchAllData])

  const saveConfig = async (): Promise<boolean> => {
    try {
      const response = await fetch(`${API_BASE}/api/liepin/config`, {
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
      console.error("[猎聘] 保存配置失败", error)
      session.notify("danger", "保存失败：无法连接后端服务。")
      return false
    }
  }

  return (
    <PlatformShell
      platform="liepin"
      session={session}
      subtitle="配置搜索关键词、目标城市与薪资筛选码"
      onSave={saveConfig}
      analytics={<AnalysisContent />}
    >
      <SectionCard
        icon={<BiSearch className="brand-text" />}
        title="搜索配置"
        description="关键词按英文逗号分隔；城市可下拉选择或手动输入城市码。"
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
                placeholder="例如：大模型, Python, Golang"
                onChange={(e) => setConfig((c) => ({ ...c, keywords: e.target.value }))}
              />
            </Field>

            <div className="space-y-2">
              <div className="flex items-center justify-between gap-3">
                <Label htmlFor="city">
                  <span className="flex items-center gap-1.5">
                    <BiMap className="text-slate-400" />
                    工作城市
                  </span>
                </Label>
                <button
                  type="button"
                  onClick={() => {
                    const next = !customCity
                    setCustomCity(next)
                    if (next) setConfig((c) => ({ ...c, city: "" }))
                  }}
                  className="text-xs text-[hsl(var(--brand))] hover:underline"
                >
                  {customCity ? "从列表选择" : "手动输入"}
                </button>
              </div>
              {customCity ? (
                <Input
                  id="city"
                  value={config.city ?? ""}
                  placeholder="请输入城市码，例如：410"
                  onChange={(e) => setConfig((c) => ({ ...c, city: e.target.value }))}
                />
              ) : (
                <Select
                  id="city"
                  value={config.city ?? ""}
                  onChange={(e) => setConfig((c) => ({ ...c, city: e.target.value }))}
                >
                  <option value="">请选择城市</option>
                  {cities.map((city) => (
                    <option key={city.id} value={city.name}>
                      {city.name}
                    </option>
                  ))}
                </Select>
              )}
              <p className="hint">
                {customCity ? "手动输入城市码（例如 410 代表北京）" : "从列表选择城市，或切换为手动输入"}
              </p>
            </div>
          </div>
        )}
      </SectionCard>

      <SectionCard
        icon={<BiMoney className="brand-text" />}
        title="薪资筛选"
        description="猎聘使用「最低$最高」形式的薪资码，例如 15$30 表示 15k–30k。"
      >
        <Field id="salaryCode" label="薪资范围码" className="max-w-sm">
          <Input
            id="salaryCode"
            value={config.salaryCode ?? ""}
            placeholder="例如：15$30"
            onChange={(e) => setConfig((c) => ({ ...c, salaryCode: e.target.value }))}
          />
        </Field>
      </SectionCard>
    </PlatformShell>
  )
}
