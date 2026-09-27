package com.example.itinerary

import android.app.Instrumentation
import android.os.Build
import android.view.WindowInsets
import android.view.inspector.WindowInspector

/** A cold-booted emulator can show a full IME. Never swipe across its suggestion keyboard. */
internal fun hideQuickTestKeyboard(ins: Instrumentation) {
    if (Build.VERSION.SDK_INT >= 30) {
        ins.runOnMainSync {
            WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.forEach {
                it.windowInsetsController?.hide(WindowInsets.Type.ime())
            }
        }
        Thread.sleep(250)
    }
}
