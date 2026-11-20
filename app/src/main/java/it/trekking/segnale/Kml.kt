package it.trekking.segnale

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Trasforma i punti registrati in segmenti di copertura omogenea e genera il KML.
 * Codice puro Kotlin/JVM: nessuna dipendenza da Android.
 */
object Kml {
    /** Tratti con meno punti di così vengono assorbiti dal tratto precedente (rumore). */
    const val MIN_CAMPIONI = 2

    class Segmento(val copertura: Copertura, val campioni: MutableList<Sample>)

    private class Info(
        val coords: List<Sample>,
        val inizio: Long,
        val fine: Long,
        val distanza: Double,
    )

    private val CRITICHE = listOf(Copertura.SOLO_SOCCORSI, Copertura.NESSUNA)
    private const val ICONE = "http://maps.google.com/mapfiles/kml/paddle/"

    fun segmenta(campioni: List<Sample>): List<Segmento> {
        val grezzi = mutableListOf<Segmento>()
        for (c in campioni) {
            val ultimo = grezzi.lastOrNull()
            if (ultimo != null && ultimo.copertura == c.copertura) ultimo.campioni.add(c)
            else grezzi.add(Segmento(c.copertura, mutableListOf(c)))
        }
        val out = mutableListOf<Segmento>()
        for (s in grezzi) {
            val prec = out.lastOrNull()
            if (prec != null && (prec.copertura == s.copertura || s.campioni.size < MIN_CAMPIONI)) {
                prec.campioni.addAll(s.campioni)
            } else {
                out.add(s)
            }
        }
        return out
    }

