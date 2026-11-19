package io.deepseekharness.mobile.companion

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 原生 Agent → 前端 WebView 全软件控制事件总线。
 *
 * 允许 Agent 工具（页面跳转、双风格/明暗主题切换、语言切换、自治模式变更等）
 * 在落盘原生配置的同时，实时驱动正在运行的界面立即生效。
 */
internal object WhaleAppControlBus {
    private val listeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()

    fun subscribe(listener: (JSONObject) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun emit(payload: JSONObject) {
        for (listener in listeners) {
            runCatching { listener(payload) }
        }
    }
}
