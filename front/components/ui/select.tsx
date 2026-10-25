import * as React from "react"
import { cn } from "@/lib/utils"

export interface SelectProps {
  value?: string
  onChange?: (e: { target: { value: string } }) => void
  placeholder?: string
  className?: string
  id?: string
  name?: string
  disabled?: boolean
  "aria-label"?: string
  children?: React.ReactNode
}

const Select = React.forwardRef<HTMLDivElement, SelectProps>(
  ({ className, children, value, onChange, placeholder, disabled, id, name, ...rest }, ref) => (
    <div ref={ref} className="min-w-0">
      <select
        id={id}
        name={name}
        value={value ?? ''}
        onChange={onChange}
        disabled={disabled}
        aria-label={rest["aria-label"] ?? id ?? placeholder ?? '选择选项'}
        className={cn("field-base cursor-pointer pr-8", className)}
      >
        {placeholder && <option value="">{placeholder}</option>}
        {children}
      </select>
    </div>
  )
)
Select.displayName = "Select"

export { Select }