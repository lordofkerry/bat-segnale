package it.trekking.segnale

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servizio in primo piano: riceve le posizioni GPS e, per ogni posizione,
 * registra lo stato della rete in un file CSV della sessione.
 */
class TrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "registrazione"
        const val NOTIF_ID = 42
        private const val GPS_MIN_TIME_MS = 5_000L   // un fix ogni 5 s
        private const val MIN_DIST_M = 5f            // registra se ti sei mosso di 5 m...
        private const val MAX_INTERVAL_MS = 60_000L  // ...o comunque ogni 60 s
        private const val MAX_ACCURACY_M = 75f       // scarta fix troppo imprecisi
        private const val LIVE_TICK_MS = 3_000L
    }

    private var monitor: NetworkMonitor? = null
    private var locationManager: LocationManager? = null
    private var writer: BufferedWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ultimo: Sample? = null
    private var ultimaNotificata: Copertura? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Aggiorna lo stato mostrato a schermo anche quando il GPS non ha un fix. */
    private val tickLive = object : Runnable {
        override fun run() {
            monitor?.let { m ->
                val s = m.sample()
                TrackerState.attuale = s
                aggiornaNotifica(s.copertura)
            }
            handler.postDelayed(this, LIVE_TICK_MS)
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location)
        }

        override fun onProviderEnabled(provider: String) {
            TrackerState.avviso = null
        }

        override fun onProviderDisabled(provider: String) {
            TrackerState.avviso = "GPS disattivato: riattivalo!"
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!TrackerState.registrando) avvia()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun avvia() {
        creaCanale()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, creaNotifica("Avvio…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            TrackerState.avviso = "Impossibile avviare la registrazione: ${e.message}"
            stopSelf()
            return
        }

        val dir = File(filesDir, "sessioni").apply { mkdirs() }
        val nome = "trek_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val file = File(dir, nome)
        writer = BufferedWriter(FileWriter(file, true)).also {
            it.write(Sample.HEADER)
            it.newLine()
            it.flush()
        }
        ultimo = null
        ultimaNotificata = null
        TrackerState.reset(file)

        monitor = NetworkMonitor(this).also { it.start() }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SegnaleTrek::registrazione").apply {
            setReferenceCounted(false)
            acquire(24L * 3600L * 1000L)
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_MIN_TIME_MS, 0f, listener, Looper.getMainLooper()
            )
        } catch (e: Exception) {
            TrackerState.avviso = "GPS non disponibile: ${e.message}"
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            TrackerState.avviso = "GPS disattivato: attivalo nelle impostazioni"
        }
        handler.post(tickLive)
    }

    private fun onFix(loc: Location) {
        TrackerState.ultimoFix = System.currentTimeMillis()
        TrackerState.precisioneGps = if (loc.hasAccuracy()) loc.accuracy else null
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return

        val snap = monitor?.sample() ?: return
        TrackerState.attuale = snap
        aggiornaNotifica(snap.copertura)

        val s = Sample(
            time = System.currentTimeMillis(),
            lat = loc.latitude,
            lon = loc.longitude,
            alt = if (loc.hasAltitude()) loc.altitude else Double.NaN,
            acc = if (loc.hasAccuracy()) loc.accuracy else -1f,
            copertura = snap.copertura,
            dbm = snap.dbm,
            rete = snap.rete,
            operatore = snap.operatore,
            celle = snap.celleVisibili,
        )
        val prec = ultimo
        val d = if (prec != null) distanza(prec, s) else 0f
        val registra = prec == null ||
            prec.copertura != s.copertura ||
            d >= MIN_DIST_M ||
            s.time - prec.time >= MAX_INTERVAL_MS
        if (!registra) return

        try {
            writer?.apply {
                write(s.toCsv())
                newLine()
                flush()
            }
        } catch (e: Exception) {
            TrackerState.avviso = "Errore di scrittura: ${e.message}"
        }
        if (prec != null) TrackerState.distanzaM += d.toDouble()
        TrackerState.punti += 1
        ultimo = s
    }

    private fun distanza(a: Sample, b: Sample): Float {
        val r = FloatArray(1)
        Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r)
        return r[0]
    }

    private fun creaCanale() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Registrazione traccia", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun creaNotifica(testo: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle("Segnale Trek: registrazione attiva")
            .setContentText(testo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun aggiornaNotifica(c: Copertura) {
        if (c == ultimaNotificata) return
        ultimaNotificata = c
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, creaNotifica(c.etichetta))
        } catch (e: Exception) {
            // permesso notifiche negato: nessun problema
        }
    }

    private fun ferma() {
        handler.removeCallbacks(tickLive)
        try {
            locationManager?.removeUpdates(listener)
        } catch (e: Exception) {
            // ignorato
        }
        monitor?.stop()
        monitor = null
        try {
            writer?.close()
        } catch (e: Exception) {
            // ignorato
        }
        writer = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        TrackerState.registrando = false
        TrackerState.attuale = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        if (TrackerState.registrando) ferma()
        super.onDestroy()
    }
}
