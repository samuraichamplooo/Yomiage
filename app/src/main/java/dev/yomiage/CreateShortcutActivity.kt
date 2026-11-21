package dev.yomiage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle

/**
 * Offers "Read screen" and "Tap to start" to apps that pick shortcuts (Good Lock's One Hand
 * Operation+, RegiStar, launchers, routines).
 */
class CreateShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val labels = arrayOf("Read screen", "Tap to start")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Yomiage shortcut")
            .setItems(labels) { _, which ->
                val tap = which == 1
                val info = ShortcutInfo.Builder(this, if (tap) "pin_tap_to_read" else "pin_read_screen")
                    .setShortLabel(labels[which])
                    .setLongLabel(labels[which])
                    .setIcon(Icon.createWithResource(this, if (tap) R.mipmap.ic_tap_screen else R.mipmap.ic_read_screen))
                    .setIntent(
                        Intent(this, ReadScreenActivity::class.java)
                            .setAction(if (tap) ReadScreenActivity.ACTION_TAP else ReadScreenActivity.ACTION_READ)
                    )
                    .build()
                val result = getSystemService(ShortcutManager::class.java).createShortcutResultIntent(info)
                setResult(RESULT_OK, result)
                finish()
            }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .show()
    }
}
