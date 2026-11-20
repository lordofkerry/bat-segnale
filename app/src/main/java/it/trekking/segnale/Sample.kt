package it.trekking.segnale

import java.util.Locale

/** Un punto registrato: posizione + stato della rete in quel momento. */
data class Sample(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val acc: Float,
    val copertura: Copertura,
    val dbm: Int?,
    val rete: String,
    val operatore: String,
    val celle: Int,
) {
    fun toCsv(): String = listOf(
        time.toString(),
        String.format(Locale.US, "%.7f", lat),
        String.format(Locale.US, "%.7f", lon),
        if (alt.isNaN()) "" else String.format(Locale.US, "%.1f", alt),
        String.format(Locale.US, "%.1f", acc),
        copertura.name,
        dbm?.toString() ?: "",
        pulisci(rete),
        pulisci(operatore),
        celle.toString(),
    ).joinToString(";")

    companion object {
        const val HEADER = "time;lat;lon;alt;acc;copertura;dbm;rete;operatore;celle"

        private fun pulisci(s: String) = s.replace(';', ',').replace('\n', ' ')

        fun fromCsv(riga: String): Sample? = try {
            val p = riga.split(";")
            if (p.size < 10) {
                null
            } else {
                Sample(
                    time = p[0].toLong(),
                    lat = p[1].toDouble(),
                    lon = p[2].toDouble(),
                    alt = p[3].toDoubleOrNull() ?: Double.NaN,
                    acc = p[4].toFloatOrNull() ?: -1f,
                    copertura = Copertura.valueOf(p[5]),
                    dbm = p[6].toIntOrNull(),
                    rete = p[7],
                    operatore = p[8],
                    celle = p[9].toIntOrNull() ?: 0,
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
