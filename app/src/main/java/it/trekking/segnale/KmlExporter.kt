package it.trekking.segnale

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Legge una sessione CSV, genera il KML e lo salva in Download/SegnaleTrek. */
object KmlExporter {
    const val MIME = "application/vnd.google-earth.kml+xml"

    class Risultato(val file: File, val salvatoIn: String?)

    fun carica(csv: File): List<Sample> = csv.readLines().mapNotNull { Sample.fromCsv(it) }

    fun esporta(context: Context, csv: File): Risultato {
        val campioni = carica(csv)
        require(campioni.size >= 2) { "La traccia ha troppo pochi punti (${campioni.size})" }
        val titolo = "Copertura rete " +
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(campioni.first().time))
        val kml = Kml.crea(titolo, campioni)
        val nome = csv.nameWithoutExtension + ".kml"

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, nome)
        out.writeText(kml)

        var salvato: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SegnaleTrek")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(kml.toByteArray()) }
                    salvato = "Download/SegnaleTrek/$nome"
                }
            } catch (e: Exception) {
                salvato = null
            }
        } else {
            val ext = context.getExternalFilesDir(null)
            if (ext != null) {
                val f = File(ext, nome)
                f.writeText(kml)
                salvato = f.absolutePath
            }
        }
        return Risultato(out, salvato)
    }
}
