package cu.axel.smartdock.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.URISyntaxException
import cu.axel.smartdock.utils.AppUtils
import cu.axel.smartdock.utils.Utils
import org.json.JSONObject
import java.io.ByteArrayOutputStream


data class InstalledApp(
    val appName: String,
    val packageName: String,
    val iconBase64: String
)

data class InstalledAppResponse(
    val installedApp: MutableList<InstalledApp>
)


object SocketIOConnection {

    private lateinit var mySocket: Socket

    private lateinit var dockService: DockService

    val Context.dataStore by preferencesDataStore(name = "settings")
    val APPID_KEY = stringPreferencesKey("appid")
    val DEFAULTAPP_KEY = stringPreferencesKey("defaultapp")

    @Synchronized
    fun getAppIdSync(context: Context): String? = runBlocking {
        val preferences = context.dataStore.data.first()
        preferences[APPID_KEY]
    }

    @Synchronized
    fun saveAppIdSync(context: Context, appid: String) = runBlocking {
        context.dataStore.edit { prefs ->
            prefs[APPID_KEY] = appid
        }
    }

    @Synchronized
    fun getDefaultAppSync(context: Context): String? = runBlocking {
        val preferences = context.dataStore.data.first()
        preferences[DEFAULTAPP_KEY]
    }

    @Synchronized
    fun saveDefaultAppSync(context: Context, defaultApp: String) = runBlocking {
        context.dataStore.edit { prefs ->
            prefs[DEFAULTAPP_KEY] = defaultApp
        }
    }


    @Synchronized
    fun setSocket(context: Context) {
        try {
            val opts = IO.Options()

            val appId = getAppIdSync(context)
            opts.query = "appId=${appId ?: "undefined"}"

            Log.i("SocketIOConnection", "Usando appid na query: ${appId ?: "undefined"}")

            mySocket = IO.socket("http://192.168.1.221:3001", opts)


            mySocket.on(Socket.EVENT_CONNECT) {
                Log.i("Socket.io", "Socket conectado com sucesso")
            }

            mySocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    Log.e("SocketIO", "Erro de conexão: ${args.joinToString()}")
                }

            mySocket.on(Socket.EVENT_DISCONNECT) {
                    Log.i("SocketIO", "Desconectado do servidor")
                }

            mySocket.on("DeviceId") { args ->
                val deviceId = args.joinToString()
                Log.i("SocketIO", "DeviceId recebido: $deviceId")
                saveAppIdSync(context, deviceId)

                // Recriar socket com novo username
                reconnectWithUpdatedAppId(context)
                mySocket.connect()
            }

            mySocket.on("isMaintanenceMode") { args ->
                val resposta = args.joinToString()
                Log.i("Server Responde - mode", "Mensagem do servidor: ${resposta}")
                Handler(Looper.getMainLooper()).post {
                    if(resposta == "false"){
                        this.dockService.removeMaintenanceMode()
                    }else{
                        this.dockService.setMaintenanceMode()
                    }
                }
            }

            mySocket.on("kiosk-mode-app") { args ->
                val resposta = args.joinToString()
                Log.i("Server Responde-kiosk-mode-app", "Mensagem do servidor: ${resposta}")

                Handler(Looper.getMainLooper()).post {
                    this.dockService.setMode("Kiosk")
                    this.dockService.whiteListedApps.clear()
                    this.dockService.whiteListedApps.add(resposta)
                    this.dockService.openDefaultApp()
                }

            }

            mySocket.on("free-mode-apps") { args ->
                val resposta = args.joinToString()
                Log.i("Server Responde-free-mode-apps", "Mensagem do servidor: ${resposta}")

                Handler(Looper.getMainLooper()).post {
                    this.dockService.setMode("Free")

                    this.dockService.whiteListedApps.clear()
                    for(app in resposta.split(",")){
                        this.dockService.whiteListedApps.add(app)
                    }

                    this.dockService.updateDockApps()
                    this.dockService.openDefaultApp()
                    this.dockService.pinDock()
                }
            }


            mySocket.on("default-app") { args ->
                val resposta = args.joinToString()

                Handler(Looper.getMainLooper()).post {
                    this.dockService.hideDock()
                    this.dockService.whiteListedApps[0] = resposta
                    this.dockService.launchApp("fullscreen", this.dockService.whiteListedApps[0])
                }

            }

            mySocket.on("updateapps") { args ->
                val apps = AppUtils.getInstalledApps(context)

                val installedApps: MutableList<InstalledApp> = mutableListOf()

                for (app in apps) {
                    val installedApp = InstalledApp(
                        appName = app.name,
                        packageName = app.packageName,
                        iconBase64 = drawableToBase64(app.icon)
                    )
                    installedApps.add(installedApp)
                }

                val installedAppsResponse = InstalledAppResponse(
                    installedApp = installedApps
                )

                // Serializa para JSON usando Gson
                val gson = Gson()
                val json = gson.toJson(installedAppsResponse)
                val jsonObject = JSONObject(json)

                // Emite o objeto JSON no socket
                mySocket.emit("update-apps-response", jsonObject)

            }
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    // Recria o socket com o novo username salvo
    @Synchronized
    fun reconnectWithUpdatedAppId(context: Context) {
        Log.i("SocketIO", "Recriando socket com novo AppId")

        mySocket.apply {
            off() // remove todos os listeners
            disconnect()
            close()
        }

        setSocket(context) // cria novo socket com nova query
    }

    // Permite acessar o socket externamente
    @Synchronized
    fun getSocket(): Socket {
        return mySocket
    }

    fun setDockService(service: DockService) {
        this.dockService = service
    }

    fun drawableToBase64(drawable: Drawable, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): String {
        // 1. Converter para Bitmap
        val bitmap = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }

        // 2. Converter para Base64
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(format, 100, outputStream)
        val byteArray = outputStream.toByteArray()

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
