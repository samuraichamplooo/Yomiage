package dev.yomiage

import android.app.Application
import android.content.res.Configuration
import dev.yomiage.theme.Look
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.yomiage.ai.Explainer
import dev.yomiage.ai.OnDevice
import dev.yomiage.media.MediaControls
import dev.yomiage.data.Prefs
import dev.yomiage.reader.Reader
import dev.yomiage.tts.ModelManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Look.refresh(this)
        // A new look chosen in settings: everything (app, reading view, floating player) follows.
        MainScope().launch {
            Prefs.settings.map { it.theme }.distinctUntilChanged().collect { Look.refresh(this@App) }
        }
        Reader.init(this)
        ModelManager.refresh(this)
        Explainer.init(this)
        OnDevice.init(this)
        MediaControls.init(this)
    }

    // Light/dark mode or a new wallpaper (Material You colors come from it).
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Look.refresh(this)
    }
}
