package com.transcriptor.app

import android.app.Application
import com.transcriptor.app.asr.LiveEngine
import com.transcriptor.app.asr.ModelManager
import com.transcriptor.app.data.TranscriptStore

class TranscriptorApp : Application() {
    lateinit var store: TranscriptStore
        private set
    lateinit var models: ModelManager
        private set
    lateinit var liveEngine: LiveEngine
        private set

    override fun onCreate() {
        super.onCreate()
        store = TranscriptStore(this)
        models = ModelManager(this)
        liveEngine = LiveEngine(models)
        store.markInterrupted()
        liveEngine.preloadIfInstalled()
    }
}
