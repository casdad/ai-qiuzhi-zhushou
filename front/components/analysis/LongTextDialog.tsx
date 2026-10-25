"use client"

import { useState } from "react"
import { BiCheck, BiCopy } from "react-icons/bi"

import { Button } from "@/components/ui/button"
import { Dialog } from "@/components/ui/dialog"

/**
 * 长文本查看框。
 *
 * 只有 Boss 页实际在用：列表里公司/岗位/HR 等十几个单元格都支持点击看全文。
 * 原先的实现是 24 行内联 modal + alert 提示复制；这里改成统一的 Dialog，
 * 复制成功在按钮上做确认态（原先弹 alert 会被浏览器拦截，且体验差）。
 */
export function LongTextDialog({
  title,
  content,
  onClose,
}: {
  title: string
  content: string
  onClose: () => void
}) {
  const [copied, setCopied] = useState(false)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(content)
    } catch {
      const ta = document.createElement("textarea")
      ta.value = content
      document.body.appendChild(ta)
      ta.select()
      document.execCommand("copy")
      document.body.removeChild(ta)
    }
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title={title}
      actions={
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={copy}>
            {copied ? <BiCheck className="mr-1" /> : <BiCopy className="mr-1" />}
            {copied ? "已复制" : "复制"}
          </Button>
          <Button variant="brand" size="sm" onClick={onClose}>
            关闭
          </Button>
        </div>
      }
    >
      <div className="mt-4 max-h-[50vh] overflow-y-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm leading-6 text-slate-700 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-300">
        {content || "—"}
      </div>
    </Dialog>
  )
}
