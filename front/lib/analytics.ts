/**
 * 投递分析页共用逻辑。
 *
 * boss / liepin / zhilian / 51job 四个分析页原本各自复制了一份筛选参数拼装、
 * 全量分页抓取、CSV 导出、日期格式化和分类配色。其中"筛选参数拼装"在每个文件里
 * 又重复了 3~4 次（列表、统计、导出、薪资兜底各一次），参数名只要有一处写错，
 * 导出的 CSV 和页面看到的数据就会不一致。这里收敛成唯一实现。
 *
 * 注意：各平台列表/统计接口都在 /api/<platform>/list|stats，
 * 参数名统一为 statuses / location / experience / degree / minK / maxK / keyword，
 * 与后端 Controller 保持一致，改动此处需同步后端。
 */

export type NameValue = { name: string; value: number }
export type BucketValue = { bucket: string; value: number }

export type StatsKpi = {
  total: number
  delivered: number
  pending: number
  filtered?: number
  failed?: number
  avgMonthlyK?: number | null
}

export type StatsResponse = {
  kpi: StatsKpi
  charts: {
    byStatus: NameValue[]
    byCity?: NameValue[]
    byIndustry?: NameValue[]
    byCompany?: NameValue[]
    byExperience?: NameValue[]
    byDegree?: NameValue[]
    salaryBuckets?: BucketValue[]
  }
}

/** 图表分类配色：柱状/饼图每个分类取一个颜色，按顺序循环。 */
export const CATEGORY_COLORS = [
  "#3b82f6",
  "#10b981",
  "#f59e0b",
  "#ef4444",
  "#6366f1",
  "#22c55e",
  "#fb7185",
  "#a78bfa",
  "#f97316",
  "#06b6d4",
  "#4ade80",
  "#2dd4bf",
  "#f472b6",
  "#64748b",
] as const

/** 分析页筛选项。字段名与后端查询参数一一对应，空值不参与请求。 */
export type AnalyticsFilters = {
  statuses?: string[]
  location?: string
  experience?: string
  degree?: string
  minK?: string | number
  maxK?: string | number
  keyword?: string
  /** 仅 Boss 支持：排除猎头发布的岗位。 */
  filterHeadhunter?: boolean
}

/** 把筛选项拼成后端查询参数（不含分页）。 */
export function buildFilterParams(filters: AnalyticsFilters): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.statuses?.length) params.set("statuses", filters.statuses.join(","))
  if (filters.location) params.set("location", filters.location)
  if (filters.experience) params.set("experience", filters.experience)
  if (filters.degree) params.set("degree", filters.degree)
  if (filters.minK !== undefined && filters.minK !== "") {
    params.set("minK", String(Number(filters.minK)))
  }
  if (filters.maxK !== undefined && filters.maxK !== "") {
    params.set("maxK", String(Number(filters.maxK)))
  }
  if (filters.keyword) params.set("keyword", filters.keyword)
  if (filters.filterHeadhunter) params.set("filterHeadhunter", "true")
  return params
}

type Paged<T> = { items?: T[]; total?: number; page?: number; size?: number }

/**
 * 按 size=1000 循环拉取列表全部页。
 *
 * 四个页面都写了同样的 while 循环；zhilian 那份的条件是 `currentPage * pageSize >= totalCount`，
 * 一旦后端 total 与实际条数不符就会多请求一轮空页。这里统一用"已取够 total 或本页为空"收敛。
 */
