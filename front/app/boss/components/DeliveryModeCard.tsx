"use client"

import { BiSend } from "react-icons/bi"

import { Field } from "@/components/ui/field"
import { SectionCard } from "@/components/ui/section-card"
import { Select } from "@/components/ui/select"
import { BOSS_DELIVERY_INTERVAL_SECONDS, BOSS_DELIVERY_INTERVAL_MAX_SECONDS, normalizeDeliveryMode, type DeliveryMode } from "@/lib/boss-delivery"

export function DeliveryModeCard({
  value,
  savedMode,
  saving,
  onChange,
}: {
  value: DeliveryMode
  savedMode: DeliveryMode | null
  saving: boolean
  onChange: (mode: DeliveryMode) => void
}) {
  const dirty = value !== savedMode
  return (
    <SectionCard
      icon={<BiSend className="brand-text" />}
      title="发送模式"
      description="所有岗位画像及单岗操作共享这一全局设置；修改后点击右上角「保存配置」生效。"
    >
      <div className="space-y-3">
        <Field id="boss-delivery-mode" label="发送方式">
          <Select
            id="boss-delivery-mode"
            value={value}
            disabled={saving}
            onChange={(event) => onChange(normalizeDeliveryMode(event.target.value))}
          >
            <option value="CONFIRM">确认并发送（推荐）</option>
            <option value="AUTO">自动投递</option>
          </Select>
        </Field>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          当前已保存模式：{savedMode === null ? "未能读取，暂不可启动" : savedMode === "AUTO" ? "自动投递" : "确认并发送"}
          {dirty && savedMode !== null && <span className="ml-2 text-amber-700 dark:text-amber-400">有未保存修改，保存后再启动新任务。</span>}
        </p>
        <p className="text-xs leading-5 text-slate-500 dark:text-slate-400">
          保存配置后，先进入对话并填好文字，再随机选择 {BOSS_DELIVERY_INTERVAL_SECONDS}-{BOSS_DELIVERY_INTERVAL_MAX_SECONDS} 秒（含端点），在对话页面完整等待后才点击发送，第一条也等待；搜索、滚动和查看详情另按浏览间隔执行。
        </p>
        <p className="text-xs leading-5 text-slate-500 dark:text-slate-400">
          确认模式只生成待确认草稿，不自动发送；请逐条核对岗位和招呼语后确认。
          自动模式会直接执行发送，仍受平台规则、额度和授权边界限制，并不保证不会被限制。
          遇验证码、登录失效或平台限制，应暂停并由本人处理，不绕过验证。
        </p>
      </div>
    </SectionCard>
  )
}
