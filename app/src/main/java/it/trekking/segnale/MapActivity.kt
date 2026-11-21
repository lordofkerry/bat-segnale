package it.trekking.segnale

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mostra una traccia su mappa OpenStreetMap con i tratti colorati per copertura.
 * Se la traccia è quella in registrazione, si aggiorna da sola ogni 5 secondi.
 */
class MapActivity : Activity() {

    companion object {
        const val EXTRA_FILE = "file"
        private const val AGGIORNA_MS = 5_000L
    }

    private lateinit var map: MapView
    private lateinit var legenda: TextView
    private lateinit var btnStile: Button
    private lateinit var file: File
    private val handler = Handler(Looper.getMainLooper())
    private var campioniMostrati = -1
    private var tuttiIPunti: List<GeoPoint> = emptyList()
    private var primoZoom = true
    private var topo = true

    private val aggiornaLive = object : Runnable {
        override fun run() {
            if (live()) {
                carica()
                handler.postDelayed(this, AGGIORNA_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configurazione osmdroid: va fatta prima di creare la MapView.
        val conf = Configuration.getInstance()
        conf.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        conf.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid")
        conf.osmdroidBasePath = base
        conf.osmdroidTileCache = File(base, "tiles")

        setContentView(R.layout.activity_map)

        val path = intent.getStringExtra(EXTRA_FILE)
        if (path == null) {
            finish()
            return
        }
        file = File(path)
        title = titoloDa(file)

        map = findViewById(R.id.mappa)
        legenda = findViewById(R.id.legendaMappa)
        btnStile = findViewById(R.id.btnStile)

        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        map.setMinZoomLevel(3.0)
        map.controller.setZoom(5.0)
        map.controller.setCenter(GeoPoint(42.5, 12.5))

        topo = getPreferences(MODE_PRIVATE).getBoolean("topo", true)
        applicaStile()
        btnStile.setOnClickListener {
            topo = !topo
            getPreferences(MODE_PRIVATE).edit().putBoolean("topo", topo).apply()
            applicaStile()
            campioniMostrati = -1
            carica()
        }
        findViewById<Button>(R.id.btnAdatta).setOnClickListener { adattaZoom() }

        legenda.text = "Caricamento…"
        carica()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (live()) handler.postDelayed(aggiornaLive, AGGIORNA_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(aggiornaLive)
        map.onPause()
        super.onPause()
    }

    private fun live(): Boolean =
        TrackerState.registrando && TrackerState.fileSessione?.absolutePath == file.absolutePath

    private fun applicaStile() {
        map.setTileSource(if (topo) TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK)
        btnStile.text = if (topo) "Mappa standard" else "Mappa topografica"
    }

    private fun carica() {
        Thread {
            val campioni = try {
                KmlExporter.carica(file)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && campioni.size != campioniMostrati) {
                    campioniMostrati = campioni.size
                    disegna(campioni)
                }
            }
        }.start()
    }

    private fun disegna(campioni: List<Sample>) {
        InfoWindow.closeAllInfoWindowsOn(map)
        map.overlays.clear()
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                InfoWindow.closeAllInfoWindowsOn(map)
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))
        map.overlays.add(ScaleBarOverlay(map).apply {
            setAlignBottom(false)
            setScaleBarOffset((10 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt())
        })

        if (campioni.isEmpty()) {
            legenda.text = "Nessun punto registrato (ancora).\n" + attribuzione()
            map.invalidate()
            return
        }

        val segmenti = Kml.segmenta(campioni)
        val tutti = ArrayList<GeoPoint>()
        val dist = mutableMapOf<Copertura, Double>()
        val contatori = mutableMapOf<Copertura, Int>()
        val segnaposto = mutableListOf<Marker>()
        val dp = resources.displayMetrics.density

        segmenti.forEachIndexed { i, seg ->
            val prossimo = segmenti.getOrNull(i + 1)
            val coords = if (prossimo != null) seg.campioni + prossimo.campioni.first() else seg.campioni.toList()
            var d = 0.0
            for (k in 1 until coords.size) d += Kml.distanza(coords[k - 1], coords[k])
            dist[seg.copertura] = (dist[seg.copertura] ?: 0.0) + d

            val punti = coords.map { GeoPoint(it.lat, it.lon) }
            val puntiLinea = if (punti.size == 1) listOf(punti[0], punti[0]) else punti
            tutti.addAll(punti)

            // bordo bianco sotto la linea colorata, per leggerla bene su qualsiasi sfondo
            val bordo = Polyline(map)
            bordo.setPoints(puntiLinea)
            bordo.outlinePaint.color = Color.WHITE
            bordo.outlinePaint.strokeWidth = 11 * dp
            bordo.outlinePaint.strokeCap = Paint.Cap.ROUND
            bordo.outlinePaint.isAntiAlias = true
            bordo.setOnClickListener { _, _, _ -> false }
            map.overlays.add(bordo)

            val inizio = coords.first().time
            val fine = coords.last().time
            val linea = Polyline(map)
            linea.setPoints(puntiLinea)
            linea.outlinePaint.color = seg.copertura.coloreUi
            linea.outlinePaint.strokeWidth = 7 * dp
            linea.outlinePaint.strokeCap = Paint.Cap.ROUND
            linea.outlinePaint.isAntiAlias = true
            linea.title = seg.copertura.etichetta
            linea.snippet = dettagli(seg, inizio, fine, d)
            map.overlays.add(linea)

            if (seg.copertura.critica) {
                val n = (contatori[seg.copertura] ?: 0) + 1
                contatori[seg.copertura] = n
                val testo = dettagli(seg, inizio, fine, d)
                segnaposto += marker(punti.first(), seg.copertura, true, n, testo)
                segnaposto += marker(punti.last(), seg.copertura, false, n, testo)
            }
        }
        map.overlays.addAll(segnaposto)

        if (live()) {
            val ultimo = campioni.last()
            val qui = Marker(map)
            qui.position = GeoPoint(ultimo.lat, ultimo.lon)
            qui.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            qui.icon = icona(0xFF1565C0.toInt(), GradientDrawable.OVAL, 18)
            qui.title = "Ultima posizione registrata"
            qui.snippet = ora(ultimo.time) + "<br>" + ultimo.copertura.etichetta
            map.overlays.add(qui)
        }

        tuttiIPunti = tutti
        legenda.text = testoLegenda(dist)
        map.invalidate()

        if (primoZoom) {
            primoZoom = false
            adattaZoom()
        }
    }

    private fun adattaZoom() {
        val punti = tuttiIPunti
        if (punti.isEmpty()) return
        if (map.width == 0 || map.height == 0) {
            map.postDelayed({ adattaZoom() }, 100)
            return
        }
        val box = BoundingBox.fromGeoPoints(punti)
        if (box.latitudeSpan < 0.0005 && box.longitudeSpanWithDateLine < 0.0005) {
            map.controller.setZoom(17.0)
            map.controller.setCenter(box.centerWithDateLine)
        } else {
            map.zoomToBoundingBox(box, false, (40 * resources.displayMetrics.density).toInt())
        }
    }

    private fun marker(p: GeoPoint, c: Copertura, inizio: Boolean, n: Int, testo: String): Marker {
        val m = Marker(map)
        m.position = p
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = icona(c.coloreUi, if (inizio) GradientDrawable.OVAL else GradientDrawable.RECTANGLE, 22)
        m.title = (if (inizio) "INIZIO " else "FINE ") + c.breve.lowercase(Locale.ITALIAN) + " #$n"
        m.snippet = testo
        return m
    }

    private fun icona(colore: Int, forma: Int, dimensioneDp: Int): Drawable {
        val dp = resources.displayMetrics.density
        val px = (dimensioneDp * dp).toInt()
        return GradientDrawable().apply {
            shape = forma
            setColor(colore)
            setStroke((3 * dp).toInt(), Color.WHITE)
            setSize(px, px)
        }
    }

    private fun dettagli(seg: Kml.Segmento, inizio: Long, fine: Long, d: Double): String {
        val dbm = seg.campioni.mapNotNull { it.dbm }
        val segnale = if (dbm.isEmpty()) "—" else "${dbm.min()} / ${dbm.average().toInt()} / ${dbm.max()} dBm"
        val reti = seg.campioni.map { it.rete }.filter { it.isNotBlank() && it != "-" }.distinct()
        return "Dalle ${ora(inizio)} alle ${ora(fine)}<br>" +
            "Durata: ${Kml.durata(fine - inizio)}<br>" +
            "Lunghezza: ${Kml.km(d)}<br>" +
            "Segnale min/medio/max: $segnale<br>" +
            "Rete: ${if (reti.isEmpty()) "—" else reti.joinToString(", ")}"
    }

    private fun testoLegenda(dist: Map<Copertura, Double>): CharSequence {
        val tot = dist.values.sum()
        val sb = SpannableStringBuilder()
        for (c in Copertura.values()) {
            val d = dist[c] ?: continue
            val start = sb.length
            sb.append("■ ")
            sb.setSpan(ForegroundColorSpan(c.coloreUi), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val perc = if (tot > 0) (d * 100 / tot).toInt() else 0
            sb.append(c.breve).append(": ").append(Kml.km(d)).append(" (").append(perc.toString()).append("%)\n")
        }
        sb.append("● inizio  ■ fine dei tratti critici. Tocca linee e segnaposto per i dettagli.")
        if (live()) sb.append("\nRegistrazione in corso: la mappa si aggiorna da sola.")
        sb.append('\n').append(attribuzione())
        return sb
    }

    private fun attribuzione(): String =
        if (topo) "Mappa: © OpenStreetMap contributors, SRTM | stile © OpenTopoMap (CC-BY-SA)"
        else "Mappa: © OpenStreetMap contributors"

    private fun ora(t: Long) = SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(t))

    private fun titoloDa(f: File): String = try {
        val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(f.nameWithoutExtension.removePrefix("trek_"))
        "Traccia del " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(d!!)
    } catch (e: Exception) {
        f.name
    }
}
