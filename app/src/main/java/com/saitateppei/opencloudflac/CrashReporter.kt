package com.saitateppei.opencloudflac

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 落ちたときのスタックトレースを内部ストレージに残し、次回起動時に画面で見せる。
 * adb が無くても原因を共有できるようにするため(9.1)。トークン等は含まれない(例外の内容のみ)。
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val file = File(ctx.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText(
                    "version: ${BuildConfig.VERSION_NAME}\n" +
                        "android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "thread: ${thread.name}\n\n" + sw
                )
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, e)
        }
    }

    fun take(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        return f.readText().also { f.delete() }
    }
}
