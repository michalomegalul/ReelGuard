package dev.reelsguard

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Shared configuration and password throttling. */
object Guard {
    /** TikTok variants; only limited while [PasswordStore.blockTiktok] is on. */
    val TIKTOK_APPS: Set<String> = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go",
    )

    /**
     * Apps with a Reels-style viewer, mapped to view IDs that only exist while that viewer is on screen.
     * Verified on Instagram's Reels tab (absent from the home feed), but Instagram can rename them
     * in any update: re-check with `adb shell uiautomator dump` (see README.md).
     */
    val REEL_VIEW_IDS = mapOf(
        "com.instagram.android" to listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_viewer_container",
        ),
        // Verified on the Shorts player (absent from the Home feed).
        "com.google.android.youtube" to listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_watch_player",
            "com.google.android.youtube:id/reel_player_page_container",
        ),
    )

    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    /** One per post in Instagram's home feed; its description is unique ("x posted a video 5 hours ago"). */
    const val INSTAGRAM_POST_HEADER = "com.instagram.android:id/row_feed_profile_header"

    /** The home feed's scrollable list; scrolled back up instead of refreshing the feed. */
    const val INSTAGRAM_FEED_LIST = "android:id/list"

    /** Instagram tab we switch to instead of pressing Back, which would leave the app. */
    const val INSTAGRAM_FEED_TAB = "com.instagram.android:id/feed_tab"

    /** YouTube's Home tab has no view ID, only a content description in the phone's language. */
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    val YOUTUBE_HOME_TABS = setOf("Home", "Domů", "Головна", "Главная")

    /** Screens that can switch this app off or remove it; closed while "Lock Settings screens" is on. */
    val GATED_PACKAGES = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.samsung.accessibility", // Accessibility screen on One UI
    )

    const val MIN_PASSWORD_LENGTH = 6
    const val DEFAULT_SELF_LOCK_MINUTES = 30
    private const val MAX_ATTEMPTS = 5
    private const val LOCKOUT_MS = 30_000L

    private var failedAttempts = 0
    private var lockedOutUntil = 0L

    fun lockoutSecondsLeft(): Int =
        ((lockedOutUntil - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)

    /** Checks the password with brute-force throttling. Nothing stays unlocked: every change asks again. */
    fun checkPassword(store: PasswordStore, password: String): Boolean {
        if (lockoutSecondsLeft() > 0 || password.isEmpty()) return false
        if (!store.verify(password)) {
            registerFailure()
            return false
        }
        failedAttempts = 0
        return true
    }

    /** Checks the build's master password (same throttling) and, when it is right, forgets the stored password. */
    fun tryMasterReset(store: PasswordStore, password: String): Boolean {
        if (lockoutSecondsLeft() > 0) return false
        if (!store.verifyMaster(password)) {
            registerFailure()
            return false
        }
        failedAttempts = 0
        store.clearPassword()
        return true
    }

    private fun registerFailure() {
        if (++failedAttempts >= MAX_ATTEMPTS) {
            failedAttempts = 0
            lockedOutUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
        }
    }
}

