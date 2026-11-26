package io.github.lumiagg.gestureevo.gesture

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.lumiagg.gestureevo.drop.ExternalDropFloating
import io.github.lumiagg.gestureevo.drop.DragContentConverter
import io.github.lumiagg.gestureevo.drop.DropContentDispatcher
import io.github.lumiagg.gestureevo.drop.DropImageDecoder
import io.github.lumiagg.gestureevo.drop.PressContentCapture
import io.github.lumiagg.gestureevo.drop.GlobalPressDragSource
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.PictureFloating
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_CONVERSION
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_ENABLED
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_POSITION
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_POSITION_BOTTOM
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_EXTERNAL_DROP_SHOW_ENGINES
import io.github.lumiagg.gestureevo.utils.AccessibilityBlacklist
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.RecentAppsRecorder
import io.github.lumiagg.gestureevo.utils.root.RootShell
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext


/**
 * 手势运行时服务，负责连接系统事件入口，并托管手势协调器与全局拖拽会话。
 *
 * 当前实现还提供系统截图和模拟触摸能力；这些能力与无障碍事件监听并非同一职责，
 * 后续应由 RootShell 与独立的 Root 事件监视器接管，不能把这些能力继续绑在无障碍回调上。
 */
class GestureService : AccessibilityService(), CoroutineScope {
    lateinit var job: Job

