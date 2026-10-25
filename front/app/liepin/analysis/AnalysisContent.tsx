"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { BiBarChart, BiDownload, BiLineChart, BiPieChart } from "react-icons/bi"

import { FilterCard, StatusFilter } from "@/components/analysis/FilterBar"
import { JobTableCard, EmptyRow, LoadingRow, Pagination, TableShell, Td, Th, Truncated } from "@/components/analysis/JobTable"
import { KpiGrid } from "@/components/analysis/KpiGrid"
import { ChartGrid, StatChart } from "@/components/analysis/StatChart"
import { Button } from "@/components/ui/button"
import { Dialog } from "@/components/ui/dialog"
import { Field } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Select } from "@/components/ui/select"
import { API_BASE } from "@/lib/api"
import {
  buildFilterParams,
  bucketize,
  deliveryBadgeClass,
  downloadCsv,
  fetchAllPages,
  formatDateOnly,
  type BucketValue,
  type StatsResponse,
} from "@/lib/analytics"
import { parseSalary } from "@/lib/salary"

type LiepinJob = {
  jobId: number
  compName?: string
  compIndustry?: string
  jobTitle?: string
  jobSalaryText?: string
  jobArea?: string
  jobEduReq?: string
  jobExpReq?: string
  jobLink?: string
  hrName?: string
  delivered?: number
  createTime?: string
}

type Paged = { items?: LiepinJob[]; total?: number; page?: number; size?: number }

const LIST_URL = `${API_BASE}/api/liepin/list`
const STATS_URL = `${API_BASE}/api/liepin/stats`
const STATUS_OPTIONS = ["未投递", "已投递"]
const EXPERIENCE_OPTIONS = ["应届", "1-3年", "3-5年", "5-10年", "10年以上"]
const DEGREE_OPTIONS = ["大专", "本科", "硕士", "博士"]

const COLUMNS = [
  { key: "compName", title: "公司", width: 200 },
  { key: "jobTitle", title: "岗位", width: 280 },
  { key: "jobSalaryText", title: "薪资", width: 130 },
  { key: "jobArea", title: "城市", width: 110 },
  { key: "jobExpReq", title: "经验", width: 110 },
  { key: "jobEduReq", title: "学历", width: 110 },
  { key: "hrName", title: "HR", width: 110 },
  { key: "status", title: "状态", width: 110 },
  { key: "jobLink", title: "链接", width: 80 },
  { key: "createTime", title: "入库时间", width: 120 },
] as const

