export type DeliveryMode = "AUTO" | "CONFIRM"

export const BOSS_DELIVERY_INTERVAL_SECONDS = 60
export const BOSS_DELIVERY_INTERVAL_MAX_SECONDS = 120
// Allow the longest random interval plus loading, AI generation and receipt checks.
export const BOSS_DELIVERY_TIMEOUT_MS = (BOSS_DELIVERY_INTERVAL_MAX_SECONDS + 120) * 1000

export type DeliveryStatus =
  | "PENDING"
  | "IN_FLIGHT"
  | "SENT"
  | "UNKNOWN"
  | "FAILED"
  | "SKIPPED"
  | "RESTRICTED"
  | "DISMISSED"

export type BossDelivery = {
  id: string
  jobName: string
  companyName: string
  jobUrl: string
  message: string
  sendImgResume: boolean
  status: DeliveryStatus
  reason: string
  createdAt: string
  updatedAt: string
}

export const DELIVERY_STATUS_LABEL: Record<DeliveryStatus, string> = {
  PENDING: "待确认",
  IN_FLIGHT: "发送处理中",
  SENT: "招呼语已确认发送",
  UNKNOWN: "结果不确定 · 不可重发",
  FAILED: "未发送成功",
  SKIPPED: "未执行发送 · 已处理",
  RESTRICTED: "已暂停 · 需人工处理",
  DISMISSED: "已跳过",
}

export function normalizeDeliveryMode(value: unknown): DeliveryMode {
  return value === "AUTO" ? "AUTO" : "CONFIRM"
}

export function isDeliveryStatus(value: unknown): value is DeliveryStatus {
  return typeof value === "string" && Object.hasOwn(DELIVERY_STATUS_LABEL, value)
}

export function normalizeDelivery(value: unknown): BossDelivery | null {
  if (!value || typeof value !== "object") return null
  const row = value as Record<string, unknown>
  if (typeof row.id !== "string" && typeof row.id !== "number") return null
  const str = (key: string) => typeof row[key] === "string" ? row[key] as string : ""
  return {
    id: String(row.id),
    jobName: str("jobName"),
    companyName: str("companyName"),
    jobUrl: str("jobUrl"),
    message: str("message"),
    sendImgResume: row.sendImgResume === true || row.sendImgResume === 1,
    status: isDeliveryStatus(row.status) ? row.status : "UNKNOWN",
    reason: str("reason"),
    createdAt: str("createdAt"),
    updatedAt: str("updatedAt"),
  }
}

export function bossJobUrl(value: string): string | undefined {
  try {
    const url = new URL(value)
    return url.protocol === "https:" && (url.hostname === "zhipin.com" || url.hostname.endsWith(".zhipin.com"))
      ? url.href
      : undefined
  } catch {
    return undefined
  }
}
