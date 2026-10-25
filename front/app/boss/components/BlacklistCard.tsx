"use client"

import { useState } from "react"
import { BiBriefcase, BiPlus, BiTrash, BiUser } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { SectionCard } from "@/components/ui/section-card"
import { cn } from "@/lib/utils"

type BlacklistItem = { id: number; value: string; type: string }

const TYPES = [
  { key: "company", label: "公司", placeholder: "输入公司名称", icon: BiBriefcase },
  { key: "job", label: "岗位", placeholder: "输入岗位关键词", icon: BiBriefcase },
  { key: "recruiter", label: "HR", placeholder: "输入 HR 职位关键词", icon: BiUser },
] as const

/**
 * 黑名单管理。
 *
 * 原实现把公司/岗位/HR 三类各写了一遍几乎相同的列表块（结构、判断、删除按钮完全一样），
 * 共约 90 行；而且三块各自带颜色边框，视觉上像三个不同功能，其实是同一个功能。
 * 这里按类型分 Tab，只渲染当前类型的一组列表。
 */
export function BlacklistCard({
  items,
  onAdd,
  onDelete,
}: {
  items: BlacklistItem[]
  onAdd: (value: string, type: string) => void
  onDelete: (id: number) => void
}) {
  const [type, setType] = useState<string>("company")
  const [draft, setDraft] = useState("")

  const active = TYPES.find((t) => t.key === type) ?? TYPES[0]
  const list = items.filter((item) => item.type === type)

  const submit = () => {
    const value = draft.trim()
    if (!value) return
    onAdd(value, type)
    setDraft("")
  }

  return (
    <SectionCard
      icon={<BiTrash className="brand-text" />}
      title={`黑名单管理（${items.length} 条）`}
      description="添加不想投递的公司、岗位或 HR，投递流程会直接跳过。"
    >
      <div className="flex flex-wrap items-center gap-2">
        {TYPES.map((t) => {
          const count = items.filter((item) => item.type === t.key).length
          const isActive = t.key === type
          return (
            <button
              key={t.key}
              type="button"
              onClick={() => setType(t.key)}
              className={cn(
                "rounded-full border px-3 py-1.5 text-xs font-medium transition-colors",
                isActive
                  ? "border-transparent bg-[hsl(var(--brand))] text-white"
                  : "border-slate-200 bg-white text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300"
              )}
            >
              {t.label}黑名单 {count}
            </button>
          )
        })}
      </div>

      <div className="mt-4 flex gap-2">
        <Input
          value={draft}
          placeholder={active.placeholder}
          aria-label={active.placeholder}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") submit()
          }}
        />
        <Button variant="brand" onClick={submit} disabled={!draft.trim()}>
          <BiPlus /> 添加
        </Button>
      </div>

      <div className="mt-4 space-y-2">
        {list.length === 0 ? (
          <p className="rounded-lg border border-dashed border-slate-200 py-8 text-center text-sm text-slate-400 dark:border-slate-700">
            暂无{active.label}黑名单
          </p>
        ) : (
          list.map((item) => (
            <div
              key={item.id}
              className="flex items-center justify-between gap-3 rounded-lg border border-slate-200 px-3 py-2 dark:border-slate-800"
            >
              <span className="truncate text-sm text-slate-700 dark:text-slate-300" title={item.value}>
                {item.value}
              </span>
              <Button
                variant="ghost"
                size="icon"
                aria-label={`删除 ${item.value}`}
                onClick={() => onDelete(item.id)}
                className="shrink-0 text-slate-400 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/40"
              >
                <BiTrash />
              </Button>
            </div>
          ))
        )}
      </div>
    </SectionCard>
  )
}