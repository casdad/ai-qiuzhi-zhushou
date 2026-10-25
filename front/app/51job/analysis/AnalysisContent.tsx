"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { BiBarChart, BiDownload, BiLineChart, BiPieChart, BiRefresh } from "react-icons/bi"

import { FilterCard, StatusFilter } from "@/components/analysis/FilterBar"
import { KpiGrid } from "@/components/analysis/KpiGrid"
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
  type StatsResponse,
} from "@/lib/analytics"

type Job51Item = {
  jobId: number
  companyName?: string
  jobName?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  hrName?: string
  deliveryStatus?: string
  jobUrl?: string
  publishTime?: string
  createdAt?: string
  industry?: string
  companyScale?: string
}

type Paged = { items?: Job51Item[]; total?: number; page?: number; size?: number }

const LIST_URL = `${API_BASE}/api/51job/list`
const STATS_URL = `${API_BASE}/api/51job/stats`
const STATUS_OPTIONS = ["未投递", "已投递"]

const COLUMNS = [
  { key: "companyName", title: "公司名称", width: 160 },
  { key: "jobName", title: "岗位名称", width: 220 },
  { key: "salary", title: "薪资", width: 110 },
  { key: "location", title: "地点", width: 110 },
  { key: "experience", title: "经验", width: 100 },
  { key: "degree", title: "学历", width: 90 },
  { key: "hrName", title: "HR", width: 110 },
  { key: "deliveryStatus", title: "投递状态", width: 110 },
  { key: "jobUrl", title: "链接", width: 80 },
  { key: "industry", title: "行业", width: 130 },
  { key: "companyScale", title: "公司规模", width: 120 },
  { key: "publishTime", title: "发布时间", width: 120 },
  { key: "createdAt", title: "入库时间", width: 120 },
] as const

export default function AnalysisContent() {
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [loadingStats, setLoadingStats] = useState(true)
  const [items, setItems] = useState<Job51Item[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [loadingList, setLoadingList] = useState(false)
  const [reloading, setReloading] = useState(false)
  const [exporting, setExporting] = useState(false)

  const [statuses, setStatuses] = useState<string[]>([])
  const [location, setLocation] = useState("")
  const [experience, setExperience] = useState("")
  const [degree, setDegree] = useState("")
  const [minK, setMinK] = useState("")
  const [maxK, setMaxK] = useState("")
  const [keyword, setKeyword] = useState("")

  const filters = useMemo(
    () => ({ statuses, location, experience, degree, minK, maxK, keyword }),
    [statuses, location, experience, degree, minK, maxK, keyword]
  )

  const loadStats = useCallback(async () => {
    setLoadingStats(true)
    try {
      const res = await fetch(`${STATS_URL}?${buildFilterParams(filters).toString()}`)
      setStats((await res.json()) as StatsResponse)
    } catch (error) {
      console.error("[51job] 获取统计失败", error)
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
        setItems(data.items ?? [])
        setTotal(data.total ?? 0)
        setPage(data.page ?? toPage)
        setSize(data.size ?? toSize)
      } catch (error) {
        console.error("[51job] 获取列表失败", error)
      } finally {
        setLoadingList(false)
      }
    },
    [filters, page, size]
  )

  useEffect(() => {
    void loadList(1, 20)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    void loadStats()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** 后端 reload 只做 SQLite 整理（checkpoint + VACUUM），不是重新采集，所以这里如实说明。 */
  const reload = async () => {
    setReloading(true)
    try {
      const res = await fetch(`${API_BASE}/api/51job/reload`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      await loadList(1, size)
      await loadStats()
    } catch (error) {
      console.error("[51job] reload 失败", error)
    } finally {
      setReloading(false)
    }
  }

  const exportCSV = async () => {
    setExporting(true)
    try {
      const all = await fetchAllPages<Job51Item>(LIST_URL, buildFilterParams(filters))
      downloadCsv(
        `51job_jobs_${new Date().toISOString().slice(0, 10)}.csv`,
        [
          "公司名称", "岗位名称", "薪资", "地点", "经验", "学历", "HR",
          "投递状态", "链接", "行业", "公司规模", "发布时间", "入库时间",
        ],
        all.map((it) => [
          it.companyName, it.jobName, it.salary, it.location, it.experience,
          it.degree, it.hrName, it.deliveryStatus, it.jobUrl, it.industry,
          it.companyScale, it.publishTime, formatDateOnly(it.createdAt),
        ])
      )
    } catch (error) {
      console.error("[51job] 导出失败", error)
    } finally {
      setExporting(false)
    }
  }

  const applyFilters = async () => {
    await loadList(1, size)
    await loadStats()
  }

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
          { label: "平均月薪", value: kpi?.avgMonthlyK ?? 0, hint: "K" },
        ]}
      />

      <FilterCard
        description="按投递状态、城市、经验、学历与薪资区间过滤统计图与列表"
        statusFilter={
          <StatusFilter options={STATUS_OPTIONS} value={statuses} onChange={setStatuses} />
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
        <Field id="f-city" label="城市"><Input value={location} placeholder="如：深圳" onChange={(e) => setLocation(e.target.value)} /></Field>
        <Field id="f-exp" label="经验"><Input value={experience} placeholder="如：3-5年" onChange={(e) => setExperience(e.target.value)} /></Field>
        <Field id="f-deg" label="学历"><Input value={degree} placeholder="如：本科" onChange={(e) => setDegree(e.target.value)} /></Field>
        <Field id="f-mink" label="最低月薪 (K)"><Input type="number" value={minK} placeholder="10" onChange={(e) => setMinK(e.target.value)} /></Field>
        <Field id="f-maxk" label="最高月薪 (K)"><Input type="number" value={maxK} placeholder="30" onChange={(e) => setMaxK(e.target.value)} /></Field>
        <Field id="f-kw" label="关键词" hint="匹配公司 / 岗位 / HR"><Input value={keyword} placeholder="输入关键词" onChange={(e) => setKeyword(e.target.value)} /></Field>
      </FilterCard>

      <ChartGrid>
        <StatChart
          kind="pie"
          icon={<BiPieChart className="brand-text" />}
          title="投递状态分布"
          description="已投递与未投递的占比"
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
          points={(charts?.salaryBuckets ?? []).map((b) => ({ label: b.bucket, value: b.value }))}
          color="#ef4444"
        />
      </ChartGrid>

      <JobTableCard
        title="岗位数据"
        description={`共 ${total} 条记录`}
        table={
          <TableShell minWidth={1400}>
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
                  <tr key={it.jobId} className="transition-colors hover:bg-slate-50 dark:hover:bg-slate-900/50">
                    <Td><Truncated value={it.companyName} /></Td>
                    <Td><Truncated value={it.jobName} /></Td>
                    <Td><Truncated value={it.salary} /></Td>
                    <Td><Truncated value={it.location} /></Td>
                    <Td><Truncated value={it.experience} /></Td>
                    <Td><Truncated value={it.degree} /></Td>
                    <Td><Truncated value={it.hrName} /></Td>
                    <Td>
                      <span className={deliveryBadgeClass(it.deliveryStatus)}>
                        {it.deliveryStatus || "—"}
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
                    <Td><Truncated value={it.industry} /></Td>
                    <Td><Truncated value={it.companyScale} /></Td>
                    <Td><Truncated value={it.publishTime} /></Td>
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
    </div>
  )
}