package com.yuushilabs.pianosheet

import android.app.Application
import com.yuushilabs.pianosheet.app.AppContainer

class PianoSheetApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
