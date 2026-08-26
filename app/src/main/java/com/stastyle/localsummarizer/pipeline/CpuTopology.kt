package com.stastyle.localsummarizer.pipeline

import android.util.Log
import java.io.File

/**
 * How many threads inference should use on a big.LITTLE phone.
 *
 * ggml splits each matrix multiply evenly across its threads and waits for
 * all of them, so a thread scheduled onto a little core holds up every other
 * thread for the whole operation. On a phone whose little cores run at
 * roughly a third of the big ones, adding them makes the work slower, not
 * faster — the opposite of the desktop intuition that more cores is better.
 *
 * So count the cores in the fastest frequency tier rather than counting cores.
 */
object CpuTopology {

    private const val TAG = "CpuTopology"

    /** Cores whose maximum frequency is within [TIER_TOLERANCE] of the fastest. */
    private const val TIER_TOLERANCE = 0.85

    val inferenceThreads: Int by lazy { computeInferenceThreads() }

    private fun computeInferenceThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val frequencies = readMaxFrequencies(cores)
        if (frequencies.size < 2) {
            // No usable topology: leave a couple of cores for the system.
            return (cores - 2).coerceIn(2, 8)
        }
        val fastest = frequencies.max()
        val bigCores = frequencies.count { it >= fastest * TIER_TOLERANCE }
        Log.i(TAG, "$cores cores, ${frequencies.distinct().sorted()} kHz tiers, $bigCores fast")
        // At least two, and never every fast core — the UI thread and the
        // audio pipeline still need somewhere to run.
        return bigCores.coerceIn(2, 8)
    }

    private fun readMaxFrequencies(cores: Int): List<Long> = buildList {
        for (cpu in 0 until cores) {
            val value = runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLong()
            }.getOrNull()
            if (value != null && value > 0) add(value)
        }
    }
}
