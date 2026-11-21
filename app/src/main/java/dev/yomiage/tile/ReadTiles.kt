package dev.yomiage.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.yomiage.MainActivity
import dev.yomiage.reader.Reader
import dev.yomiage.reader.Status
import dev.yomiage.service.ReaderService

/** Quick Settings tiles: pull down the shade, tap, and the app underneath is read. */
abstract class BaseReadTile(private val tapMode: Boolean) : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { t ->
            val busy = Reader.state.value.status.let { it != Status.Idle && it != Status.Error }
            t.state = if (busy && !tapMode) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            t.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val service = ReaderService.instance
        if (service == null) {
            openApp()
            return
        }
        val status = Reader.state.value.status
        if (!tapMode && status != Status.Idle && status != Status.Error) {
            // Tapping the tile again stops reading.
            Reader.stop()
            onStartListening()
            return
        }
        service.readWhenBack(tapMode = tapMode, closeShade = true)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

class ReadTileService : BaseReadTile(tapMode = false)

class TapTileService : BaseReadTile(tapMode = true)
