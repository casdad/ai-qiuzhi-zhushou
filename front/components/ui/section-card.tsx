import type { ReactNode } from "react"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { cn } from "@/lib/utils"

/**
 * 配置表单里的小节卡片。
 *
 * env-config / ai-config / 各平台配置面板都重复了
 * `<Card><CardHeader className="border-b"><CardTitle className="flex gap-2"><Icon/>标题</CardTitle><CardDescription>说明</CardDescription></CardHeader><CardContent className="pt-6">…`
 * 这一整段。收敛到一处，并统一标题字号、内边距和分隔线粗细。
 */
export function SectionCard({
  icon,
  title,
  description,
  actions,
  className,
  contentClassName,
  children,
}: {
  icon?: ReactNode
  title: string
  description?: string
  actions?: ReactNode
  className?: string
  contentClassName?: string
  children: ReactNode
}) {
  return (
    <Card className={cn("overflow-hidden", className)}>
      <CardHeader className="flex-row items-start justify-between gap-4 space-y-0 border-b border-slate-100 pb-4 dark:border-slate-800">
        <div className="min-w-0 space-y-1">
          <CardTitle className="flex items-center gap-2 text-sm font-semibold">
            {icon}
            {title}
          </CardTitle>
          {description && <CardDescription>{description}</CardDescription>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </CardHeader>
      <CardContent className={cn("pt-5", contentClassName)}>{children}</CardContent>
    </Card>
  )
}