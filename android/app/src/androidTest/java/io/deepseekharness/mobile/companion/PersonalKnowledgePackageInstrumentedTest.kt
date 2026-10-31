package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalKnowledgePackageInstrumentedTest {
    @Test fun encryptedPackageRejectsWrongPassphraseWithoutChangingBackup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = UUID.randomUUID().toString(); val dir = File(base.cacheDir, "personal-package-$tag").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
            override fun getCacheDir(): File = dir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences("$tag-$name", mode)
        }
        assertTrue(CompanionKnowledgeStore(context).add("encrypted package source"))
        val packageFile = PersonalKnowledgePackage.export(context, "correct passphrase".toCharArray())
        assertTrue(packageFile.isFile && packageFile.length() > 32)
        assertThrows(Exception::class.java) { PersonalKnowledgePackage.decrypt(context, packageFile, "incorrect passphrase".toCharArray()) }
        val archive = PersonalKnowledgePackage.decrypt(context, packageFile, "correct passphrase".toCharArray())
        try { assertTrue(UnifiedFullBackup.isFullArchive(archive)) } finally { archive.delete(); packageFile.delete() }
    }
}
