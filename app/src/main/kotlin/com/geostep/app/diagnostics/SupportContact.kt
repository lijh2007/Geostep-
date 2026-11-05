package com.geostep.app.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.geostep.app.BuildConfig

object SupportContact {
    const val EMAIL = "1590887927@qq.com"
    const val AUTHOR = "sayonara"
    const val AUTHOR_GITHUB = "https://github.com/lijh2007"
    const val UPSTREAM_AUTHOR = "jie8190"
    const val UPSTREAM_GITHUB = "https://github.com/jie8190/MockGeo"
    const val UPSTREAM_EMAIL = "kprxr.660@qq.com"
    fun emailIntent(): Intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", EMAIL, null))
        .putExtra(Intent.EXTRA_SUBJECT, "Geostep ${BuildConfig.VERSION_NAME} 使用反馈")

    fun attachmentIntent(uri: Uri): Intent {
        require(uri.scheme == "content")
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Geostep ${BuildConfig.VERSION_NAME} 诊断反馈")
            putExtra(Intent.EXTRA_TEXT, "问题现象：\n\n发生时间：\n\n操作步骤：\n\n是否在熄屏或切到后台后发生：\n\n已附上诊断日志，请在发送前确认附件。")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Geostep 诊断日志", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Offer only mail-capable packages; the user reviews the draft and sends it. */
    @Suppress("DEPRECATION")
    fun attachmentChooser(context: Context, uri: Uri): Intent? {
        val pm = context.packageManager
        val choices = pm.queryIntentActivities(emailIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.distinct()
            .map { attachmentIntent(uri).setPackage(it) }.filter { it.resolveActivity(pm) != null }
        if (choices.isEmpty()) return null
        return Intent.createChooser(choices.first(), "选择邮箱发送诊断日志").apply {
            if (choices.size > 1) putExtra(Intent.EXTRA_INITIAL_INTENTS, choices.drop(1).toTypedArray())
            clipData = choices.first().clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
