package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WhaleNativeCodeWorkspaceTest {
    @Test
    fun writesReadsListsAndSearchesCodeFilesSafely() {
        val dir = Files.createTempDirectory("whale-code-ws").toFile()
        try {
            val ws = WhaleNativeCodeWorkspace(dir)
            val written = ws.writeFile("demo-app", "src/main.ts", "export function greet() {\n  return 'hello whale'\n}\n")
            assertTrue(written.getBoolean("ok"))

            val listed = ws.listFiles("demo-app", "src")
            assertEquals(1, listed.getJSONArray("items").length())

            val read = ws.readFile("demo-app", "src/main.ts", 1, 10)
            assertTrue(read.getString("content").contains("hello whale"))

            val found = ws.searchCode("demo-app", "greet")
            assertEquals(1, found.getJSONArray("matches").length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversalOutsideWorkspace() {
        val dir = Files.createTempDirectory("whale-code-ws-sec").toFile()
        try {
            val ws = WhaleNativeCodeWorkspace(dir)
            ws.writeFile("demo-app", "../../escape.txt", "blocked")
        } finally {
            dir.deleteRecursively()
        }
    }
}
