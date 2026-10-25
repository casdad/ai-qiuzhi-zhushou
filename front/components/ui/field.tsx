"use client"

import * as React from "react"

import { Label } from "@/components/ui/label"
import { cn } from "@/lib/utils"

/**
 * 表单字段外壳：标签 + 控件 + 说明。
 *
 * 之前每个页面都手写一遍 `<div className="space-y-2"><Label/><Input/><p className="text-xs"/></div>`，
 * 十几个字段就是十几份几乎相同的类名串。这里收敛成一处，
 * hint 只在真正传了内容时才渲染出节点，避免留下空的占位行。
 */
export function Field({
  id,
  label,
  hint,
  htmlFor,
  className,
  children,
}: {
  id?: string
  label?: React.ReactNode
  hint?: React.ReactNode
  htmlFor?: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className={cn("space-y-2", className)}>
      {label && <Label htmlFor={htmlFor ?? id}>{label}</Label>}
      {children}
      {hint ? <p className="hint">{hint}</p> : null}
    </div>
  )
}