package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.R

internal object PetControlLabels {
    fun label(context: Context, phase: String): String = context.getString(when(phase) {
        "observing" -> R.string.pet_control_observing
        "operating" -> R.string.pet_control_operating
        "paused" -> R.string.pet_control_paused
        "stopped" -> R.string.pet_control_stopped
        "failed" -> R.string.pet_control_failed
        else -> R.string.pet_control_waiting
    })
    fun description(context: Context, phase: String) = context.getString(R.string.pet_control_tap,label(context,phase))
}
