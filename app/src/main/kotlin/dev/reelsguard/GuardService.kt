package dev.reelsguard

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Closes Reels, Shorts and TikTok once the daily allowance is used up, and keeps Settings/installer screens away from the app. */
class GuardService : AccessibilityService() {
    private var lastContentCheck = 0L
    private var lastReelsAction = 0L
    private val seenPosts = LinkedHashSet<String>()
    private var settingsWindowClass: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var returningToTop = false
    private val store by lazy { PasswordStore(this) }

    /** The app whose viewer is using up the daily allowance right now, and when its time was last counted. */
    private var watchingPackage: String? = null
    private var lastTick = 0L

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopWatching()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val reelIds = Guard.REEL_VIEW_IDS[packageName]
        val isTiktok = packageName in Guard.TIKTOK_APPS && store.blockTiktok
        val relevant = isTiktok ||
            reelIds != null ||
            packageName in Guard.GATED_PACKAGES
        if (!relevant) return
        store.startNewDay()
        if (!store.blocking) return

        // Only window-state events carry the Activity class; content events carry the class of some view.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            settingsWindowClass = event.className?.toString()
        }

        // Content changes arrive in bursts; window changes are rare and always checked.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastContentCheck < CONTENT_CHECK_INTERVAL_MS) return
            lastContentCheck = now
        }

        // The event can come from a background window; only act on what the user is looking at.
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != packageName) return

        when {
            isTiktok -> shortVideoOpened(root, packageName)
            reelIds != null -> when {
                reelIds.any { hasViewId(root, it) } -> if (isBlocked(packageName)) shortVideoOpened(root, packageName)
                packageName == Guard.INSTAGRAM_PACKAGE -> limitFeed(root)
            }
            else -> guardSettings(root, settingsWindowClass)
        }
    }

    override fun onInterrupt() {}

    private fun isBlocked(packageName: String) = when (packageName) {
        in Guard.TIKTOK_APPS -> store.blockTiktok
        Guard.YOUTUBE_PACKAGE -> store.blockShorts
        else -> store.blockReels
    }

    /** Lets the viewer stay open while there is daily allowance left, otherwise closes it. */
    private fun shortVideoOpened(root: AccessibilityNodeInfo, packageName: String) {
        if (watchingPackage != null) return
        val left = store.allowanceLeftMs
        if (left <= 0) {
            close(root, packageName)
            return
        }
        watchingPackage = packageName
        lastTick = SystemClock.elapsedRealtime()
        AllowanceNotification.showCountdown(this, left)
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick = Runnable { checkWatching() }

    /** Adds the time since the last tick to today's usage, and stops when the viewer is gone or the time is up. */
    private fun checkWatching() {
        val packageName = watchingPackage ?: return
        store.startNewDay()
        val now = SystemClock.elapsedRealtime()
        store.addUsage(now - lastTick)
        lastTick = now

        val root = rootInActiveWindow
        val rootPackage = root?.packageName?.toString()
        val onScreen = when (rootPackage) {
            // Pulling down the notification shade to look at the countdown does not leave the viewer.
            SYSTEM_UI -> true
            packageName -> packageName in Guard.TIKTOK_APPS ||
                Guard.REEL_VIEW_IDS[packageName].orEmpty().any { hasViewId(root, it) }
            else -> false
        }
        val stillGuarded = store.blocking && isBlocked(packageName) && store.dailyMinutes > 0
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        when {
            !onScreen || !stillGuarded || !interactive -> stopWatching()
            store.allowanceLeftMs <= 0 -> {
                stopWatching()
                AllowanceNotification.showTimeUp(this)
                if (root != null && rootPackage == packageName) close(root, packageName)
            }
            else -> handler.postDelayed(tick, TICK_MS)
        }
    }

    private fun stopWatching() {
        if (watchingPackage == null) return
        handler.removeCallbacks(tick)
        store.addUsage(SystemClock.elapsedRealtime() - lastTick)
        watchingPackage = null
        AllowanceNotification.cancel(this)
    }

    private fun close(root: AccessibilityNodeInfo, packageName: String) {
        if (packageName in Guard.TIKTOK_APPS) performGlobalAction(GLOBAL_ACTION_HOME) else leaveReels(root, packageName)
    }

    /** Taps the feed tab so the app stays open; Back is the fallback (e.g. a Reel opened from a DM). */
    private fun leaveReels(root: AccessibilityNodeInfo, packageName: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReelsAction < REELS_ACTION_COOLDOWN_MS) return
        lastReelsAction = now

        if (!tapHomeTab(root, packageName)) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Lets the user pass [PasswordStore.feedPostLimit] distinct posts in Instagram's home feed, then taps the
     * already selected Home tab, which scrolls back to the top. Stories are not posts and are not counted.
     */
    private fun limitFeed(root: AccessibilityNodeInfo) {
        if (!store.feedLimitEnabled || returningToTop) return
        val limit = store.feedPostLimit
        val homeTab = root.findAccessibilityNodeInfosByViewId(Guard.INSTAGRAM_FEED_TAB).firstOrNull()
        if (homeTab?.isSelected != true) {
            seenPosts.clear() // Left the home feed: the next visit starts a fresh count.
            return
        }

        root.findAccessibilityNodeInfosByViewId(Guard.INSTAGRAM_POST_HEADER)
            .filter { it.isVisibleToUser }
            .forEach { header -> header.contentDescription?.toString()?.let { seenPosts.add(postKey(it)) } }
        if (seenPosts.size <= limit) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastReelsAction < REELS_ACTION_COOLDOWN_MS) return
        lastReelsAction = now
        returningToTop = true
        scrollFeedToTop(steps = 0)
    }

    /**
     * Scrolls the feed list back up page by page, which unlike tapping the Home tab does not refresh the feed.
     * Falls back to the tab tap when the list cannot be scrolled this way.
     */
    private fun scrollFeedToTop(steps: Int) {
        val root = rootInActiveWindow
        val list = root?.takeIf { it.packageName?.toString() == Guard.INSTAGRAM_PACKAGE }
            ?.findAccessibilityNodeInfosByViewId(Guard.INSTAGRAM_FEED_LIST)
            ?.firstOrNull { it.isVisibleToUser && it.isScrollable }
        val scrolled = list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        when {
            scrolled && steps < MAX_SCROLL_STEPS -> handler.postDelayed({ scrollFeedToTop(steps + 1) }, SCROLL_STEP_MS)
            // Never managed to scroll at all: use the tab tap rather than leave the user in the feed.
            !scrolled && steps == 0 && root != null -> {
                tapHomeTab(root, Guard.INSTAGRAM_PACKAGE)
                finishReturn()
            }
            else -> finishReturn()
        }
    }

    private fun finishReturn() {
        seenPosts.clear()
        returningToTop = false
    }

    /** "name posted a video 5 hours ago" -> "name posted a video": the age changes while the post stays the same. */
    private fun postKey(description: String) = description.replace(Regex(" \\d+ \\w+ ago$"), "")

    private fun tapHomeTab(root: AccessibilityNodeInfo, packageName: String): Boolean {
        var tab: AccessibilityNodeInfo? = if (packageName == Guard.YOUTUBE_PACKAGE) {
            Guard.YOUTUBE_HOME_TABS.firstNotNullOfOrNull { label ->
                root.findAccessibilityNodeInfosByText(label)
                    .firstOrNull { it.contentDescription?.toString() == label }
            }
        } else {
            root.findAccessibilityNodeInfosByViewId(Guard.INSTAGRAM_FEED_TAB).firstOrNull { it.isVisibleToUser }
        }
        while (tab != null && !tab.isClickable) tab = tab.parent
        return tab?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun guardSettings(root: AccessibilityNodeInfo, className: String?) {
        if (!store.settingsLock || !hasText(root, getString(R.string.app_name))) return
        // The "activate device admin" screen shows our name too; closing it made activation impossible.
        // Once the admin is active, that screen is gated like the rest.
        val adminActive = getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(ComponentName(this, GuardAdminReceiver::class.java))
        if (!adminActive && className?.contains("DeviceAdminAdd") == true) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Instagram keeps the Reels views in memory after you leave the tab, so only visible ones count. */
    private fun hasViewId(root: AccessibilityNodeInfo, viewId: String) =
        root.findAccessibilityNodeInfosByViewId(viewId).any { it.isVisibleToUser }

    private fun hasText(root: AccessibilityNodeInfo, text: String) =
        root.findAccessibilityNodeInfosByText(text).isNotEmpty()

    private companion object {
        const val CONTENT_CHECK_INTERVAL_MS = 250L
        const val REELS_ACTION_COOLDOWN_MS = 1000L
        const val MAX_SCROLL_STEPS = 25
        const val SCROLL_STEP_MS = 120L
        const val TICK_MS = 2000L
        const val SYSTEM_UI = "com.android.systemui"
    }
}
