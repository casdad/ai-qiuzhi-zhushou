"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { BiBarChart, BiDownload, BiLineChart, BiPieChart } from "react-icons/bi"

import { FilterCard, StatusFilter } from "@/components/analysis/FilterBar"
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
  bucketize,
  buildFilterParams,
  deliveryBadgeClass,
  downloadCsv,
  fetchAllPages,
  formatDateOnly,
  type BucketValue,
  type StatsResponse,
} from "@/lib/analytics"
import { parseSalary } from "@/lib/salary"

type ZhilianJob = {
  jobId: string
  companyName?: string
  jobTitle?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  deliveryStatus?: string
  jobLink?: string
  createTime?: string
}

type Paged = { items?: ZhilianJob[]; total?: number; page?: number; size?: number }

const LIST_URL = `${API_BASE}/api/zhilian/list`
const STATS_URL = `${API_BASE}/api/zhilian/stats`
const STATUS_OPTIONS = ["未投递", "已投递", "已过滤", "投递失败"]

const COLUMNS = [
  { key: "companyName", title: "公司", width: 200 },
  { key: "jobTitle", title: "岗位", width: 260 },
  { key: "salary", title: "薪资", width: 130 },
  { key: "location", title: "地点", width: 110 },
  { key: "experience", title: "经验", width: 110 },
  { key: "degree", title: "学历", width: 110 },
  { key: "deliveryStatus", title: "投递状态", width: 110 },
  { key: "jobLink", title: "链接", width: 80 },
  { key: "createTime", title: "入库时间", width: 120 },
] as const

/** 统计图随筛选自动刷新，但对输入做防抖，避免每敲一个字符打一次接口。 */
const STATS_DEBOUNCE_MS = 400