    // CoroutineScope implementation
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    private var powerManager: PowerManager? = null
    private val gestureBroadcastReceiver = GestureBroadcastReceiver()
    var gestureManager: GestureManager? = null
        private set
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var externalDropFloating: ExternalDropFloating? = null
    private var externalDropSettingsJob: Job? = null
    private var globalPressDragSource: GlobalPressDragSource? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        CurrentPackageProvider.onAccessibilityEvent(event)
        gestureManager?.onWindowStateChanged(event)
        globalPressDragSource?.onWindowEvent(event)
    }

    override fun onInterrupt() {
        gestureManager?.onAccessibilityInterrupted()
        globalPressDragSource?.cancel("accessibility_interrupted")
        AppLogger.event(
            LogModule.SERVICE, "accessibility_interrupted",
            LogLevel.WARN)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.event(
            LogModule.SERVICE, "accessibility_connected")
        AccessibilityBlacklist.start(this)
        CurrentPackageProvider.setService(this)
        initReceiver()
        launch(Dispatchers.Main) {
            delay(1000)
            gestureManager = GestureManager(this@GestureService, this)
            globalPressDragSource = GlobalPressDragSource(
                this@GestureService, this@GestureService,
                PressContentCapture(this@GestureService, this@GestureService::takeScreenshot),
            ) { externalDropFloating }.also { it.start() }
            observeExternalDropSettings()
            DynamicColorUtils.themeRevision.collect {
                gestureManager?.refreshOverlayThemes()
            }
        }
        launch(Dispatchers.IO) {
            DynamicColorUtils.captureColor(
                this@GestureService,
                this@GestureService
            )
            RecentAppsRecorder.initLauncherPackagesInDB(applicationContext)
        }
    }

    private fun observeExternalDropSettings() {
        data class DropSettings(val showTools: Boolean, val showEngines: Boolean, val position: String,
            val conversion: Boolean)
        externalDropSettingsJob?.cancel()
        externalDropSettingsJob = launch(Dispatchers.Main) {
            var previousSettings: DropSettings? = null
            settingsStore.data.map { preferences ->
                DropSettings(
                    preferences.readKey(KEY_EXTERNAL_DROP_ENABLED, true),
                    preferences.readKey(KEY_EXTERNAL_DROP_SHOW_ENGINES, true),
                    preferences.readKey(KEY_EXTERNAL_DROP_POSITION, EXTERNAL_DROP_POSITION_BOTTOM),
                    preferences.readKey(KEY_EXTERNAL_DROP_CONVERSION, false),
                )
            }.distinctUntilChanged().combine(DynamicColorUtils.themeRevision) { settings, _ ->
                settings
            }.collect { settings ->
                val (showTools, showEngines, position) = settings
                // Cold dynamic-color publication must not replace the panel mid-press.
                if (previousSettings == settings) {
                    while (globalPressDragSource?.isGestureActive == true) delay(50)
                }
                previousSettings = settings
                globalPressDragSource?.cancel("panel_settings_rebuild")
                externalDropFloating?.destroy()
                externalDropFloating = if (showTools || showEngines) {
                    val decoder = DropImageDecoder(this@GestureService)
                    val dispatcher = DropContentDispatcher(
                        this@GestureService, decoder,
                        showTextPin = { PictureFloating.build(this@GestureService, it) },
                        showImagePin = { PictureFloating.build(this@GestureService, it) },
                    )
                    ExternalDropFloating(this@GestureService, this@GestureService, position, showTools, dispatcher,
                        DragContentConverter(this@GestureService, decoder)).also {
                        it.onSystemDragStarted = { globalPressDragSource?.onSystemDragStarted() }
                        it.onSystemDragEnded = { globalPressDragSource?.onSystemDragEnded() }
                        it.start()
                    }
                } else null
            }
        }
    }

    private fun initReceiver() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gestureBroadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(gestureBroadcastReceiver, filter)
        }
        gestureBroadcastReceiver.isReceiverRegistered = true
    }

    override fun onCreate() {
        super.onCreate()
        job = Job()

        setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        launch {
            IconHelper.initIconConfig(applicationContext)
        }
    }

    /**
     * 关闭拖拽会话、悬浮面板、系统事件接收器和协程任务，避免服务退出后残留窗口。
     */
    override fun onDestroy() {
        AppLogger.event(
            LogModule.SERVICE, "accessibility_destroyed",
            LogLevel.WARN)
        globalPressDragSource?.close()
        globalPressDragSource = null
        externalDropSettingsJob?.cancel()
        externalDropSettingsJob = null
        externalDropFloating?.destroy()
        externalDropFloating = null
        super.onDestroy()
        gestureManager?.destroy()
        gestureManager = null
        if (gestureBroadcastReceiver.isReceiverRegistered) {
            unregisterReceiver(gestureBroadcastReceiver)
        }
        CurrentPackageProvider.clearService()
        AccessibilityBlacklist.stop()
        io.github.lumiagg.gestureevo.pin.PinDragCancelOverlay.hide(notify = false)
        job.cancel()
    }

    suspend fun setActionViewTouchEnabled(enabled: Boolean) = withContext(Dispatchers.Main) {
        gestureManager?.setActionViewTouchEnabled(enabled)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        globalPressDragSource?.cancel("configuration_changed")
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMode != lastNightMode) {
            lastNightMode = nightMode
            // Day/night flips are theme revisions too: overlays built on stale themed
            // contexts (e.g. the external drop panel) must be recreated.
            DynamicColorUtils.notifySystemThemeChanged(createConfigurationContext(newConfig))
        }
        super.onConfigurationChanged(newConfig)
        gestureManager?.onConfigurationChanged(newConfig)
        externalDropFloating?.onConfigurationChanged()
    }

    /**
     * 请求系统截取当前显示画面，成功时返回独立的可读 Bitmap。
     * 仅支持 Android 11 及以上；调用有超时，失败或系统拒绝时返回 null。
     */
    /**
     * Captures the current display through the root screencap command.
     * This path does not depend on AccessibilityService.takeScreenshot().
     */
    suspend fun takeScreenshot(): Bitmap? = RootShell.captureScreen(this)

    suspend fun takeManagedScreenshot(): ManagedBitmap? =
        takeScreenshot()?.let(ManagedBitmap::from)

    /**
     * 通过系统手势注入执行单击或双击，并等待系统报告完成或取消。
     */
    /**
     * Injects a tap through Android's root input command. A longer duration is represented
     * by a stationary swipe so the system receives a press rather than an instant tap.
     */
    suspend fun performClickAsync(
        x: Float,
        y: Float,
        duration: Long = 100L,
        clickCount: Int = 1,
    ): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 ||
            duration !in 1L..10_000L || clickCount !in 1..2
        ) return false

        repeat(clickCount) { index ->
            val px = x.toInt()
            val py = y.toInt()
            val result = if (duration <= 150L) {
                RootShell.tap(px, py)
            } else {
                RootShell.swipe(px, py, px, py, duration)
            }
            if (!result.isSuccess) return false
            if (index < clickCount - 1) delay(100L)
        }
        return true
    }

    /**
     * Injects a linear swipe through the root input service.
     *
     * Android's public input command cannot keep the same pointer down after the swipe or
     * reproduce a custom deceleration curve. Those advanced modes return false instead of
     * silently pretending that the original gesture semantics were preserved.
     */
    suspend fun performSwipeAsync(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 600L,
        holdDuration: Long = 0L,
        useDecelerationCurve: Boolean = true,
    ): Boolean {
        if (listOf(startX, startY, endX, endY).any { !it.isFinite() || it < 0f }) return false
        if (holdDuration > 0L || duration !in 1L..10_000L) {
            Log.w(TAG, "Continuous post-swipe hold is not supported by the root input backend")
            return false
        }
        if (useDecelerationCurve) {
            // The shell input service only exposes a linear swipe; preserve the gesture itself.
            Log.d(TAG, "Using a linear root swipe; custom deceleration is unavailable")
        }
        return RootShell.swipe(
            startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt(), duration
        ).isSuccess
    }

    /** Executes common navigation actions without calling AccessibilityService.performGlobalAction. */
    suspend fun performGlobalActionRoot(action: Int): Boolean =
        RootShell.performGlobalAction(action).isSuccess

    private inner class GestureBroadcastReceiver : BroadcastReceiver() {
        var isReceiverRegistered = false
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "onReceive: ${intent.action}")
            globalPressDragSource?.onScreenChanged()
            when (intent.action) {

                Intent.ACTION_USER_PRESENT -> {
                    gestureManager?.activeSomeFloating()
                }

                Intent.ACTION_SCREEN_OFF -> {
                    if (powerManager == null) {
                        powerManager = getSystemService(POWER_SERVICE) as PowerManager
                    }
                    // This broadcast may arrive after ACTION_USER_PRESENT, leading to deadlock
                    if (powerManager?.isInteractive == true) {
                        return
                    }
                    gestureManager?.inactiveAllFloating()
                }
            }
        }
    }


    companion object {
        private const val TAG = "GestureService"

    }
}
