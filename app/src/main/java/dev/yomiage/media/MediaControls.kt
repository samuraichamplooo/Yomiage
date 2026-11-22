package dev.yomiage.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import dev.yomiage.R
import dev.yomiage.data.Prefs
import dev.yomiage.data.Voices
import dev.yomiage.reader.BlockKind
import dev.yomiage.service.ReaderService
import dev.yomiage.reader.Reader
import dev.yomiage.reader.ReaderState
import dev.yomiage.reader.Status
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Media controls for whatever is being read: in the quick panel, on the lock screen and Always On
 * Display (Samsung's Now Bar draws them in its own sleek black style) and on headphones.
 * Next/previous jump the big skip (5 seconds unless changed); extra buttons jump the small skip
 * (3 seconds); the bar can be dragged to scrub.
 * Tapping the controls brings the on-screen player back if it was hidden.
 *
 * Easy on the battery: the title is the page's title (not each sentence), the progress bar is
 * animated by the phone itself, and the notification is only re-posted when play/pause or the
 * title actually changes. So the lock screen and Always On Display don't redraw every sentence.
 */
object MediaControls {
    private const val TAG = "MediaControls"
    private const val CHANNEL = "player"
    private const val NOTIFICATION_ID = 7
    private const val PREFIX = "dev.yomiage.media."
    private const val BLACK = 0xFF0A0A0A.toInt()

    private lateinit var app: Context
    private var session: MediaSession? = null
    private val scope = MainScope()

    // What was last sent, so nothing is re-sent unless it changed.
    private var postedKey: String? = null
    private var metaKey: String? = null
    private var titleSession = -1L
    private var pageTitle = "Yomiage"
    private var lastState = -1
    private var lastPos = 0L
    private var lastPosAt = 0L
    private var lastSpeed = 1f
    private var lastDuration = 0L

    fun init(context: Context) {
        app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading controls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Play, pause and skip while Yomiage reads"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        scope.launch { Reader.state.collect { update(it) } }
    }

    /** Minimal black artwork with the red dot: the lock screen and Now Bar players take their look from it. */
    private val art: Bitmap by lazy {
        val size = 512
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val mid = size / 2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 7f
        p.color = 0x2EFFFFFF
        c.drawCircle(mid, mid, 86f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFD8462B.toInt()
        c.drawCircle(mid, mid, 50f, p)
        b
    }

    private fun session(): MediaSession = session ?: MediaSession(app, "Yomiage").also { s ->
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = Reader.resume()
            override fun onPause() = Reader.pause()
            override fun onSkipToNext() = Reader.seekBy(big())
            override fun onSkipToPrevious() = Reader.seekBy(-big())
            override fun onFastForward() = Reader.seekBy(small())
            override fun onRewind() = Reader.seekBy(-small())
            override fun onSeekTo(pos: Long) = Reader.seekToMs(pos)
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

    private fun big() = Prefs.current.skipBig.toFloat()
    private fun small() = Prefs.current.skipSmall.toFloat()

    fun handle(name: String) {
        when (name.removePrefix(PREFIX)) {
            "toggle" -> Reader.togglePlay()
            "back5" -> Reader.seekBy(-big())
            "fwd5" -> Reader.seekBy(big())
            "back2" -> Reader.seekBy(-small())
            "fwd2" -> Reader.seekBy(small())
            "stop" -> Reader.stop()
        }
    }

    /** A steady name for what's being read: the page's heading, or how it began. */
    private fun titleFor(st: ReaderState): String {
        if (st.session == titleSession) return pageTitle
        val snap = Reader.snapshot() ?: return st.label ?: "Yomiage"
        titleSession = st.session
        val heading = snap.blocks.take(12).firstOrNull { it.kind == BlockKind.HEADING && it.text.length in 3..120 }?.text
        val first = snap.blocks.firstOrNull { it.text.isNotBlank() && !it.hold }?.text
        pageTitle = (st.label ?: heading ?: first?.let { clip(it, 70) } ?: "Yomiage").trim()
        return pageTitle
    }

    private fun clip(t: String, max: Int): String {
        val one = t.replace(Regex("\\s+"), " ").trim()
        if (one.length <= max) return one
        val cut = one.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return one.substring(0, cut) + "…"
    }

    private fun reset() {
        postedKey = null
        metaKey = null
        titleSession = -1L
        lastState = -1
        lastDuration = 0L
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
            reset()
            return
        }
        try {
            val s = session()
            val active = st.status == Status.Playing || st.status == Status.Buffering || st.status == Status.Preparing
            val title = when (st.status) {
                Status.Error -> st.message ?: "Yomiage"
                Status.Finished -> "Finished: " + titleFor(st)
                else -> titleFor(st)
            }
            val voice = Voices.byId(Prefs.current.voiceId).name
            val subtitle = "Yomiage · $voice"

            // Position and length: the phone moves the progress bar along by itself between updates.
            val (pos, duration) = Reader.timeline() ?: (0L to 0L)
            val state = when (st.status) {
                Status.Playing -> PlaybackState.STATE_PLAYING
                Status.Buffering, Status.Preparing -> PlaybackState.STATE_BUFFERING
                Status.Error -> PlaybackState.STATE_ERROR
                else -> PlaybackState.STATE_PAUSED
            }
            val now = SystemClock.elapsedRealtime()
            val expected = if (lastState == PlaybackState.STATE_PLAYING) lastPos + ((now - lastPosAt) * lastSpeed).toLong() else lastPos
            if (state != lastState || st.speed != lastSpeed || abs(expected - pos) > 2_500) {
                lastState = state
                lastPos = pos
                lastPosAt = now
                lastSpeed = st.speed
                s.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
                                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                        )
                        .addCustomAction(
                            PlaybackState.CustomAction.Builder(PREFIX + "back2", "Back ${Prefs.current.skipSmall} seconds", R.drawable.ic_rewind).build()
                        )
                        .addCustomAction(
                            PlaybackState.CustomAction.Builder(PREFIX + "fwd2", "Forward ${Prefs.current.skipSmall} seconds", R.drawable.ic_forward).build()
                        )
                        .setState(state, pos, if (state == PlaybackState.STATE_PLAYING) st.speed else 0f, now)
                        .build()
                )
            }

            // The length is an estimate that firms up as reading goes: only re-send it when it moves a lot.
            val durationChanged = lastDuration == 0L || abs(duration - lastDuration) > max(15_000L, lastDuration / 12)
            val mKey = "$title|$subtitle"
            if (mKey != metaKey || durationChanged) {
                metaKey = mKey
                lastDuration = duration
                s.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "Yomiage")
                        .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, if (duration > 0) duration else -1L)
                        .build()
                )
            }
            s.isActive = true

            // The notification itself only changes with play/pause, the title or an error.
            if (!nm.areNotificationsEnabled()) return
            val key = "$active|$title|$subtitle|${st.status == Status.Error}|${Prefs.current.skipBig}"
            if (key == postedKey) return
            postedKey = key
            val icon = { res: Int -> Icon.createWithResource(app, res) }
            val notification = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setLargeIcon(art)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
                .addAction(Notification.Action.Builder(icon(R.drawable.ic_rewind), "Back ${Prefs.current.skipBig} seconds", action("back5", 11)).build())
                .addAction(
                    Notification.Action.Builder(
                        icon(if (active) R.drawable.ic_pause else R.drawable.ic_play),
                        if (active) "Pause" else "Play", action("toggle", 12),
                    ).build()
                )
                .addAction(Notification.Action.Builder(icon(R.drawable.ic_forward), "Forward ${Prefs.current.skipBig} seconds", action("fwd5", 13)).build())
                .addAction(Notification.Action.Builder(icon(R.drawable.ic_close), "Stop", action("stop", 14)).build())
                .setContentIntent(showPlayerIntent())
                .setDeleteIntent(action("stop", 15))
                // Black, like Samsung's own Now Playing: media notifications may be colored.
                .setColorized(true)
                .setColor(BLACK)
                .setOngoing(active)
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
        ReaderService.instance?.revealPlayer(fromShade = true)
        finish()
    }
}
