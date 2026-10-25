"use client"

import type { ReactNode } from "react"
import { BiBriefcase } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select } from "@/components/ui/select"
import { cn } from "@/lib/utils"

const PAGE_SIZES = [20, 50, 100, 200] as const

/** 表格外框：统一横向滚动、边框和表头样式。 */
export function TableShell({
  children,
  minWidth = 900,
}: {
  children: ReactNode
  minWidth?: number
}) {
  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-800">
      <table className="w-full text-sm" style={{ minWidth }}>
        {children}
      </table>
    </div>
  )
}

export function Th({
  children,
  className,
  ...rest
}: { children?: ReactNode; className?: string } & React.ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      className={cn(
        "whitespace-nowrap border-b border-slate-200 bg-slate-50/80 px-3 py-2.5 text-left text-xs font-semibold text-slate-600 dark:border-slate-800 dark:bg-slate-900/60 dark:text-slate-300",
        className
      )}
      {...rest}
    >
      {children}
    </th>
  )
}

export function Td({
  children,
  className,
  ...rest
}: { children?: ReactNode; className?: string } & React.TdHTMLAttributes<HTMLTableCellElement>) {
  return (
    <td
      className={cn(
        "border-b border-slate-100 px-3 py-2.5 align-middle text-slate-700 dark:border-slate-800/70 dark:text-slate-300",
        className
      )}
      {...rest}
    >
      {children}
    </td>
  )
}

/** 单元格内的省略文本；过长时点击可查看全文。 */
export function Truncated({
  value,
  onExpand,
}: {
  value?: string | null
  onExpand?: (value: string) => void
}) {
  const text = value || "—"
  if (!onExpand) {
    return <span className="block truncate" title={text}>{text}</span>
  }
  return (
    <button
      type="button"
      onClick={() => onExpand(text)}
      title={text}
      className="block w-full truncate text-left transition-colors hover:text-[hsl(var(--brand))]"
    >
      {text}
    </button>
  )
}

/** 空态行。zhilian 的表格原本没有空态，数据为空时是白板。 */
export function EmptyRow({ colSpan, text = "暂无数据" }: { colSpan: number; text?: string }) {
  return (
    <tr>
      <Td colSpan={colSpan} className="!border-b-0">
        <div className="flex flex-col items-center gap-2 py-12 text-slate-400">
          <BiBriefcase className="h-8 w-8 text-slate-300 dark:text-slate-600" />
          <p className="text-sm">{text}</p>
        </div>
      </Td>
    </tr>
  )
}

export function LoadingRow({ colSpan }: { colSpan: number }) {
  return (
    <tr>
      <Td colSpan={colSpan} className="!border-b-0">
        <div className="flex flex-col items-center gap-2 py-12 text-slate-400">
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-transparent" />
          <p className="text-sm">加载中…</p>
        </div>
      </Td>
    </tr>
  )
}

/**
 * 分页条。
 *
 * 四个分析页各有版本：boss/51job 有上一页下一页 + 页码输入 + 每页选择，
 * zhilian 的"跳转"按钮读的是当前 state 而不是输入框里的值，点了等于原地刷新。
 * 这里统一成一套：上一页/下一页 + 页码输入（回车跳转）+ 每页条数 + 总数，
 * 数值由服务端返回的 page/size/total 驱动，不存在输入框与实际页码不一致的问题。
 */
export function Pagination({
  page,
  size,
  total,
  loading,
  onPageChange,
  onSizeChange,
}: {
  page: number
  size: number
  total: number
  loading?: boolean
  onPageChange: (page: number) => void
  onSizeChange: (size: number) => void
}) {
  const totalPages = Math.max(1, Math.ceil(total / size))
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 pt-4">
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={loading || page <= 1}
          onClick={() => onPageChange(Math.max(1, page - 1))}
        >
          上一页
        </Button>
        <span className="text-xs text-slate-500 dark:text-slate-400">
          第 {page} / {totalPages} 页
        </span>
        <Button
          variant="outline"
          size="sm"
          disabled={loading || page >= totalPages}
          onClick={() => onPageChange(page + 1)}
        >
          下一页
        </Button>
      </div>

      <div className="flex items-center gap-3">
        <Label className="text-xs text-slate-500 dark:text-slate-400" htmlFor="page-jump">
          跳至
        </Label>
        <Input
          id="page-jump"
          type="number"
          min={1}
          max={totalPages}
          defaultValue={page}
          key={page}
          onKeyDown={(e) => {
            if (e.key !== "Enter") return
            const next = Math.min(totalPages, Math.max(1, Number(e.currentTarget.value) || 1))
            onPageChange(next)
          }}
          className="h-8 w-16"
        />
        <Label className="text-xs text-slate-500 dark:text-slate-400" htmlFor="page-size">
          每页
        </Label>
        <Select
          id="page-size"
          value={String(size)}
          onChange={(e) => onSizeChange(Number(e.target.value))}
          className="h-8 w-24"
        >
          {PAGE_SIZES.map((value) => (
            <option key={value} value={value}>
              {value} 条
            </option>
          ))}
        </Select>
        <span className="text-xs text-slate-500 dark:text-slate-400">共 {total} 条</span>
      </div>
    </div>
  )
}

/** 岗位列表卡片外壳：标题 + 表格 + 分页。 */
export function JobTableCard({
  title,
  description,
  table,
  pagination,
}: {
  title: string
  description?: string
  table: ReactNode
  pagination?: ReactNode
}) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-sm font-semibold">{title}</CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
      </CardHeader>
      <CardContent>
        {table}
        {pagination}
      </CardContent>
    </Card>
  )
}