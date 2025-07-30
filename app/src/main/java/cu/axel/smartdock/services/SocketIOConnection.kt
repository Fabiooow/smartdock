package cu.axel.smartdock.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.URISyntaxException

object SocketIOConnection {

    private lateinit var mySocket: Socket

    private lateinit var dockService: DockService

    val Context.dataStore by preferencesDataStore(name = "settings")
    val APPID_KEY = stringPreferencesKey("appid")

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
    fun setSocket(context: Context) {
        try {
            val opts = IO.Options()

            val appId = getAppIdSync(context)
            opts.query = "appId=${appId ?: "undefined"}"

            Log.i("SocketIOConnection", "Usando appid na query: ${appId ?: "undefined"}")

            mySocket = IO.socket("https://sporting-backend-production.up.railway.app", opts)


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

            mySocket.on("maintenance") { args ->
                val resposta = args.joinToString()
                Log.i("Server Responde", "Mensagem do servidor: ${resposta}")
                if(resposta == "true"){
                    //config para admin mode
                    Handler(Looper.getMainLooper()).post {
                        this.dockService.showDock()
                    }

                }

                if(resposta == "false"){
                    //config to remove admin mode
                    Handler(Looper.getMainLooper()).post {
                        this.dockService.hideDock()
                        this.dockService.launchApp("fullscreen", this.dockService.whiteListedApps[0])
                    }

                }

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
}
