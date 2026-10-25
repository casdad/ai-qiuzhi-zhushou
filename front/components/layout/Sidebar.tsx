'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useEffect, useState } from 'react'
import { BiBriefcase, BiBrain, BiMoon, BiSun, BiCog, BiChevronRight } from 'react-icons/bi'
import { useTheme } from 'next-themes'
import { API_BASE } from '@/lib/api'

const navigation = [
  { label: '工作区', items: [
    { href: '/env-config', icon: BiCog, label: '服务与通知' },
    { href: '/ai-config', icon: BiBrain, label: 'AI 画像与规则' },
  ] },
  { label: '招聘平台', items: [
    { href: '/boss', icon: BiBriefcase, label: 'Boss 直聘' },
    { href: '/liepin', icon: BiBriefcase, label: '猎聘' },
    { href: '/51job', icon: BiBriefcase, label: '前程无忧' },
    { href: '/zhilian', icon: BiBriefcase, label: '智联招聘' },
  ] },
]

type Health = 'up' | 'degraded' | 'down' | 'unknown'

export default function Sidebar() {
  const pathname = usePathname()
  const { resolvedTheme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  const [health, setHealth] = useState<Health>('unknown')

  useEffect(() => {
    setMounted(true)
    let disposed = false
    let controller: AbortController | undefined
    let pending = false
    const check = async () => {
      if (pending) return
      pending = true
      controller = new AbortController()
      const timeout = setTimeout(() => controller?.abort(), 3000)
      try {
        let response = await fetch(`${API_BASE}/api/health`, { signal: controller.signal })
        if (response.status === 404) response = await fetch(`${API_BASE}/actuator/health`, { signal: controller.signal })
        if (!response.ok) throw new Error('服务不可用')
        const data = await response.json()
        const status = String(data.status || data.state || '').toUpperCase()
        if (!disposed) setHealth(['UP', 'HEALTHY'].includes(status) ? 'up' : ['DEGRADED', 'WARN'].includes(status) ? 'degraded' : 'down')
      } catch {
        if (!disposed) setHealth('down')
      } finally {
        clearTimeout(timeout)
        pending = false
      }
    }
    void check()
    const interval = setInterval(() => void check(), 30_000)
    return () => { disposed = true; controller?.abort(); clearInterval(interval) }
  }, [])

  const healthLabel = { up: '服务已连接', degraded: '服务降级', down: '服务未连接', unknown: '正在连接服务' }[health]

  return (
    <aside className="z-40 flex w-full shrink-0 flex-col border-b border-slate-200 bg-white lg:fixed lg:inset-y-0 lg:left-0 lg:w-60 lg:border-b-0 lg:border-r dark:border-slate-800 dark:bg-slate-950">
      <div className="flex items-center justify-between gap-3 px-5 py-5 lg:px-6 lg:py-8">
        <Link href="/env-config" className="flex items-center gap-3 rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">
          <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-600 text-white shadow-sm"><BiBriefcase size={23} /></span>
          <span><span className="block text-lg font-bold tracking-tight">Get Jobs<span className="text-emerald-600">.</span></span><span className="block text-[11px] tracking-wider text-slate-400">求职工作台</span></span>
        </Link>
        {mounted && <button type="button" onClick={() => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark')} aria-label={resolvedTheme === 'dark' ? '切换到浅色' : '切换到深色'} className="rounded-lg p-2 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-white">{resolvedTheme === 'dark' ? <BiSun size={19} /> : <BiMoon size={19} />}</button>}
      </div>
      <nav aria-label="主导航" className="flex gap-5 overflow-x-auto px-4 pb-4 lg:flex-1 lg:flex-col lg:gap-7 lg:overflow-y-auto lg:px-3">
        {navigation.map((group) => (
          <div key={group.label} className="shrink-0">
            <p className="mb-2 hidden px-3 text-[11px] font-medium tracking-widest text-slate-400 lg:block">{group.label}</p>
            <div className="flex gap-1 lg:flex-col">
              {group.items.map(({ href, icon: Icon, label }) => {
                const active = pathname === href || pathname.startsWith(`${href}/`)
                return <Link key={href} href={href} aria-current={active ? 'page' : undefined} className={`flex items-center gap-3 whitespace-nowrap rounded-lg px-3 py-2.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${active ? 'bg-emerald-50 font-semibold text-emerald-700 dark:bg-emerald-950/60 dark:text-emerald-300' : 'text-slate-500 hover:bg-slate-50 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-900 dark:hover:text-white'}`}><Icon size={19} /><span>{label}</span>{active && <BiChevronRight className="ml-auto hidden lg:block" size={17} />}</Link>
              })}
            </div>
          </div>
        ))}
      </nav>
      <div className="hidden border-t border-slate-100 px-6 py-5 lg:block dark:border-slate-800">
        <div className="flex items-center gap-2 text-xs text-slate-500" role="status">
          <span className={`h-1.5 w-1.5 rounded-full ${health === 'up' ? 'bg-emerald-500' : health === 'degraded' ? 'bg-amber-500' : health === 'down' ? 'bg-rose-500' : 'bg-slate-400'}`} />
          {healthLabel}
        </div>
      </div>
    </aside>
  )
}
