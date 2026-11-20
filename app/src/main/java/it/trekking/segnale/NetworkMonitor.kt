package it.trekking.segnale

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Legge lo stato della rete cellulare e lo classifica in un livello di [Copertura].
 *
 * Logica:
 *  - INTERNET      rete dati cellulare validata e l'ultimo test HTTP (se recente) è andato a buon fine
 *  - VOCE          registrato sulla rete del proprio operatore, ma internet non funziona
 *  - SOLO_SOCCORSI non registrato sul proprio operatore, ma il telefono vede almeno una cella
 *                  (di qualunque operatore): il 112 passa su qualsiasi rete disponibile
 *  - NESSUNA       nessuna cella visibile: neanche il 112 può partire
 */
class NetworkMonitor(private val context: Context) {

    data class Snapshot(
        val copertura: Copertura,
        val dbm: Int?,
        val rete: String,
        val operatore: String,
        val celleVisibili: Int,
        val internetOk: Boolean,
    )

    companion object {
        private const val PROBE_URL = "https://www.google.com/generate_204"
        private const val PROBE_INTERVAL_S = 30L
        private const val PROBE_VALID_MS = 75_000L
        private const val CELL_REFRESH_S = 10L
        private const val CELL_MAX_AGE_NS = 60_000_000_000L // 60 s
    }

    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val reteCellulare = ConcurrentHashMap<Network, NetworkCapabilities>()

    @Volatile private var ultimeCelle: List<CellInfo> = emptyList()
    @Volatile private var ultimoProbeOk: Boolean? = null
    @Volatile private var ultimoProbeAt = 0L
    private var executor: ScheduledExecutorService? = null
    private var callbackRegistrato = false

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            reteCellulare[network] = networkCapabilities
        }

        override fun onLost(network: Network) {
            reteCellulare.remove(network)
        }
    }

    fun start() {
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, netCallback)
            callbackRegistrato = true
        } catch (e: Exception) {
            callbackRegistrato = false
        }
        val ex = Executors.newScheduledThreadPool(2)
        executor = ex
        ex.scheduleWithFixedDelay({ sicuro { aggiornaCelle() } }, 0, CELL_REFRESH_S, TimeUnit.SECONDS)
        ex.scheduleWithFixedDelay({ sicuro { testInternet() } }, 2, PROBE_INTERVAL_S, TimeUnit.SECONDS)
    }

    fun stop() {
        if (callbackRegistrato) {
            try {
                cm.unregisterNetworkCallback(netCallback)
            } catch (e: Exception) {
                // ignorato
            }
            callbackRegistrato = false
        }
        executor?.shutdownNow()
        executor = null
    }

    private inline fun sicuro(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // un errore non deve fermare i controlli periodici
        }
    }

    @SuppressLint("MissingPermission")
    private fun aggiornaCelle() {
        val cached = tm.allCellInfo
        if (cached != null) ultimeCelle = cached.toList()
        val ex = executor
        if (Build.VERSION.SDK_INT >= 29 && ex != null) {
            tm.requestCellInfoUpdate(ex, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    ultimeCelle = cellInfo.toList()
                }
            })
        }
    }

    /** Verifica che internet funzioni davvero sulla rete cellulare (risposta HTTP 204). */
    private fun testInternet() {
        val rete = reteCellulare.entries
            .firstOrNull { it.value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }?.key
            ?: reteCellulare.keys.firstOrNull()
        if (rete == null) {
            ultimoProbeOk = false
            ultimoProbeAt = SystemClock.elapsedRealtime()
            return
        }
        var ok = false
        var conn: HttpURLConnection? = null
        try {
            conn = rete.openConnection(URL(PROBE_URL)) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            ok = conn.responseCode == 204
        } catch (e: Exception) {
            ok = false
        } finally {
            conn?.disconnect()
        }
        ultimoProbeOk = ok
        ultimoProbeAt = SystemClock.elapsedRealtime()
    }

    @SuppressLint("MissingPermission")
    fun sample(): Snapshot {
        val aereo = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

        val ss: ServiceState? = try {
            tm.serviceState
        } catch (e: Exception) {
            null
        }
        val statoVoce = ss?.state ?: -1

        val oraNs = SystemClock.elapsedRealtimeNanos()
        @Suppress("DEPRECATION")
        val celle = ultimeCelle.filter { c ->
            val ts = c.timeStamp
            ts <= 0L || oraNs - ts < CELL_MAX_AGE_NS
        }
        val dbmVisibili = celle.mapNotNull { dbmDi(it) }
        val dbmRegistrata = celle.firstOrNull { it.isRegistered }?.let { dbmDi(it) }
        val dbm = dbmRegistrata ?: dbmVisibili.maxOrNull()

        val validata = reteCellulare.values.any {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val probeRecente = SystemClock.elapsedRealtime() - ultimoProbeAt < PROBE_VALID_MS
        val internet = validata && !(probeRecente && ultimoProbeOk == false)

        val copertura = when {
            aereo -> Copertura.SCONOSCIUTA
            internet -> Copertura.INTERNET
            statoVoce == ServiceState.STATE_IN_SERVICE -> Copertura.VOCE
            statoVoce == ServiceState.STATE_EMERGENCY_ONLY -> Copertura.SOLO_SOCCORSI
            dbmVisibili.isNotEmpty() -> Copertura.SOLO_SOCCORSI
            statoVoce == -1 && ultimeCelle.isEmpty() -> Copertura.SCONOSCIUTA
            else -> Copertura.NESSUNA
        }

        val tipoRete = try {
            var t = tm.dataNetworkType
            if (t == TelephonyManager.NETWORK_TYPE_UNKNOWN) t = tm.voiceNetworkType
            nomeRete(t)
        } catch (e: Exception) {
            "-"
        }
        val operatore = ss?.operatorAlphaShort?.takeIf { it.isNotBlank() }
            ?: tm.networkOperatorName.orEmpty()

        return Snapshot(copertura, dbm, tipoRete, operatore, dbmVisibili.size, internet)
    }

    private fun dbmDi(ci: CellInfo): Int? {
        val d = if (Build.VERSION.SDK_INT >= 30) {
            ci.cellSignalStrength.dbm
        } else {
            when (ci) {
                is CellInfoLte -> ci.cellSignalStrength.dbm
                is CellInfoWcdma -> ci.cellSignalStrength.dbm
                is CellInfoGsm -> ci.cellSignalStrength.dbm
                is CellInfoCdma -> ci.cellSignalStrength.dbm
                else -> Int.MAX_VALUE
            }
        }
        return if (d in -150..-20) d else null
    }

    private fun nomeRete(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, 19 -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "WiFi-call"
        else -> "-"
    }
}
