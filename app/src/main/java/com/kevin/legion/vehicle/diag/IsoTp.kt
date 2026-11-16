package com.kevin.legion.vehicle.diag

/**
 * ISO 15765-2 reassembly of frames as an ELM327 prints them with headers on. Frames are grouped by
 * sender, so two modules answering at once never interleave. A first frame whose consecutive frames
 * never arrived (the module waited for a flow control that did not reach it) becomes a message with
 * `complete = false` holding what did arrive; it is never padded out or passed off as whole.
 */
object IsoTp {
    data class Frame(val id: Long, val bytes: List<Int>)

    private const val PCI_SINGLE = 0x0
    private const val PCI_FIRST = 0x1
    private const val PCI_CONSECUTIVE = 0x2
    private const val PCI_FLOW = 0x3
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0x0F
    private const val FIRST_FRAME_HEADER = 2
    private const val SEQ_MOD = 16

    private class Pending(val sender: Long, val length: Int, val data: MutableList<Int>, var nextSeq: Int)

    fun assemble(frames: List<Frame>): List<CanMessage> {
        val out = mutableListOf<CanMessage>()
        val open = linkedMapOf<Long, Pending>()
        for (f in frames) {
            if (f.bytes.isEmpty()) continue
            val pci = f.bytes[0]
            when (pci shr NIBBLE) {
                PCI_SINGLE -> {
                    open.remove(f.id)?.let { out += cut(it) }
                    val len = (pci and LOW_NIBBLE).coerceAtMost(f.bytes.size - 1)
                    out += CanMessage(f.id, f.bytes.subList(1, 1 + len), complete = true)
                }
                PCI_FIRST -> {
                    open.remove(f.id)?.let { out += cut(it) }
                    if (f.bytes.size < FIRST_FRAME_HEADER) continue
                    val len = ((pci and LOW_NIBBLE) shl Byte.SIZE_BITS) or f.bytes[1]
                    open[f.id] = Pending(f.id, len, f.bytes.drop(FIRST_FRAME_HEADER).toMutableList(), 1)
                }
                PCI_CONSECUTIVE -> consecutive(open, f, out)
                PCI_FLOW -> Unit
                else -> Unit
            }
        }
        open.values.forEach { out += cut(it) }
        return out
    }

    private fun consecutive(open: MutableMap<Long, Pending>, f: Frame, out: MutableList<CanMessage>) {
        val p = open[f.id] ?: return
        val seq = f.bytes[0] and LOW_NIBBLE
        if (seq != p.nextSeq % SEQ_MOD) {
            // A missing frame: what follows cannot be trusted, so the message ends here, cut off.
            open.remove(f.id)
            out += cut(p)
            return
        }
        p.nextSeq++
        p.data += f.bytes.drop(1)
        if (p.data.size >= p.length) {
            open.remove(f.id)
            out += CanMessage(p.sender, p.data.take(p.length), complete = true)
        }
    }

    private fun cut(p: Pending) = CanMessage(p.sender, p.data.take(p.length), complete = false)
}
