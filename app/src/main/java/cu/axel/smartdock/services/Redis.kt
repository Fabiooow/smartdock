package cu.axel.smartdock.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPubSub


class Redis : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jedis: Jedis? = null
    private var subscriber: JedisPubSub? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("RedisService", "Iniciando RedisService...")

        serviceScope.launch {
            try {
                // ⚠️ Usa o IP da tua máquina/servidor Redis
                Log.d("RedisService", "Iniciando RedisService...")
                jedis = Jedis("192.168.1.199", 6379)

                subscriber = object : JedisPubSub() {
                    override fun onMessage(channel: String?, message: String?) {
                        Log.d("RedisService", "Recebido [$channel]: $message")

                        val intent = Intent("REDIS_MESSAGE")
                        intent.putExtra("channel", channel)
                        intent.putExtra("message", message)
                        sendBroadcast(intent)
                    }
                }

                Log.d("RedisService", "Subscrevendo ao canal my-channel...")
                jedis?.subscribe(subscriber, "my-channel")

            } catch (e: Exception) {
                Log.e("RedisService", "Erro ao ligar ao Redis: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("RedisService", "A desligar RedisService...")
        serviceScope.cancel()
        try {
            subscriber?.unsubscribe()
            jedis?.close()
        } catch (e: Exception) {
            Log.e("RedisService", "Erro ao fechar Redis: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}