package cu.axel.smartdock.services

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Visibility
import cu.axel.smartdock.R
import cu.axel.smartdock.activities.LAUNCHER_ACTION
import cu.axel.smartdock.activities.LAUNCHER_RESUMED
import cu.axel.smartdock.activities.MainActivity
import cu.axel.smartdock.adapters.AppAdapter
import cu.axel.smartdock.adapters.AppAdapter.OnAppClickListener
import cu.axel.smartdock.adapters.AppTaskAdapter
import cu.axel.smartdock.adapters.DockAppAdapter
import cu.axel.smartdock.adapters.DockAppAdapter.OnDockAppClickListener
import cu.axel.smartdock.db.DBHelper
import cu.axel.smartdock.models.App
import cu.axel.smartdock.models.AppTask
import cu.axel.smartdock.models.DockApp
import cu.axel.smartdock.utils.AppUtils
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.IconPackUtils
import cu.axel.smartdock.utils.Utils
import cu.axel.smartdock.widgets.HoverInterceptorLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

const val DOCK_SERVICE_CONNECTED = "service_connected"
const val ACTION_LAUNCH_APP = "launch_app"
const val DOCK_SERVICE_ACTION = "dock_service_action"

class DockService : AccessibilityService(), OnSharedPreferenceChangeListener, OnTouchListener,
    OnAppClickListener, OnDockAppClickListener {

    private var orientation = -1
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var activityManager: ActivityManager
    private lateinit var appsBtn: ImageView
    private lateinit var pinBtn: ImageView
    private lateinit var tasks: ArrayList<AppTask>
    private lateinit var topRightCorner: Button
    private lateinit var bottomRightCorner: Button
    private lateinit var dockHandle: Button
    private lateinit var appMenu: LinearLayout
    private lateinit var searchLayout: LinearLayout
    private lateinit var searchEntry: LinearLayout
    private lateinit var dockLayout: RelativeLayout
    private lateinit var windowManager: WindowManager
    private lateinit var appsSeparator: View
    private var appMenuVisible = false
    private var isPinned = false
    private var systemApp = false
    private var secondary = false
    var displayMode = "Kiosk"
    private var lastUpdate: Long = 0
    private lateinit var dockLayoutParams: WindowManager.LayoutParams
    private lateinit var searchEt: EditText
    private lateinit var tasksGv: RecyclerView
    private lateinit var favoritesGv: RecyclerView
    private lateinit var appsGv: RecyclerView
    private lateinit var db: DBHelper
    private lateinit var dockHandler: Handler
    private lateinit var dock: HoverInterceptorLayout
    private var maxApps = 0
    private var maxAppsLandscape = 0
    private lateinit var context: Context
    private var dockHeight: Int = 80
    private lateinit var handleLayoutParams: WindowManager.LayoutParams
    private lateinit var launcherApps: LauncherApps
    private var iconPackUtils: IconPackUtils? = null
    private var isMaintenanceMode = false
    private var isVolumePressed = false
    private var isVolumeUpPressed = false
    private var isVolumeDownPressed = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val LONG_PRESS_DELAY = 3000L

    private val longPressRunnable = Runnable {
        if (isVolumePressed) {
            volumeMaintenanceMode()
        }
    }


    override fun onCreate() {
        super.onCreate()
        db = DBHelper(this)
        activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        secondary = sharedPreferences.getBoolean("prefer_last_display", false)
        context = DeviceUtils.getDisplayContext(this, secondary)
        windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        dockHandler = Handler(Looper.getMainLooper())
        if (sharedPreferences.getString("icon_pack", "")!!.isNotEmpty()) {
            iconPackUtils = IconPackUtils(this)
        }

        SocketIOConnection.setSocket(context)
        SocketIOConnection.setDockService(this)
        SocketIOConnection.getSocket().connect()

        Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 999999999)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Utils.startupTime = System.currentTimeMillis()
        systemApp = AppUtils.isSystemApp(context, packageName)
        maxApps = sharedPreferences.getString("max_running_apps", "10")!!.toInt()
        maxAppsLandscape = sharedPreferences.getString("max_running_apps_landscape", "10")!!.toInt()
        orientation = resources.configuration.orientation

        //Create the dock
        dock = LayoutInflater.from(
            androidx.appcompat.view.ContextThemeWrapper(
                context,
                R.style.AppTheme_Dock
            )
        ).inflate(R.layout.dock, null) as HoverInterceptorLayout
        dockLayout = dock.findViewById(R.id.dock_layout)
        dockHandle = LayoutInflater.from(context).inflate(R.layout.dock_handle, null) as Button

        val icon1 = ContextCompat.getDrawable(context, R.drawable.circle)
        val icon2 = ContextCompat.getDrawable(context, R.drawable.ic_dock)

        val layers = arrayOf(icon1, icon2)
        val layerDrawable = LayerDrawable(layers)

        dockHandle.background = layerDrawable
        appsBtn = dock.findViewById(R.id.apps_btn)
        tasksGv = dock.findViewById(R.id.apps_lv)
        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        tasksGv.layoutManager = layoutManager
        pinBtn = dock.findViewById(R.id.pin_btn)
        dockLayout.visibility = View.GONE
        dock.visibility = View.GONE
        dock.setOnTouchListener(this)
        dockLayout.setOnTouchListener(this)
        dockHandle.setOnClickListener { pinDock() }
        appsBtn.setOnClickListener { toggleAppMenu() }
        appsBtn.setOnLongClickListener {
            launchApp(
                null, null,
                Intent(Settings.ACTION_APPLICATION_SETTINGS)
            )
            true
        }

        pinBtn.setOnClickListener { unpinDock() }

        dockLayoutParams = Utils.makeWindowParams(-1, dockHeight, context, secondary)
        dockLayoutParams.screenOrientation =
            if (sharedPreferences.getBoolean("lock_landscape", false))
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        dockLayoutParams.gravity = Gravity.BOTTOM or Gravity.START
        windowManager.addView(dock, dockLayoutParams)

        //Hot corners
        topRightCorner = Button(context)
        topRightCorner.setBackgroundResource(R.drawable.corner_background)
        bottomRightCorner = Button(context)
        bottomRightCorner.setBackgroundResource(R.drawable.corner_background)
        topRightCorner.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                val handler = Handler(mainLooper)
                handler.postDelayed({
                    if (topRightCorner.isHovered) performGlobalAction(
                        GLOBAL_ACTION_RECENTS
                    )
                }, sharedPreferences.getString("hot_corners_delay", "300")!!.toInt().toLong())
            }
            false
        }
        bottomRightCorner.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                val handler = Handler(mainLooper)
                handler.postDelayed({
                    if (bottomRightCorner.isHovered) DeviceUtils.lockScreen(
                        context
                    )
                }, sharedPreferences.getString("hot_corners_delay", "300")!!.toInt().toLong())
            }
            false
        }
        updateCorners()
        val cornersLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 2), -2, context,
            secondary
        )
        cornersLayoutParams.gravity = Gravity.TOP or Gravity.END
        windowManager.addView(topRightCorner, cornersLayoutParams)
        cornersLayoutParams.gravity = Gravity.BOTTOM or Gravity.END
        windowManager.addView(bottomRightCorner, cornersLayoutParams)

        //App menu
        appMenu = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
            .inflate(R.layout.apps_menu, null) as LinearLayout
        searchEntry = appMenu.findViewById(R.id.search_entry)
        searchEt = appMenu.findViewById(R.id.menu_et)
        appsGv = appMenu.findViewById(R.id.menu_applist_lv)
        appsGv.setHasFixedSize(true)
        appsGv.layoutManager = GridLayoutManager(context, 5)
        favoritesGv = appMenu.findViewById(R.id.fav_applist_lv)
        favoritesGv.layoutManager = GridLayoutManager(context, 5)
        searchLayout = appMenu.findViewById(R.id.search_layout)
        appsSeparator = appMenu.findViewById(R.id.apps_separator)

        searchEt.addTextChangedListener { text ->
            if (text != null) {
                val appAdapter = appsGv.adapter as AppAdapter
                appAdapter.filter(text.toString())
                if (text.length > 1) {
                    searchLayout.visibility = View.VISIBLE
                    toggleFavorites(false)
                } else {
                    searchLayout.visibility = View.GONE
                    toggleFavorites(
                        AppUtils.getPinnedApps(
                            context,
                            AppUtils.PINNED_LIST
                        ).size > 0
                    )
                }
            }
        }

        searchEt.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_ENTER && searchEt.text.toString().length > 1) {
                    try {
                        launchApp(
                            null, null,
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://www.google.com/search?q="
                                            + URLEncoder.encode(searchEt.text.toString(), "UTF-8")
                                )
                            )
                        )
                    } catch (e: UnsupportedEncodingException) {
                        throw RuntimeException(e)
                    }
                    true
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN)
                    appsGv.requestFocus()
            }
            false
        }

        updateAppMenu()

        //TODO: Filter app button menu click only
        appMenu.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE
                && (event.y < appMenu.measuredHeight || event.x > appMenu.measuredWidth)
            ) {
                hideAppMenu()
            }
            false
        }

        //Dock handle
        handleLayoutParams = Utils.makeWindowParams(
            Utils.dpToPx(context, 22), -2, context,
            secondary
        )
        updateHandlePositionValues()

        //Listen for launcher messages
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    LAUNCHER_RESUMED -> pinDock()
                    ACTION_LAUNCH_APP -> launchApp(
                        intent.getStringExtra("mode"),
                        intent.getStringExtra("app")!!
                    )
                }
            }
        }, IntentFilter(LAUNCHER_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        //Tell the launcher the service has connected
        sendBroadcast(
            Intent(DOCK_SERVICE_ACTION)
                .setPackage(packageName)
                .putExtra("action", DOCK_SERVICE_CONNECTED)
        )

        ContextCompat.registerReceiver(
            this, object : BroadcastReceiver() {
                override fun onReceive(p1: Context, intent: Intent) {
                    applyTheme()
                }
            }, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        //Play startup sound
        DeviceUtils.playEventSound(context, "startup_sound")
        updateNavigationBar()
        updateQuickSettings()
        updateDockShape()
        applyTheme()
        updateMenuIcon()
        loadPinnedApps()
        placeRunningApps()
        windowManager.addView(dockHandle, handleLayoutParams)
        if (sharedPreferences.getBoolean("pin_dock", true))
            //pinDock()
        else
            Toast.makeText(context, R.string.start_message, Toast.LENGTH_LONG).show()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && !isVolumePressed) {
                    isVolumePressed = true

                    if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        Log.i("VolumeService", "Botão volume UP pressionado")
                        isVolumeUpPressed = true
                    } else if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        Log.i("VolumeService", "Botão volume DOWN pressionado")
                        isVolumeDownPressed = true
                    }

                    Log.i("VolumeService", "Volume pressionado. Iniciando timer de 15s.")
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DELAY)
                }

                if (event.action == KeyEvent.ACTION_UP) {
                    Log.i("VolumeService", "Volume liberado. Cancelando.")
                    longPressHandler.removeCallbacks(longPressRunnable)
                    isVolumePressed = false
                    isVolumeUpPressed = false
                    isVolumeDownPressed = false
                }
                return true
            }
        }
        return super.onKeyEvent(event)
    }



    override fun onDockAppClicked(app: DockApp, anchor: View) {
        val tasks = app.tasks
        if (tasks.size == 1) {
            val taskId = tasks[0].id
            if (taskId == -1)
                launchApp(null, app.packageName)
            else
                activityManager.moveTaskToFront(taskId, 0)
        } else if (tasks.size > 1) {
            val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
            val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
            ColorUtils.applyMainColor(context, sharedPreferences, view)
            layoutParams.gravity = Gravity.BOTTOM or Gravity.START
            layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            layoutParams.x = location[0]
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    windowManager.removeView(view)
                }
                false
            }
            val tasksLv = view.findViewById<ListView>(R.id.tasks_lv)
            tasksLv.adapter = AppTaskAdapter(context, tasks)
            tasksLv.setOnItemClickListener { adapterView, _, position, _ ->
                activityManager.moveTaskToFront(
                    (adapterView.getItemAtPosition(position) as AppTask).id, 0
                )
                windowManager.removeView(view)
            }
            windowManager.addView(view, layoutParams)
        } else launchApp(getDefaultLaunchMode(app.packageName), app.packageName)
        if (getDefaultLaunchMode(app.packageName) == "fullscreen") {
            if (isPinned && sharedPreferences.getBoolean("auto_unpin", true)) {
                unpinDock()
            }
        } else {
            if (!isPinned && sharedPreferences.getBoolean("auto_pin", true)) {
                pinDock()
            }
        }
    }

    override fun onDockAppLongClicked(app: DockApp, view: View) {
        showDockAppContextMenu(app, view)
    }

    override fun onInterrupt() {}

    override fun onAppClicked(app: App, item: View) {
        if (app.packageName == "$packageName.calc") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("results", app.name))
            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        } else launchApp(null, app.packageName, null, app)
    }

    override fun onAppLongClicked(app: App, view: View) {
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        checkAndLaunchDefaultApp()

        if (!isPinned)
            return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (Build.VERSION.SDK_INT >= 28)
                if (event.windowChanges.and(AccessibilityEvent.WINDOWS_CHANGE_REMOVED) == AccessibilityEvent.WINDOWS_CHANGE_REMOVED ||
                    event.windowChanges.and(
                        AccessibilityEvent.WINDOWS_CHANGE_ADDED
                    ) == AccessibilityEvent.WINDOWS_CHANGE_ADDED
                )
                    updateRunningTasks()
                else
                    updateRunningTasks()
        } else if (sharedPreferences.getBoolean(
                "custom_toasts",
                false
            ) && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.parcelableData !is Notification && event.text.size > 0
        ) {
            val text = event.text[0].toString()
            val app = event.packageName.toString()
            showToast(app, text)
        }
    }

    private fun showToast(app: String, text: String) {
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER
        layoutParams.y = dock.measuredHeight + Utils.dpToPx(context, 4)
        val toast = LayoutInflater.from(context).inflate(R.layout.toast, null)
        ColorUtils.applyMainColor(context, sharedPreferences, toast)
        val textTv = toast.findViewById<TextView>(R.id.toast_tv)
        val iconIv = toast.findViewById<ImageView>(R.id.toast_iv)
        textTv.text = text
        val notificationIcon = AppUtils.getAppIcon(context, app)
        iconIv.setImageDrawable(notificationIcon)
        ColorUtils.applyColor(iconIv, ColorUtils.getDrawableDominantColor(notificationIcon))
        toast.alpha = 0f
        toast.animate().alpha(1f).setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
        Handler(Looper.getMainLooper()).postDelayed({
            toast.animate().alpha(0f).setDuration(400)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        windowManager.removeView(toast)
                    }
                })
        }, 5000)
        windowManager.addView(toast, layoutParams)
    }





    private fun getDefaultLaunchMode(app: String?): String {
        return "fullscreen"
    }

    fun launchApp(
        mode: String?,
        packageName: String?,
        intent: Intent? = null,
        app: App? = null,
        displayId: Int = Display.DEFAULT_DISPLAY,
        newInstance: Boolean = false,
        rememberMode: Boolean = true
    ) {
        var launchMode = mode

        if (launchMode == null)
            launchMode = getDefaultLaunchMode(packageName)
        else
            if (rememberMode && sharedPreferences.getBoolean(
                    "remember_launch_mode",
                    true
                ) && packageName != null
            )
                db.saveLaunchMode(packageName, launchMode)

        val options = AppUtils.makeActivityOptions(context, launchMode, dockHeight, displayId)

        //Used only for work apps
        if (app != null && app.userHandle != Process.myUserHandle())
            launcherApps.startMainActivity(
                app.componentName,
                app.userHandle,
                null,
                options.toBundle()
            )
        else {
            val launchIntent: Intent? = if (intent == null && packageName != null)
                packageManager.getLaunchIntentForPackage(packageName)
            else
                intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (launchIntent == null)
                return

            if (newInstance)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(launchIntent, options.toBundle())
        }

        if (appMenuVisible)
            hideAppMenu()

        if (launchMode == "fullscreen" && sharedPreferences.getBoolean("auto_unpin", true)) {
            if (isPinned)
                unpinDock()
        } else {
            if (!isPinned && sharedPreferences.getBoolean("auto_pin", true))
                pinDock()
        }
        updateRunningTasks()
        this.unpinDock()
    }

    private fun setOrientation() {
        dockLayoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        windowManager.updateViewLayout(dock, dockLayoutParams)
    }

    private fun toggleAppMenu() {
        if (appMenuVisible)
            hideAppMenu()
        else
            showAppMenu()
    }

    fun showAppMenu() {
        val layoutParams: WindowManager.LayoutParams?
        val displayId =
            if (secondary) DeviceUtils.getSecondaryDisplay(context).displayId else Display.DEFAULT_DISPLAY
        val deviceWidth = DeviceUtils.getDisplayMetrics(context, displayId).widthPixels
        val deviceHeight = DeviceUtils.getDisplayMetrics(context, displayId).heightPixels
        val margins = Utils.dpToPx(context, 2)
        val navHeight = DeviceUtils.getNavBarHeight(context)
        val diff = if (dockHeight - navHeight > 0) dockHeight - navHeight else 0
        val usableHeight =
            if (DeviceUtils.shouldApplyNavbarFix())
                deviceHeight - margins - diff - DeviceUtils.getStatusBarHeight(context)
            else
                deviceHeight - dockHeight - DeviceUtils.getStatusBarHeight(context) - margins

            val width = Utils.dpToPx(
                context,
                sharedPreferences.getString("app_menu_width", "650")!!.toInt()
            )
            val height = Utils.dpToPx(
                context,
                sharedPreferences.getString("app_menu_height", "540")!!.toInt()
            )
            layoutParams = Utils.makeWindowParams(
                width.coerceAtMost(deviceWidth - margins * 2), height.coerceAtMost(usableHeight),
                context, secondary
            )
            layoutParams.x = margins
            layoutParams.y = margins + (dockHeight * 2)
            appsGv.layoutManager = GridLayoutManager(
                context,
                sharedPreferences.getString("num_columns", "5")!!.toInt()
            )
            favoritesGv.layoutManager = GridLayoutManager(
                context,
                sharedPreferences.getString("num_columns", "5")!!.toInt()
            )
            val padding = Utils.dpToPx(context, 10)
            appMenu.setPadding(padding, padding, padding, padding)
            searchEntry.gravity = Gravity.START
            searchLayout.gravity = Gravity.START
            appMenu.setBackgroundResource(R.drawable.round_rect)

        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        val halign = if (sharedPreferences.getBoolean(
                "center_app_menu",
                false
            )
        ) Gravity.CENTER else Gravity.CENTER
        layoutParams.gravity = Gravity.CENTER or halign
        ColorUtils.applyMainColor(context, sharedPreferences, appMenu)
        ColorUtils.applyColor(appsSeparator, ColorUtils.getMainColors(sharedPreferences, this)[4])
        windowManager.addView(appMenu, layoutParams)

        //Load apps
        updateAppMenu()

        //Load user info
        val avatarIv = appMenu.findViewById<ImageView>(R.id.avatar_iv)
        val userNameTv = appMenu.findViewById<TextView>(R.id.user_name_tv)
        avatarIv.setOnClickListener {
            if (AppUtils.isSystemApp(context, packageName))
                launchApp(null, null, Intent("android.settings.USER_SETTINGS"))
            else
                launchApp(null, null, Intent(this, MainActivity::class.java))
        }
        if (AppUtils.isSystemApp(context, packageName)) {
            val name = DeviceUtils.getUserName(context)
            if (name != null) userNameTv.text = name
            val icon = DeviceUtils.getUserIcon(context)
            if (icon != null) avatarIv.setImageBitmap(icon)
        } else {
            val name = sharedPreferences.getString("user_name", "")
            if (name!!.isNotEmpty()) userNameTv.text = name
            val iconUri = sharedPreferences.getString("user_icon_uri", "default")
            if (iconUri != "default") {
                val bitmap = Utils.getBitmapFromUri(context, Uri.parse(iconUri))
                val icon = Utils.getCircularBitmap(bitmap)
                if (icon != null)
                    avatarIv.setImageBitmap(icon)
            } else avatarIv.setImageResource(R.drawable.ic_user)
        }
        appMenu.alpha = 0f
        appMenu.animate().alpha(1f).setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())

        //Work around android showing the ime system ui bar
        val softwareKeyboard =
            context.resources.configuration.keyboard == Configuration.KEYBOARD_NOKEYS
        val tabletMode = sharedPreferences.getInt("dock_layout", -1) == 1

        searchEt.showSoftInputOnFocus = softwareKeyboard || tabletMode
        searchEt.requestFocus()

        appMenuVisible = true
    }

    fun hideAppMenu() {
        searchEt.setText("")
        windowManager.removeView(appMenu)
        appMenuVisible = false
    }

    private suspend fun fetchInstalledApps(): ArrayList<App> = withContext(Dispatchers.Default) {
        return@withContext AppUtils.getInstalledApps(context)
    }

    private fun updateAppMenu() {
        CoroutineScope(Dispatchers.Default).launch {
            val hiddenApps = sharedPreferences.getStringSet(
                "hidden_apps_grid",
                setOf()
            )!!
            val apps = fetchInstalledApps().filterNot { hiddenApps.contains(it.packageName) }

            withContext(Dispatchers.Main) {
                var menuFullscreen = sharedPreferences.getBoolean("app_menu_fullscreen", false)
                menuFullscreen = false
                val phoneLayout = sharedPreferences.getInt("dock_layout", -1) == 0
                //TODO: Implement efficient adapter
                appsGv.adapter = AppAdapter(
                    context, apps, this@DockService,
                    menuFullscreen && !phoneLayout, iconPackUtils
                )
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showDockAppContextMenu(app: App, anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.pin_entry, null)
        val pinLayout = view.findViewById<LinearLayout>(R.id.pin_entry_pin)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, secondary)
        view.setBackgroundResource(R.drawable.round_rect)
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.START
        layoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.y = Utils.dpToPx(context, 2) + dockHeight
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE)
                windowManager.removeView(view)

            false
        }
        val icon = view.findViewById<ImageView>(R.id.pin_entry_iv)
        ColorUtils.applySecondaryColor(context, sharedPreferences, icon)
        val text = view.findViewById<TextView>(R.id.pin_entry_tv)
        if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST)) {
            icon.setImageResource(R.drawable.ic_unpin)
            text.setText(R.string.unpin)
            val moveLayout = view.findViewById<LinearLayout>(R.id.pin_entry_move)
            moveLayout.visibility = View.VISIBLE
            val moveLeft = view.findViewById<ImageView>(R.id.pin_entry_left)
            val moveRight = view.findViewById<ImageView>(R.id.pin_entry_right)
            ColorUtils.applySecondaryColor(context, sharedPreferences, moveLeft)
            ColorUtils.applySecondaryColor(context, sharedPreferences, moveRight)
            moveLeft.setOnClickListener {
                AppUtils.moveApp(this, app, AppUtils.DOCK_PINNED_LIST, 0)
                loadPinnedApps()
                updateRunningTasks()
            }
            moveRight.setOnClickListener {
                AppUtils.moveApp(this, app, AppUtils.DOCK_PINNED_LIST, 1)
                loadPinnedApps()
                updateRunningTasks()
            }
        }
        pinLayout.setOnClickListener {
            if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST))
                AppUtils.unpinApp(
                    context,
                    app.packageName,
                    AppUtils.DOCK_PINNED_LIST
                ) else
                AppUtils.pinApp(context, app, AppUtils.DOCK_PINNED_LIST)
            loadPinnedApps()
            updateRunningTasks()
            windowManager.removeView(view)
        }
        windowManager.addView(view, layoutParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onSharedPreferenceChanged(p1: SharedPreferences, preference: String?) {
        if (preference == null)
            return
        if (preference.startsWith("theme"))
            applyTheme()
        else if (preference == "menu_icon_uri")
            updateMenuIcon()
        else if (preference.startsWith("icon_")) {
            val iconPack = sharedPreferences.getString("icon_pack", "")!!
            iconPackUtils = if (iconPack.isNotEmpty()) {
                IconPackUtils(this)
            } else
                null
            updateRunningTasks()
        } else if (preference == "tint_indicators")
            updateRunningTasks()
        else if (preference == "lock_landscape")
            setOrientation()
        else if (preference == "center_running_apps") {
            placeRunningApps()
            updateRunningTasks()
        } else if (preference == "dock_activation_area")
            updateDockTrigger()
        else if (preference.startsWith("enable_corner_"))
            updateCorners()
        else if (preference.startsWith("enable_nav_")) {
            updateNavigationBar()
        } else if (preference.startsWith("enable_qs_")) {
            updateQuickSettings()
        } else if (preference == "round_dock")
            updateDockShape()
        else if (preference.startsWith("max_running_apps")) {
            maxApps = sharedPreferences.getString("max_running_apps", "10")!!.toInt()
            maxAppsLandscape =
                sharedPreferences.getString("max_running_apps_landscape", "10")!!.toInt()
            updateRunningTasks()
        } else if (preference == "activation_method") {
            updateActivationMethod()
        } else if (preference == "handle_opacity")
            dockHandle.alpha = sharedPreferences.getString("handle_opacity", "0.5")!!.toFloat()
        else if (preference == "dock_height")
            updateDockHeight()
        else if (preference == "handle_position")
            updateHandlePosition()
        else if (preference == "show_battery_level")
            updateBatteryBtn()
    }

    private fun updateDockTrigger() {
        if (!isPinned) {
            val height = sharedPreferences.getString("dock_activation_area", "10")!!.toInt()
            dockLayoutParams.height = Utils.dpToPx(context, height)
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }
    }

    private fun updateActivationMethod() {}

    private fun updateDockHeight() {
        if (isPinned) {
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }
    }

    private fun placeRunningApps() {}

    private fun loadPinnedApps() {}


    val whiteListedApps = mutableListOf("")

    fun getForegroundApp(): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val appList = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            time - 1000 * 10,
            time
        )

        return if (!appList.isNullOrEmpty()) {
            val sortedList = appList.sortedByDescending { it.lastTimeUsed }
            sortedList[0].packageName
        } else {
            "null"
        }
    }

    var lastCheckedApp: String? = null

    fun checkAndLaunchDefaultApp(force: Boolean = false) {
        val foregroundApp = getForegroundApp()

        if (foregroundApp == lastCheckedApp && !force) return  // evita repetição

        lastCheckedApp = foregroundApp

        if(!isMaintenanceMode && !whiteListedApps.contains(foregroundApp)){
            launchApp("fullscreen", whiteListedApps[0])
        }
    }

    fun openDefaultApp(){
        this.launchApp("fullscreen", whiteListedApps[0])
    }

    private fun updateRunningTasks() { }

    fun updateDockApps() {
        if(this.displayMode == "Free"){
            val apps = ArrayList<DockApp>()

            for(app in AppUtils.getInstalledApps(context)){
                if (whiteListedApps.contains(app.packageName)){
                    apps.add(DockApp(app.name, app.packageName, app.icon))
                }
            }

            val gridSize = Utils.dpToPx(context, 52)

            tasksGv.layoutParams.width = gridSize * apps.size
            tasksGv.adapter = DockAppAdapter(context, apps, this, iconPackUtils)
        }
    }

    private fun volumeMaintenanceMode() {
        if (isVolumeUpPressed) {
            setMaintenanceMode()
        }
        if (isVolumeDownPressed) {
            removeMaintenanceMode()
        }
    }

    fun setMaintenanceMode() {
        isMaintenanceMode = true
        showDock()

    }

    fun removeMaintenanceMode() {
        isMaintenanceMode = false
        appsBtn.visibility = View.GONE
        setMode(displayMode)
    }

    fun showDock() {
        dock.visibility = View.VISIBLE
        dockLayout.visibility = View.VISIBLE
        dockHandle.visibility = View.GONE

        if(isMaintenanceMode){
            appsBtn.visibility = View.VISIBLE
        }
    }

    fun hideDock() {
        dock.visibility = View.GONE
        dockLayout.visibility = View.GONE

        if(isMaintenanceMode){
            appsBtn.visibility = View.GONE
        }
    }

    fun pinDock() {
        isPinned = true
        pinBtn.setImageResource(R.drawable.arrow_down)
        pinBtn.layoutParams.width = 50
        pinBtn.layoutParams.height = 50
        if(isMaintenanceMode || displayMode != "Kiosk"){
            dockHandle.visibility = View.GONE
            showDock()
        }

    }

    private fun unpinDock() {
        isPinned = false
        hideDock()
        pinBtn.setImageResource(R.drawable.arrow_down)
        pinBtn.layoutParams.width = 50
        pinBtn.layoutParams.height = 50
        if(isMaintenanceMode || displayMode != "Kiosk"){
            dockHandle.visibility = View.VISIBLE
        }
    }

    fun setMode(mode: String) {
        displayMode = mode

        when (mode) {
            "Kiosk" -> {
                hideDock()
                dockHandle.visibility = View.GONE
                appsBtn.visibility = View.GONE
                launchApp("fullscreen", whiteListedApps[0])
            }
            "Free" -> {
                appsBtn.visibility = View.GONE
                checkAndLaunchDefaultApp(true)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orientation = newConfig.orientation
        updateRunningTasks()
    }

    private fun updateDockShape() {
        dockLayout.setBackgroundResource(R.drawable.rect)
        ColorUtils.applyMainColor(context, sharedPreferences, dockLayout)
    }

    private fun updateNavigationBar() {
        appsBtn.visibility = View.VISIBLE
            //if (isMaintenanceMode) View.VISIBLE else View.GONE
    }

    private fun updateQuickSettings() {

        pinBtn.visibility =
            if (sharedPreferences.getBoolean("enable_qs_pin", true)) View.VISIBLE else View.VISIBLE
    }

    fun applyTheme() {
        ColorUtils.applyMainColor(context, sharedPreferences, dockLayout)
        ColorUtils.applyMainColor(context, sharedPreferences, appMenu)
        ColorUtils.applySecondaryColor(context, sharedPreferences, searchEntry)
        ColorUtils.applySecondaryColor(context, sharedPreferences, pinBtn)
    }

    private fun updateCorners() {
        topRightCorner.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_top_right",
                false
            )
        ) View.VISIBLE else View.GONE
        bottomRightCorner.visibility = if (sharedPreferences.getBoolean(
                "enable_corner_bottom_right",
                false
            )
        ) View.VISIBLE else View.GONE
    }

    private fun updateMenuIcon() {
        val iconUri = sharedPreferences.getString("menu_icon_uri", "default")
        if (iconUri == "default") appsBtn.setImageResource(R.drawable.ic_apps_menu) else {
            try {
                val icon = Uri.parse(iconUri)
                if (icon != null)
                    appsBtn.setImageURI(icon)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateBatteryBtn() {
    }

    private fun toggleFavorites(visible: Boolean) {
        favoritesGv.visibility = if (visible) View.VISIBLE else View.GONE
        appsSeparator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateHandlePositionValues() {
        handleLayoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER
        handleLayoutParams.height = 50
        handleLayoutParams.width = 50
        handleLayoutParams.y = 10

        dockHandle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.rect,
            0,
            0,
            0
        )
    }

    private fun updateHandlePosition() {
        updateHandlePositionValues()
        windowManager.updateViewLayout(dockHandle, handleLayoutParams)
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        return false
    }

    override fun onDestroy() {
        //TODO: Unregister all receivers
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        try {
            windowManager.removeView(dock)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
