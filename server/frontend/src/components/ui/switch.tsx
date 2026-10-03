import * as React from "react"
import { cn } from "cn"
import { Switch as SwitchPrimitive } from "radix-ui"

/**
 * Soft Material switch (ADR 0053): a 52 x 32 pill track, the thumb growing
 * when on. Radix supplies the `role="switch"`, `aria-checked` and keyboard
 * behaviour; the 44 px hit area is the `after` pseudo, as on the checkbox.
 */
function Switch({
  className,
  ...props
}: React.ComponentProps<typeof SwitchPrimitive.Root>) {
  return (
    <SwitchPrimitive.Root
      data-slot="switch"
      className={cn(
        "peer relative inline-flex h-8 w-13 shrink-0 items-center rounded-full border-2 border-outline bg-surface-3 transition-colors outline-none after:absolute after:-inset-1.5 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:border-primary data-[state=checked]:bg-primary",
        className
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb
        data-slot="switch-thumb"
        className="pointer-events-none block size-4 translate-x-1.5 rounded-full bg-outline transition-all data-[state=checked]:size-6 data-[state=checked]:translate-x-6 data-[state=checked]:bg-primary-foreground"
      />
    </SwitchPrimitive.Root>
  )
}

export { Switch }