export async function fetchAllPages<T>(
  listUrl: string,
  base: URLSearchParams,
  pageSize = 1000
): Promise<T[]> {
  const all: T[] = []
  let currentPage = 1
  let total = Number.POSITIVE_INFINITY
  while (all.length < total) {
    const params = new URLSearchParams(base)
    params.set("page", String(currentPage))
    params.set("size", String(pageSize))
    const res = await fetch(`${listUrl}?${params.toString()}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data: Paged<T> = await res.json()
    const chunk = data.items ?? []
    if (currentPage === 1) total = data.total ?? chunk.length
    if (chunk.length === 0) break
    all.push(...chunk)
    currentPage += 1
  }
  return all
}

/**
 * 导出 CSV。
 *
 * 原来的实现里 zhilian 只做了引号转义却没有给含逗号的字段加引号，
 * 公司名带逗号时整行会错列；这里统一加引号，杜绝该问题。
 * 另加 BOM，避免 Excel 打开中文乱码。
 */
export function downloadCsv(
  filename: string,
  header: string[],
  rows: (string | number | null | undefined)[][]
): void {
  const escape = (value: string | number | null | undefined) =>
    String(value ?? "").replace(/"/g, '""')
  const lines = [header, ...rows].map((row) =>
    row.map((cell) => `"${escape(cell)}"`).join(",")
  )
  const blob = new Blob(["\uFEFF" + lines.join("\r\n")], {
    type: "text/csv;charset=utf-8;",
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** 仅保留 YYYY-MM-DD；非标准时间串退回前 10 位。 */
export function formatDateOnly(value?: string | null): string {
  if (!value) return ""
  const date = new Date(value)
  if (!Number.isNaN(date.getTime())) {
    const y = date.getFullYear()
    const m = String(date.getMonth() + 1).padStart(2, "0")
    const d = String(date.getDate()).padStart(2, "0")
    return `${y}-${m}-${d}`
  }
  return value.slice(0, 10)
}

const BADGE_BASE = "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap"
const BADGE_NEUTRAL = `${BADGE_BASE} bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300`
const BADGE_SUCCESS = `${BADGE_BASE} bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300`
const BADGE_WARN = `${BADGE_BASE} bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300`
const BADGE_DANGER = `${BADGE_BASE} bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300`

/** 投递状态徽章：四个平台口径一致。 */
export function deliveryBadgeClass(status?: string): string {
  const v = (status || "").trim()
  if (v.includes("已投递")) return BADGE_SUCCESS
  if (v.includes("失败")) return BADGE_DANGER
  if (v.includes("已过滤")) return BADGE_NEUTRAL
  if (v.includes("未投递") || v === "") return BADGE_WARN
  return BADGE_NEUTRAL
}

/** HR 活跃度徽章（仅 Boss 使用）。 */
export function hrActivityBadgeClass(status?: string): string {
  const v = (status || "").trim()
  if (/刚|在线|今日/.test(v)) return BADGE_SUCCESS
  if (/小时|近/.test(v)) return BADGE_WARN
  return BADGE_NEUTRAL
}

/** 招聘状态徽章（仅 Boss 使用）。 */
export function recruitmentBadgeClass(status?: string): string {
  const v = (status || "").trim()
  if (/暂停|关闭|下线|结束/.test(v)) return BADGE_NEUTRAL
  if (/急/.test(v)) return BADGE_DANGER
  if (/招|招聘|中/.test(v)) return `${BADGE_BASE} bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300`
  return BADGE_NEUTRAL
}

/** 薪资分桶定义，liepin/zhilian 在前端兜底计算时共用。 */
const SALARY_BUCKETS: ReadonlyArray<{ key: string; min: number; max: number | null }> = [
  { key: "0-10K", min: 0, max: 10 },
  { key: "10-15K", min: 10, max: 15 },
  { key: "15-20K", min: 15, max: 20 },
  { key: "20-25K", min: 20, max: 25 },
  { key: ">=25K", min: 25, max: null },
]

/** 把一组中位数(K) 落入薪资桶。 */
export function bucketize(medianKs: number[]): BucketValue[] {
  return SALARY_BUCKETS.map((bucket) => ({
    bucket: bucket.key,
    value: medianKs.filter((k) =>
      bucket.max == null ? k >= bucket.min : k >= bucket.min && k < bucket.max
    ).length,
  }))
}
