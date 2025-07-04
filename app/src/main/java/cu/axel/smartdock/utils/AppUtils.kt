package cu.axel.smartdock.utils

import android.app.ActivityOptions
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserManager
import android.view.Display
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.PreferenceManager
import cu.axel.smartdock.models.App
import java.io.File

object AppUtils {
    const val PINNED_LIST = "pinned.lst"
    const val DOCK_PINNED_LIST = "dock_pinned.lst"
    var currentApp = ""

    fun getInstalledApps(context: Context): ArrayList<App> {
        val apps = ArrayList<App>()
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        var appsInfo = mutableListOf<LauncherActivityInfo>()
        for (profile in userManager.userProfiles) appsInfo.addAll(
            launcherApps.getActivityList(
                null,
                profile
            )
        )

        appsInfo = appsInfo.sortedWith(compareBy { it.label.toString() }).toMutableList()

        //TODO: Filter Google App
        for (appInfo in appsInfo) {
            apps.add(
                App(
                    appInfo.label.toString(),
                    appInfo.componentName.packageName,
                    appInfo.getIcon(0),
                    appInfo.componentName,
                    appInfo.user
                )
            )
        }
        return apps
    }

    fun getPinnedApps(context: Context, type: String): ArrayList<App> {
        val file = File(context.filesDir, type)
        val apps = ArrayList<App>()
        val appsInfo = mutableListOf<LauncherActivityInfo>()
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        if (file.exists()) {
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val info = line.split(" ")
                val packageName = info[0]
                val userHandle = userManager.getUserForSerialNumber(info[1].toLong())
                val list = launcherApps.getActivityList(packageName, userHandle)
                if (list.isNullOrEmpty()) unpinApp(context, packageName, type)
                appsInfo.addAll(list)
            }
        }

        for (appInfo in appsInfo) {
            apps.add(
                App(
                    appInfo.label.toString(),
                    appInfo.componentName.packageName,
                    appInfo.getIcon(0),
                    appInfo.componentName,
                    appInfo.user
                )
            )
        }
        return apps
    }

    fun pinApp(context: Context, app: App, type: String) {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val file = File(context.filesDir, type)
        file.appendText("${app.packageName} ${userManager.getSerialNumberForUser(app.userHandle)}\n")
    }

    fun unpinApp(context: Context, packageName: String, type: String) {
        val file = File(context.filesDir, type)
        val updatedList = file.readLines().filter { it.split(" ")[0] != packageName }
        if (updatedList.isNotEmpty()) file.writeText(updatedList.joinToString("\n") + "\n")
        else {
            file.writeText("")
        }
    }

    fun moveApp(context: Context, app: App, type: String, direction: Int) {
        val file = File(context.filesDir, type)
        val lines = file.readLines().toMutableList()

        val lineIndex = lines.indexOfFirst { it.split(" ")[0] == app.packageName }

        if (lineIndex != -1) {
            if (direction == 0 && lineIndex > 0) {
                val line = lines.removeAt(lineIndex)
                lines.add(lineIndex - 1, line)
            } else if (direction == 1 && lineIndex < lines.size - 1) {
                val line = lines.removeAt(lineIndex)
                lines.add(lineIndex + 1, line)
            }

            file.writeText(lines.joinToString("\n") + "\n")
        }
    }

    fun isPinned(context: Context, app: App, type: String): Boolean {
        val file = File(context.filesDir, type)
        if (!file.exists()) return false
        file.readLines().forEach { line ->
            if (line.split(" ")[0] == app.packageName) return true
        }
        return false
    }

    fun isSystemApp(context: Context, app: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(app, 0)
            appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getAppIcon(context: Context, app: String): Drawable {
        return try {
            context.packageManager.getApplicationIcon(app)
        } catch (_: PackageManager.NameNotFoundException) {
            AppCompatResources.getDrawable(context, android.R.drawable.sym_def_app_icon)!!
        }
    }

    private fun makeLaunchBounds(
        context: Context, mode: String, dockHeight: Int, displayId: Int = Display.DEFAULT_DISPLAY
    ): Rect {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
        val deviceWidth = DeviceUtils.getDisplayMetrics(context, displayId).widthPixels
        val deviceHeight = DeviceUtils.getDisplayMetrics(context, displayId).heightPixels
        val statusHeight = DeviceUtils.getStatusBarHeight(context)
        val navHeight = DeviceUtils.getNavBarHeight(context)
        val diff = if (dockHeight - navHeight > 0) dockHeight - navHeight else 0

        val usableHeight =
            if (DeviceUtils.shouldApplyNavbarFix()) deviceHeight - diff - DeviceUtils.getStatusBarHeight(
                context
            )
            else deviceHeight - dockHeight - DeviceUtils.getStatusBarHeight(context)
        val scaleFactor = sharedPreferences.getString("scale_factor", "1.0")!!.toFloat()
        when (mode) {
            "standard" -> {
                left = (deviceWidth / (5 * scaleFactor)).toInt()
                top = ((usableHeight + statusHeight) / (7 * scaleFactor)).toInt()
                right = deviceWidth - left
                bottom = usableHeight + dockHeight - top
            }

            "maximized" -> {
                right = deviceWidth
                bottom = usableHeight
            }

            "portrait" -> {
                left = deviceWidth / 3
                top = usableHeight / 15
                right = deviceWidth - left
                bottom = usableHeight + dockHeight - top
            }

            "tiled-left" -> {
                right = deviceWidth / 2
                bottom = usableHeight
            }

            "tiled-top" -> {
                right = deviceWidth
                bottom = (usableHeight + statusHeight) / 2
            }

            "tiled-right" -> {
                left = deviceWidth / 2
                right = deviceWidth
                bottom = usableHeight
            }

            "tiled-bottom" -> {
                right = deviceWidth
                top = (usableHeight + statusHeight) / 2
                bottom = usableHeight + statusHeight
            }
        }
        return Rect(left, top, right, bottom)
    }

    fun makeActivityOptions(
        context: Context, mode: String, dockHeight: Int, displayId: Int
    ): ActivityOptions {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val secondary = sharedPreferences.getBoolean("prefer_last_display", false)

        val display: Int =
            if (displayId != Display.DEFAULT_DISPLAY) displayId else (if (secondary) DeviceUtils.getSecondaryDisplay(
                context
            ).displayId else displayId)
        val options: ActivityOptions = ActivityOptions.makeBasic()

        val windowMode: Int
        if (mode == "fullscreen") windowMode = 1
        else {
            windowMode = if (Build.VERSION.SDK_INT >= 28) 5 else 2
            options.setLaunchBounds(
                makeLaunchBounds(
                    context, mode, dockHeight, display
                )
            )
        }
        if (Build.VERSION.SDK_INT > 28) options.setLaunchDisplayId(display)
        val methodName =
            if (Build.VERSION.SDK_INT >= 28) "setLaunchWindowingMode" else "setLaunchStackId"
        val method = ActivityOptions::class.java.getMethod(methodName, Int::class.javaPrimitiveType)
        method.invoke(options, windowMode)

        return options
    }
}
