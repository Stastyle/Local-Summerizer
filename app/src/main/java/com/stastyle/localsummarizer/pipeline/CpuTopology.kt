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

    /**
     * Take whole frequency tiers from the fastest down until at least half the
     * cores are covered, then clamp.
     *
     * A single tolerance around the top frequency does not survive a
     * three-cluster phone: on a Galaxy S26 Ultra the prime cores sit far
     * enough above the performance cores that an 85% window selected only the
     * two prime cores, and two threads made a 77-second clip take 691 seconds.
     * The floor is what stops that; the ceiling still keeps a couple of cores
     * for the UI and the audio pipeline.
     */
    val inferenceThreads: Int by lazy { computeInferenceThreads() }

    private fun computeInferenceThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val floor = (cores / 2).coerceAtLeast(2)
        val ceiling = (cores - 2).coerceIn(floor, 8)

        val frequencies = readMaxFrequencies(cores)
        if (frequencies.size < 2) return ceiling

        // Distinct tiers, fastest first, accumulating cores until the floor.
        val tiers = frequencies.groupingBy { it }.eachCount().toList().sortedByDescending { it.first }
        var selected = 0
        for ((khz, count) in tiers) {
            selected += count
            Log.i(TAG, "tier ${khz / 1000} MHz x$count -> $selected core(s)")
            if (selected >= floor) break
        }
        val threads = selected.coerceIn(floor, ceiling)
        Log.i(TAG, "$cores cores, ${tiers.size} tier(s) -> $threads thread(s)")
        return threads
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
