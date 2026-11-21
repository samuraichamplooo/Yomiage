package dev.yomiage

import android.app.Application
import dev.yomiage.data.Prefs
import dev.yomiage.reader.Reader
import dev.yomiage.tts.ModelManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Reader.init(this)
        ModelManager.refresh(this)
    }
}
