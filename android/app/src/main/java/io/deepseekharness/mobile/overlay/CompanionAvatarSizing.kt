package io.deepseekharness.mobile.overlay

/** Largest power-of-two sample that still covers a fitCenter avatar in a square window. */
internal object CompanionAvatarSizing {
    fun sample(sourceWidth: Int, sourceHeight: Int, targetSide: Int): Int {
        require(sourceWidth > 0 && sourceHeight > 0 && targetSide > 0)
        val largest = maxOf(sourceWidth, sourceHeight).toLong()
        fun required(dimension: Int): Long = ((dimension.toLong() * targetSide + largest - 1) / largest).coerceAtLeast(1)
        val width = required(sourceWidth)
        val height = required(sourceHeight)
        var sample = 1
        while (sample <= (1 shl 29) && sourceWidth.toLong() / (sample * 2L) >= width && sourceHeight.toLong() / (sample * 2L) >= height) sample *= 2
        return sample
    }
}
