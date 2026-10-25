"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { BiBarChart, BiDownload, BiLineChart, BiPieChart, BiRefresh } from "react-icons/bi"

import { FilterCard, FilterToggle, StatusFilter } from "@/components/analysis/FilterBar"
import { LongTextDialog } from "@/components/analysis/LongTextDialog"
import {
  EmptyRow,
  JobTableCard,
  LoadingRow,
  Pagination,
  TableShell,
  Td,
  Th,
  Truncated,
} from "@/components/analysis/JobTable"
import { KpiGrid } from "@/components/analysis/KpiGrid"
import { ChartGrid, StatChart } from "@/components/analysis/StatChart"
import { Button } from "@/components/ui/button"
import { Field } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { API_BASE } from "@/lib/api"
import {
  CATEGORY_COLORS,
  buildFilterParams,
  deliveryBadgeClass,
  downloadCsv,
  fetchAllPages,
  formatDateOnly,
  hrActivityBadgeClass,
  recruitmentBadgeClass,
  type StatsResponse,
} from "@/lib/analytics"

type BossJob = {
  id: number
  companyName?: string
  jobName?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  hrName?: string
  hrPosition?: string
  hrActiveStatus?: string
  deliveryStatus?: string
  recruitmentStatus?: string
  jobUrl?: string
  companyAddress?: string
  industry?: string
  introduce?: string
  financingStage?: string
  companyScale?: string
  jobDescription?: string
  createdAt?: string
}

type Paged = { items?: BossJob[]; total?: number; page?: number; size?: number }

const LIST_URL = `${API_BASE}/api/boss/list`
const STATS_URL = `${API_BASE}/api/boss/stats`
const STATUS_OPTIONS = ["未投递", "已投递", "已过滤", "投递失败"]

const COLUMNS = [
  { key: "companyName", title: "公司名称", width: 160 },
  { key: "jobName", title: "岗位名称", width: 200 },
  { key: "salary", title: "薪资", width: 110 },
  { key: "location", title: "地点", width: 110 },
  { key: "experience", title: "经验", width: 100 },
  { key: "degree", title: "学历", width: 90 },
  { key: "hrName", title: "HR", width: 100 },
  { key: "hrPosition", title: "HR 职位", width: 120 },
  { key: "hrActiveStatus", title: "HR 活跃", width: 110 },
  { key: "deliveryStatus", title: "投递状态", width: 110 },
  { key: "recruitmentStatus", title: "招聘状态", width: 110 },
  { key: "jobUrl", title: "链接", width: 80 },
  { key: "companyAddress", title: "公司地址", width: 180 },
  { key: "industry", title: "行业", width: 130 },
  { key: "companyScale", title: "公司规模", width: 120 },
  { key: "financingStage", title: "融资阶段", width: 110 },
  { key: "introduce", title: "公司介绍", width: 200 },
  { key: "jobDescription", title: "岗位描述", width: 220 },
  { key: "createdAt", title: "入库时间", width: 120 },
] as const

/** 猎头岗位识别：后端未过滤时在前端兜底（列表与导出都走这里）。 */
function isHeadhunter(job: BossJob): boolean {
  const position = (job.hrPosition || "").toLowerCase()
  return position.includes("猎头") || position.includes("獵頭")
}

