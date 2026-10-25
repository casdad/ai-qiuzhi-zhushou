import { Card } from "@/components/ui/card"
import { cn } from "@/lib/utils"

type Kpi = { label: string; value: string | number; hint?: string }

/**
 * KPI 汇总条。
 *
 * 四个分析页原本都是 Card + CardHeader + CardTitle + CardDescription 套四层，
 * 结果标题是 14px、值是 16px —— 和它作为"页面最重要数字"的身份不匹配，
 * 视觉上比下面的图表卡片还弱。这里改成标签小、数字大，值默认走品牌色。
 */
export function KpiGrid({ items, loading }: { items: Kpi[]; loading?: boolean }) {
  return (
    <div className="grid grid-cols-2 gap-4 md:grid-cols-4 xl:grid-cols-6">
      {items.map((item) => (
        <Card key={item.label} className="p-4">
          <p className="text-xs font-medium text-slate-500 dark:text-slate-400">{item.label}</p>
          {loading ? (
            <div className="skeleton mt-2 h-8 w-16" />
          ) : (
            <p
              className={cn(
                "mt-1 text-2xl font-semibold tabular-nums tracking-tight",
                item.hint ? "text-slate-500 dark:text-slate-400" : "text-slate-900 dark:text-white"
              )}
              title={item.hint}
            >
              {item.value}
              {item.hint && <span className="ml-1 text-xs font-normal">{item.hint}</span>}
            </p>
          )}
        </Card>
      ))}
    </div>
  )
}