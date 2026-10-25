"use client"

import type { ReactNode } from "react"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { cn } from "@/lib/utils"

/**
 * 状态筛选胶囊。
 *
 * 四个分析页的状态筛选各写了一份：boss/51job/liepin 用 label+sr-only checkbox，
 * zhilian 用 button，两者视觉完全不同，切换逻辑也不一致。
 * 这里统一为可点击胶囊，checked 状态清晰可见。
 */
export function StatusFilter({
  options,
  value,
  onChange,
  label = "投递状态",
  extra,
}: {
  options: string[]
  value: string[]
  onChange: (next: string[]) => void
  label?: string
  /** 平台特有的附加开关，例如 Boss 的"过滤猎头岗位"。 */
  extra?: ReactNode
}) {
  const toggle = (option: string) =>
    onChange(
      value.includes(option) ? value.filter((v) => v !== option) : [...value, option]
    )

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{label}</p>
      <div className="flex flex-wrap items-center gap-2">
        {options.map((option) => {
          const active = value.includes(option)
          return (
            <button
              key={option}
              type="button"
              aria-pressed={active}
              onClick={() => toggle(option)}
              className={cn(
                "rounded-full border px-3 py-1.5 text-xs font-medium transition-colors",
                active
                  ? "border-transparent bg-[hsl(var(--brand))] text-white"
                  : "border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
              )}
            >
              {option}
            </button>
          )
        })}
        {extra}
      </div>
    </div>
  )
}

/** Boss 专用的"过滤猎头岗位"开关，样式与 StatusFilter 的胶囊一致。 */
export function FilterToggle({
  label,
  active,
  onChange,
}: {
  label: string
  active: boolean
  onChange: (next: boolean) => void
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={() => onChange(!active)}
      className={cn(
        "rounded-full border px-3 py-1.5 text-xs font-medium transition-colors",
        active
          ? "border-transparent bg-[hsl(var(--brand))] text-white"
          : "border-slate-200 bg-white text-slate-600 hover:border-slate-300 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
      )}
    >
      {label}
    </button>
  )
}

/**
 * 筛选卡片外壳。
 *
 * 提供标题、说明、状态胶囊区、字段栅格和操作按钮行；字段和按钮由各平台自己传入，
 * 这样既统一了外观，又不会为了抽象而抹平平台之间的真实差异。
 */
export function FilterCard({
  title = "筛选与操作",
  description,
  statusFilter,
  children,
  actions,
  columns = 6,
}: {
  title?: string
  description?: string
  statusFilter?: ReactNode
  children: ReactNode
  actions: ReactNode
  columns?: 3 | 4 | 5 | 6
}) {
  const gridCols = {
    3: "sm:grid-cols-2 lg:grid-cols-3",
    4: "sm:grid-cols-2 lg:grid-cols-4",
    5: "sm:grid-cols-2 lg:grid-cols-5",
    6: "sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6",
  }[columns]

  return (
    <Card>
      <CardHeader className="space-y-0 pb-4">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <CardTitle className="text-sm font-semibold">{title}</CardTitle>
            {description && <CardDescription>{description}</CardDescription>}
          </div>
          {statusFilter}
        </div>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className={`grid grid-cols-1 gap-4 ${gridCols}`}>{children}</div>
        <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-4 dark:border-slate-800">
          {actions}
        </div>
      </CardContent>
    </Card>
  )
}