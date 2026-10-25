"use client"

import { BiBriefcase, BiPlus, BiTrash } from "react-icons/bi"

import type { JobProfile, MultiField } from "@/app/boss/components/useJobProfiles"
import { Button } from "@/components/ui/button"
import { Field } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { MultiSelect } from "@/components/ui/multiselect"
import { SectionCard } from "@/components/ui/section-card"
import { Select } from "@/components/ui/select"

type BossOption = { id: number; type: string; name: string; code: string }
type Options = {
  city: BossOption[]
  jobType: BossOption[]
  industry: BossOption[]
  experience: BossOption[]
  degree: BossOption[]
  scale: BossOption[]
  stage: BossOption[]
  salary: BossOption[]
}

/**
 * 岗位画像 —— Boss 直聘唯一的配置入口。
 *
 * 后端 loadBossConfig() 是「有启用画像就用画像，没有才回退 boss_config」，
 * 所以搜索关键词、城市、行业、薪资这些东西真正生效的地方就是这里。
 * 之前页面下方还并排放着「搜索配置 / 薪资与经验 / 公司要求」三块，
 * 写的是同一批值，两边各存一份还能互相冲突（城市不会双向同步、薪资两套口径会打死）。
 * 现在合并到这一张卡片里。
 */
export function ProfileCard({
  profiles,
  activeId,
  draft,
  options,
  saving,
  message,
  onChange,
  onSelect,
  onCreate,
  onDelete,
}: {
  profiles: JobProfile[]
  activeId?: number
  draft: JobProfile
  options: Options
  saving: boolean
  message: string
  onChange: (patch: Partial<JobProfile>) => void
  onSelect: (profile: JobProfile) => void
  onCreate: () => void
  onDelete: () => void
}) {
  const multi = (
    field: MultiField,
    label: string,
    placeholder: string,
    list: BossOption[]
  ) => (
    <Field label={label} hint="可多选">
      <MultiSelect
        options={list}
        selected={draft[field] ?? []}
        onChange={(next) => onChange({ [field]: next } as Partial<JobProfile>)}
        placeholder={placeholder}
        ariaLabel={label}
      />
    </Field>
  )

  const hasExpectedSalary =
    draft.expectedSalaryMin != null || draft.expectedSalaryMax != null

  return (
    <SectionCard
      icon={<BiBriefcase className="brand-text" />}
      title="岗位画像"
      description="Boss 投递时实际生效的就是这份画像（有启用画像时后端不再读取平台级配置）。"
      actions={
        <Button size="sm" variant="outline" onClick={onCreate}>
          <BiPlus /> 新建画像
        </Button>
      }
    >
      {profiles.length > 0 && (
        <div className="mb-5 flex flex-wrap gap-2">
          {profiles.map((profile) => (
            <Button
              key={profile.id ?? profile.name}
              size="sm"
              variant={profile.id === activeId ? "brand" : "outline"}
              onClick={() => onSelect(profile)}
            >
              {profile.name}
              {profile.type ? <span className="opacity-70">· {profile.type}</span> : null}
              {profile.enabled === false || profile.enabled === 0 ? (
                <span className="opacity-60">· 未启用</span>
              ) : null}
            </Button>
          ))}
        </div>
      )}

      <div className="space-y-6">
        {/* 身份 */}
        <div className="grid gap-5 md:grid-cols-4">
          <Field id="pf-name" label="画像名称">
            <Input
              id="pf-name"
              value={draft.name}
              placeholder="例如：视觉算法秋招"
              onChange={(e) => onChange({ name: e.target.value })}
            />
          </Field>
          <Field id="pf-type" label="画像类型">
            <Select
              id="pf-type"
              value={draft.type || "自定义"}
              onChange={(e) => onChange({ type: e.target.value })}
            >
              <option value="实习">实习</option>
              <option value="秋招">秋招</option>
              <option value="自定义">自定义</option>
            </Select>
          </Field>
          <Field label="工作城市">
            <Select
              value={draft.cityCode ?? ""}
              aria-label="工作城市"
              onChange={(e) => onChange({ cityCode: e.target.value })}
            >
              {/* 后端城市字典自带「不限」(code=0)，有它就不再补空项，否则会出现两个「不限」 */}
              {!options.city.some((c) => c.name === "不限") && <option value="">不限</option>}
              {options.city.map((city) => (
                <option key={city.id} value={city.code}>
                  {city.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="职位类型">
            <Select
              value={draft.jobType ?? ""}
              aria-label="职位类型"
              onChange={(e) => onChange({ jobType: e.target.value })}
            >
              {!options.jobType.some((t) => t.name === "不限") && <option value="">不限</option>}
              {options.jobType.map((job) => (
                <option key={job.id} value={job.code}>
                  {job.name}
                </option>
              ))}
            </Select>
          </Field>
        </div>

        <Field
          id="pf-keywords"
          label="搜索关键词"
          hint="多个关键词用英文逗号分隔；这是 Boss 站内搜索实际使用的关键字。"
        >
          <Input
            id="pf-keywords"
            value={draft.keywords ?? ""}
            placeholder="例如：计算机视觉算法, 深度学习, 目标检测"
            onChange={(e) => onChange({ keywords: e.target.value })}
          />
        </Field>

        {/* 薪资：两个环节，语义不同 */}
        <div className="grid gap-5 md:grid-cols-2">
          {multi("salary", "薪资待遇（Boss 搜索筛选）", "选择薪资待遇", options.salary)}
          <Field label="投递前薪资过滤" hint="与左侧是两步不同的过滤，详见下方说明。">
            <div className="grid grid-cols-3 gap-2">
              <Input
                aria-label="期望薪资下限"
                type="number"
                min={0}
                placeholder="下限"
                value={draft.expectedSalaryMin ?? ""}
                onChange={(e) =>
                  onChange({ expectedSalaryMin: e.target.value === "" ? null : Number(e.target.value) })
                }
              />
              <Input
                aria-label="期望薪资上限"
                type="number"
                min={0}
                placeholder="上限"
                value={draft.expectedSalaryMax ?? ""}
                onChange={(e) =>
                  onChange({ expectedSalaryMax: e.target.value === "" ? null : Number(e.target.value) })
                }
              />
              <Select
                aria-label="期望薪资单位"
                value={draft.salaryUnit ?? "month"}
                onChange={(e) => onChange({ salaryUnit: e.target.value })}
              >
                <option value="month">元/月</option>
                <option value="day">元/天</option>
                <option value="">不限</option>
              </Select>
            </div>
          </Field>
        </div>
        <SalaryExplainer hasExpectedSalary={hasExpectedSalary} />

        {/* 岗位要求 */}
        <div className="grid gap-5 md:grid-cols-3">
          {multi("experience", "工作经验", "选择工作经验", options.experience)}
          {multi("degree", "学历要求", "选择学历要求", options.degree)}
          {multi("industry", "公司行业", "选择公司行业", options.industry)}
          {multi("scale", "公司规模", "选择公司规模", options.scale)}
          {multi("stage", "融资阶段", "选择融资阶段", options.stage)}
          <Field label="HR 活跃过滤" hint="开启后活跃状态含「年」的 HR 会被跳过；数据仍会保存。">
            <Select
              value={String(draft.filterDeadHr ?? 0)}
              aria-label="HR 活跃过滤"
              onChange={(e) => onChange({ filterDeadHr: Number(e.target.value) })}
            >
              <option value="0">关闭</option>
              <option value="1">开启</option>
            </Select>
          </Field>
        </div>

        {/* AI 门禁 */}
        <div className="grid gap-5 md:grid-cols-3">
          <Field label="AI 岗位匹配门禁" hint="开启后低于最低分的岗位会被跳过。">
            <Select
              value={String(draft.enableAiRelevance ?? 0)}
              aria-label="AI 岗位匹配门禁"
              onChange={(e) => onChange({ enableAiRelevance: Number(e.target.value) })}
            >
              <option value="0">关闭（不评分）</option>
              <option value="1">开启（低分跳过）</option>
            </Select>
          </Field>
          <Field id="pf-minscore" label="AI 匹配最低分" hint="0-100">
            <Input
              id="pf-minscore"
              type="number"
              min={0}
              max={100}
              value={draft.minAiScore ?? 75}
              onChange={(e) => onChange({ minAiScore: Number(e.target.value) })}
            />
          </Field>
          <div className="flex flex-col justify-end gap-3">
            <label className="flex cursor-pointer items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
              <input
                type="checkbox"
                className="h-4 w-4 accent-[hsl(var(--brand))]"
                checked={draft.enabled !== false && draft.enabled !== 0}
                onChange={(e) => onChange({ enabled: e.target.checked })}
              />
              启用该画像
            </label>
            <p className="text-xs leading-5 text-slate-500">
              未启用时后端不读取该画像，投递会回退到上一次保存的平台配置。
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4 dark:border-slate-800">
          <p className="text-xs text-slate-500">
            {saving ? "保存中…" : "修改后点击右上角「保存配置」写入"}
            {message ? ` · ${message}` : ""}
          </p>
          {draft.id && (
            <Button
              size="sm"
              variant="ghost"
              onClick={onDelete}
              className="text-rose-600 hover:bg-rose-50 dark:hover:bg-rose-950/40"
            >
              <BiTrash /> 删除画像
            </Button>
          )}
        </div>
      </div>
    </SectionCard>
  )
}

/**
 * 说明两处薪资各自在什么时候生效。
 *
 * 这两个参数以前分别埋在「期望薪资」和「薪资待遇」两个卡片里，语义上看不出区别，
 * 很容易填出「Boss 只返回 10-20K、期望却是 18000-30000」这种互相打死的组合，
 * 结果一条都投不出去且界面毫无提示。
 */
function SalaryExplainer({ hasExpectedSalary }: { hasExpectedSalary: boolean }) {
  return (
    <div className="space-y-1 rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs leading-5 text-slate-500 dark:border-slate-800 dark:bg-slate-900/60 dark:text-slate-400">
      <p>
        <strong className="font-medium text-slate-700 dark:text-slate-200">薪资待遇</strong>
        ：拼进 Boss 搜索链接，由 Boss 服务端在搜索阶段就只返回这些区间的岗位。
      </p>
      <p>
        <strong className="font-medium text-slate-700 dark:text-slate-200">投递前薪资过滤</strong>
        ：抓到岗位后本地解析其薪资，折算成月薪后与该区间求交集；无交集则标记为「已过滤」并跳过投递。
      </p>
      {hasExpectedSalary && (
        <p className="text-amber-700 dark:text-amber-400">
          注意：两者是先后两道过滤。若「薪资待遇」选的区间与投递前过滤区间没有重叠，
          投递结果会为空。
        </p>
      )}
    </div>
  )
}