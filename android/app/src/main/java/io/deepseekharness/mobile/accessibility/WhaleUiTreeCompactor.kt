package io.deepseekharness.mobile.accessibility

import org.json.JSONArray
import org.json.JSONObject

/**
 * 纯 JVM 可测试的移动端 UI 控件树压缩与语义提纯引擎。
 *
 * 将原始 Android 无障碍树中 70%–85% 的空布局容器噪声（不可点击/无文字的 ViewGroup、
 * 零面积或屏外节点）剪枝，把子 TextView 文本聚合到最近的可点击父容器上，并按阅读顺序
 * 分配紧凑的 `#ref` 短编号与中心坐标 `(cx, cy)`，大幅减少 LLM 推理 token 与单步时延。
 */
internal object WhaleUiTreeCompactor {
    data class RawNode(
        val index: Int,
        val parentIndex: Int,
        val className: String,
        val viewId: String,
        val text: String,
        val contentDesc: String,
        val hint: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val visible: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val ownLabel: String
            get() = sequenceOf(text, contentDesc, hint)
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()

        val interactive: Boolean
            get() = clickable || editable || scrollable
    }

    data class CompactElement(
        val ref: Int,
        val label: String,
        val viewId: String,
        val className: String,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val cx: Int,
        val cy: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        fun json(): JSONObject = JSONObject().apply {
            put("r", ref)
            if (label.isNotEmpty()) put("t", label.take(80))
            if (viewId.isNotEmpty()) put("id", viewId.substringAfterLast('/').take(64))
            if (clickable) put("c", true)
            if (editable) put("e", true)
            if (scrollable) put("s", true)
            put("x", cx)
            put("y", cy)
        }
    }

    fun compact(
        rawNodes: List<RawNode>,
        screenWidth: Int,
        screenHeight: Int,
        maxElements: Int = 80,
    ): List<CompactElement> {
        if (rawNodes.isEmpty()) return emptyList()
        val byIndex = HashMap<Int, RawNode>(rawNodes.size * 2)
        for (node in rawNodes) byIndex[node.index] = node

        // Bubble child labels up to the nearest clickable/editable ancestor that lacks its own label.
        val aggregatedLabels = HashMap<Int, MutableList<String>>()
        val absorbedChildren = HashSet<Int>()

        for (node in rawNodes) {
            if (!isOnScreen(node, screenWidth, screenHeight)) continue
            if (!node.interactive && node.ownLabel.isNotEmpty()) {
                val ancestor = findInteractiveAncestor(node, byIndex, maxHops = 3)
                if (ancestor != null && isOnScreen(ancestor, screenWidth, screenHeight)) {
                    val bucket = aggregatedLabels.getOrPut(ancestor.index) { ArrayList(2) }
                    if (bucket.size < 3 && node.ownLabel !in bucket) {
                        bucket.add(node.ownLabel)
                    }
                    absorbedChildren.add(node.index)
                }
            }
        }

        val candidates = ArrayList<RawNode>()
        for (node in rawNodes) {
            if (node.index in absorbedChildren) continue
            if (!isOnScreen(node, screenWidth, screenHeight)) continue
            val mergedLabel = buildLabel(node, aggregatedLabels[node.index])
            if (!node.interactive && mergedLabel.isEmpty()) continue
            candidates.add(node)
        }

        // Deduplicate nodes sharing identical label and nearly identical center coordinates.
        val seenKeys = HashSet<String>()
        val deduped = ArrayList<CompactElement>(candidates.size)
        val sorted = candidates.sortedWith(compareBy<RawNode>({ it.top / 24 }, { it.left }))

        var nextRef = 1
        for (node in sorted) {
            if (deduped.size >= maxElements) break
            val label = buildLabel(node, aggregatedLabels[node.index])
            val cx = (node.left + node.right) / 2
            val cy = (node.top + node.bottom) / 2
            val dedupKey = "${label}|${cx / 12}|${cy / 12}|${node.clickable}|${node.editable}"
            if (!seenKeys.add(dedupKey)) continue
            deduped.add(
                CompactElement(
                    ref = nextRef++,
                    label = label,
                    viewId = node.viewId,
                    className = node.className,
                    clickable = node.clickable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                    cx = cx,
                    cy = cy,
                    left = node.left,
                    top = node.top,
                    right = node.right,
                    bottom = node.bottom,
                ),
            )
        }
        return deduped
    }

    fun toJsonArray(elements: List<CompactElement>): JSONArray = JSONArray().apply {
        elements.forEach { put(it.json()) }
    }

    private fun buildLabel(node: RawNode, bubbled: List<String>?): String {
        val parts = ArrayList<String>(4)
        if (node.ownLabel.isNotEmpty()) parts.add(node.ownLabel)
        bubbled?.forEach { if (it !in parts) parts.add(it) }
        return parts.joinToString(" ").trim().take(80)
    }

    private fun findInteractiveAncestor(
        node: RawNode,
        byIndex: Map<Int, RawNode>,
        maxHops: Int,
    ): RawNode? {
        var cur = node.parentIndex
        var hops = 0
        while (cur >= 0 && hops < maxHops) {
            val parent = byIndex[cur] ?: return null
            if (parent.clickable || parent.editable) return parent
            cur = parent.parentIndex
            hops += 1
        }
        return null
    }

    private fun isOnScreen(node: RawNode, screenWidth: Int, screenHeight: Int): Boolean {
        if (!node.visible) return false
        if (node.right <= node.left || node.bottom <= node.top) return false
        if (screenWidth > 0 && (node.right <= 0 || node.left >= screenWidth)) return false
        if (screenHeight > 0 && (node.bottom <= 0 || node.top >= screenHeight)) return false
        return true
    }
}