    fun crea(titolo: String, campioni: List<Sample>): String {
        val segmenti = segmenta(campioni)
        val info = segmenti.mapIndexed { i, s -> info(s, segmenti.getOrNull(i + 1)) }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sb.append("<name>").append(esc(titolo)).append("</name>\n<open>1</open>\n")
        sb.append("<description><![CDATA[").append(riepilogo(segmenti, info)).append("]]></description>\n")
        stili(sb)

        sb.append("<Folder>\n<name>Tratti per copertura</name>\n<open>0</open>\n")
        segmenti.forEachIndexed { i, seg -> segmentoKml(sb, seg, info[i]) }
        sb.append("</Folder>\n")

        for (tipo in CRITICHE) {
            val quanti = segmenti.count { it.copertura == tipo }
            sb.append("<Folder>\n<name>").append(esc("${tipo.breve}: inizio e fine ($quanti)"))
                .append("</name>\n<open>1</open>\n")
            var n = 0
            segmenti.forEachIndexed { i, seg ->
                if (seg.copertura == tipo) {
                    n++
                    val prossimo = segmenti.getOrNull(i + 1)
                    marker(sb, tipo, n, true, seg.campioni.first(), info[i], null)
                    marker(sb, tipo, n, false, info[i].coords.last(), info[i], prossimo?.copertura)
                }
            }
            sb.append("</Folder>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    // ---------------------------------------------------------------- dettagli

    private fun info(seg: Segmento, prossimo: Segmento?): Info {
        // Il tratto termina dove inizia il successivo, così la linea è continua
        // e la "fine" di un tratto critico è il punto in cui la copertura cambia.
        val coords = if (prossimo != null) seg.campioni + prossimo.campioni.first() else seg.campioni.toList()
        var d = 0.0
        for (k in 1 until coords.size) d += distanza(coords[k - 1], coords[k])
        return Info(coords, seg.campioni.first().time, coords.last().time, d)
    }

    private fun stili(sb: StringBuilder) {
        for (c in Copertura.values()) {
            sb.append("<Style id=\"linea_").append(c.name).append("\"><LineStyle><color>")
                .append(c.coloreKml).append("</color><width>6</width></LineStyle></Style>\n")
        }
        for (c in CRITICHE) {
            val col = if (c == Copertura.SOLO_SOCCORSI) "orange" else "red"
            stileIcona(sb, "inizio_${c.name}", c.coloreKml, "$ICONE$col-circle.png")
            stileIcona(sb, "fine_${c.name}", c.coloreKml, "$ICONE$col-square.png")
        }
    }

    private fun stileIcona(sb: StringBuilder, id: String, colore: String, href: String) {
        sb.append("<Style id=\"").append(id).append("\">")
            .append("<IconStyle><scale>1.1</scale><Icon><href>").append(href).append("</href></Icon>")
            .append("<hotSpot x=\"32\" y=\"1\" xunits=\"pixels\" yunits=\"pixels\"/></IconStyle>")
            .append("<LabelStyle><color>").append(colore).append("</color><scale>0.9</scale></LabelStyle>")
            .append("</Style>\n")
    }

    private fun segmentoKml(sb: StringBuilder, seg: Segmento, inf: Info) {
        val nome = "${seg.copertura.breve} · ${km(inf.distanza)} · ${durata(inf.fine - inf.inizio)}"
        sb.append("<Placemark>\n<name>").append(esc(nome)).append("</name>\n")
        sb.append("<description><![CDATA[").append(descrizioneSegmento(seg, inf)).append("]]></description>\n")
        sb.append("<TimeSpan><begin>").append(iso(inf.inizio)).append("</begin><end>")
            .append(iso(inf.fine)).append("</end></TimeSpan>\n")
        sb.append("<styleUrl>#linea_").append(seg.copertura.name).append("</styleUrl>\n")
        sb.append("<LineString><tessellate>1</tessellate><coordinates>\n")
        val punti = if (inf.coords.size == 1) inf.coords + inf.coords else inf.coords
        for (p in punti) sb.append(coord(p)).append('\n')
        sb.append("</coordinates></LineString>\n</Placemark>\n")
    }

    private fun marker(
        sb: StringBuilder, tipo: Copertura, n: Int, inizio: Boolean,
        p: Sample, inf: Info, dopo: Copertura?,
    ) {
        val nome = (if (inizio) "INIZIO " else "FINE ") + tipo.breve.lowercase(Locale.ITALIAN) + " #$n"
        val righe = mutableListOf<Pair<String, String>>()
        if (inizio) {
            righe += "Da qui" to tipo.etichetta
        } else {
            righe += "Qui termina" to tipo.etichetta
            righe += "Dopo questo punto" to (dopo?.etichetta ?: "fine della registrazione")
        }
        righe += "Ora" to ora(p.time)
        righe += "Quota GPS" to (if (p.alt.isNaN()) "—" else "${p.alt.toInt()} m")
        righe += "Lunghezza del tratto" to km(inf.distanza)
        righe += "Durata del tratto" to durata(inf.fine - inf.inizio)
        righe += "Coordinate" to String.format(Locale.US, "%.6f, %.6f", p.lat, p.lon)

        sb.append("<Placemark>\n<name>").append(esc(nome)).append("</name>\n")
        sb.append("<description><![CDATA[").append(tabella(righe)).append("]]></description>\n")
        sb.append("<TimeStamp><when>").append(iso(p.time)).append("</when></TimeStamp>\n")
        sb.append("<styleUrl>#").append(if (inizio) "inizio_" else "fine_").append(tipo.name).append("</styleUrl>\n")
        sb.append("<Point><coordinates>").append(coord(p)).append("</coordinates></Point>\n</Placemark>\n")
    }

    private fun descrizioneSegmento(seg: Segmento, inf: Info): String {
        val dbm = seg.campioni.mapNotNull { it.dbm }
        val alt = seg.campioni.map { it.alt }.filter { !it.isNaN() }
        val reti = seg.campioni.map { it.rete }.filter { it.isNotBlank() && it != "-" }.distinct()
        val oper = seg.campioni.map { it.operatore }.filter { it.isNotBlank() }.distinct()
        val righe = listOf(
            "Copertura" to seg.copertura.etichetta,
            "Inizio" to ora(inf.inizio),
            "Fine" to ora(inf.fine),
            "Durata" to durata(inf.fine - inf.inizio),
            "Lunghezza" to km(inf.distanza),
            "Quota min / max" to (if (alt.isEmpty()) "—" else "${alt.min().toInt()} / ${alt.max().toInt()} m"),
            "Segnale min / medio / max" to
                (if (dbm.isEmpty()) "—" else "${dbm.min()} / ${dbm.average().toInt()} / ${dbm.max()} dBm"),
            "Tipo di rete" to (if (reti.isEmpty()) "—" else reti.joinToString(", ")),
            "Operatore" to (if (oper.isEmpty()) "—" else oper.joinToString(", ")),
            "Celle visibili (max)" to (seg.campioni.maxOfOrNull { it.celle } ?: 0).toString(),
            "Punti registrati" to seg.campioni.size.toString(),
        )
        return tabella(righe)
    }

    private fun riepilogo(segmenti: List<Segmento>, info: List<Info>): String {
        val dist = mutableMapOf<Copertura, Double>()
        val tempo = mutableMapOf<Copertura, Long>()
        segmenti.forEachIndexed { i, s ->
            dist[s.copertura] = (dist[s.copertura] ?: 0.0) + info[i].distanza
            tempo[s.copertura] = (tempo[s.copertura] ?: 0L) + (info[i].fine - info[i].inizio)
        }
        val totD = dist.values.sum()
        val sb = StringBuilder()
        if (info.isNotEmpty()) {
            sb.append("<p>").append(ora(info.first().inizio)).append(" → ").append(ora(info.last().fine))
                .append("<br/>Totale: <b>").append(km(totD)).append("</b> in ")
                .append(durata(info.last().fine - info.first().inizio)).append("</p>")
        }
        sb.append("<table border='1' cellpadding='4' style='border-collapse:collapse'>")
            .append("<tr><th>Copertura</th><th>Distanza</th><th>%</th><th>Tempo</th></tr>")
        for (c in Copertura.values()) {
            val d = dist[c] ?: continue
            val perc = if (totD > 0) d * 100 / totD else 0.0
            sb.append("<tr><td><span style='color:").append(hex(c.coloreUi)).append("'>■</span> ")
                .append(esc(c.etichetta)).append("</td><td>").append(km(d)).append("</td><td>")
                .append(String.format(Locale.ITALIAN, "%.0f%%", perc)).append("</td><td>")
                .append(durata(tempo[c] ?: 0L)).append("</td></tr>")
        }
        sb.append("</table>")
        for (c in CRITICHE) {
            val idx = segmenti.indices.filter { segmenti[it].copertura == c }
            if (idx.isNotEmpty()) {
                val piuLungo = idx.maxOf { info[it].distanza }
                sb.append("<p><b>").append(esc(c.breve)).append("</b>: ").append(idx.size)
                    .append(" tratti, il più lungo ").append(km(piuLungo)).append("</p>")
            }
        }
        return sb.toString()
    }

    private fun tabella(righe: List<Pair<String, String>>): String {
        val sb = StringBuilder("<table border='0' cellpadding='2'>")
        for ((k, v) in righe) {
            sb.append("<tr><td><b>").append(esc(k)).append("</b></td><td>").append(esc(v)).append("</td></tr>")
        }
        return sb.append("</table>").toString()
    }

    // ---------------------------------------------------------------- utilità

    fun distanza(a: Sample, b: Sample): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun coord(p: Sample) =
        String.format(Locale.US, "%.7f,%.7f,%.1f", p.lon, p.lat, if (p.alt.isNaN()) 0.0 else p.alt)

    fun km(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else String.format(Locale.ITALIAN, "%.2f km", m / 1000)

    fun durata(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val min = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d h %02d min", h, min)
            min > 0 -> String.format(Locale.US, "%d min %02d s", min, sec)
            else -> "$sec s"
        }
    }

    private fun ora(t: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALIAN).format(Date(t))

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
