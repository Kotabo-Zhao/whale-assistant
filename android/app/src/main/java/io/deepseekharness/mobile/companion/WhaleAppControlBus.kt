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
    private val pending = ArrayDeque<JSONObject>()

    fun subscribe(listener: (JSONObject) -> Unit): () -> Unit {
        listeners.add(listener)
        val replay = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        for (item in replay) {
            runCatching { listener(item) }
        }
        return { listeners.remove(listener) }
    }

    fun emit(payload: JSONObject) {
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) {
            synchronized(pending) {
                while (pending.size >= 4) pending.removeFirst()
                pending.addLast(payload)
            }
            return
        }
        for (listener in snapshot) {
            runCatching { listener(payload) }
        }
    }
}
