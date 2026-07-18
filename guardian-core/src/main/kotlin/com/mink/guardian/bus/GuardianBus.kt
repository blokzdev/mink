package com.mink.guardian.bus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A read-out, never a way in. A hook observes events; it holds no actuator
 * handle, so an event can never invoke the model, persist state, or notify.
 * [onEvent] runs on the bus's own scope and may suspend; a hook that wedges or
 * throws only affects its own delivery (see [GuardianBus.attach]).
 */
fun interface GuardianHook {
    suspend fun onEvent(event: GuardianEvent)
}

/**
 * The guardian's typed, post-commit, **advisory** event bus. Core code publishes
 * a [GuardianEvent] only after the corresponding durable write / StateFlow
 * update has happened; the `Guardian` StateFlows stay canonical and every
 * notification, persistence, and state decision remains a synchronous direct
 * call. A bus with zero attached hooks produces byte-identical persisted state,
 * StateFlow contents, and notification decisions — the permanent invariant its
 * tests pin.
 *
 * Dispatch never blocks the emitter. [emit] stamps the event with a monotonic
 * [GuardianEvent.seq] and [GuardianEvent.atEpochMs], `tryEmit`s it into the raw
 * [events] `SharedFlow` (for any direct-stream consumer), and — for each attached
 * hook — non-blockingly `trySend`s it straight into that hook's own bounded
 * channel. Delivering from [emit] itself (rather than through a separately-
 * subscribed fan-out collector) means there is no startup window in which an
 * event could be lost before a collector subscribes: an event reaches exactly
 * the hooks attached at emit time, and no others. When a hook is wedged its
 * channel fills and the newest events are dropped (its own tail) and counted,
 * never backpressuring the sweep or a sibling; a channel closed by a concurrent
 * [HookHandle.detach] is skipped, not counted. Hook exceptions are caught and
 * counted per hook.
 *
 * @param scope the coroutine scope each per-hook consumer runs in; cancelling it
 *   tears down all delivery. [emit] does not depend on the scope — it is a plain
 *   non-suspending call safe from the sweep thread.
 * @param clock supplies [GuardianEvent.atEpochMs]; injectable for tests.
 */
class GuardianBus(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _events = MutableSharedFlow<GuardianEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The raw event stream for a direct-stream consumer. Prefer [attach] for isolated per-hook delivery. */
    val events: SharedFlow<GuardianEvent> = _events.asSharedFlow()

    private val seq = AtomicLong(0L)
    private val dropped = AtomicLong(0L)

    /** Total events dropped because a hook's channel was full (a wedged/slow hook). */
    val droppedCount: Long get() = dropped.get()

    private val hooks = CopyOnWriteArrayList<Registration>()

    /**
     * Publish a post-commit event. Non-suspending and safe from the sweep thread:
     * it stamps the event, offers it to the raw stream, and `trySend`s it to each
     * attached hook. Never throws.
     */
    fun emit(event: GuardianEvent) {
        event.seq = seq.getAndIncrement()
        event.atEpochMs = clock()
        _events.tryEmit(event)
        for (reg in hooks) {
            val result = reg.channel.trySend(event)
            // A full channel (wedged hook) drops this newest event and counts it;
            // a channel closed by a racing detach is simply skipped, not counted.
            if (result.isFailure && !result.isClosed) {
                reg.dropped.incrementAndGet()
                dropped.incrementAndGet()
            }
        }
    }

    /**
     * Attach a [hook] with its own bounded channel and consumer coroutine.
     * Returns a [HookHandle] exposing this hook's dropped-event and caught-exception
     * counts and a [HookHandle.detach]. A throwing hook is isolated — its exception
     * is counted and its consumer continues; a wedged hook only drops its own tail.
     */
    fun attach(hook: GuardianHook): HookHandle {
        val channel = Channel<GuardianEvent>(capacity = 64)
        val hookDropped = AtomicLong(0L)
        val exceptions = AtomicLong(0L)
        val reg = Registration(channel, hookDropped)
        hooks.add(reg)
        val job = scope.launch {
            for (event in channel) {
                try {
                    hook.onEvent(event)
                } catch (t: Throwable) {
                    exceptions.incrementAndGet()
                }
            }
        }
        return HookHandle(hookDropped, exceptions, onDetach = {
            hooks.remove(reg)
            channel.close()
            job.cancel()
        })
    }

    private class Registration(
        val channel: Channel<GuardianEvent>,
        val dropped: AtomicLong,
    )

    /** A live hook attachment: its drop/exception counts and how to remove it. */
    class HookHandle internal constructor(
        private val dropped: AtomicLong,
        private val exceptions: AtomicLong,
        private val onDetach: () -> Unit,
    ) {
        /** Events dropped for this hook because its channel was full (it fell behind). */
        val droppedCount: Long get() = dropped.get()

        /** How many times this hook's [GuardianHook.onEvent] threw (and was isolated). */
        val exceptionCount: Long get() = exceptions.get()

        /** Stop delivering to this hook and release its channel. */
        fun detach() = onDetach()
    }
}
