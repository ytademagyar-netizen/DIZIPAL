package com.dizipal

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DizipalPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DizipalProvider())
    }
}