export default function AnalysisContent() {
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [loadingStats, setLoadingStats] = useState(true)
  const [items, setItems] = useState<BossJob[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [loadingList, setLoadingList] = useState(false)
  const [reloading, setReloading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [filterHeadhunter, setFilterHeadhunter] = useState(false)

  const [statuses, setStatuses] = useState<string[]>([])
  const [location, setLocation] = useState("")
  const [experience, setExperience] = useState("")
  const [degree, setDegree] = useState("")
  const [minK, setMinK] = useState("")
  const [maxK, setMaxK] = useState("")
  const [keyword, setKeyword] = useState("")

  const [expanded, setExpanded] = useState<{ title: string; content: string } | null>(null)

  const filters = useMemo(
    () => ({ statuses, location, experience, degree, minK, maxK, keyword, filterHeadhunter }),
    [statuses, location, experience, degree, minK, maxK, keyword, filterHeadhunter]
  )

  const loadStats = useCallback(async () => {
    setLoadingStats(true)
    try {
      const res = await fetch(`${STATS_URL}?${buildFilterParams(filters).toString()}`)
      setStats((await res.json()) as StatsResponse)
    } catch (error) {
      console.error("[Boss] 获取统计失败", error)
    } finally {
      setLoadingStats(false)
    }
  }, [filters])

  const loadList = useCallback(
    async (toPage = page, toSize = size) => {
      const params = buildFilterParams(filters)
      params.set("page", String(toPage))
      params.set("size", String(toSize))
      setLoadingList(true)
      try {
        const res = await fetch(`${LIST_URL}?${params.toString()}`)
        const data: Paged = await res.json()
        const rows = data.items ?? []
        setItems(filterHeadhunter ? rows.filter((job) => !isHeadhunter(job)) : rows)
        setTotal(data.total ?? 0)
        setPage(data.page ?? toPage)
        setSize(data.size ?? toSize)
      } catch (error) {
        console.error("[Boss] 获取列表失败", error)
      } finally {
        setLoadingList(false)
      }
    },
    [filters, page, size, filterHeadhunter]
  )

  useEffect(() => {
    void loadList(1, 20)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    void loadStats()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** 切换「过滤猎头岗位」立即生效，其余筛选走「应用筛选」。 */
  const toggleHeadhunter = (next: boolean) => {
    setFilterHeadhunter(next)
    void loadList(1, size)
    void loadStats()
  }

  /** 后端 reload 只做 SQLite 整理（checkpoint + VACUUM），不是重新采集。 */
  const reload = async () => {
    setReloading(true)
    try {
      const res = await fetch(`${API_BASE}/api/boss/reload`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      await loadList(1, size)
      await loadStats()
    } catch (error) {
      console.error("[Boss] reload 失败", error)
    } finally {
      setReloading(false)
    }
  }

  const exportCSV = async () => {
    setExporting(true)
    try {
      let rows = await fetchAllPages<BossJob>(LIST_URL, buildFilterParams(filters))
      if (filterHeadhunter) rows = rows.filter((job) => !isHeadhunter(job))
      downloadCsv(
        `boss_jobs_${new Date().toISOString().slice(0, 10)}.csv`,
        [
          "公司名称", "岗位名称", "薪资", "工作地点", "经验", "学历", "HR", "HR职位",
          "投递状态", "链接", "行业", "公司规模", "融资阶段", "入库时间",
        ],
        rows.map((it) => [
          it.companyName, it.jobName, it.salary, it.location, it.experience,
          it.degree, it.hrName, it.hrPosition, it.deliveryStatus, it.jobUrl,
          it.industry, it.companyScale, it.financingStage, formatDateOnly(it.createdAt),
        ])
      )
    } catch (error) {
      console.error("[Boss] 导出失败", error)
    } finally {
      setExporting(false)
    }
  }

  const applyFilters = async () => {
    await loadList(1, size)
    await loadStats()
  }

  const expand = (title: string, value?: string | null) =>
    setExpanded({ title, content: value || "" })

  const kpi = stats?.kpi
  const charts = stats?.charts

  return (
    <div className="space-y-5">
      <KpiGrid
        loading={loadingStats && !stats}
        items={[
          { label: "总岗位数", value: kpi?.total ?? 0 },
          { label: "已投递", value: kpi?.delivered ?? 0 },
          { label: "未投递", value: kpi?.pending ?? 0 },
          { label: "已过滤", value: kpi?.filtered ?? 0 },
          { label: "投递失败", value: kpi?.failed ?? 0 },
          { label: "平均月薪", value: kpi?.avgMonthlyK ?? 0, hint: "K" },
        ]}
      />

      <FilterCard
        description="按投递状态、城市、经验、学历与薪资区间过滤统计图与列表"
        statusFilter={
          <StatusFilter
            options={STATUS_OPTIONS}
            value={statuses}
            onChange={setStatuses}
            extra={
              <FilterToggle
                label="过滤猎头岗位"
                active={filterHeadhunter}
                onChange={toggleHeadhunter}
              />
            }
          />
        }
        actions={
          <>
            <Button size="sm" variant="brand" disabled={loadingList} onClick={() => void applyFilters()}>
              <BiBarChart /> 应用筛选
            </Button>
            <Button size="sm" variant="outline" disabled={exporting} onClick={() => void exportCSV()}>
              <BiDownload /> {exporting ? "导出中…" : "导出 CSV"}
            </Button>
            <Button size="sm" variant="ghost" disabled={reloading} onClick={() => void reload()}>
              <BiRefresh /> {reloading ? "整理中…" : "整理数据库"}
            </Button>
          </>
        }
      >
        <Field id="bs-city" label="城市"><Input value={location} placeholder="如：深圳" onChange={(e) => setLocation(e.target.value)} /></Field>
        <Field id="bs-exp" label="经验"><Input value={experience} placeholder="如：3-5年" onChange={(e) => setExperience(e.target.value)} /></Field>
        <Field id="bs-deg" label="学历"><Input value={degree} placeholder="如：本科" onChange={(e) => setDegree(e.target.value)} /></Field>
        <Field id="bs-mink" label="最低月薪 (K)"><Input type="number" value={minK} placeholder="10" onChange={(e) => setMinK(e.target.value)} /></Field>
        <Field id="bs-maxk" label="最高月薪 (K)"><Input type="number" value={maxK} placeholder="30" onChange={(e) => setMaxK(e.target.value)} /></Field>
        <Field id="bs-kw" label="关键词" hint="匹配公司 / 岗位 / HR"><Input value={keyword} placeholder="输入关键词" onChange={(e) => setKeyword(e.target.value)} /></Field>
      </FilterCard>

      <ChartGrid>
        <StatChart
          kind="pie"
          icon={<BiPieChart className="brand-text" />}
          title="投递状态分布"
          description="已投递 / 未投递 / 已过滤 / 失败占比"
          loading={loadingStats && !stats}
          points={charts?.byStatus ?? []}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="行业 TOP10"
          description="按公司行业聚合的岗位数"
          loading={loadingStats && !stats}
          points={charts?.byIndustry ?? []}
          colors={CATEGORY_COLORS}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="公司岗位数 TOP10"
          description="按公司名称聚合"
          loading={loadingStats && !stats}
          points={charts?.byCompany ?? []}
          colors={CATEGORY_COLORS}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="经验分布"
          description="不同经验要求的岗位数"
          loading={loadingStats && !stats}
          points={charts?.byExperience ?? []}
          colors={CATEGORY_COLORS}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="学历分布"
          description="不同学历要求的岗位数"
          loading={loadingStats && !stats}
          points={charts?.byDegree ?? []}
          colors={CATEGORY_COLORS}
        />
        <StatChart
          kind="line"
          icon={<BiLineChart className="brand-text" />}
          title="薪资区间分布"
          description="按中位数月薪 K 分桶"
          loading={loadingStats && !stats}
          points={charts?.salaryBuckets ?? []}
          color="#ef4444"
        />
      </ChartGrid>

      <JobTableCard
        title="岗位数据"
        description={`共 ${total} 条记录；点击单元格可查看完整文本`}
        table={
          <TableShell minWidth={2100}>
            <thead>
              <tr>
                {COLUMNS.map((col) => (
                  <Th key={col.key} style={{ width: col.width }}>{col.title}</Th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loadingList && items.length === 0 ? (
                <LoadingRow colSpan={COLUMNS.length} />
              ) : items.length === 0 ? (
                <EmptyRow colSpan={COLUMNS.length} text="当前筛选条件下暂无岗位数据" />
              ) : (
                items.map((it) => (
                  <tr key={it.id} className="transition-colors hover:bg-slate-50 dark:hover:bg-slate-900/50">
                    <Td><Truncated value={it.companyName} onExpand={(v) => expand("公司名称", v)} /></Td>
                    <Td><Truncated value={it.jobName} onExpand={(v) => expand("岗位名称", v)} /></Td>
                    <Td><Truncated value={it.salary} onExpand={(v) => expand("薪资", v)} /></Td>
                    <Td><Truncated value={it.location} onExpand={(v) => expand("地点", v)} /></Td>
                    <Td><Truncated value={it.experience} onExpand={(v) => expand("经验", v)} /></Td>
                    <Td><Truncated value={it.degree} onExpand={(v) => expand("学历", v)} /></Td>
                    <Td><Truncated value={it.hrName} onExpand={(v) => expand("HR", v)} /></Td>
                    <Td><Truncated value={it.hrPosition} onExpand={(v) => expand("HR 职位", v)} /></Td>
                    <Td>
                      <span className={hrActivityBadgeClass(it.hrActiveStatus)}>
                        {it.hrActiveStatus || "—"}
                      </span>
                    </Td>
                    <Td>
                      <span className={deliveryBadgeClass(it.deliveryStatus)}>
                        {it.deliveryStatus || "—"}
                      </span>
                    </Td>
                    <Td>
                      <span className={recruitmentBadgeClass(it.recruitmentStatus)}>
                        {it.recruitmentStatus || "—"}
                      </span>
                    </Td>
                    <Td>
                      {it.jobUrl ? (
                        <a href={it.jobUrl} target="_blank" rel="noreferrer" className="text-[hsl(var(--brand))] hover:underline">
                          打开
                        </a>
                      ) : (
                        "—"
                      )}
                    </Td>
                    <Td><Truncated value={it.companyAddress} onExpand={(v) => expand("公司地址", v)} /></Td>
                    <Td><Truncated value={it.industry} onExpand={(v) => expand("行业", v)} /></Td>
                    <Td><Truncated value={it.companyScale} onExpand={(v) => expand("公司规模", v)} /></Td>
                    <Td><Truncated value={it.financingStage} onExpand={(v) => expand("融资阶段", v)} /></Td>
                    <Td><Truncated value={it.introduce} onExpand={(v) => expand("公司介绍", v)} /></Td>
                    <Td><Truncated value={it.jobDescription} onExpand={(v) => expand("岗位描述", v)} /></Td>
                    <Td><Truncated value={formatDateOnly(it.createdAt)} /></Td>
                  </tr>
                ))
              )}
            </tbody>
          </TableShell>
        }
        pagination={
          <Pagination
            page={page}
            size={size}
            total={total}
            loading={loadingList}
            onPageChange={(next) => void loadList(next, size)}
            onSizeChange={(next) => void loadList(1, next)}
          />
        }
      />

      {expanded && (
        <LongTextDialog
          title={expanded.title}
          content={expanded.content}
          onClose={() => setExpanded(null)}
        />
      )}
    </div>
  )
}