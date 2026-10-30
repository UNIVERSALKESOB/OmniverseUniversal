package com.omniverse.universal

import android.app.Application
import com.telemetrydeck.sdk.TelemetryDeck

class AntiGemApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Independent initialization - telemetry to upstream developer removed
    }
}