/** 后端没返回该维度时，用当前列表页在前端补一个分组。 */
function fallbackGroup(items: LiepinJob[], pick: (job: LiepinJob) => string, emptyLabel: string) {
  const counts = new Map<string, number>()
  for (const it of items) {
    const key = pick(it).trim() || emptyLabel
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return [...counts.entries()].map(([name, value]) => ({ name, value }))
}

function medianKs(items: LiepinJob[], pick: (job: LiepinJob) => string | undefined) {
  const ks: number[] = []
  for (const it of items) {
    const info = parseSalary(pick(it))
    if (info && !Number.isNaN(info.medianK)) ks.push(info.medianK)
  }
  return ks
}

export default function AnalysisContent() {
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [loadingStats, setLoadingStats] = useState(true)
  const [items, setItems] = useState<LiepinJob[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [loadingList, setLoadingList] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [detailJob, setDetailJob] = useState<LiepinJob | null>(null)
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
      console.error("[猎聘] 获取统计失败", error)
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
        console.error("[猎聘] 获取列表失败", error)
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

  const exportCSV = async () => {
    setExporting(true)
    try {
      const all = await fetchAllPages<LiepinJob>(LIST_URL, buildFilterParams(filters))
      downloadCsv(
        `liepin_jobs_${new Date().toISOString().slice(0, 10)}.csv`,
        ["公司名称", "岗位名称", "薪资", "工作地点", "经验", "学历", "HR", "投递状态", "链接", "入库时间"],
        all.map((it) => [
          it.compName, it.jobTitle, it.jobSalaryText, it.jobArea, it.jobExpReq,
          it.jobEduReq, it.hrName, it.delivered === 1 ? "已投递" : "未投递",
          it.jobLink, formatDateOnly(it.createTime),
        ])
      )
    } catch (error) {
      console.error("[猎聘] 导出失败", error)
    } finally {
      setExporting(false)
    }
  }

  /** 后端薪资桶为空时，用全量数据在前端补算一次。 */
  const recomputeSalaryBuckets = useCallback(async () => {
    try {
      const all = await fetchAllPages<LiepinJob>(LIST_URL, buildFilterParams(filters))
      const ks = medianKs(all, (it) => it.jobSalaryText)
      setComputedSalaryBuckets(ks.length ? bucketize(ks) : [])
    } catch (error) {
      console.error("[猎聘] 前端薪资分桶失败", error)
      setComputedSalaryBuckets([])
    }
  }, [filters, setComputedSalaryBuckets])

  useEffect(() => {
    if (loadingStats || !stats) return
    const apiBuckets = stats.charts.salaryBuckets ?? []
    if (apiBuckets.length === 0 || apiBuckets.every((b) => !b.value)) {
      void recomputeSalaryBuckets()
    } else {
      setComputedSalaryBuckets([])
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stats])

  const applyFilters = async () => {
    await loadList(1, size)
    await loadStats()
  }

  const kpi = stats?.kpi
  const charts = stats?.charts

  const avgMonthlyK = useMemo(() => {
    if (kpi?.avgMonthlyK != null) return kpi.avgMonthlyK
    const ks = medianKs(items, (it) => it.jobSalaryText)
    if (!ks.length) return 0
    return Math.round((ks.reduce((a, b) => a + b, 0) / ks.length) * 10) / 10
  }, [kpi, items])

  const byExperience = useMemo(
    () =>
      charts?.byExperience?.length
        ? charts.byExperience
        : fallbackGroup(items, (it) => it.jobExpReq ?? "", "未知"),
    [charts, items]
  )

  const byDegree = useMemo(
    () =>
      charts?.byDegree?.length
        ? charts.byDegree
        : fallbackGroup(items, (it) => it.jobEduReq ?? "", "不限"),
    [charts, items]
  )

  const salaryPoints = useMemo(() => {
    const apiBuckets = charts?.salaryBuckets ?? []
    if (apiBuckets.length > 0 && apiBuckets.some((b) => b.value > 0)) return apiBuckets
    return computedSalaryBuckets
  }, [charts, computedSalaryBuckets])

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
        description="按投递状态、城市、经验、学历与薪资区间过滤统计图与列表"
        statusFilter={
          <StatusFilter options={STATUS_OPTIONS} value={statuses} onChange={setStatuses} />
        }
        actions={
          <>
            <Button size="sm" variant="brand" disabled={loadingList} onClick={() => void applyFilters()}>
              应用筛选
            </Button>
            <Button size="sm" variant="outline" disabled={exporting} onClick={() => void exportCSV()}>
              <BiDownload /> {exporting ? "导出中…" : "导出 CSV"}
            </Button>
          </>
        }
      >
        <Field id="lp-city" label="城市"><Input value={location} placeholder="例如：北京" onChange={(e) => setLocation(e.target.value)} /></Field>
        <Field id="lp-kw" label="关键词" hint="匹配公司 / 岗位 / HR"><Input value={keyword} placeholder="输入关键词" onChange={(e) => setKeyword(e.target.value)} /></Field>
        <Field id="lp-exp" label="经验">
          <Select value={experience} onChange={(e) => setExperience(e.target.value)} aria-label="经验">
            <option value="">不限</option>
            {EXPERIENCE_OPTIONS.map((o) => (
              <option key={o} value={o}>{o}</option>
            ))}
          </Select>
        </Field>
        <Field id="lp-deg" label="学历">
          <Select value={degree} onChange={(e) => setDegree(e.target.value)} aria-label="学历">
            <option value="">不限</option>
            {DEGREE_OPTIONS.map((o) => (
              <option key={o} value={o}>{o}</option>
            ))}
          </Select>
        </Field>
        <Field id="lp-mink" label="最低月薪 (K)"><Input type="number" value={minK} placeholder="如：15" onChange={(e) => setMinK(e.target.value)} /></Field>
        <Field id="lp-maxk" label="最高月薪 (K)"><Input type="number" value={maxK} placeholder="如：30" onChange={(e) => setMaxK(e.target.value)} /></Field>
      </FilterCard>

      <ChartGrid>
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="城市 TOP10"
          description="热门城市岗位数排行"
          loading={loadingStats && !stats}
          points={charts?.byCity ?? []}
        />
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
          title="行业分布"
          description="按公司行业聚合"
          loading={loadingStats && !stats}
          points={charts?.byIndustry ?? []}
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
          points={byExperience}
        />
        <StatChart
          kind="bar"
          icon={<BiBarChart className="brand-text" />}
          title="学历分布"
          description="不同学历要求的岗位数"
          loading={loadingStats && !stats}
          points={byDegree}
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
          <TableShell minWidth={1180}>
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
                    <Td>
                      <Truncated value={it.compName} />
                      {it.compIndustry && (
                        <span className="block truncate text-xs text-slate-400" title={it.compIndustry}>
                          {it.compIndustry}
                        </span>
                      )}
                    </Td>
                    <Td>
                      <button
                        type="button"
                        onClick={() => setDetailJob(it)}
                        title={it.jobTitle || ""}
                        className="block w-full truncate text-left font-medium text-slate-800 transition-colors hover:text-[hsl(var(--brand))] dark:text-slate-100"
                      >
                        {it.jobTitle || "—"}
                      </button>
                    </Td>
                    <Td><Truncated value={it.jobSalaryText} /></Td>
                    <Td><Truncated value={it.jobArea} /></Td>
                    <Td><Truncated value={it.jobExpReq} /></Td>
                    <Td><Truncated value={it.jobEduReq} /></Td>
                    <Td><Truncated value={it.hrName} /></Td>
                    <Td>
                      <span className={deliveryBadgeClass(it.delivered === 1 ? "已投递" : "未投递")}>
                        {it.delivered === 1 ? "已投递" : "未投递"}
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

      {detailJob && (
        <Dialog
          open
          onClose={() => setDetailJob(null)}
          title={detailJob.jobTitle || "岗位详情"}
          description={detailJob.compName}
        >
          <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
            {[
              ["公司行业", detailJob.compIndustry],
              ["薪资", detailJob.jobSalaryText],
              ["城市", detailJob.jobArea],
              ["经验", detailJob.jobExpReq],
              ["学历", detailJob.jobEduReq],
              ["HR", detailJob.hrName],
              ["状态", detailJob.delivered === 1 ? "已投递" : "未投递"],
              ["入库时间", formatDateOnly(detailJob.createTime)],
            ].map(([label, value]) => (
              <div key={label} className="flex gap-2">
                <dt className="shrink-0 text-slate-400">{label}：</dt>
                <dd className="min-w-0 truncate text-slate-700 dark:text-slate-200" title={value || "—"}>
                  {value || "—"}
                </dd>
              </div>
            ))}
          </dl>
          <div className="mt-4">
            {detailJob.jobLink ? (
              <a href={detailJob.jobLink} target="_blank" rel="noreferrer" className="text-sm text-[hsl(var(--brand))] hover:underline">
                打开职位链接
              </a>
            ) : (
              <span className="text-sm text-slate-400">暂无链接</span>
            )}
          </div>
        </Dialog>
      )}
    </div>
  )
}