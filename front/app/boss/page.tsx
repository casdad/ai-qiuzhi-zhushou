"use client"

import { useCallback, useEffect, useRef, useState } from "react"

import AnalysisContent from "@/app/boss/analysis/AnalysisContent"
import { BlacklistCard } from "@/app/boss/components/BlacklistCard"
import { DeliveryModeCard } from "@/app/boss/components/DeliveryModeCard"
import { DeliveryQueuePanel } from "@/app/boss/components/DeliveryQueuePanel"
import { ProfileCard } from "@/app/boss/components/ProfileCard"
import { RecommendationPanel } from "@/app/boss/components/RecommendationPanel"
import {
  buildProfileBody,
  isProfileEnabled,
  useJobProfiles,
  type JobProfile,
} from "@/app/boss/components/useJobProfiles"
import { PlatformShell } from "@/components/platform/PlatformShell"
import { usePlatformSession } from "@/components/platform/usePlatformSession"
import { API_BASE, requestJson } from "@/lib/api"
import { BOSS_DELIVERY_INTERVAL_SECONDS, BOSS_DELIVERY_INTERVAL_MAX_SECONDS, normalizeDeliveryMode, type DeliveryMode } from "@/lib/boss-delivery"

type BossOption = {
  id: number
  type: string
  name: string
  code: string
  sortOrder?: number
  sort_order?: number
}

type BossOptions = {
  city: BossOption[]
  industry: BossOption[]
  experience: BossOption[]
  jobType: BossOption[]
  salary: BossOption[]
  degree: BossOption[]
  scale: BossOption[]
  stage: BossOption[]
}

/** 字典项有 sortOrder 时按其稳定排序，否则保持后端原始顺序。 */
function sortOptions<T extends { sortOrder?: number; sort_order?: number }>(list: T[]): T[] {
  if (!list.some((o) => o.sortOrder != null || o.sort_order != null)) return list
  return [...list]
    .map((option, index) => ({ option, index }))
    .sort((a, b) => {
      const ao = a.option.sortOrder ?? a.option.sort_order
      const bo = b.option.sortOrder ?? b.option.sort_order
      if (ao == null && bo == null) return a.index - b.index
      if (ao == null) return 1
      if (bo == null) return -1
      if (ao !== bo) return ao - bo
      return a.index - b.index
    })
    .map(({ option }) => option)
}

const EMPTY_OPTIONS: BossOptions = {
  city: [], industry: [], experience: [], jobType: [], salary: [], degree: [], scale: [], stage: [],
}

