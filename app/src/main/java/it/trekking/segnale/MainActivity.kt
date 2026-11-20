package it.trekking.segnale

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMESSI = 1
    }

    private lateinit var stato: TextView
    private lateinit var info: TextView
    private lateinit var btnAvvia: Button
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            aggiornaUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        stato = findViewById(R.id.stato)
        info = findViewById(R.id.info)
        btnAvvia = findViewById(R.id.btnAvvia)

        btnAvvia.setOnClickListener {
            if (TrackerState.registrando) {
                stopService(Intent(this, TrackingService::class.java))
            } else {
                controllaPermessiEAvvia()
            }
        }
        findViewById<Button>(R.id.btnEsporta).setOnClickListener { scegliSessione() }
        findViewById<Button>(R.id.btnBatteria).setOnClickListener { apriImpostazioniBatteria() }

        findViewById<TextView>(R.id.legenda).text = """
            Colori nella traccia KML:
            ■ Verde: internet funzionante (e chiamate)
            ■ Blu: solo chiamate e SMS, niente internet
            ■ Arancione: solo chiamate di emergenza (112) tramite la rete di un altro operatore
            ■ Rosso: nessuna rete, neanche il 112
            ■ Grigio: stato sconosciuto (es. modalità aereo)

            Nel KML l'inizio e la fine dei tratti arancioni e rossi sono segnati con un pallino (inizio) e un quadrato (fine).

            Nota: "solo soccorsi" è una stima basata sulle celle che il telefono vede. Non è una garanzia che il 112 risponda.
        """.trimIndent()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ------------------------------------------------------------ permessi

    private fun permessiNecessari(): List<String> {
        val p = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p
    }

    private fun controllaPermessiEAvvia() {
        val mancanti = permessiNecessari().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (mancanti.isEmpty()) avviaRegistrazione()
        else requestPermissions(mancanti.toTypedArray(), REQ_PERMESSI)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMESSI) return
        val ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (ok) {
            avviaRegistrazione()
        } else {
            Toast.makeText(
                this,
                "Servono i permessi di posizione precisa e di stato del telefono",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun avviaRegistrazione() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Attiva la localizzazione (GPS)", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                // ignorato
            }
            return
        }
        startForegroundService(Intent(this, TrackingService::class.java))
    }

    private fun apriImpostazioniBatteria() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    // ------------------------------------------------------------ interfaccia

    private fun aggiornaUi() {
        val reg = TrackerState.registrando
        btnAvvia.text = if (reg) "■  Ferma registrazione" else "▶  Avvia registrazione"

        if (!reg) {
            stato.text = "Registrazione ferma"
            stato.setBackgroundColor(0xFF607D8B.toInt())
            info.text = TrackerState.avviso?.let { "⚠ $it" } ?: "Premi Avvia prima di partire."
            return
        }

        val snap = TrackerState.attuale
        if (snap == null) {
            stato.text = "Rilevamento in corso…"
            stato.setBackgroundColor(0xFF9E9E9E.toInt())
        } else {
            stato.text = snap.copertura.etichetta
            stato.setBackgroundColor(snap.copertura.coloreUi)
        }

        val sb = StringBuilder()
        if (snap != null) {
            val segnale = if (snap.dbm != null) "${snap.dbm} dBm" else "—"
            val operatore = snap.operatore.ifBlank { "—" }
            sb.append("Segnale: ").append(segnale).append("    Rete: ").append(snap.rete).append('\n')
            sb.append("Operatore: ").append(operatore).append("    Celle visibili: ").append(snap.celleVisibili).append('\n')
            sb.append("Internet verificato: ").append(if (snap.internetOk) "sì" else "no").append('\n')
        }
        val fix = TrackerState.ultimoFix
        if (fix == 0L) {
            sb.append("GPS: in attesa del primo fix…\n")
        } else {
            val eta = (System.currentTimeMillis() - fix) / 1000
            val acc = TrackerState.precisioneGps?.let { "±${it.toInt()} m" } ?: "?"
            sb.append("GPS: ").append(acc).append(", ultimo fix ").append(eta).append(" s fa\n")
        }
        sb.append("Punti: ").append(TrackerState.punti)
            .append("    Distanza: ").append(Kml.km(TrackerState.distanzaM)).append('\n')
        sb.append("Durata: ").append(Kml.durata(System.currentTimeMillis() - TrackerState.inizio))
        TrackerState.avviso?.let { sb.append("\n⚠ ").append(it) }
        info.text = sb.toString()
    }

    // ------------------------------------------------------------ esportazione

    private fun scegliSessione() {
        val dir = File(filesDir, "sessioni")
        val files = dir.listFiles { f -> f.extension == "csv" }?.sortedByDescending { it.name } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "Nessuna traccia registrata", Toast.LENGTH_SHORT).show()
            return
        }
        val inLettura = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val inScrittura = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.ITALIAN)
        val nomi = files.map { f ->
            val data = try {
                inScrittura.format(inLettura.parse(f.nameWithoutExtension.removePrefix("trek_"))!!)
            } catch (e: Exception) {
                f.name
            }
            val punti = try {
                f.useLines { righe -> righe.count() } - 1
            } catch (e: Exception) {
                0
            }
            val corrente = if (TrackerState.registrando && f == TrackerState.fileSessione) "  (in corso)" else ""
            "$data · $punti punti$corrente"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Scegli la traccia da esportare")
            .setItems(nomi) { _, which -> esporta(files[which]) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun esporta(csv: File) {
        Toast.makeText(this, "Creo il KML…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val r = KmlExporter.esporta(applicationContext, csv)
                runOnUiThread {
                    val dove = r.salvatoIn?.let { "Salvato in $it" } ?: "KML creato"
                    Toast.makeText(this, dove, Toast.LENGTH_LONG).show()
                    condividi(r.file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun condividi(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND)
            .setType(KmlExporter.MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Condividi o apri il KML"))
    }
}
