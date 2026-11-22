import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'

import { useAssistant } from '@/assistant/assistant-context'
import { CascadeClient } from '@/assistant/cascade-client'
import { SHOP_STATE_KEY, setScenario } from '@/lib/store-rep'

/**
 * One way to open a conversation as the store rep or as the guide, for the section's button and
 * for the orb (Kevin, 2026-11-22: in the store-rep section the orb is the rep). In the tap: the
 * scenario switch goes to the engine and a fresh call opens at once (an open conversation ends
 * first, so the right persona greets); the greeting waits for the switch, and a tap or close
 * meanwhile cancels it (`CascadeClient.startVoice`). Only on the engine's own voice: the Live
 * fallback does not play the rep, so `cascade` is null there.
 */
export function useShopVoice() {
  const { client, state } = useAssistant()
  const queryClient = useQueryClient()
  const busy = useRef(false)
  const [switching, setSwitching] = useState(false)
  const cascade = client instanceof CascadeClient ? client : null

  function startAs(rep: boolean) {
    if (!cascade || busy.current) return
    busy.current = true
    if (state.phase !== 'idle') cascade.reset()
    // Busy until the switch lands: a second tap would cut the hello and switch twice.
    setSwitching(true)
    const switched = setScenario(rep)
      .then((next) => {
        if (next) queryClient.setQueryData(SHOP_STATE_KEY, next)
      })
      .finally(() => {
        busy.current = false
        setSwitching(false)
      })
    void cascade.startVoice(switched)
  }

  return { cascade, startAs, switching }
}
