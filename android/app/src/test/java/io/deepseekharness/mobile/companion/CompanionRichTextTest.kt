package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionRichTextTest {
    @Test fun rendersCommonMarkdownWithoutLeakingMarkers() {
        assertEquals("标题\n• 重点 和 code\n│ 旧内容", CompanionRichText.visibleText("# 标题\n- **重点** 和 `code`\n> ~~旧内容~~"))
    }

    @Test fun rendersInlineAndBlockLatexAsReadableMath() {
        assertEquals("能量 E = mc²\na⁄b + √x × π", CompanionRichText.visibleText("能量 \$E = mc^2\$\n\$\$\\frac{a}{b} + \\sqrt{x} \\times \\pi\$\$"))
    }

    @Test fun rendersMultilineBracketLatexAndCommonMarkdown() {
        assertEquals("斜体 和 1. 有序\nx² + y₁ = α", CompanionRichText.visibleText("*斜体* 和 1. 有序\n\\[\nx^{2} + y_{1} = \\alpha\n\\]"))
    }

    @Test fun onlySafeHttpLinksBecomeClickable() {
        assertEquals("看 文档 与 [文本](file:///private)", CompanionRichText.visibleText("看 [文档](https://example.com/docs) 与 [文本](file:///private)"))
    }

    @Test fun keepsUnderscoresInToolNamesWhileRenderingItalic() {
        assertEquals("使用 mobile_knowledge_hybrid_search 和 斜体", CompanionRichText.visibleText("使用 mobile_knowledge_hybrid_search 和 _斜体_"))
    }
}
