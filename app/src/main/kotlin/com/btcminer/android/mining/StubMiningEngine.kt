package com.btcminer.android.mining

import com.btcminer.android.config.MiningConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Placeholder mining engine. Does not connect to a pool or hash.
 * Replace with cgminer-based implementation (Phase 3.2).
 */
class StubMiningEngine : MiningEngine {

    private val running = AtomicBoolean(false)
    private val configRef = AtomicReference<MiningConfig?>(null)
    private val statusRef = AtomicReference(MiningStatus(MiningStatus.State.Idle))
    private var worker: Thread? = null

    override fun start(config: MiningConfig) {
        if (running.getAndSet(true)) return
        configRef.set(config)
        statusRef.set(MiningStatus(MiningStatus.State.Connecting))
        worker = Thread {
            try {
                statusRef.set(MiningStatus(MiningStatus.State.Mining, hashrateHs = 0.0))
                while (running.get()) {
                    Thread.sleep(2000)
                }
            } finally {
                statusRef.set(MiningStatus(MiningStatus.State.Idle))
                running.set(false)
                configRef.set(null)
            }
        }.apply { isDaemon = true; start() }
    }

    override fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
        statusRef.set(MiningStatus(MiningStatus.State.Idle))
        configRef.set(null)
    }

    override fun isRunning(): Boolean = running.get()

    override fun getStatus(): MiningStatus = statusRef.get()
}
