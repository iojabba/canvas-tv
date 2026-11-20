package com.canvastv.dashboard.data

import android.content.Context
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

data class InputInfo(val id: String, val label: String, val isHdmi: Boolean, val passthrough: Boolean)

/** HDMI and other hardware inputs the TV exposes to apps (Hisense lists HDMI 1–4 here). */
object Inputs {
    fun list(ctx: Context): List<InputInfo> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return runCatching {
            tim.tvInputList
                .filter { it.isPassthroughInput && !it.isHidden(ctx) }
                .map {
                    InputInfo(
                        id = it.id,
                        label = it.loadCustomLabel(ctx)?.toString()?.takeIf { l -> l.isNotBlank() } ?: it.loadLabel(ctx).toString(),
                        isHdmi = it.type == TvInputInfo.TYPE_HDMI,
                        passthrough = true,
                    )
                }
                .sortedBy { it.label }
        }.getOrDefault(emptyList())
    }
}
