package com.stastyle.localsummarizer.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.stastyle.localsummarizer.pipeline.CpuTopology

/**
 * Samples the two things that can make the same work take four times longer
 * on a phone, and that a report taken afterwards cannot recover.
 *
 * Android can move an app to a smaller set of cores when it leaves the
 * foreground, and it throttles the whole SoC when it gets hot. Both are
 * transient: by the time anyone opens the diagnostics screen the app is in
 * the foreground again and the phone has cooled, and both read as normal.
 *
 * So this is sampled from inside the inference callbacks — the points that
 * only fire while the work is actually running — and kept until the run ends.
 */
object RunTelemetry {

    @Volatile
    private var cpuSets: MutableSet<String> = linkedSetOf()

    @Volatile
    private var worstThermal: Int = -1

    @Volatile
    private var samples: Int = 0

    fun reset() {
        cpuSets = linkedSetOf()
        worstThermal = -1
        samples = 0
    }

    /**
     * Cheap enough to call from a per-token callback: two small reads, and
     * only when something changed does it allocate.
     */
    fun sample(context: Context) {
        samples++
        runCatching {
            val cpus = CpuTopology.allowedCpus()
            if (cpus.isNotEmpty()) cpuSets.add(CpuTopology.compact(cpus))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val status = context.getSystemService(PowerManager::class.java)
                    ?.currentThermalStatus ?: return@runCatching
                if (status > worstThermal) worstThermal = status
            }
        }
    }

    /** Empty when nothing was sampled, so callers can leave it out entirely. */
    fun summary(): String {
        if (samples == 0) return ""
        val cores = when {
            cpuSets.isEmpty() -> "cores unknown"
            cpuSets.size == 1 -> "cores ${cpuSets.first()} throughout"
            // More than one set means Android moved the app mid-run, which is
            // the finding, not a detail.
            else -> "CORES CHANGED: ${cpuSets.joinToString(" -> ")}"
        }
        val thermal = when (worstThermal) {
            -1 -> "thermal unknown"
            PowerManager.THERMAL_STATUS_NONE -> "thermal none"
            PowerManager.THERMAL_STATUS_LIGHT -> "thermal light"
            PowerManager.THERMAL_STATUS_MODERATE -> "thermal moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "THERMAL SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "THERMAL CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "THERMAL EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "THERMAL SHUTDOWN"
            else -> "thermal $worstThermal"
        }
        return "$cores, $thermal peak, $samples samples"
    }
}