export default function BossPage() {
  const session = usePlatformSession("boss")
  const [options, setOptions] = useState<BossOptions>(EMPTY_OPTIONS)
  const [blacklist, setBlacklist] = useState<{ id: number; value: string; type: string }[]>([])
  const [loading, setLoading] = useState(true)
  const [deliveryModeDraft, setDeliveryModeDraft] = useState<DeliveryMode>("CONFIRM")
  const [savedDeliveryMode, setSavedDeliveryMode] = useState<DeliveryMode | null>(null)
  const [configSaving, setConfigSaving] = useState(false)
  const [deliveryRefreshKey, setDeliveryRefreshKey] = useState(0)
  const modeInitialized = useRef(false)

  const {
    profiles,
    activeProfile,
    profileDraft,
    setProfileDraft,
    profileSaving,
    profileMessage,
    applyProfile,
    createProfile,
    deleteProfile,
    saveProfile,
  } = useJobProfiles()

  const jobIntentDirty = (profileDraft.jobIntent ?? "").trim() !== (activeProfile.jobIntent ?? "").trim()
  const deliveryDisabled = configSaving || profileSaving || savedDeliveryMode === null
    || deliveryModeDraft !== savedDeliveryMode || jobIntentDirty

  /**
   * 搜索条件收进画像；发送模式始终读取全局平台配置。
   * （后端 boss_config 仍会被写入一份镜像，仅供「没有启用画像」时回退使用。）
   */
  const fetchOptions = useCallback(async () => {
    try {
      const data = await requestJson<{
        config?: { deliveryMode?: string }
        options?: BossOptions
        blacklist?: { id: number; value: string; type: string }[]
      }>(`${API_BASE}/api/boss/config`)
      const mode = normalizeDeliveryMode(data.config?.deliveryMode)
      setSavedDeliveryMode(data.config?.deliveryMode === "AUTO" || data.config?.deliveryMode === "CONFIRM" ? mode : null)
      if (!modeInitialized.current) {
        setDeliveryModeDraft(mode)
        modeInitialized.current = true
      }

      const raw: BossOptions = data.options || EMPTY_OPTIONS
      const next: BossOptions = {
        city: sortOptions(raw.city || []),
        industry: sortOptions(raw.industry || []),
        experience: raw.experience || [],
        jobType: raw.jobType || [],
        salary: raw.salary || [],
        degree: raw.degree || [],
        scale: raw.scale || [],
        stage: raw.stage || [],
      }
      // 后端没返回「不限」时补一个，保证城市可以清空
      if (!next.city.some((c) => c.code === "0" || c.name === "不限")) {
        next.city = [{ id: -1, type: "city", name: "不限", code: "0", sortOrder: 0 }, ...next.city]
      }
      setOptions(next)

      if (Array.isArray(data.blacklist)) {
        setBlacklist(
          data.blacklist.map((item: { id: number; value: string; type: string }) => ({
            ...item,
            // 旧数据 type='boss' 归一化为 'company'
            type: item.type === "boss" ? "company" : item.type,
          }))
        )
      }
    } catch (error) {
      console.error("[Boss] 获取字典失败", error)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void fetchOptions()
  }, [fetchOptions])

  /**
   * 唯一的保存入口。
   *
   * 同时写两处，缺一不可：
   *   1. 画像 —— 有启用画像时后端只读它
   *   2. boss_config 镜像 —— 没有启用画像时后端回退读它
   * 少写任何一处，用户都会遇到「保存了但没生效」。
   */
  const saveConfig = async (): Promise<boolean> => {
    setConfigSaving(true)
    try {
      const saved = await saveProfile()
      if (!saved) {
        session.notify("danger", "画像保存失败，请检查后端服务后重试。")
        return false
      }
      const body = buildProfileBody(saved)
      const result = await requestJson<{ deliveryMode?: string; jobIntent?: string; waitTime?: number; waitTimeMax?: number }>(`${API_BASE}/api/boss/config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          keywords: body.keywords ?? "",
          jobIntent: body.jobIntent ?? "",
          waitTime: BOSS_DELIVERY_INTERVAL_SECONDS,
          waitTimeMax: BOSS_DELIVERY_INTERVAL_MAX_SECONDS,
          cityCode: body.cityCode ?? "",
          jobType: body.jobType ?? "",
          industry: body.industry ?? "",
          experience: body.experience ?? "",
          degree: body.degree ?? "",
          scale: body.scale ?? "",
          stage: body.stage ?? "",
          salary: body.salary ?? "",
          expectedSalaryMin: body.expectedSalaryMin ?? null,
          expectedSalaryMax: body.expectedSalaryMax ?? null,
          expectedSalaryUnit: body.salaryUnit || "month",
          sayHi: body.sayHi ?? "",
          enableAi: body.enableAi ?? 0,
          filterDeadHr: body.filterDeadHr ?? 0,
          enableAiRelevance: body.enableAiRelevance ?? 0,
          minAiScore: body.minAiScore ?? 75,
          deliveryMode: deliveryModeDraft,
        }),
      })
      if (result.deliveryMode !== deliveryModeDraft) {
        session.notify("danger", "平台未确认保存发送模式，请检查后端版本并重新读取配置。")
        setSavedDeliveryMode(null)
        return false
      }
      if (result.jobIntent !== body.jobIntent || result.waitTime !== BOSS_DELIVERY_INTERVAL_SECONDS
        || result.waitTimeMax !== BOSS_DELIVERY_INTERVAL_MAX_SECONDS) {
        session.notify("danger", "岗位描述或投递间隔未确认保存，请重启更新后的后端并重新保存配置。")
        setSavedDeliveryMode(null)
        return false
      }
      setSavedDeliveryMode(deliveryModeDraft)
      return true
    } catch (error) {
      console.error("[Boss] 同步平台配置失败", error)
      session.notify("danger", `平台配置未确认保存：${error instanceof Error ? error.message : "无法连接后端服务。"}`)
      return false
    } finally {
      setConfigSaving(false)
    }
  }

  const updateProfile = (patch: Partial<JobProfile>) => {
    setProfileDraft((draft) => ({ ...draft, ...patch }))
  }

  const addBlacklist = async (value: string, type: string) => {
    try {
      const response = await fetch(`${API_BASE}/api/boss/config/blacklist`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ value, type }),
      })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      await fetchOptions()
    } catch (error) {
      console.error("[Boss] 添加黑名单失败", error)
      session.notify("danger", "添加黑名单失败，请检查后端服务。")
    }
  }

  const deleteBlacklist = async (id: number) => {
    try {
      const response = await fetch(`${API_BASE}/api/boss/config/blacklist/${id}`, { method: "DELETE" })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      await fetchOptions()
    } catch (error) {
      console.error("[Boss] 删除黑名单失败", error)
      session.notify("danger", "删除黑名单失败，请检查后端服务。")
    }
  }

  return (
    <PlatformShell
      platform="boss"
      session={session}
      subtitle="配置搜索条件、岗位画像与投递过滤规则"
      onSave={saveConfig}
      startLabel={savedDeliveryMode === "AUTO" ? "开始自动投递" : "生成待确认草稿"}
      startDisabled={deliveryDisabled}
      startDisabledReason="请先成功读取并保存发送模式及意向岗位描述。"
      analytics={<AnalysisContent />}
    >
      {loading ? (
        <div className="space-y-5">
          <div className="skeleton h-24" />
          <div className="skeleton h-96" />
        </div>
      ) : (
        <>
          <DeliveryModeCard
            value={deliveryModeDraft}
            savedMode={savedDeliveryMode}
            saving={configSaving}
            onChange={setDeliveryModeDraft}
          />
          <RecommendationPanel
            key={activeProfile.id ?? activeProfile.name}
            jobIntent={profileDraft.jobIntent ?? ""}
            profileId={profileDraft.id}
            aiRelevanceEnabled={profileDraft.enableAiRelevance === 1}
            onJobIntentChange={(jobIntent) => updateProfile({ jobIntent })}
            deliveryMode={savedDeliveryMode}
            deliveryDisabled={deliveryDisabled}
            onDeliveryChanged={() => setDeliveryRefreshKey((value) => value + 1)}
          />
          <ProfileCard
            profiles={profiles}
            activeId={activeProfile.id}
            draft={profileDraft}
            options={options}
            saving={profileSaving}
            message={profileMessage}
            onChange={updateProfile}
            onSelect={applyProfile}
            onCreate={createProfile}
            onDelete={() => void deleteProfile()}
          />

          {!isProfileEnabled(profileDraft) && (
            <p className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-300">
              当前画像未启用，投递时不会读取上面的设置，而是回退到最近一次保存的平台配置。
              勾选「启用该画像」后才会生效。
            </p>
          )}

          <DeliveryQueuePanel refreshKey={deliveryRefreshKey} />

          <BlacklistCard items={blacklist} onAdd={addBlacklist} onDelete={deleteBlacklist} />
        </>
      )}
    </PlatformShell>
  )
}
