package dev.reelsguard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** First launch: set the password. Afterwards: status, per-feature switches and the password-protected actions. */
class MainActivity : Activity() {
    private lateinit var store: PasswordStore
    private lateinit var admin: ComponentName
    private lateinit var dpm: DevicePolicyManager

    /** The current page, so a redraw after a tap keeps the scroll position instead of jumping to the top. */
    private var scrollView: ScrollView? = null

    /** Changes that are not applied yet (Apply writes them to [PasswordStore]); null when the screen shows the saved settings. */
    private var draft: GuardSettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PasswordStore(this)
        admin = ComponentName(this, GuardAdminReceiver::class.java)
        dpm = getSystemService(DevicePolicyManager::class.java)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        store.startNewDay()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(header())
        if (store.isSet) addDashboard(content) else addSetup(content)
        // Pushes the footer to the bottom of the screen when the page is shorter than it.
        content.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(footer())
        val scrollY = scrollView?.scrollY ?: 0
        val page = ScrollView(this).apply {
            isFillViewport = true
            // Only scrolls when the page is taller than the screen, and without the bounce when it fits.
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
            post { scrollTo(0, scrollY) }
        }
        scrollView = page
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(pageColor)
                fitsSystemWindows = true
                addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                val pending = draft
                if (store.isSet && pending != null) addView(applyBar(pending))
            },
        )
    }

    /** Without a password the minutes slider sets tomorrow's minutes; today's are fixed. */
    private fun saved() = store.settings.let { if (store.selfLock) it.copy(dailyMinutes = store.tomorrowMinutes) else it }

    /** What the dashboard shows: the unapplied changes, or the saved settings. */
    private fun shown() = draft ?: saved()

    /** Records a change; changing a setting back to its saved value leaves nothing to apply. */
    private fun edit(change: GuardSettings.() -> GuardSettings) {
        draft = shown().change().takeIf { it != saved() }
        render()
    }

    /** Stays at the bottom while there are changes; asks for the password only when they loosen something. */
    private fun applyBar(pending: GuardSettings) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(cardColor, 0f).apply { setStroke(dp(1), borderColor) }
        val loosens = pending.loosens(saved())
        val needsPassword = loosens && !store.selfLock
        addView(TextView(context).apply {
            text = getString(R.string.pending_title)
            textSize = 16f
            setTextColor(accent)
        })
        val password = passwordField(getString(R.string.password))
        if (needsPassword) {
            addView(label(getString(R.string.pending_password)))
            addView(password)
        } else if (loosens) {
            addView(label(getString(R.string.pending_tomorrow)))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(textButton(getString(R.string.discard)) {
                draft = null
                render()
            })
            addView(primaryButton(getString(R.string.apply)) {
                if (!needsPassword || checkPassword(password)) {
                    save(pending)
                    render()
                }
            }.apply { layoutParams = weighted().apply { marginStart = dp(8) } })
        })
    }

    private fun save(settings: GuardSettings) {
        draft = null
        if (settings.dailyMinutes > 0) askForNotifications()
        if (!store.selfLock) {
            store.settings = settings
            return
        }
        // Without a password, whatever is stricter applies now and the rest waits for tomorrow.
        val current = store.settings
        val now = current.tightenedBy(settings.copy(dailyMinutes = current.dailyMinutes))
        val later = (store.queued ?: current).withChanges(saved(), settings).copy(dailyMinutes = now.dailyMinutes)
        store.tomorrowMinutes = settings.dailyMinutes
        store.settings = now
        store.queued = later.takeIf { it != now }
    }

    private fun addSetup(root: LinearLayout) {
        val password = passwordField(getString(R.string.password))
        val repeat = passwordField(getString(R.string.password_repeat))
        var minutes = 0
        root.addView(
            section(getString(R.string.setup_title)).apply {
                addView(label(getString(R.string.setup_hint)))
                addView(password)
                addView(repeat)
                addView(label(getString(R.string.allowance_hint)))
                addView(
                    slider(getString(R.string.allowance_minutes), minutes, changed = false) { minutes = it },
                )
                addView(
                    primaryButton(getString(R.string.save_password)) {
                        val value = password.text.toString()
                        when {
                            value.length < Guard.MIN_PASSWORD_LENGTH ->
                                toast(getString(R.string.password_too_short, Guard.MIN_PASSWORD_LENGTH))
                            value != repeat.text.toString() -> toast(getString(R.string.password_mismatch))
                            else -> {
                                store.set(value)
                                store.dailyMinutes = minutes
                                if (minutes > 0) askForNotifications()
                                render()
                            }
                        }
                    },
                )
                addView(label(getString(R.string.setup_self_lock_hint)).apply { setPadding(0, dp(16), 0, 0) })
                addView(textButton(getString(R.string.setup_self_lock)) {
                    store.startSelfLock(minutes)
                    if (minutes > 0) askForNotifications()
                    render()
                })
            },
        )
    }

    private fun addDashboard(root: LinearLayout) {
        val shown = shown()
        val saved = saved()
        val queued = store.queued
        /** Whether a change of this setting is waiting for tomorrow (self-lock only). */
        fun waits(setting: GuardSettings.() -> Any) = queued != null && queued.setting() != saved.setting()

        root.addView(statusCard(shown.protectionOn, saved.protectionOn, waits { protectionOn }))
        root.addView(
            section(getString(R.string.card_blocking)).apply {
                addView(label(getString(if (store.selfLock) R.string.password_hint_self_lock else R.string.password_hint)))
                addView(toggle(getString(R.string.toggle_reels), shown.blockReels, saved.blockReels, waits { blockReels }) {
                    edit { copy(blockReels = it) }
                })
                addView(toggle(getString(R.string.toggle_shorts), shown.blockShorts, saved.blockShorts, waits { blockShorts }) {
                    edit { copy(blockShorts = it) }
                })
                addView(toggle(getString(R.string.toggle_tiktok), shown.blockTiktok, saved.blockTiktok, waits { blockTiktok }) {
                    edit { copy(blockTiktok = it) }
                })
                addView(
                    toggle(
                        getString(R.string.toggle_feed_limit),
                        shown.feedLimitEnabled,
                        saved.feedLimitEnabled,
                        waits { feedLimitEnabled },
                    ) { edit { copy(feedLimitEnabled = it) } },
                )
                if (shown.feedLimitEnabled) {
                    addView(feedLimitStepper(shown.feedPostLimit, saved.feedPostLimit, waits { feedPostLimit }))
                }
                addView(
                    toggle(getString(R.string.toggle_settings_lock), shown.settingsLock, saved.settingsLock, waits { settingsLock }) {
                        edit { copy(settingsLock = it) }
                    },
                )
                if (queued != null) {
                    addView(textButton(getString(R.string.cancel_tomorrow)) {
                        store.queued = null
                        render()
                    })
                }
            },
        )
        root.addView(allowanceCard(shown.dailyMinutes, saved.dailyMinutes))
        if (store.hasMaster && !store.selfLock) {
            root.addView(textButton(getString(R.string.forgot_password)) { showMasterReset() })
        }
    }

    private fun showMasterReset() {
        val field = passwordField(getString(R.string.master_hint))
        AlertDialog.Builder(this)
            .setTitle(R.string.master_title)
            .setView(field)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.master_reset) { _, _ ->
                val lockout = Guard.lockoutSecondsLeft()
                when {
                    lockout > 0 -> toast(getString(R.string.locked_out, lockout))
                    Guard.tryMasterReset(store, field.text.toString()) -> render()
                    else -> toast(getString(R.string.master_wrong))
                }
            }
            .show()
    }

    /** The main switch turns all protection off and on; like the others, switching it off needs the password on Apply. */
    private fun statusCard(protectionOn: Boolean, savedValue: Boolean, waiting: Boolean): LinearLayout {
        val serviceOn = isServiceEnabled()
        val adminOn = dpm.isAdminActive(admin)
        return section(null).apply {
            addView(toggle(getString(R.string.toggle_protection), protectionOn, savedValue, waiting) {
                edit { copy(protectionOn = it) }
            }.apply { textSize = 18f })
            addView(label(getString(if (store.selfLock) R.string.access_hint_self_lock else R.string.access_hint)))
            addView(statusRow(getString(R.string.accessibility_label), serviceOn))
            if (!serviceOn) {
                addView(label(getString(R.string.restricted_hint)))
                addView(textButton(getString(R.string.open_app_info)) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                    )
                })
                addView(textButton(getString(R.string.open_accessibility)) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                })
            }
            addView(statusRow(getString(R.string.admin_label), adminOn))
            if (!adminOn) {
                addView(textButton(getString(R.string.activate_admin)) {
                    startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation)),
                    )
                })
            }
        }
    }

    /** Allowing more posts needs the password on Apply; fewer is free. */
    private fun feedLimitStepper(value: Int, savedValue: Int, waiting: Boolean): LinearLayout {
        fun change(delta: Int) = edit { copy(feedPostLimit = (feedPostLimit + delta).coerceIn(1, MAX_FEED_LIMIT)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, dp(8))
            addView(label(pendingText(getString(R.string.feed_limit_posts), value != savedValue, waiting)).apply {
                layoutParams = weighted()
                if (value != savedValue || waiting) setTextColor(accent)
            })
            addView(textButton("−") { change(-1) })
            addView(TextView(context).apply {
                text = value.toString()
                textSize = 18f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                minWidth = dp(32)
            })
            addView(textButton("+") { change(1) })
        }
    }

    /** Raising the allowance needs the password on Apply; lowering it is free. */
    private fun allowanceCard(value: Int, savedValue: Int) = section(getString(R.string.card_allowance)).apply {
        val minutesLeft = ((store.allowanceLeftMs + 59_999) / 60_000).toInt()
        addView(label(getString(R.string.allowance_hint)))
        if (store.selfLock) {
            addView(label(getString(R.string.allowance_tomorrow_hint, Guard.DEFAULT_SELF_LOCK_MINUTES)))
        }
        val sliderLabel = if (store.selfLock) R.string.allowance_minutes_tomorrow else R.string.allowance_minutes
        addView(
            slider(getString(sliderLabel), value, changed = value != savedValue) { minutes ->
                edit { copy(dailyMinutes = minutes) }
            },
        )
        if (store.selfLock) {
            addView(label(getString(R.string.allowance_today, store.dailyMinutes, minutesLeft)))
        } else if (store.dailyMinutes > 0) {
            addView(label(getString(R.string.allowance_left, minutesLeft)))
        }
        if (store.dailyMinutes > 0) {
            if (!notificationsAllowed()) {
                addView(label(getString(R.string.allowance_no_notifications)))
                addView(textButton(getString(R.string.allow_notifications)) { askForNotifications() })
            }
        }
    }

    /** Minutes in steps of [ALLOWANCE_STEP]; [change] gets the value when the finger lifts. */
    private fun slider(text: String, value: Int, changed: Boolean, change: (Int) -> Unit): LinearLayout {
        val number = TextView(this).apply {
            this.text = value.toString()
            textSize = 18f
            setTextColor(if (changed) accent else textColor)
        }
        val step = ALLOWANCE_STEP
        val bar = SeekBar(this).apply {
            max = MAX_ALLOWANCE / step
            progress = value / step
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setPadding(0, dp(8), 0, dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    number.text = (progress * step).toString()
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    change(bar.progress * step)
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(pendingText(text, changed)).apply {
                    layoutParams = weighted()
                    if (changed) setTextColor(accent)
                })
                addView(number)
            })
            addView(bar)
        }
    }

    /** The countdown notification needs this permission on Android 13+; the allowance works without it. */
    private fun notificationsAllowed() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun askForNotifications() {
        if (!notificationsAllowed()) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    private fun footer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(16), 0, 0)
        addView(label(getString(R.string.footer_version, BuildConfig.VERSION_NAME)).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        })
        addView(label(getString(R.string.footer_source)).apply {
            textSize = 12f
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
        })
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), 0, dp(16))
        addView(ImageView(context).apply {
            setImageDrawable(packageManager.getApplicationIcon(packageName))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        addView(TextView(context).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTextColor(textColor)
            setPadding(dp(12), 0, 0, 0)
        })
    }

    private fun statusRow(name: String, on: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        addView(label(name).apply { layoutParams = weighted() })
        addView(TextView(context).apply {
            text = getString(if (on) R.string.state_on else R.string.state_off)
            textSize = 14f
            setTextColor(onAccent)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = rounded(if (on) good else bad, 100f)
        })
    }

    /** Only changes the draft; [savedValue] marks the switch while it differs from what is applied. */
    private fun toggle(text: String, checked: Boolean, savedValue: Boolean, waiting: Boolean, change: (Boolean) -> Unit) =
        Switch(this).apply {
            this.text = pendingText(text, checked != savedValue, waiting)
            textSize = 16f
            setTextColor(if (checked != savedValue || waiting) accent else textColor)
            isChecked = checked
            setPadding(0, dp(12), 0, dp(12))
            val checkedState = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(checkedState, intArrayOf(accent, getColor(R.color.switch_thumb_off)))
            trackTintList = ColorStateList(
                checkedState,
                intArrayOf(getColor(R.color.switch_track_on), getColor(R.color.switch_track_off)),
            )
            setOnCheckedChangeListener { _, on -> change(on) }
        }

    /** Marks a setting that is changed but not applied, or (self-lock) applied but waiting for tomorrow. */
    private fun pendingText(text: String, changed: Boolean, waiting: Boolean = false) = when {
        changed -> getString(R.string.not_applied, text)
        waiting -> getString(R.string.changes_tomorrow, text)
        else -> text
    }

    /** Shows why a password was refused; the field is cleared after a wrong one. */
    private fun checkPassword(field: EditText): Boolean {
        val lockout = Guard.lockoutSecondsLeft()
        if (lockout > 0) {
            toast(getString(R.string.locked_out, lockout))
            return false
        }
        if (Guard.checkPassword(store, field.text.toString())) return true
        field.text.clear()
        val newLockout = Guard.lockoutSecondsLeft()
        toast(if (newLockout > 0) getString(R.string.locked_out, newLockout) else getString(R.string.wrong_password))
        return false
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val service = ComponentName(this, GuardService::class.java)
        return enabled.split(':').any {
            it.equals(service.flattenToString(), ignoreCase = true) ||
                it.equals(service.flattenToShortString(), ignoreCase = true)
        }
    }

    /** One part of the page; titled sections are separated from the one above by a thin line. */
    private fun section(title: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        if (title != null) {
            addView(View(context).apply {
                setBackgroundColor(borderColor)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    bottomMargin = dp(16)
                }
            })
            addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTextColor(accent)
                setPadding(0, 0, 0, dp(4))
            })
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(secondaryText)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun passwordField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        backgroundTintList = ColorStateList.valueOf(accent)
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(onAccent)
        background = rounded(accent, 100f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(8) }
    }

    private fun textButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(accent)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private val pageColor get() = getColor(R.color.background)
    private val cardColor get() = getColor(R.color.card)
    private val borderColor get() = getColor(R.color.border)
    private val textColor get() = getColor(R.color.text)
    private val secondaryText get() = getColor(R.color.text_secondary)
    private val accent get() = getColor(R.color.accent)
    private val onAccent get() = getColor(R.color.on_accent)
    private val good get() = getColor(R.color.good)
    private val bad get() = getColor(R.color.bad)

    private companion object {
        const val MAX_FEED_LIMIT = 20
        const val REPO_URL = "https://github.com/michalomegalul/ReelGuard"
        const val ALLOWANCE_STEP = 5
        const val MAX_ALLOWANCE = 120
    }
}