/** Everything the dashboard can change, as one snapshot. */
data class GuardSettings(
    val protectionOn: Boolean,
    val blockReels: Boolean,
    val blockShorts: Boolean,
    val blockTiktok: Boolean,
    val feedLimitEnabled: Boolean,
    val feedPostLimit: Int,
    val settingsLock: Boolean,
    val dailyMinutes: Int,
) {
    /** Switching something off or allowing more posts or minutes than [saved]. */
    fun loosens(saved: GuardSettings) =
        (saved.protectionOn && !protectionOn) ||
            (saved.blockReels && !blockReels) ||
            (saved.blockShorts && !blockShorts) ||
            (saved.blockTiktok && !blockTiktok) ||
            (saved.feedLimitEnabled && !feedLimitEnabled) ||
            (saved.settingsLock && !settingsLock) ||
            feedPostLimit > saved.feedPostLimit ||
            dailyMinutes > saved.dailyMinutes

    /** These settings, with every setting from [other] that is stricter. */
    fun tightenedBy(other: GuardSettings) = GuardSettings(
        protectionOn = protectionOn || other.protectionOn,
        blockReels = blockReels || other.blockReels,
        blockShorts = blockShorts || other.blockShorts,
        blockTiktok = blockTiktok || other.blockTiktok,
        feedLimitEnabled = feedLimitEnabled || other.feedLimitEnabled,
        feedPostLimit = minOf(feedPostLimit, other.feedPostLimit),
        settingsLock = settingsLock || other.settingsLock,
        dailyMinutes = minOf(dailyMinutes, other.dailyMinutes),
    )

    /** These settings, with every setting that differs between [from] and [to] taken from [to]. */
    fun withChanges(from: GuardSettings, to: GuardSettings) = GuardSettings(
        protectionOn = if (from.protectionOn != to.protectionOn) to.protectionOn else protectionOn,
        blockReels = if (from.blockReels != to.blockReels) to.blockReels else blockReels,
        blockShorts = if (from.blockShorts != to.blockShorts) to.blockShorts else blockShorts,
        blockTiktok = if (from.blockTiktok != to.blockTiktok) to.blockTiktok else blockTiktok,
        feedLimitEnabled = if (from.feedLimitEnabled != to.feedLimitEnabled) to.feedLimitEnabled else feedLimitEnabled,
        feedPostLimit = if (from.feedPostLimit != to.feedPostLimit) to.feedPostLimit else feedPostLimit,
        settingsLock = if (from.settingsLock != to.settingsLock) to.settingsLock else settingsLock,
        dailyMinutes = if (from.dailyMinutes != to.dailyMinutes) to.dailyMinutes else dailyMinutes,
    )
}

