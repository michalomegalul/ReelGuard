package dev.reelsguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * The countdown shown while Reels, Shorts or TikTok use up the daily allowance.
 * It counts down by itself (no updates needed); on Android 16+ it is also a Live Update in the status bar.
 */
object AllowanceNotification {
    private const val CHANNEL = "allowance"
    private const val ID = 1
    private const val TIME_UP_TIMEOUT_MS = 10_000L
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun showCountdown(context: Context, leftMs: Long) {
        val builder = builder(context)
            .setContentTitle(context.getString(R.string.allowance_notification_title))
            .setContentText(context.getString(R.string.allowance_notification_text))
            .setWhen(System.currentTimeMillis() + leftMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
        // Same as setRequestPromotedOngoing(true), which needs SDK 36.1 to compile; older Androids ignore it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
        manager(context).notify(ID, builder.build())
    }

    /** Replaces the countdown when the allowance runs out, and goes away by itself. */
    fun showTimeUp(context: Context) {
        manager(context).notify(
            ID,
            builder(context)
                .setContentTitle(context.getString(R.string.allowance_time_up))
                .setTimeoutAfter(TIME_UP_TIMEOUT_MS)
                .build(),
        )
    }

    fun cancel(context: Context) = manager(context).cancel(ID)

    private fun builder(context: Context) =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setOnlyAlertOnce(true)

    /** Low importance: the countdown should be visible, not make a sound or pop up. */
    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.allowance_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
}
