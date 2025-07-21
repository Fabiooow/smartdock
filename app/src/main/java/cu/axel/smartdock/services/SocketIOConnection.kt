package cu.axel.smartdock.services

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URISyntaxException

object SocketIOConnection {

    private lateinit var mySocket: Socket

    @Synchronized
    fun setSocket() {
        try {

            mySocket = IO.socket("http://192.168.1.178:3000")

            // Eventos para debug
            mySocket.on(Socket.EVENT_CONNECT) {
                Log.i("Socket.io", "Socket conectado com sucesso")
            }

            mySocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.i("Socket.io", "Erro de conexão: ${args.joinToString()}")
            }

        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getSocket(): Socket {
        return mySocket
    }
}