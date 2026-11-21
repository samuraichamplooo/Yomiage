package dev.yomiage.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log
import dev.yomiage.R
import dev.yomiage.service.ReaderService
import dev.yomiage.reader.Reader
import dev.yomiage.reader.ReaderState
import dev.yomiage.reader.Status
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Media controls for whatever is being read: in the quick panel, on the lock screen and on
 * headphones. Next/previous jump 5 seconds; extra buttons jump 2 seconds. Tapping the controls
 * brings the on-screen player back if it was hidden.
 */
object MediaControls {
    private const val TAG = "MediaControls"
    private const val CHANNEL = "player"
    private const val NOTIFICATION_ID = 7
    private const val PREFIX = "dev.yomiage.media."

    private lateinit var app: Context
    private var session: MediaSession? = null
    private val scope = MainScope()
    private var lastTitle: String? = null

    fun init(context: Context) {
        app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading controls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Play, pause and skip while Yomiage reads"
                setShowBadge(false)
            }
        )
        scope.launch { Reader.state.collect { update(it) } }
    }

    private fun session(): MediaSession = session ?: MediaSession(app, "Yomiage").also { s ->
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = Reader.resume()
            override fun onPause() = Reader.pause()
            override fun onSkipToNext() = Reader.seekBy(5f)
            override fun onSkipToPrevious() = Reader.seekBy(-5f)
            override fun onFastForward() = Reader.seekBy(2f)
            override fun onRewind() = Reader.seekBy(-2f)
            override fun onStop() = Reader.stop()
            override fun onCustomAction(action: String, extras: Bundle?) {
                handle(action)
            }
        })
        s.setSessionActivity(showPlayerIntent())
        session = s
    }

    private fun showPlayerIntent(): PendingIntent = PendingIntent.getActivity(
        app, 1, Intent(app, ShowPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        app, code, Intent(app, MediaActionReceiver::class.java).setAction(PREFIX + name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun handle(name: String) {
        when (name.removePrefix(PREFIX)) {
            "toggle" -> Reader.togglePlay()
            "back5" -> Reader.seekBy(-5f)
            "fwd5" -> Reader.seekBy(5f)
            "back2" -> Reader.seekBy(-2f)
            "fwd2" -> Reader.seekBy(2f)
            "stop" -> Reader.stop()
        }
    }

    private fun update(st: ReaderState) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (st.status == Status.Idle) {
            nm.cancel(NOTIFICATION_ID)
            session?.let {
                it.isActive = false
                it.release()
            }
            session = null
            lastTitle = null
            return
        }
        try {
            val s = session()
            val playing = st.status == Status.Playing || st.status == Status.Buffering || st.status == Status.Preparing
            val title = when (st.status) {
                Status.Error -> st.message ?: "Yomiage"
                Status.Finished -> "Finished reading"
                else -> st.chunk?.text ?: st.caption.ifBlank { "Getting ready…" }
            }
            val subtitle = st.label ?: if (st.total > 0) "Yomiage · ${st.index} of ${st.total}" else "Yomiage"

            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
                    )
                    .addCustomAction(
                        PlaybackState.CustomAction.Builder(PREFIX + "back2", "Back 2 seconds", R.drawable.ic_rewind).build()
                    )
                    .addCustomAction(
                        PlaybackState.CustomAction.Builder(PREFIX + "fwd2", "Forward 2 seconds", R.drawable.ic_forward).build()
                    )
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (playing) st.speed else 0f,
                    )
                    .build()
            )
            if (title != lastTitle) {
                lastTitle = title
                s.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                        .build()
                )
            }
            s.isActive = true

            if (!nm.areNotificationsEnabled()) return
            val icon = { res: Int -> Icon.createWithResource(app, res) }
            val notification = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
                .addAction(Notification.Action.Builder(icon(R.drawable.ic_rewind), "Back 5 seconds", action("back5", 11)).build())
                .addAction(
                    Notification.Action.Builder(
                        icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        if (playing) "Pause" else "Play", action("toggle", 12),
                    ).build()
                )
                .addAction(Notification.Action.Builder(icon(R.drawable.ic_forward), "Forward 5 seconds", action("fwd5", 13)).build())
                .addAction(Notification.Action.Builder(icon(R.drawable.ic_close), "Stop", action("stop", 14)).build())
                .setContentIntent(showPlayerIntent())
                .setDeleteIntent(action("stop", 15))
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "media controls update failed", e)
        }
    }
}

class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.action?.let { MediaControls.handle(it) }
    }
}

/** Opened by tapping the media controls: brings the on-screen player back. */
class ShowPlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderService.instance?.revealPlayer()
        finish()
    }
}
