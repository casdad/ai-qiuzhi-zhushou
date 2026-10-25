import { ReactNode } from "react"

export default function PageHeader({
  icon,
  title,
  subtitle,
  actions,
}: {
  icon: ReactNode
  title: string
  subtitle?: string
  actions?: ReactNode
}) {
  return (
    <header className="mb-6 flex flex-col gap-5 border-b border-slate-200 pb-6 lg:flex-row lg:items-center dark:border-slate-800">
      <div className="flex min-w-0 items-center gap-3">
        <span className="brand-ring flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-white shadow-sm">
          {icon}
        </span>
        <div className="min-w-0">
          <h1 className="text-xl font-semibold tracking-tight text-slate-900 dark:text-white">
            {title}
          </h1>
          {subtitle && (
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{subtitle}</p>
          )}
        </div>
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2 lg:ml-auto">{actions}</div>}
    </header>
  )
}