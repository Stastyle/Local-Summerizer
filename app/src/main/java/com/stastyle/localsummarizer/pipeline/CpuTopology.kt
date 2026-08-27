package com.stastyle.localsummarizer.pipeline

import android.util.Log
import java.io.File

/**
 * How many threads inference should use, decided against the cores the process
 * is allowed to run on *right now*.
 *
 * Two things make this harder than counting cores.
 *
 * ggml splits every matrix multiply evenly and waits for all its threads, so
 * one thread on a slow core holds up the whole operation, and asking for more
 * threads than there are cores available makes it strictly worse — the threads
 * take turns, and the barrier waits for the last of them.
 *
 * And the set of available cores is not fixed. Android moves an app between
 * cpusets as it goes to and from the foreground; a backgrounded app is
 * commonly confined to the little cluster. That is why nothing here is cached:
 * the answer at process start is not the answer during a run.
 */
object CpuTopology {

    private const val TAG = "CpuTopology"

    /**
     * The cores this process may run on at this moment, from
     * /proc/self/status. Empty when it cannot be read.
     */
    fun allowedCpus(): List<Int> = runCatching {
        val line = File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }
        } ?: return@runCatching emptyList()
        line.substringAfter(':').trim().split(',').flatMap { part ->
            // Ranges look like "0-3"; single cores are just "6".
            val bounds = part.split('-')
            when (bounds.size) {
                1 -> bounds[0].toIntOrNull()?.let(::listOf).orEmpty()
                2 -> {
                    val from = bounds[0].toIntOrNull()
                    val to = bounds[1].toIntOrNull()
                    if (from != null && to != null && to >= from) (from..to).toList() else emptyList()
                }
                else -> emptyList()
            }
        }
    }.getOrDefault(emptyList())

    /**
     * Thread count for a run starting now.
     *
     * Never larger than the number of cores actually available: oversubscribing
     * a restricted cpuset is how six threads came to be slower than two.
     */
    fun inferenceThreads(): Int {
        val allowed = allowedCpus().ifEmpty {
            (0 until Runtime.getRuntime().availableProcessors()).toList()
        }
        if (allowed.size <= 4) {
            // Already a small set — almost certainly the little cluster. Use it
            // all; there is nothing left to hold back for.
            Log.i(TAG, "confined to ${allowed.size} core(s) -> ${allowed.size} thread(s)")
            return allowed.size.coerceAtLeast(1)
        }

        val frequencies = allowed.mapNotNull { cpu -> maxFrequency(cpu)?.let { cpu to it } }
        val floor = (allowed.size / 2).coerceAtLeast(2)
        val ceiling = (allowed.size - 2).coerceIn(floor, 8)
        if (frequencies.size < 2) return ceiling

        // Whole frequency tiers from the fastest down, until half the allowed
        // cores are covered. A single tolerance around the top frequency does
        // not survive a three-cluster phone: on a Galaxy S26 Ultra an 85%
        // window selected only the two prime cores.
        val tiers = frequencies.groupBy { it.second }.toList().sortedByDescending { it.first }
        var selected = 0
        for ((khz, cores) in tiers) {
            selected += cores.size
            Log.i(TAG, "tier ${khz / 1000} MHz x${cores.size} -> $selected core(s)")
            if (selected >= floor) break
        }
        val threads = selected.coerceIn(floor, ceiling)
        Log.i(TAG, "${allowed.size} allowed, ${tiers.size} tier(s) -> $threads thread(s)")
        return threads
    }

    /** One line for the diagnostics report and for the run log. */
    fun describe(): String {
        val allowed = allowedCpus()
        val list = if (allowed.isEmpty()) "unknown" else compact(allowed)
        val tiers = allowed.mapNotNull { maxFrequency(it) }
            .groupingBy { it / 1000 }.eachCount()
            .toList().sortedByDescending { it.first }
            .joinToString(", ") { "${it.first}MHz x${it.second}" }
        return "cores allowed: $list" + if (tiers.isEmpty()) "" else "  ($tiers)"
    }

    private fun maxFrequency(cpu: Int): Long? = runCatching {
        File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
            .readText().trim().toLong().takeIf { it > 0 }
    }.getOrNull()

    /** "0,1,2,3,6" -> "0-3,6", which is how the kernel would have written it. */
    private fun compact(cpus: List<Int>): String {
        val sorted = cpus.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var previous = start
        for (cpu in sorted.drop(1)) {
            if (cpu != previous + 1) {
                parts += if (start == previous) "$start" else "$start-$previous"
                start = cpu
            }
            previous = cpu
        }
        parts += if (start == previous) "$start" else "$start-$previous"
        return parts.joinToString(",")
    }
}
