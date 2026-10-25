"use client"

import { useCallback, useEffect, useState } from "react"

import { API_BASE, requestJson } from "@/lib/api"

export interface JobProfile {
  id?: number
  name: string
  type?: string
  keywords?: string
  jobIntent?: string
  aiIntroduce?: string
  aiPrompt?: string
  cityCode?: string
  jobType?: string
  industry?: string[]
  experience?: string[]
  degree?: string[]
  scale?: string[]
  stage?: string[]
  /** Boss 搜索页的薪资筛选字典（拼进搜索 URL，由 Boss 服务端过滤）。 */
  salary?: string[]
  /** 投递前的本地薪资过滤区间（不符合则标记已过滤、跳过投递）。 */
  expectedSalaryMin?: number | null
  expectedSalaryMax?: number | null
  salaryUnit?: string
  sayHi?: string
  enableAi?: number
  filterDeadHr?: number
  enableAiRelevance?: number
  minAiScore?: number
  enabled?: boolean | number
}

/** 多选维度，与 MultiSelect 的多选字段一一对应。 */
export const MULTI_FIELDS = ["salary", "industry", "experience", "degree", "scale", "stage"] as const
export type MultiField = (typeof MULTI_FIELDS)[number]

/** 后端把多选值存成「[a,b]」；前端统一按数组操作。 */
export function parseList(raw?: string | null): string[] {
  if (!raw) return []
  let text = String(raw).trim()
  if (text.startsWith("[") && text.endsWith("]")) text = text.slice(1, -1)
  if (!text) return []
  return text
    .split(",")
    .map((v) => v.trim().replace(/^"|"$/g, ""))
    .filter(Boolean)
}

export function toBracketList(list?: string[]): string {
  if (!list || list.length === 0) return ""
  return `[${list.join(",")}]`
}

/**
 * 把后端返回的画像行整理成前端形态：多选字段拆成数组，关键词转成可编辑文本。
 * 城市/职位类型存的是中文名，下拉回显由调用方用字典再映射一次 code。
 */
export function normalizeProfile(raw: Record<string, unknown>): JobProfile {
  const keywords = String(raw.keywords ?? "")
  return {
    id: raw.id as number | undefined,
    name: String(raw.name ?? "未命名画像"),
    type: String(raw.type ?? "自定义"),
    jobIntent: String(raw.jobIntent ?? ""),
    aiIntroduce: String(raw.aiIntroduce ?? ""),
    aiPrompt: String(raw.aiPrompt ?? ""),
    keywords: keywords
      .split(",")
      .map((v) => v.trim())
      .filter(Boolean)
      .join(", "),
    cityCode: String(raw.cityCode ?? ""),
    jobType: String(raw.jobType ?? ""),
    industry: parseList(raw.industry as string),
    experience: parseList(raw.experience as string),
    degree: parseList(raw.degree as string),
    scale: parseList(raw.scale as string),
    stage: parseList(raw.stage as string),
    salary: parseList(raw.salary as string),
    expectedSalaryMin: (raw.expectedSalaryMin as number | null) ?? null,
    expectedSalaryMax: (raw.expectedSalaryMax as number | null) ?? null,
    salaryUnit: normalizeUnit(raw.salaryUnit as string | undefined),
    sayHi: (raw.sayHi as string) ?? "",
    enableAi: raw.enableAi as number | undefined,
    filterDeadHr: raw.filterDeadHr as number | undefined,
    enableAiRelevance: raw.enableAiRelevance as number | undefined,
    minAiScore: raw.minAiScore as number | undefined,
    enabled: raw.enabled as boolean | number | undefined,
  }
}

