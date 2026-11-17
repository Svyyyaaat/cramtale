package com.cramtale.app

import android.app.Application

class CramtaleApp : Application() {
    val state by lazy { AppState(this) }

    override fun onCreate() {
        super.onCreate()
        // the deck snapshot (thousands of notes) and the dictionary, warmed up off the main thread so the
        // first screens that need them find them ready
        Thread({ runCatching { state.deck(); state.matcher() } }, "warm-up").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
