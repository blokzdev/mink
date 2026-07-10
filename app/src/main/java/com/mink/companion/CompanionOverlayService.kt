package com.mink.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mink.MainActivity
import com.mink.ui.theme.MinkTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared, in-process bridge between [CompanionController] (which owns the
 * companion's logic) and [CompanionOverlayService] (which draws it). The
 * controller writes; the overlay's Compose content reads. Keeping this here
 * means the service never holds a back-reference to the controller.
 */
internal object CompanionLink {
    val mood = MutableStateFlow(CompanionMood.IDLE)
    val utterance = MutableStateFlow<CompanionUtterance?>(null)
    val bubbleVisible = MutableStateFlow(false)

    /** Toggle the bubble; supply a calm default line when nothing is queued. */
    fun toggleBubble() {
        if (utterance.value == null) {
            utterance.value = CompanionUtterance(
                text = "Mink is watching quietly. Hold to open the app.",
                mood = mood.value,
                epochMs = System.currentTimeMillis(),
                actionLabel = "Open Mink",
                actionRoute = ROUTE_GUARDIAN,
            )
        }
        bubbleVisible.value = !bubbleVisible.value
    }

    const val ROUTE_GUARDIAN = "guardian"
}

/**
 * Foreground (specialUse) service that hosts the floating Mink. It adds a
 * [ComposeView] to the window manager as a [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * window, wired with its own lifecycle, saved-state and view-model owners so
 * Compose can run outside an Activity. The sprite is draggable and snaps to the
 * nearest side edge; a tap opens the speech bubble and a long-press opens the app.
 */
class CompanionOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val owner = OverlayOwner()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        owner.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        addOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        CompanionLink.bubbleVisible.value = false
        owner.onDestroy()
        super.onDestroy()
    }

    // --- Foreground notification -------------------------------------------

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mink companion",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the floating Mink on screen." }
            runCatching { manager.createNotificationChannel(channel) }
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mink is on screen")
            .setContentText("Tap the app to see what your phone reveals.")
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    // --- Overlay window -----------------------------------------------------

    private fun addOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.3f).toInt()
        }
        layoutParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                OverlayContent(
                    onDrag = ::onDrag,
                    onDragEnd = ::onDragEnd,
                    onTap = CompanionLink::toggleBubble,
                    onLongPress = { launchApp(null) },
                    onAction = { route -> launchApp(route) },
                )
            }
        }
        overlayView = view

        owner.onResume()
        runCatching { wm.addView(view, params) }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }

    private fun onDrag(dx: Float, dy: Float) {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun onDragEnd() {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        val placement = SnapMath.snap(
            rawX = params.x,
            rawY = params.y,
            viewWidth = view.width,
            viewHeight = view.height,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            margin = (8 * metrics.density).toInt(),
        )
        params.x = placement.x
        params.y = placement.y
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun launchApp(route: String?) {
        CompanionLink.bubbleVisible.value = false
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (route != null) intent.putExtra(EXTRA_ROUTE, route)
        runCatching { startActivity(intent) }
    }

    /**
     * A minimal owner so a [ComposeView] can run inside a service window:
     * Compose needs a lifecycle, a saved-state registry, and a view-model store.
     */
    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun onCreate() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun onResume() {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun onDestroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    companion object {
        private const val CHANNEL_ID = "mink_companion"
        // Distinct from GuardianService.ONGOING_ID (4201): two foreground
        // services in the same process must not share a notification slot, or
        // one silently overwrites and tears down the other's ongoing notice.
        private const val NOTIFICATION_ID = 4202
        private const val ACTION_STOP = "com.mink.companion.action.STOP"

        /** Intent extra carrying the in-app route a bubble action targets. */
        const val EXTRA_ROUTE = "com.mink.extra.ROUTE"

        /** Start the overlay as a foreground service. */
        fun start(context: Context) {
            val intent = Intent(context, CompanionOverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** Stop the overlay and remove the floating window. */
        fun stop(context: Context) {
            val intent = Intent(context, CompanionOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OverlayContent(
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onAction: (route: String) -> Unit,
) {
    val mood by CompanionLink.mood.collectAsState()
    val utterance by CompanionLink.utterance.collectAsState()
    val bubbleVisible by CompanionLink.bubbleVisible.collectAsState()

    MinkTheme(dynamicColor = false) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val current = utterance
            if (bubbleVisible && current != null) {
                CompanionBubble(
                    utterance = current,
                    onAction = onAction,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(68.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            },
                            onDragEnd = onDragEnd,
                        )
                    }
                    .combinedClickable(
                        onClick = onTap,
                        onLongClick = onLongPress,
                    ),
            ) {
                MinkSprite(mood = mood, modifier = Modifier.size(68.dp))
            }
        }
    }
}
