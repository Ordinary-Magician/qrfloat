package com.qrfloat.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 保存最近收到的内容，自动复制到剪贴板并发通知。
 */
object ReceivedStore {
    var latest: String? = null
        private set
    var onChange: (() -> Unit)? = null

    fun load() { latest = Prefs.getLastReceived() }

    fun add(text: String) {
        latest = text
        Prefs.setLastReceived(text)
        onChange?.invoke()
        copyToClipboard(text)
        notify(text)
    }

    private fun copyToClipboard(text: String) {
        val cm = App.instance.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qr", text))
    }

    private fun notify(text: String) {
        val ctx = App.instance
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val summary = if (text.length > 80) text.substring(0, 80) + "…" else text
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(ctx, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qr)
            .setContentTitle("收到二维码内容")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
        // 内容若是链接，加「打开」按钮，由 Android 自动路由到对应 App（软件无关）
        toUrl(text)?.let { url ->
            val openPi = PendingIntent.getActivity(ctx, 1, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "打开", openPi)
        }
        val n = builder.build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2, n)
    }

    private fun toUrl(text: String): String? {
        val t = text.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("weixin://") || t.startsWith("wemeet://") ||
                t.startsWith("tencent://") || t.startsWith("meeting://") -> t
            else -> null
        }
    }
}
