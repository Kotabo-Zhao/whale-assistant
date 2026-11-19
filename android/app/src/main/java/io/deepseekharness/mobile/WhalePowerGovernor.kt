package io.deepseekharness.mobile

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import io.deepseekharness.mobile.companion.CompanionStore

/**
 * 全软件自适应性能与能耗调度器（Performance / Balanced / Eco 三档）。
 *
 * 结合系统省电模式、实时电量/充电状态以及用户「减少动效」偏好，统一驱动
 * Android 原生动效（[WhaleMotion]）、桌宠刷新节奏与 WebView 渲染档位。
 */
internal object WhalePowerGovernor {
    enum class Profile(val wireValue: String) {
        PERFORMANCE("performance"),
        BALANCED("balanced"),
        ECO("eco"),
    }

    fun resolve(
        batteryPercent: Int,
        charging: Boolean,
        powerSaveMode: Boolean,
        reduceMotion: Boolean,
    ): Profile {
        if (powerSaveMode || reduceMotion) return Profile.ECO
        if (!charging && batteryPercent in 0..15) return Profile.ECO
        if (!charging && batteryPercent in 16..34) return Profile.BALANCED
        return Profile.PERFORMANCE
    }

    fun current(context: Context): Profile {
        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val powerSave = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)
        val charging = runCatching { bm?.isCharging == true }.getOrDefault(false)
        val level = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }.getOrDefault(-1)
        val reduceMotion = runCatching { CompanionStore(app).reduceMotion }.getOrDefault(false)
        return resolve(level, charging, powerSave, reduceMotion)
    }

    fun durationScale(profile: Profile): Float = when (profile) {
        Profile.PERFORMANCE -> 1.0f
        Profile.BALANCED -> 0.72f
        Profile.ECO -> 0.0f
    }

    fun pollIntervalMultiplier(profile: Profile): Int = when (profile) {
        Profile.PERFORMANCE -> 1
        Profile.BALANCED -> 2
        Profile.ECO -> 4
    }
}
