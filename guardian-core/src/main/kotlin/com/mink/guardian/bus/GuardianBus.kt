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
 * Dispatch never blocks the emitter. [emit] assigns the event a monotonic
 * [GuardianEvent.seq] and [GuardianEvent.atEpochMs] and `tryEmit`s into a
 * `MutableSharedFlow` (replay 0, 256-slot buffer, `DROP_OLDEST`) — so a burst
 * never suspends the sweep thread. A single fan-out collector forwards each
 * event to every attached hook's own bounded channel with a non-suspending
 * `trySend`; when a hook is wedged its channel fills and the newest events are
 * dropped (its own tail) and counted, never backpressuring the sweep or a
 * sibling. Hook exceptions are caught and counted per hook.
 *
 * @param scope the coroutine scope the fan-out and per-hook consumers run in;
 *   cancelling it tears down all delivery. [emit] does not depend on the scope —
 *   it is a plain non-suspending call safe from the sweep thread.
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

    /** The raw event stream. Prefer [attach] for isolated, per-hook delivery. */
    val events: SharedFlow<GuardianEvent> = _events.asSharedFlow()

    private val seq = AtomicLong(0L)
    private val dropped = AtomicLong(0L)

    /** Total events dropped because a hook's channel was full (a wedged/slow hook). */
    val droppedCount: Long get() = dropped.get()

    private val hooks = CopyOnWriteArrayList<Registration>()

    init {
        // One fan-out collector: the sole subscriber to the shared flow, forwarding
        // each event to every hook's private channel. With no hooks it is an inert
        // no-op loop, so the bus stays advisory.
        scope.launch {
            _events.collect { event ->
                for (reg in hooks) {
                    // trySend never suspends: on a full channel it fails, dropping
                    // this (newest) event for that hook and counting it — the sweep
                    // and every other hook are untouched.
                    if (reg.channel.trySend(event).isFailure) {
                        reg.dropped.incrementAndGet()
                        dropped.incrementAndGet()
                    }
                }
            }
        }
    }

    /**
     * Publish a post-commit event. Non-suspending and safe from the sweep thread:
     * it stamps the event and `tryEmit`s. Never throws.
     */
    fun emit(event: GuardianEvent) {
        event.seq = seq.getAndIncrement()
        event.atEpochMs = clock()
        _events.tryEmit(event)
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