/** 首次使用时的本地草稿：不会自动写入后端，用户点「保存画像」才落库。 */
export const DEFAULT_PROFILES: JobProfile[] = [
  {
    name: "计算机视觉实习",
    type: "实习",
    expectedSalaryMin: 200,
    expectedSalaryMax: 500,
    salaryUnit: "day",
    enabled: true,
    minAiScore: 75,
  },
  {
    name: "计算机视觉秋招",
    type: "秋招",
    expectedSalaryMin: 18000,
    expectedSalaryMax: 30000,
    salaryUnit: "month",
    enabled: false,
    minAiScore: 75,
  },
]

export function normalizeUnit(unit?: string): string {
  const value = (unit || "").toLowerCase().replace(/\s/g, "")
  if (value.includes("天") || value.includes("day")) return "day"
  if (value.includes("月") || value.includes("month")) return "month"
  return ""
}

export function isProfileEnabled(profile?: JobProfile): boolean {
  return profile?.enabled !== false && profile?.enabled !== 0
}

/**
 * 岗位画像的读写与切换。
 *
 * 后端 loadBossConfig() 里「有启用画像时优先使用画像；没有画像时才回退 boss_config」，
 * 也就是说画像才是投递时真正生效的那份配置。所以这里让画像持有全部搜索条件
 * （关键词、城市、行业、薪资……），页面上不再另设一份平台级配置，避免两边填不一致。
 *
 * 原先这段逻辑内联在 page.tsx 里约 140 行，和字典加载、多选状态交织在一起，
 * 很难单独看出「选中画像会做什么」。现在拆成 hook：
 *   - 画像列表、当前草稿、保存/新建/删除
 *   - 切换画像时标记后端激活项
 * 后端未提供 /api/boss/profiles 时回退到 DEFAULT_PROFILES。
 */
