package com.cramtale.app

import android.app.Application

class CramtaleApp : Application() {
    val state by lazy { AppState(this) }
}
