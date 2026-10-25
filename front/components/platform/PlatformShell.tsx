"use client"

import { useEffect, useRef, useState, type ReactNode } from "react"
import { BiBriefcase, BiLogOut, BiPlay, BiSave, BiStop } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { Dialog } from "@/components/ui/dialog"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  PLATFORM_LABEL,
  usePlatformSession,
  type PlatformKey,
} from "@/components/platform/usePlatformSession"
import { cn } from "@/lib/utils"
import { PlatformDeliveryPanel } from "@/components/platform/PlatformDeliveryPanel"

/**
 * 招聘平台页外壳。
 *
 * 四个平台页结构完全相同，只是接口路径、字典和文案不同，此前各自复制了：
 *   登录态三态按钮（检查中/未登录/投递中/空闲）、退出确认框、
 *   退出结果框、保存结果框、PageHeader + Tabs 的排版。
 * 合起来是约 600 行重复 JSX，而且四个版本略有差异（例如 Boss 的保存失败
 * 会弹框，猎聘/51job/智联不弹）。
 *
 * 现在统一由这里提供：页面只负责"配置表单"和"投递分析"两块内容。
 */
export function PlatformShell({
  platform,
  session,
  subtitle,
  onSave,
  savingLabel = "保存中…",
  startLabel = "开始投递",
  startDisabled = false,
  startDisabledReason,
  children,
  analytics,
}: {
  platform: PlatformKey
  session: ReturnType<typeof usePlatformSession>
  subtitle: string
  /** 保存配置。返回 false 时不弹成功提示，由页面自行处理错误提示。 */
  onSave: () => Promise<boolean> | boolean
  savingLabel?: string
  startLabel?: string
  startDisabled?: boolean
  startDisabledReason?: string
  /** 平台配置面板。 */
  children: ReactNode
  /** 投递分析面板。 */
  analytics: ReactNode
}) {
  const [saving, setSaving] = useState(false)
  const [confirmLogout, setConfirmLogout] = useState(false)
  const [logoutBusy, setLogoutBusy] = useState(false)
  const saveBusy = useRef(false)

  const label = PLATFORM_LABEL[platform]

  // 平台切换时把 <body> 换成对应主题色，页面里的 brand-* 类随之改变。
  useEffect(() => {
    const previous = document.body.className
    const base = previous
      .split(" ")
      .filter((c) => c && !c.startsWith("platform-"))
      .join(" ")
    document.body.className = `${base} platform-${platform}`.trim()
    return () => {
      document.body.className = previous
    }
  }, [platform])

  const handleSave = async () => {
    if (saveBusy.current) return
    saveBusy.current = true
    setSaving(true)
    try {
      const ok = await onSave()
      if (ok !== false) session.notify("success", "配置已保存。")
    } catch (error) {
      session.notify("danger", error instanceof Error ? error.message : "配置保存失败，请检查后端服务。")
    } finally {
      setSaving(false)
      saveBusy.current = false
    }
  }

  const handleLogout = async () => {
    setLogoutBusy(true)
    try {
      await session.logout()
    } finally {
      setLogoutBusy(false)
      setConfirmLogout(false)
    }
  }

  const deliveryButton = session.isStopping ? (
    <Button size="sm" disabled><BiStop /> 正在停止，请等待…</Button>
  ) : session.isDelivering ? (
    <Button size="sm" variant="destructive" onClick={() => void session.stop()}>
      <BiStop /> 停止投递
    </Button>
  ) : session.checking ? (
    <Button size="sm" disabled>
      <BiPlay /> 检查登录中…
    </Button>
  ) : !session.isLoggedIn ? (
    <Button size="sm" disabled>
      <BiPlay /> 请先在{label}完成登录
    </Button>
  ) : (
    <Button size="sm" variant="brand" disabled={startDisabled || saving || !session.taskStatusKnown || !!session.safetyHoldReason} title={session.safetyHoldReason || (startDisabled ? startDisabledReason : !session.taskStatusKnown ? "任务状态未知，请稍后或重新聚焦页面读取状态。" : undefined)} onClick={() => void session.start()}>
      <BiPlay /> {platform === "boss" ? startLabel : "开始任务"}
    </Button>
  )

  return (
    <div className="space-y-6">
      <header className="brand-bar -mx-4 -mt-6 border-b border-slate-200 px-4 pb-6 pt-6 sm:-mx-6 sm:px-6 lg:-mx-10 lg:px-10 lg:pt-9 dark:border-slate-800">
        <div className="flex flex-col gap-5 lg:flex-row lg:items-center">
          <div className="flex min-w-0 items-center gap-3">
            <span className="brand-ring flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-white shadow-sm">
              <BiBriefcase className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-xl font-semibold tracking-tight text-slate-900 dark:text-white">
                  {label}
                </h1>
                <LoginPill
                  loading={session.checking}
                  loggedIn={session.isLoggedIn}
                  label={label}
                />
              </div>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{subtitle}</p>
              <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                登录态仅在本次运行有效，重启或关闭浏览器后需重新扫码/认证。
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2 lg:ml-auto">
            {deliveryButton}
            {platform !== "boss" && <Button size="sm" variant="outline" disabled={session.isDelivering || logoutBusy} onClick={() => void session.openLogin()}>打开平台登录页</Button>}
            <Button size="sm" variant="outline" onClick={() => setConfirmLogout(true)}>
              <BiLogOut /> 退出登录
            </Button>
            <Button size="sm" variant="outline" onClick={() => void handleSave()} disabled={saving}>
              <BiSave /> {saving ? savingLabel : "保存配置"}
            </Button>
          </div>
        </div>
      </header>

      <Tabs defaultValue="config" className="w-full">
        <TabsList className="w-full max-w-sm">
          <TabsTrigger className="flex-1" value="config">
            平台配置
          </TabsTrigger>
          <TabsTrigger className="flex-1" value="analytics">
            投递分析
          </TabsTrigger>
        </TabsList>

        <TabsContent value="config" className="mt-5 space-y-5">
          {platform !== "boss" && <PlatformDeliveryPanel platform={platform} session={session} />}
          {children}
        </TabsContent>
        <TabsContent value="analytics" className="mt-5">
          {analytics}
        </TabsContent>
      </Tabs>

      <Dialog
        open={confirmLogout}
        onClose={() => setConfirmLogout(false)}
        tone="danger"
        title="确认退出登录"
        description={`请先停止任务并等待实际退出。退出后将清除${label}的登录态，下次使用需要重新认证；不会清除历史投递记录。`}
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" onClick={() => setConfirmLogout(false)}>
              取消
            </Button>
            <Button variant="destructive" size="sm" disabled={logoutBusy} onClick={() => void handleLogout()}>
              {logoutBusy ? "退出中…" : "确认退出"}
            </Button>
          </div>
        }
      />

      <Dialog
        open={Boolean(session.notice)}
        onClose={session.clearNotice}
        tone={session.notice?.tone ?? "neutral"}
        title={session.notice?.tone === "danger" ? "操作未完成" : "操作成功"}
        description={session.notice?.message}
      />
    </div>
  )
}

/** 登录状态标签：展示后台快照，不用点按钮才知道。 */
function LoginPill({
  loading,
  loggedIn,
  label,
}: {
  loading: boolean
  loggedIn: boolean
  label: string
}) {
  const tone = loading
    ? "bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400"
    : loggedIn
      ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"
      : "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium",
        tone
      )}
    >
      <span
        className={cn(
          "h-1.5 w-1.5 rounded-full",
          loading
            ? "bg-slate-400"
            : loggedIn
              ? "bg-emerald-500"
              : "bg-amber-500"
        )}
      />
      {loading ? "检查登录中" : loggedIn ? "已登录" : `未登录 ${label}`}
    </span>
  )
}