export function useJobProfiles() {
  const [profiles, setProfiles] = useState<JobProfile[]>([])
  const [activeProfile, setActiveProfile] = useState<JobProfile>({ ...DEFAULT_PROFILES[0], enabled: true })
  const [profileDraft, setProfileDraft] = useState<JobProfile>({
    ...DEFAULT_PROFILES[0],
    enableAiRelevance: 0,
    minAiScore: 75,
    enabled: true,
  })
  const [profileSaving, setProfileSaving] = useState(false)
  const [profileMessage, setProfileMessage] = useState("")

  const applyProfile = useCallback((profile: JobProfile) => {
    setActiveProfile(profile)
    setProfileDraft({ ...profile, enabled: isProfileEnabled(profile) })
    if (profile.id && isProfileEnabled(profile)) {
      // 标记后端当前激活画像
      void requestJson(`${API_BASE}/api/boss/profiles/${profile.id}/activate`, { method: "POST" }).catch(
        (error) => console.info("激活岗位画像失败", error)
      )
    }
  }, [])

  const fetchProfiles = useCallback(async () => {
    const fallbackToDefaults = (reason?: unknown) => {
      if (reason) console.info("岗位画像接口不可用，继续使用本地草稿画像", reason)
      setProfiles(DEFAULT_PROFILES)
      setActiveProfile(DEFAULT_PROFILES[0])
      setProfileDraft({ ...DEFAULT_PROFILES[0], enabled: true })
    }
    try {
      const payload = await requestJson<Record<string, unknown>[] | {
        data?: Record<string, unknown>[]; profiles?: Record<string, unknown>[]
      }>(`${API_BASE}/api/boss/profiles`)
      const raw: unknown[] = Array.isArray(payload)
        ? payload
        : payload?.data || payload?.profiles || []
      const rows = (raw as Record<string, unknown>[]).map(normalizeProfile)
      if (!rows.length) {
        fallbackToDefaults()
        return
      }
      setProfiles(rows)
      const selected = rows.find((item) => isProfileEnabled(item)) ?? rows[0]
      setActiveProfile(selected)
      setProfileDraft({ ...selected, enabled: isProfileEnabled(selected) })
    } catch (error) {
      fallbackToDefaults(error)
    }
  }, [])

  useEffect(() => {
    void fetchProfiles()
  }, [fetchProfiles])

  /**
   * 保存画像（新建或更新），返回保存后的画像；失败返回 null。
   * 由页面唯一的「保存配置」按钮调用，避免页面上出现两个职责重叠的保存按钮。
   */
  const saveProfile = useCallback(async (): Promise<JobProfile | null> => {
    if (!profileDraft.name.trim()) {
      setProfileMessage("请填写画像名称")
      return null
    }
    setProfileSaving(true)
    setProfileMessage("")
    try {
      const body = buildProfileBody(profileDraft)
      const payload = await requestJson(
        profileDraft.id
          ? `${API_BASE}/api/boss/profiles/${profileDraft.id}?replace=true`
          : `${API_BASE}/api/boss/profiles`,
        {
          method: profileDraft.id ? "PUT" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      )
      const savedRaw = (payload.data || payload.profile || payload) as Record<string, unknown>
      const saved = normalizeProfile({ ...body, ...savedRaw })
      const normalized: JobProfile = { ...saved, enabled: isProfileEnabled(saved) }
      setProfiles((current) => {
        const index = current.findIndex((item) => item.id === normalized.id)
        if (index < 0) return [...current, normalized]
        return current.map((item, i) => (i === index ? normalized : item))
      })
      setActiveProfile(normalized)
      setProfileDraft(normalized)
      return normalized
    } catch (error) {
      console.error("保存岗位画像失败", error)
      setProfileMessage(error instanceof Error ? error.message : "画像保存失败，请检查后端服务")
      return null
    } finally {
      setProfileSaving(false)
    }
  }, [profileDraft])

  const createProfile = useCallback(() => {
    setActiveProfile({ name: "新画像", type: "自定义" })
    setProfileDraft({
      name: "新画像",
      type: "自定义",
      keywords: "",
      jobIntent: "",
      cityCode: "",
      jobType: "",
      industry: [],
      experience: [],
      degree: [],
      scale: [],
      stage: [],
      salary: [],
      expectedSalaryMin: null,
      expectedSalaryMax: null,
      salaryUnit: "month",
      enabled: true,
    })
    setProfileMessage("已创建草稿，填写后点击右上角「保存配置」")
  }, [])

  const deleteProfile = useCallback(async () => {
    if (!profileDraft.id) return
    if (!window.confirm(`确定删除画像“${profileDraft.name}”吗？`)) return
    try {
      const response = await fetch(`${API_BASE}/api/boss/profiles/${profileDraft.id}`, { method: "DELETE" })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const remain = profiles.filter((item) => item.id !== profileDraft.id)
      setProfiles(remain)
      if (remain[0]) {
        applyProfile(remain[0])
      } else {
        createProfile()
      }
      setProfileMessage("岗位画像已删除")
    } catch (error) {
      console.error("删除岗位画像失败", error)
      setProfileMessage("删除失败，请检查后端服务")
    }
  }, [profileDraft, profiles, applyProfile, createProfile])

  return {
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
  }
}

/**
 * 提交给后端的画像形态：多选维度在传输时是「[a,b]」字符串，内存里才是数组。
 * 单独建模，避免把两种形态混在一个类型里。
 */
export type ProfilePayload = Omit<JobProfile, MultiField> & Record<MultiField, string>

/** 组装提交给后端的画像字段：多选数组转成括号列表，其余原样带上。 */
export function buildProfileBody(draft: JobProfile): ProfilePayload {
  return {
    ...draft,
    name: draft.name.trim(),
    type: draft.type || "自定义",
    jobIntent: (draft.jobIntent ?? "").trim(),
    salary: toBracketList(draft.salary),
    industry: toBracketList(draft.industry),
    experience: toBracketList(draft.experience),
    degree: toBracketList(draft.degree),
    scale: toBracketList(draft.scale),
    stage: toBracketList(draft.stage),
    expectedSalaryMin: draft.expectedSalaryMin ?? null,
    expectedSalaryMax: draft.expectedSalaryMax ?? null,
    enabled: isProfileEnabled(draft) ? 1 : 0,
  }
}
