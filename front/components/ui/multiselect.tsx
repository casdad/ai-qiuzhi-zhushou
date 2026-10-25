"use client"

import * as React from "react"
import { createPortal } from "react-dom"
import { BiCheck, BiSearch, BiX } from "react-icons/bi"

import { cn } from "@/lib/utils"

export interface MultiSelectOption {
  id?: number | string
  code: string
  name: string
}

/** 选项超过这个数量时，筛选框里出现搜索框。 */
const SEARCH_THRESHOLD = 12

/**
 * 多选下拉。
 *
 * 原来有两份实现：Boss 页内联的 165 行版本（含大段 console.log 调试输出和一个
 * 从未被任何调用方传入的 onClose 参数），以及 51job 页单独写的薪资下拉。
 * 两者外观不一致，51job 那份还漏了 Esc 关闭和搜索。这里合并成一份。
 *
 * 用 Portal 渲染面板，避免被父级 overflow:hidden 裁掉。
 */
export function MultiSelect({
  options,
  selected,
  onChange,
  placeholder = "请选择",
  disabled,
  max,
  className,
  ariaLabel,
}: {
  options: MultiSelectOption[]
  selected: string[]
  onChange: (codes: string[]) => void
  placeholder?: string
  disabled?: boolean
  /** 最多可选个数；超出后未选项置灰。51job 薪资限制 5 个。 */
  max?: number
  className?: string
  ariaLabel?: string
}) {
  const [open, setOpen] = React.useState(false)
  const [keyword, setKeyword] = React.useState("")
  const [position, setPosition] = React.useState({ top: 0, left: 0, width: 0 })
  const triggerRef = React.useRef<HTMLButtonElement>(null)
  const panelRef = React.useRef<HTMLDivElement>(null)

  const updatePosition = React.useCallback(() => {
    const rect = triggerRef.current?.getBoundingClientRect()
    if (!rect) return
    // 面板默认向下展开；下方空间不足时翻到上方。
    const below = window.innerHeight - rect.bottom
    const flip = below < 260 && rect.top > below
    setPosition({
      top: flip ? rect.top - 8 : rect.bottom + 8,
      left: rect.left,
      width: rect.width,
    })
  }, [])

  React.useEffect(() => {
    if (!open) return
    updatePosition()
    const handler = () => updatePosition()
    window.addEventListener("scroll", handler, true)
    window.addEventListener("resize", handler)
    return () => {
      window.removeEventListener("scroll", handler, true)
      window.removeEventListener("resize", handler)
    }
  }, [open, updatePosition])

  React.useEffect(() => {
    if (!open) return
    const onPointerDown = (e: MouseEvent) => {
      const target = e.target as Node
      if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) return
      setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false)
    }
    document.addEventListener("mousedown", onPointerDown)
    document.addEventListener("keydown", onKey)
    return () => {
      document.removeEventListener("mousedown", onPointerDown)
      document.removeEventListener("keydown", onKey)
    }
  }, [open])

  React.useEffect(() => {
    if (!open) setKeyword("")
  }, [open])

  const nameOf = React.useCallback(
    (code: string) => options.find((o) => o.code === code)?.name ?? code,
    [options]
  )

  const selectedNames = selected.map(nameOf)
  const atLimit = max != null && selected.length >= max

  const visible = React.useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    if (!kw) return options
    return options.filter((o) => o.name.toLowerCase().includes(kw))
  }, [options, keyword])

  const toggle = (code: string) => {
    if (selected.includes(code)) {
      onChange(selected.filter((c) => c !== code))
    } else if (!atLimit) {
      onChange([...selected, code])
    }
  }

  const triggerLabel =
    selectedNames.length > 0 ? selectedNames.join("、") : placeholder

  return (
    <div className={cn("relative min-w-0", className)}>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        aria-label={ariaLabel ?? placeholder}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "field-base flex items-center justify-between gap-2 text-left disabled:cursor-not-allowed disabled:opacity-60"
        )}
      >
        <span className={cn("truncate", !selectedNames.length && "text-slate-400")}>
          {triggerLabel}
        </span>
        <span className="flex shrink-0 items-center gap-1">
          {max != null && (
            <span className="text-[11px] tabular-nums text-slate-400">
              {selected.length}/{max}
            </span>
          )}
          {selectedNames.length > 0 && (
            <span
              role="button"
              tabIndex={-1}
              aria-label="清空选择"
              className="rounded p-0.5 text-slate-400 hover:bg-slate-200 hover:text-slate-700 dark:hover:bg-slate-700 dark:hover:text-slate-200"
              onClick={(e) => {
                e.stopPropagation()
                onChange([])
              }}
            >
              <BiX className="h-3.5 w-3.5" />
            </span>
          )}
          <svg
            viewBox="0 0 20 20"
            className={cn("h-4 w-4 shrink-0 text-slate-400 transition-transform", open && "rotate-180")}
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="m5 7.5 5 5 5-5" />
          </svg>
        </span>
      </button>

      {open &&
        createPortal(
          <div
            ref={panelRef}
            style={{ top: position.top, left: position.left, width: position.width }}
            className="rise-in fixed z-[99999] max-h-72 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xl dark:border-slate-700 dark:bg-slate-900"
          >
            {options.length > SEARCH_THRESHOLD && (
              <div className="border-b border-slate-100 p-2 dark:border-slate-800">
                <div className="relative">
                  <BiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
                  <input
                    autoFocus
                    value={keyword}
                    onChange={(e) => setKeyword(e.target.value)}
                    placeholder="搜索"
                    className="h-8 w-full rounded-lg border border-slate-200 bg-slate-50 pl-8 pr-2 text-xs outline-none focus:border-emerald-500 dark:border-slate-700 dark:bg-slate-950"
                  />
                </div>
              </div>
            )}
            <div role="listbox" aria-multiselectable className="max-h-56 overflow-y-auto p-1.5">
              {visible.length === 0 ? (
                <p className="px-3 py-6 text-center text-xs text-slate-400">没有匹配的选项</p>
              ) : (
                visible.map((option) => {
                  const checked = selected.includes(option.code)
                  const blocked = !checked && atLimit
                  return (
                    <button
                      key={option.id ?? option.code}
                      type="button"
                      role="option"
                      aria-selected={checked}
                      disabled={blocked}
                      onClick={() => toggle(option.code)}
                      className={cn(
                        "flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm transition-colors",
                        blocked
                          ? "cursor-not-allowed text-slate-400 opacity-60"
                          : "text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800"
                      )}
                    >
                      <span
                        className={cn(
                          "flex h-4 w-4 shrink-0 items-center justify-center rounded border transition-colors",
                          checked
                            ? "border-[hsl(var(--brand))] bg-[hsl(var(--brand))] text-white"
                            : "border-slate-300 bg-white dark:border-slate-600 dark:bg-slate-950"
                        )}
                      >
                        {checked && <BiCheck className="h-3 w-3" />}
                      </span>
                      <span className="truncate">{option.name}</span>
                    </button>
                  )
                })
              )}
            </div>
          </div>,
          document.body
        )}
    </div>
  )
}