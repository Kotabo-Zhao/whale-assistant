"""Compile a reviewed Kotlin screen as a separate, permission-free Android preview APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = "whale-native-screen-v1"
NAMESPACE = "io.deepseekharness.candidate"


def put(root, relative, text):
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def build(source, title, out):
    data = source.read_bytes()
    if not 0 < len(data) <= 96 * 1024 or not 0 < len(title.strip()) <= 50:
        raise ValueError("功能源码或标题长度无效")
    code = data.decode("utf-8-sig").replace("\r\n", "\n")
    revision = hashlib.sha256((CONTRACT + "\0" + title + "\0" + code).encode()).hexdigest()
    package = NAMESPACE + ".f" + revision[:24]
    android = Path(os.environ["ANDROID_HOME"])
    java = Path(os.environ["JAVA_HOME"])
    win = os.name == "nt"
    wrapper = ROOT / "android" / ("gradlew.bat" if win else "gradlew")
    keytool = java / "bin" / ("keytool.exe" if win else "keytool")
    aapt = android / "build-tools" / "35.0.0" / ("aapt.exe" if win else "aapt")
    if not all(path.is_file() for path in (wrapper, keytool, aapt)):
        raise ValueError("缺少项目 Gradle 包装器、JDK 或 Android build-tools 35.0.0")
    out.mkdir(parents=True, exist_ok=False)
    put(out, "Feature.kt", code)
    with tempfile.TemporaryDirectory(prefix="whale-native-") as directory:
        project = Path(directory)
        put(project, "settings.gradle", """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = 'WhaleNativePreview'
include ':app'
""")
        # Only trusted project build files and one plain Kotlin source enter the compiler.
        shutil.copyfile(ROOT / "android/build.gradle", project / "build.gradle")
        put(project, "gradle.properties", "android.useAndroidX=true\nandroid.overridePathCheck=true\n")
        put(project, "app/build.gradle", """plugins { id 'com.android.application'; id 'org.jetbrains.kotlin.android' }
android {
    namespace 'io.deepseekharness.candidate'
    compileSdk 35
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = '17' }
    defaultConfig { applicationId '%s'; minSdk 26; targetSdk 35; versionCode 1; versionName '1' }
    signingConfigs {
        preview { storeFile rootProject.file('preview.jks'); storePassword 'preview'; keyAlias 'preview'; keyPassword 'preview' }
    }
    buildTypes { debug { signingConfig signingConfigs.preview } }
}
""" % package)
        put(project, "app/src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:label="鲸鱼候选功能" android:allowBackup="false" android:usesCleartextTraffic="false"
      android:theme="@android:style/Theme.Material.Light.NoActionBar">
    <activity android:name=".PreviewActivity" android:exported="true">
      <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
      <intent-filter><action android:name="io.deepseekharness.mobile.action.NATIVE_FEATURE"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>
    </activity>
  </application>
</manifest>
""")
        put(project, "app/src/main/java/Feature.kt", code)
        put(project, "app/src/main/java/PreviewActivity.kt", """package io.deepseekharness.candidate
import android.app.Activity
import android.os.Bundle
class PreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(Feature.create(this))
    }
}
""")
        subprocess.run([str(keytool), "-genkeypair", "-keystore", str(project/"preview.jks"),
                        "-storepass", "preview", "-keypass", "preview", "-alias", "preview", "-keyalg", "RSA",
                        "-dname", "CN=Whale Native Preview", "-validity", "365"], check=True)
        subprocess.run([str(wrapper), "--offline", "--no-daemon", "-p", str(project), ":app:assembleDebug"], check=True)
        apk = project / "app/build/outputs/apk/debug/app-debug.apk"
        manifest = subprocess.check_output([str(aapt), "dump", "badging", str(apk)]).decode("utf-8", "replace")
        if not manifest.startswith("package: name='%s'" % package) or any(
                line.startswith("uses-permission") for line in manifest.splitlines()):
            raise ValueError("候选包标识或权限与预期不符")
        data = apk.read_bytes()
        metadata = dict(contract=CONTRACT, title=title, revision=revision, package=package,
                        activity=NAMESPACE + ".PreviewActivity", apkSha256=hashlib.sha256(data).hexdigest())
        (out / "preview.apk").write_bytes(data)
        put(out, "preview.json", json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(out / "preview.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--title", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    build(args.source, args.title, args.out)
