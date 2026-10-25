"use client"

import * as React from "react"
import { createPortal } from "react-dom"
import { BiCheckCircle, BiErrorCircle, BiInfoCircle } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

type Tone = "neutral" | "success" | "danger"

const TONE_ICON = {
  neutral: { Icon: BiInfoCircle, className: "text-slate-500" },
  success: { Icon: BiCheckCircle, className: "text-emerald-600" },
  danger: { Icon: BiErrorCircle, className: "text-rose-600" },
} as const

/**
 * 全站唯一的模态框。
 *
 * 四个招聘平台页各自复制了一份退出确认框、退出结果框和保存结果框，
 * 三种结构完全一样，只有文案和按钮色不同。现在统一到这里：
 * 通过 tone 切换图标与主按钮语义色，actions 覆盖底部按钮。
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  tone = "neutral",
  actions,
  children,
}: {
  open: boolean
  onClose: () => void
  title: React.ReactNode
  description?: React.ReactNode
  tone?: Tone
  actions?: React.ReactNode
  children?: React.ReactNode
}) {
  const [mounted, setMounted] = React.useState(false)
  React.useEffect(() => setMounted(true), [])

  React.useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose()
    }
    document.addEventListener("keydown", onKey)
    const previous = document.body.style.overflow
    document.body.style.overflow = "hidden"
    return () => {
      document.removeEventListener("keydown", onKey)
      document.body.style.overflow = previous
    }
  }, [open, onClose])

  if (!mounted || !open) return null

  const { Icon, className: iconClass } = TONE_ICON[tone]

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/45 p-4 backdrop-blur-[2px]"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        className="rise-in w-full max-w-sm overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl dark:border-slate-800 dark:bg-slate-900"
      >
        <div className="p-6">
          <div className="flex items-start gap-3">
            <Icon className={cn("mt-0.5 h-5 w-5 shrink-0", iconClass)} />
            <div className="min-w-0">
              <h2 className="text-base font-semibold text-slate-900 dark:text-white">{title}</h2>
              {description && (
                <p className="mt-2 text-sm leading-6 text-slate-600 dark:text-slate-400">{description}</p>
              )}
            </div>
          </div>
          {children}
        </div>
        <div className="flex items-center justify-end gap-2 border-t border-slate-100 bg-slate-50/70 px-6 py-4 dark:border-slate-800 dark:bg-slate-950/40">
          {actions ?? (
            <Button
              variant={tone === "danger" ? "destructive" : "brand"}
              size="sm"
              onClick={onClose}
            >
              知道了
            </Button>
          )}
        </div>
      </div>
    </div>,
    document.body
  )
}