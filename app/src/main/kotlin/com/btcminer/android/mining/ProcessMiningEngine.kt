package com.btcminer.android.mining

import android.content.Context
import com.btcminer.android.config.MiningConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Runs the cgminer binary as a subprocess (Option B). Passes Stratum URL/user/pass
 * and optional -I for intensity; parses stdout for hashrate and shares.
 */
class ProcessMiningEngine(private val context: Context) : MiningEngine {

    private val running = AtomicBoolean(false)
    private val statusRef = AtomicReference(MiningStatus(MiningStatus.State.Idle))
    private var process: Process? = null
    private var readerThread: Thread? = null

    override fun start(config: MiningConfig) {
        if (running.getAndSet(true)) return

        val binary = CgminerBinary.getExecutablePath(context)
        if (binary == null) {
            statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = "cgminer binary not found for this device. Build and add to assets/cgminer/<abi>/cgminer."))
            running.set(false)
            return
        }

        statusRef.set(MiningStatus(MiningStatus.State.Connecting))

        val stratumUrl = "stratum+tcp://${config.stratumUrl.trim()}:${config.stratumPort}"
        val intensity = mapIntensityToCgminer(config.maxIntensityPercent)

        val args = mutableListOf(
            binary.absolutePath,
            "-o", stratumUrl,
            "-u", config.stratumUser.trim(),
            "-p", config.stratumPass,
        )
        if (intensity > 0) {
            args.add("-I")
            args.add(intensity.toString())
        }

        try {
            process = ProcessBuilder(args)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = e.message ?: "Failed to start cgminer"))
            running.set(false)
            return
        }

        readerThread = Thread {
            try {
                process?.inputStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream)).useLines { lines ->
                        for (line in lines) {
                            if (!running.get()) break
                            parseLine(line)
                        }
                    }
                }
            } catch (_: Exception) { }
            running.set(false)
            val exitCode = try { process?.exitValue() ?: -1 } catch (_: Exception) { -1 }
            if (exitCode != 0) {
                val s = statusRef.get()
                if (s.state == MiningStatus.State.Mining || s.state == MiningStatus.State.Connecting) {
                    statusRef.set(MiningStatus(MiningStatus.State.Error, lastError = "cgminer exited with code $exitCode"))
                }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun stop() {
        running.set(false)
        process?.destroyForcibly()
        process = null
        readerThread?.interrupt()
        readerThread = null
        statusRef.set(MiningStatus(MiningStatus.State.Idle))
    }

    override fun isRunning(): Boolean = running.get() && process?.isAlive == true

    override fun getStatus(): MiningStatus = statusRef.get()

    private fun mapIntensityToCgminer(percent: Int): Int {
        val p = percent.coerceIn(MiningConfig.MAX_INTENSITY_MIN, MiningConfig.MAX_INTENSITY_MAX)
        return when {
            p <= 25 -> 1
            p <= 50 -> 5
            p <= 75 -> 10
            else -> 15
        }
    }

    private fun parseLine(line: String) {
        if (HASHRATE_AVG.matcher(line).find() || SHARES.matcher(line).find()) {
            var hashrateHs = 0.0
            var accepted = 0L
            var rejected = 0L

            val avgMatcher = HASHRATE_AVG.matcher(line)
            if (avgMatcher.find()) {
                val value = avgMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                val unit = avgMatcher.group(2)?.uppercase() ?: "H"
                hashrateHs = when (unit) {
                    "K" -> value * 1e3
                    "M" -> value * 1e6
                    "G" -> value * 1e9
                    else -> value
                }
            }

            val aMatcher = A_SHARES.matcher(line)
            if (aMatcher.find()) accepted = aMatcher.group(1)?.toLongOrNull() ?: 0L
            val rMatcher = R_SHARES.matcher(line)
            if (rMatcher.find()) rejected = rMatcher.group(1)?.toLongOrNull() ?: 0L

            val current = statusRef.get()
            statusRef.set(MiningStatus(
                state = MiningStatus.State.Mining,
                hashrateHs = if (hashrateHs > 0) hashrateHs else current.hashrateHs,
                noncesScanned = 0L,
                acceptedShares = if (accepted > 0) accepted else current.acceptedShares,
                rejectedShares = if (rejected > 0) rejected else current.rejectedShares,
            ))
        }
    }

    companion object {
        private val HASHRATE_AVG = Pattern.compile("\\(avg\\):\\s*([\\d.]+)\\s*([KMG]?)h/s", Pattern.CASE_INSENSITIVE)
        private val SHARES = Pattern.compile("\\|\\s*A:\\s*\\d+\\s+R:\\s*\\d+")
        private val A_SHARES = Pattern.compile("A:\\s*(\\d+)")
        private val R_SHARES = Pattern.compile("R:\\s*(\\d+)")
    }
}
