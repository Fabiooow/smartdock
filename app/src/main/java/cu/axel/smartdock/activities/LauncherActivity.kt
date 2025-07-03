package cu.axel.smartdock.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import cu.axel.smartdock.R
import cu.axel.smartdock.adapters.AppActionsAdapter
import cu.axel.smartdock.adapters.AppAdapter.OnAppClickListener
import cu.axel.smartdock.models.Action
import cu.axel.smartdock.models.App
import cu.axel.smartdock.services.ACTION_LAUNCH_APP
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.IconPackUtils
import cu.axel.smartdock.utils.Utils

const val LAUNCHER_ACTION = "launcher_action"
const val LAUNCHER_RESUMED = "launcher_resumed"

open class LauncherActivity : AppCompatActivity(), OnAppClickListener,
    OnSharedPreferenceChangeListener {
    private var iconPackUtils: IconPackUtils? = null
    private lateinit var sharedPreferences: SharedPreferences
    private var x = 0f
    private var y = 0f
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        val backgroundLayout = findViewById<LinearLayout>(R.id.ll_background)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        backgroundLayout.setOnLongClickListener {
            val view = LayoutInflater.from(this).inflate(R.layout.task_list, null)
            val layoutParams = Utils.makeWindowParams(-2, -2, this)
            ColorUtils.applyMainColor(this, sharedPreferences, view)
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            layoutParams.x = x.toInt()
            layoutParams.y = y.toInt()
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    windowManager.removeView(view)
                }
                false
            }
            val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
            val actions = ArrayList<Action>()
            actions.add(Action(R.drawable.ic_wallpaper, getString(R.string.change_wallpaper)))
            actions.add(Action(R.drawable.ic_fullscreen, getString(R.string.display_settings)))
            actionsLv.adapter = AppActionsAdapter(this, actions)
            actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
                val action = adapterView.getItemAtPosition(position) as Action
                if (action.text == getString(R.string.change_wallpaper)) startActivityForResult(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SET_WALLPAPER),
                        getString(R.string.change_wallpaper)
                    ), 18
                ) else if (action.text == getString(R.string.display_settings)) startActivity(
                    Intent(
                        Settings.ACTION_DISPLAY_SETTINGS
                    )
                )
                windowManager.removeView(view)
            }
            windowManager.addView(view, layoutParams)
            true
        }
        backgroundLayout.setOnTouchListener { _, event ->
            x = event.x
            y = event.y
            false
        }

        if (sharedPreferences.getString("icon_pack", "")!!.isNotEmpty()) {
            iconPackUtils = IconPackUtils(this)
        }
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {}

    private fun launchApp(mode: String?, app: String) {
        sendBroadcast(
            Intent(LAUNCHER_ACTION)
                .setPackage(packageName)
                .putExtra("action", ACTION_LAUNCH_APP)
                .putExtra("mode", mode)
                .putExtra("app", app)
        )
    }

    override fun onAppClicked(app: App, item: View) {
        launchApp("fullscreen", app.packageName)
    }

    override fun onAppLongClicked(app: App, item: View) {
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        preference: String?
    ) {
        if (preference == null)
            return
        if (preference == "icon_pack") {
            val iconPack = sharedPreferences.getString("icon_pack", "")!!
            iconPackUtils = if (iconPack.isNotEmpty()) {
                IconPackUtils(this)
            } else
                null
        }
    }
}
