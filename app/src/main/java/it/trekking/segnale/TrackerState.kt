package it.trekking.segnale

import java.io.File

/** Stato condiviso tra il servizio di registrazione e la schermata. */
object TrackerState {
    @Volatile var registrando = false
    @Volatile var fileSessione: File? = null
    @Volatile var inizio = 0L
    @Volatile var punti = 0
    @Volatile var distanzaM = 0.0
    @Volatile var attuale: NetworkMonitor.Snapshot? = null
    @Volatile var precisioneGps: Float? = null
    @Volatile var ultimoFix = 0L
    @Volatile var avviso: String? = null

    fun reset(file: File) {
        fileSessione = file
        inizio = System.currentTimeMillis()
        punti = 0
        distanzaM = 0.0
        attuale = null
        precisioneGps = null
        ultimoFix = 0L
        avviso = null
        registrando = true
    }
}
