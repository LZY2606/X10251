package jtag.server

import jtag.model.CandidateStatus
import jtag.tap.*

fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

object Svg {

    private val stateRank = TapState.entries.mapIndexed { i, s -> s to i }.toMap()

    /** TAP trajectory as an SVG path: x = step, y = state rank, anomalies highlighted. */
    fun trajectory(traj: TapTrajectory, version: String): String {
        val steps = traj.steps
        if (steps.isEmpty()) return "<p>no trajectory</p>"
        val w = (steps.size * 14 + 120).coerceAtLeast(400)
        val h = 16 * 22 + 60
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" style="background:#0d1117;font:11px monospace">""")
        for (s in TapState.entries) {
            val y = 30 + stateRank[s]!! * 22
            sb.append("""<text x="4" y="${y + 4}" fill="#8b949e" font-size="9">${s.name.take(12)}</text>""")
            sb.append("""<line x1="110" y1="$y" x2="$w" y2="$y" stroke="#21262d" stroke-width="1"/>""")
        }
        var px = 0; var py = 0
        steps.forEachIndexed { i, st ->
            val x = 120 + i * 14
            val y = 30 + stateRank[st.state]!! * 22
            if (i > 0) {
                val segBoundary = traj.segments.any { seg -> seg.steps.firstOrNull()?.seq == st.seq && seg.index > 0 }
                val color = if (segBoundary) "#f85149" else "#30363d"
                sb.append("""<line x1="$px" y1="$py" x2="$x" y2="$y" stroke="$color" stroke-width="1.5"/>""")
            }
            val (fill, r) = when (st.anomaly) {
                EdgeAnomaly.MID_STREAM_RESET -> "#f85149" to 6
                EdgeAnomaly.ILLEGAL_TRANSITION_HINT -> "#d29922" to 6
                EdgeAnomaly.NONE -> (if (st.state.isShift) "#58a6ff" else "#3fb950") to 4
            }
            sb.append("""<a href="/anomaly?version=$version&amp;seq=${st.seq}"><circle cx="$x" cy="$y" r="$r" fill="$fill"><title>seq=${st.seq} ${st.state} tms=${st.tms} tdi=${st.tdi} tdo=${st.tdo}${st.note?.let { " — " + esc(it) } ?: ""}</title></circle></a>""")
            px = x; py = y
        }
        sb.append("""<text x="120" y="14" fill="#8b949e">polarity=${traj.polarity} steps=${steps.size} resets=${traj.resets.size} illegalHints=${traj.illegalHints.size}</text>""")
        sb.append("</svg>")
        return sb.toString()
    }

    /** Device chain as SVG boxes in TDO-output order. */
    fun chain(chain: jtag.tap.ChainAnalysis): String {
        val w = (chain.devices.size * 190 + 160).coerceAtLeast(420)
        val h = 200 + chain.offsetCandidates.size * 18
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" style="background:#0d1117;font:11px monospace">""")
        sb.append("""<text x="10" y="20" fill="#8b949e">TDI</text>""")
        chain.devices.forEachIndexed { i, d ->
            val x = 50 + i * 190
            val y = 40
            val stroke = when {
                d.missing -> "#f85149"
                d.bypass -> "#d29922"
                else -> "#3fb950"
            }
            sb.append("""<rect x="$x" y="$y" width="160" height="80" rx="6" fill="#161b22" stroke="$stroke" stroke-width="2" stroke-dasharray="${if (d.missing) "5,3" else "none"}"/>""")
            sb.append("""<text x="${x + 10}" y="${y + 20}" fill="#c9d1d9" font-size="13">${esc(d.name)}</text>""")
            val line2 = when {
                d.missing -> "MISSING (offsets kept)"
                d.bypass -> "BYPASS (1-bit DR)"
                else -> "IDCODE ${d.idcode}"
            }
            sb.append("""<text x="${x + 10}" y="${y + 40}" fill="$stroke">${esc(line2)}</text>""")
            sb.append("""<text x="${x + 10}" y="${y + 58}" fill="#8b949e">bsdl=${esc(d.bsdlRef ?: "-")}</text>""")
            if (i < chain.devices.size - 1)
                sb.append("""<line x1="${x + 160}" y1="${y + 40}" x2="${x + 190}" y2="${y + 40}" stroke="#8b949e" stroke-width="2"/>""")
        }
        val lastX = 50 + chain.devices.size * 190 - 30
        sb.append("""<text x="$lastX" y="20" fill="#8b949e">TDO</text>""")
        sb.append("""<text x="10" y="150" fill="#8b949e">idcodes=${chain.idcodes.joinToString(",")} bypass=${chain.bypassCount}</text>""")
        chain.offsetCandidates.forEachIndexed { i, oc ->
            val y = 175 + i * 18
            val color = if (oc.status == CandidateStatus.ACCEPTED) "#3fb950" else "#d29922"
            sb.append("""<text x="10" y="$y" fill="$color">offset-candidate ${oc.id.take(8)}: '${esc(oc.missingDevice)}' @ pos ${oc.insertPosition} [${oc.status}]</text>""")
        }
        sb.append("</svg>")
        return sb.toString()
    }
}
