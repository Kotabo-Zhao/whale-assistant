package io.deepseekharness.mobile.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File

/** Official, unmodified APK; Android still owns installation and privilege approval. */
object BundledShizuku {
    const val PACKAGE = "moe.shizuku.privileged.api"

    fun open(context: Context) {
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "请允许安装未知应用，返回后再次点击安装 Shizuku", Toast.LENGTH_LONG).show()
            }
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val apk = File(context.cacheDir, "app-updates/bundled-shizuku.apk")
        apk.parentFile!!.mkdirs()
        context.assets.open("bundled-apps/shizuku.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.appupdates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
