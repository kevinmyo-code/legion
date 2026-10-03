import * as React from "react"
import { cn } from "cn"

function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        // The tonal "filled" field of ADR 0053: a surface step, rounded on top, with
        // the underline as the border. A pill field (the add-an-item bar) overrides
        // this with `rounded-full border-0`.
        "h-12 w-full min-w-0 rounded-t-control rounded-b-md border-0 border-b-2 border-outline bg-surface-2 px-4 py-1 text-base transition-colors outline-none file:inline-flex file:h-6 file:border-0 file:bg-transparent file:text-sm file:font-medium file:text-foreground placeholder:text-muted-foreground focus-visible:border-primary focus-visible:bg-surface-3 disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive",
        className
      )}
      {...props}
    />
  )
}

export { Input }
