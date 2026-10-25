"use client"

import type { ReactNode } from "react"

import { ChartCanvas, type ChartKind } from "@/components/analysis/ChartCanvas"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { CATEGORY_COLORS } from "@/lib/analytics"

/** 图表加载中的占位高度，与 ChartCanvas 默认高度一致，避免布局跳动。 */
const PLACEHOLDER_HEIGHT = 256

function ChartPlaceholder({ children }: { children: ReactNode }) {
  return (
    <div
      className="flex items-center justify-center rounded-lg bg-slate-50 text-sm text-slate-400 dark:bg-slate-950/40"
      style={{ height: PLACEHOLDER_HEIGHT }}
    >
      {children}
    </div>
  )
}

/**
 * 一张统计图卡。
 *
 * 四个分析页的 26 张图卡结构完全一致（标题 + 说明 + 图/加载中/空态），
 * 只是每次重复一遍 `stats ? <ChartCanvas/> : <div>加载中...</div>`，
 * 而"加载中"这一支在数据回来后永远不会消失。现在统一在这里处理三种状态。
 */
export function StatChart({
  title,
  description,
  icon,
  kind,
  points,
  loading,
  emptyText = "当前筛选条件下暂无数据",
  colors,
  color,
}: {
  title: string
  description?: string
  icon?: ReactNode
  kind: ChartKind
  /**
   * 后端各维度字段名不统一（byStatus/byIndustry 用 `name`，
   * salaryBuckets 用 `bucket`），这里统一接受并归一化，
   * 避免每个页面都再写一次 `.map(x => ({label: x.name, value: x.value}))`。
   */
  points: Array<{ name?: string; bucket?: string; label?: string; value: number }>
  loading?: boolean
  emptyText?: string
  colors?: readonly string[]
  color?: string
}) {
  const series = points.map((p) => ({
    label: p.label ?? p.name ?? p.bucket ?? "",
    value: p.value ?? 0,
  }))
  const hasData = series.some((p) => p.value > 0)

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm font-semibold">
          {icon}
          {title}
        </CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent>
        {loading ? (
          <div className="skeleton" style={{ height: PLACEHOLDER_HEIGHT }} />
        ) : hasData ? (
          <ChartCanvas
            kind={kind}
            labels={series.map((p) => p.label)}
            data={series.map((p) => p.value)}
            colors={colors ?? (kind === "pie" ? CATEGORY_COLORS : undefined)}
            color={color}
          />
        ) : (
          <ChartPlaceholder>{emptyText}</ChartPlaceholder>
        )}
      </CardContent>
    </Card>
  )
}

/** 统计图两列栅格。 */
export function ChartGrid({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">{children}</div>
}