export default function AnalysisContent() {
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [loadingStats, setLoadingStats] = useState(true)
  const [items, setItems] = useState<ZhilianJob[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [loadingList, setLoadingList] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [computedSalaryBuckets, setComputedSalaryBuckets] = useState<BucketValue[]>([])

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
      console.error("[智联] 获取统计失败", error)
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
        console.error("[智联] 获取列表失败", error)
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

  // 统计图随筛选自动刷新（防抖）。原先这个 effect 无防抖，每输入一个字符就请求一次。
  const firstRun = useRef(true)
  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false
      void loadStats()
      return
    }
    const timer = setTimeout(() => void loadStats(), STATS_DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters])

  /** 后端薪资桶为空时，用全量数据在前端补算一次。 */
  useEffect(() => {
    if (loadingStats || !stats) return
    const apiBuckets = stats.charts.salaryBuckets ?? []
    if (apiBuckets.length > 0 && apiBuckets.some((b) => b.value > 0)) {
      setComputedSalaryBuckets([])
      return
    }
    let cancelled = false
    const run = async () => {
      try {
        const all = await fetchAllPages<ZhilianJob>(LIST_URL, buildFilterParams(filters))
        const ks: number[] = []
        for (const it of all) {
          const info = parseSalary(it.salary)
          if (info && !Number.isNaN(info.medianK)) ks.push(info.medianK)
        }
        if (!cancelled) setComputedSalaryBuckets(ks.length ? bucketize(ks) : [])
      } catch (error) {
        console.error("[智联] 前端薪资分桶失败", error)
        if (!cancelled) setComputedSalaryBuckets([])
      }
    }
    void run()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stats])

  const exportCSV = async () => {
    setExporting(true)
    try {
      const all = await fetchAllPages<ZhilianJob>(LIST_URL, buildFilterParams(filters))
      downloadCsv(
        `zhilian_jobs_${new Date().toISOString().slice(0, 10)}.csv`,
        ["公司名称", "岗位名称", "薪资", "工作地点", "经验", "学历", "投递状态", "链接", "入库时间"],
        all.map((it) => [
          it.companyName, it.jobTitle, it.salary, it.location, it.experience,
          it.degree, it.deliveryStatus, it.jobLink, formatDateOnly(it.createTime),
        ])
      )
    } catch (error) {
      console.error("[智联] 导出失败", error)
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

  const avgMonthlyK = useMemo(() => {
    if (kpi?.avgMonthlyK != null) return kpi.avgMonthlyK
    const ks: number[] = []
    for (const it of items) {
      const info = parseSalary(it.salary)
      if (info && !Number.isNaN(info.medianK)) ks.push(info.medianK)
    }
    if (!ks.length) return 0
    return Math.round((ks.reduce((a, b) => a + b, 0) / ks.length) * 10) / 10
  }, [kpi, items])

  const salaryPoints = useMemo(() => {
    const apiBuckets = charts?.salaryBuckets ?? []
    if (apiBuckets.length > 0 && apiBuckets.some((b) => b.value > 0)) return apiBuckets
    return computedSalaryBuckets
  }, [charts, computedSalaryBuckets])

  const dirty = Boolean(
    location || experience || degree || minK || maxK || keyword || statuses.length
  )

  return (
    <div className="space-y-5">
      <KpiGrid
        loading={loadingStats && !stats}
        items={[
          { label: "总岗位数", value: kpi?.total ?? 0 },
          { label: "已投递", value: kpi?.delivered ?? 0 },
          { label: "未投递", value: kpi?.pending ?? 0 },
          { label: "平均月薪", value: avgMonthlyK, hint: "K" },
        ]}
      />

      <FilterCard
        description="修改筛选后统计图自动刷新；列表需要点击「应用筛选」"
        statusFilter={
          <StatusFilter
            options={STATUS_OPTIONS}
            value={statuses}
            onChange={setStatuses}
            extra={
              <button
                type="button"
                disabled={!statuses.length}
                onClick={() => setStatuses([])}
                className="rounded-full px-2.5 py-1.5 text-xs text-slate-400 transition-colors hover:text-slate-600 disabled:opacity-40 dark:hover:text-slate-300"
              >
                清空
              </button>
            }
          />
        }
        actions={
          <>
            <Button size="sm" variant="brand" disabled={loadingList} onClick={() => void applyFilters()}>
              应用筛选
            </Button>
            <Button size="sm" variant="outline" disabled={exporting || !dirty} onClick={() => void exportCSV()}>
              <BiDownload /> {exporting ? "导出中…" : "导出 CSV"}
            </Button>
          </>
        }
      >
        <Field id="zl-city" label="地区"><Input value={location} placeholder="如：北京" onChange={(e) => setLocation(e.target.value)} /></Field>
        <Field id="zl-exp" label="经验"><Input value={experience} placeholder="如：3-5年" onChange={(e) => setExperience(e.target.value)} /></Field>
        <Field id="zl-deg" label="学历"><Input value={degree} placeholder="如：本科" onChange={(e) => setDegree(e.target.value)} /></Field>
        <Field id="zl-mink" label="最低月薪 (K)"><Input type="number" value={minK} placeholder="如：10" onChange={(e) => setMinK(e.target.value)} /></Field>
        <Field id="zl-maxk" label="最高月薪 (K)"><Input type="number" value={maxK} placeholder="如：30" onChange={(e) => setMaxK(e.target.value)} /></Field>
        <Field id="zl-kw" label="关键词" hint="匹配公司或岗位"><Input value={keyword} placeholder="输入关键词" onChange={(e) => setKeyword(e.target.value)} /></Field>
      </FilterCard>

      <ChartGrid>
        <StatChart
          kind="pie"
          icon={<BiPieChart className="brand-text" />}
          title="投递状态分布"
          description="按投递状态聚合"
          loading={loadingStats && !stats}
          points={charts?.byStatus ?? []}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="城市 TOP10"
          description="按地区聚合的岗位数"
          loading={loadingStats && !stats}
          points={charts?.byCity ?? []}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="公司岗位数 TOP10"
          description="按公司名称聚合"
          loading={loadingStats && !stats}
          points={charts?.byCompany ?? []}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="经验分布"
          description="不同经验要求的岗位数"
          loading={loadingStats && !stats}
          points={charts?.byExperience ?? []}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="学历分布"
          description="不同学历要求的岗位数"
          loading={loadingStats && !stats}
          points={charts?.byDegree ?? []}
        />
        <StatChart
          kind="line"
          icon={<BiLineChart className="brand-text" />}
          title="薪资区间分布"
          description="按中位数月薪 K 分桶；后端缺失时前端补算"
          loading={loadingStats && !stats}
          points={salaryPoints}
          color="#ef4444"
        />
      </ChartGrid>

      <JobTableCard
        title="岗位列表"
        description={`共 ${total} 条记录`}
        table={
          <TableShell minWidth={1160}>
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
                    <Td><Truncated value={it.jobTitle} /></Td>
                    <Td><Truncated value={it.salary} /></Td>
                    <Td><Truncated value={it.location} /></Td>
                    <Td><Truncated value={it.experience} /></Td>
                    <Td><Truncated value={it.degree} /></Td>
                    <Td>
                      <span className={deliveryBadgeClass(it.deliveryStatus)}>
                        {it.deliveryStatus || "—"}
                      </span>
                    </Td>
                    <Td>
                      {it.jobLink ? (
                        <a href={it.jobLink} target="_blank" rel="noreferrer" className="text-[hsl(var(--brand))] hover:underline">
                          打开
                        </a>
                      ) : (
                        "—"
                      )}
                    </Td>
                    <Td><Truncated value={formatDateOnly(it.createTime)} /></Td>
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