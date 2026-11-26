package io.github.lumiagg.gestureevo.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceFreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.FreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.FreeTriggerFloating
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class GestureTileService : TileService(), CoroutineScope {
    lateinit var job: Job

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    override fun onStartListening() {
        super.onStartListening()
        launch {
            val tile = qsTile ?: return@launch
            val rootGranted = RootShell.isAvailable()
            var isRunning = rootGranted && FreeTriggerFloating.realTriggerIsEnable

            if (rootGranted && !isRunning &&
                applicationContext.settingsStore.readKey("gesture_tile_enable", true)
            ) {
                FreeTriggerFloating.expectTriggerIsEnable = true
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, isEnable = true))
                isRunning = true
            }

            tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        if (tile.state == Tile.STATE_ACTIVE) {
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
            launch {
                applicationContext.settingsStore.saveKey("gesture_tile_enable", false)
                FreeTriggerFloating.expectTriggerIsEnable = false
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, isEnable = false))
            }
        } else {
            launch {
                if (!RootShell.isAvailable()) {
                    tile.state = Tile.STATE_INACTIVE
                    tile.updateTile()
                    return@launch
                }
                applicationContext.settingsStore.saveKey("gesture_tile_enable", true)
                FreeTriggerFloating.expectTriggerIsEnable = true
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, isEnable = true))
                tile.state = Tile.STATE_ACTIVE
                tile.updateTile()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        job = Job()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
