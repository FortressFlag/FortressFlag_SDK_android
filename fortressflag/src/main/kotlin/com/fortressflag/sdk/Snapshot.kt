package com.fortressflag.sdk

/**
 * Everything a synchronous read needs, in one immutable value. A single reference updated
 * atomically rather than several independently-updated fields, so a read can never observe a
 * half-applied refresh — fresh values from one payload alongside a device ID from another.
 */
internal class Snapshot(
    /** Values from the most recent accepted payload. */
    val fresh: Map<String, FlagValue>? = null,
    /** Values from the durable cache: the last payload this device ever accepted. */
    val cached: Map<String, FlagValue>? = null,
    val deviceId: String? = null,
    val lastSuccessfulFetchEpochMillis: Long? = null,
    val isStarted: Boolean = false,
    /** The KEYS of the tags a fetch will send — built-in and custom merged, sorted. Keys
     * only: tag values never enter the snapshot, for the same reason they never enter a
     * log line. */
    val sentTagKeys: List<String> = emptyList(),
) {
    fun copy(
        fresh: Map<String, FlagValue>? = this.fresh,
        cached: Map<String, FlagValue>? = this.cached,
        deviceId: String? = this.deviceId,
        lastSuccessfulFetchEpochMillis: Long? = this.lastSuccessfulFetchEpochMillis,
        isStarted: Boolean = this.isStarted,
        sentTagKeys: List<String> = this.sentTagKeys,
    ): Snapshot = Snapshot(fresh, cached, deviceId, lastSuccessfulFetchEpochMillis, isStarted, sentTagKeys)
}

/**
 * Holds the snapshot for the synchronous read path. `isEnabled` may be called from a UI
 * render pass many times per frame, so it cannot suspend, cannot do I/O, and cannot
 * contend with the refresh machinery: reads are a volatile load of an immutable object,
 * writes are serialised by a lock.
 */
internal class SnapshotStore {
    @Volatile
    private var snapshot = Snapshot()
    private val writeLock = Any()

    val current: Snapshot get() = snapshot

    fun update(transform: (Snapshot) -> Snapshot) {
        synchronized(writeLock) {
            snapshot = transform(snapshot)
        }
    }

    fun reset() {
        synchronized(writeLock) {
            snapshot = Snapshot()
        }
    }
}