/** Stores a salted PBKDF2 hash of the password. */
class PasswordStore(context: Context) {
    private val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)

    init {
        // A build with a preset hash starts out protected, so the phone's owner never sees a setup screen.
        if (!prefs.contains(KEY_HASH) && !prefs.getBoolean(KEY_PRESET_USED, false) && BuildConfig.PRESET_HASH.isNotEmpty() && BuildConfig.PRESET_SALT.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_SALT, BuildConfig.PRESET_SALT)
                .putString(KEY_HASH, BuildConfig.PRESET_HASH)
                .putBoolean(KEY_PRESET_USED, true)
                .apply()
        }
    }

    val isSet: Boolean get() = prefs.contains(KEY_HASH) || selfLock

    /**
     * Set up without a password, for people who do not trust themselves: nothing asks for a password,
     * and loosening changes wait for the next day instead ([queued]).
     */
    val selfLock: Boolean get() = prefs.getBoolean(KEY_SELF_LOCK, false)

    fun startSelfLock(minutes: Int) {
        prefs.edit().putBoolean(KEY_SELF_LOCK, true).putInt(KEY_DAILY_MINUTES, minutes).apply()
        markDayStart()
    }

    var settings: GuardSettings
        get() = read("")
        set(value) = prefs.edit().apply { write(this, "", value) }.apply()

    /** Self-lock: the settings that take over when the next day starts; null when nothing is waiting. */
    var queued: GuardSettings?
        get() = if (prefs.contains(QUEUED + KEY_PROTECTION_ON)) read(QUEUED) else null
        set(value) = prefs.edit().apply {
            if (value != null) write(this, QUEUED, value) else SETTING_KEYS.forEach { remove(QUEUED + it) }
        }.apply()

    /** Self-lock: minutes for the next day, chosen today; [Guard.DEFAULT_SELF_LOCK_MINUTES] when nobody chose. */
    var tomorrowMinutes: Int
        get() = prefs.getInt(KEY_TOMORROW_MINUTES, Guard.DEFAULT_SELF_LOCK_MINUTES)
        set(value) = prefs.edit().putInt(KEY_TOMORROW_MINUTES, value).apply()

    /**
     * Self-lock: on the first check of a new day the waiting changes take over and tomorrow's minutes become today's.
     * The date can be moved forward in Settings, so the time until midnight must also have really passed
     * (elapsed realtime, which the clock does not change). After a reboot that counter restarts, so only the date counts.
     */
    fun startNewDay() {
        if (!selfLock || prefs.getLong(KEY_SELF_LOCK_DAY, 0L) >= today()) return
        val now = SystemClock.elapsedRealtime()
        val rebooted = now < prefs.getLong(KEY_DAY_STARTED_AT, 0L)
        if (!rebooted && now < prefs.getLong(KEY_NEXT_DAY_AT, 0L)) return
        settings = (queued ?: settings).copy(dailyMinutes = tomorrowMinutes)
        queued = null
        prefs.edit().remove(KEY_TOMORROW_MINUTES).apply()
        markDayStart()
    }

    private fun markDayStart() {
        val now = SystemClock.elapsedRealtime()
        val untilMidnight = Duration.between(LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay()).toMillis()
        prefs.edit()
            .putLong(KEY_SELF_LOCK_DAY, today())
            .putLong(KEY_DAY_STARTED_AT, now)
            .putLong(KEY_NEXT_DAY_AT, now + untilMidnight)
            .apply()
    }

    private fun read(prefix: String) = GuardSettings(
        protectionOn = prefs.getBoolean(prefix + KEY_PROTECTION_ON, true),
        blockReels = prefs.getBoolean(prefix + KEY_BLOCK_REELS, false),
        blockShorts = prefs.getBoolean(prefix + KEY_BLOCK_SHORTS, false),
        blockTiktok = prefs.getBoolean(prefix + KEY_BLOCK_TIKTOK, false),
        feedLimitEnabled = prefs.getBoolean(prefix + KEY_FEED_LIMIT_ON, false),
        feedPostLimit = prefs.getInt(prefix + KEY_FEED_LIMIT, DEFAULT_FEED_LIMIT),
        settingsLock = prefs.getBoolean(prefix + KEY_SETTINGS_LOCK, false),
        dailyMinutes = prefs.getInt(prefix + KEY_DAILY_MINUTES, 0),
    )

    private fun write(editor: SharedPreferences.Editor, prefix: String, value: GuardSettings) {
        editor.putBoolean(prefix + KEY_PROTECTION_ON, value.protectionOn)
            .putBoolean(prefix + KEY_BLOCK_REELS, value.blockReels)
            .putBoolean(prefix + KEY_BLOCK_SHORTS, value.blockShorts)
            .putBoolean(prefix + KEY_BLOCK_TIKTOK, value.blockTiktok)
            .putBoolean(prefix + KEY_FEED_LIMIT_ON, value.feedLimitEnabled)
            .putInt(prefix + KEY_FEED_LIMIT, value.feedPostLimit)
            .putBoolean(prefix + KEY_SETTINGS_LOCK, value.settingsLock)
            .putInt(prefix + KEY_DAILY_MINUTES, value.dailyMinutes)
    }

    /** Whether protection is active (blocking, feed limit, Settings gate). */
    val blocking: Boolean
        get() = prefs.getBoolean(KEY_PROTECTION_ON, true)

    /** Whether TikTok is closed on open (after the daily allowance). */
    val blockTiktok: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_TIKTOK, false)

    /** Minutes a day that blocked Reels, Shorts and TikTok stay open; 0 blocks them right away. */
    var dailyMinutes: Int
        get() = prefs.getInt(KEY_DAILY_MINUTES, 0)
        set(value) = prefs.edit().putInt(KEY_DAILY_MINUTES, value).apply()

    /** What is left of today's allowance, in ms. */
    val allowanceLeftMs: Long
        get() = (dailyMinutes * 60_000L - usedTodayMs()).coerceAtLeast(0L)

    fun addUsage(ms: Long) {
        prefs.edit()
            .putLong(KEY_USAGE_DAY, maxOf(today(), prefs.getLong(KEY_USAGE_DAY, 0L)))
            .putLong(KEY_USED_MS, usedTodayMs() + ms)
            .apply()
    }

    /** A new day only starts when the date moves forward, so setting the clock back does not refill the allowance. */
    private fun usedTodayMs(): Long =
        if (prefs.getLong(KEY_USAGE_DAY, 0L) < today()) 0L else prefs.getLong(KEY_USED_MS, 0L)

    private fun today(): Long = LocalDate.now().toEpochDay()

    /** Posts allowed in Instagram's home feed before it jumps back to the top. */
    val feedPostLimit: Int
        get() = prefs.getInt(KEY_FEED_LIMIT, DEFAULT_FEED_LIMIT)

    /** Per-feature switches, all off until someone turns them on. */
    val blockReels: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_REELS, false)

    val blockShorts: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORTS, false)

    val feedLimitEnabled: Boolean
        get() = prefs.getBoolean(KEY_FEED_LIMIT_ON, false)

    val settingsLock: Boolean
        get() = prefs.getBoolean(KEY_SETTINGS_LOCK, false)

    fun set(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash(password, salt), Base64.NO_WRAP))
            .apply()
    }

    /** Whether this build carries a master password hash (set in local.properties, never shown anywhere). */
    val hasMaster: Boolean get() = BuildConfig.MASTER_HASH.isNotEmpty() && BuildConfig.MASTER_SALT.isNotEmpty()

    fun verifyMaster(password: String): Boolean {
        if (!hasMaster || password.isEmpty()) return false
        return MessageDigest.isEqual(
            Base64.decode(BuildConfig.MASTER_HASH, Base64.NO_WRAP),
            hash(password, Base64.decode(BuildConfig.MASTER_SALT, Base64.NO_WRAP)),
        )
    }

    /** Forgets the password so the setup screen shows again; the preset hash is not restored. */
    fun clearPassword() {
        prefs.edit().remove(KEY_SALT).remove(KEY_HASH).putBoolean(KEY_PRESET_USED, true).apply()
    }

    fun verify(password: String): Boolean {
        if (password.isEmpty()) return false // PBEKeySpec rejects empty passwords
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(
            Base64.decode(expected, Base64.NO_WRAP),
            hash(password, Base64.decode(salt, Base64.NO_WRAP)),
        )
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    private companion object {
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
        const val KEY_PRESET_USED = "preset_used"
        const val KEY_PROTECTION_ON = "protection_on"
        const val KEY_FEED_LIMIT = "feed_limit"
        const val KEY_BLOCK_REELS = "block_reels"
        const val KEY_BLOCK_SHORTS = "block_shorts"
        const val KEY_FEED_LIMIT_ON = "feed_limit_on"
        const val KEY_SETTINGS_LOCK = "settings_lock"
        const val DEFAULT_FEED_LIMIT = 3
        const val KEY_BLOCK_TIKTOK = "block_tiktok"
        const val KEY_DAILY_MINUTES = "daily_minutes"
        const val KEY_USED_MS = "used_ms"
        const val KEY_USAGE_DAY = "usage_day"
        const val ITERATIONS = 120_000
        const val KEY_SELF_LOCK = "self_lock"
        const val KEY_SELF_LOCK_DAY = "self_lock_day"
        const val KEY_DAY_STARTED_AT = "self_lock_day_started_at"
        const val KEY_NEXT_DAY_AT = "self_lock_next_day_at"
        const val KEY_TOMORROW_MINUTES = "tomorrow_minutes"
        const val QUEUED = "queued_"
        val SETTING_KEYS = listOf(
            KEY_PROTECTION_ON, KEY_BLOCK_REELS, KEY_BLOCK_SHORTS, KEY_BLOCK_TIKTOK,
            KEY_FEED_LIMIT_ON, KEY_FEED_LIMIT, KEY_SETTINGS_LOCK, KEY_DAILY_MINUTES,
        )
    }
}
