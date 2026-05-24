package com.wdtt.client.xray

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.wdtt.client.TunnelManager

/**
 * VpnService для режима VLESS-через-ВК.
 *
 * Поднимает TUN-интерфейс и отдаёт его fd встроенному Xray (libXray) через
 * [XrayBridge.runFromJson]. Xray-конфиг ([XrayConfig.buildVlessViaRelay]) гонит
 * VLESS+REALITY на локальный vk-turn relay (libvkturn.so -vless), который уже
 * проброшен через звонок ВК.
 *
 * Сам пакет WDTT исключён из TUN (addDisallowedApplication) — иначе трафик
 * libvkturn.so к серверам ВК зациклился бы обратно в TUN.
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.wdtt.client.xray.START"
        const val ACTION_STOP = "com.wdtt.client.xray.STOP"
        const val EXTRA_VLESS = "vless_link"
        const val EXTRA_RELAY_PORT = "relay_port"
        private const val MTU = 1500
        private const val TAG = "XrayVpn"

        @Volatile
        var isActive: Boolean = false
            private set
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVless()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val vless = intent.getStringExtra(EXTRA_VLESS).orEmpty()
                val relayPort = intent.getIntExtra(EXTRA_RELAY_PORT, 0)
                if (vless.isBlank() || relayPort <= 0) {
                    TunnelManager.addDeployErrorLog("VLESS: пустая ссылка или порт relay")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVless(vless, relayPort)
            }
        }
        return START_STICKY
    }

    private fun startVless(vlessLink: String, relayPort: Int) {
        try {
            val builder = Builder()
                .setSession("WDTT-VLESS")
                .setMtu(MTU)
                .addAddress("10.99.99.2", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            // Трафик самого WDTT (включая процесс libvkturn.so → серверы ВК) мимо TUN
            runCatching { builder.addDisallowedApplication(packageName) }

            val pfd = builder.establish() ?: throw IllegalStateException("establish() вернул null")
            tun = pfd

            val config = XrayConfig.buildVlessViaRelay(vlessLink, "127.0.0.1", relayPort, MTU)
            XrayBridge.runFromJson(applicationContext, config, pfd.fd) { fd -> protect(fd) }

            isActive = true
            TunnelManager.addDeploySuccessLog("[VLESS] Xray запущен (relay 127.0.0.1:$relayPort)")
            Log.i(TAG, "VLESS Xray started, relay port $relayPort")
        } catch (e: Exception) {
            TunnelManager.addDeployErrorLog("VLESS Xray ошибка: ${e.message}")
            Log.e(TAG, "startVless failed", e)
            stopVless()
        }
    }

    private fun stopVless() {
        runCatching { XrayBridge.stop() }
        runCatching { tun?.close() }
        tun = null
        isActive = false
        stopSelf()
    }

    override fun onDestroy() {
        stopVless()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVless()
        super.onRevoke()
    }
}
