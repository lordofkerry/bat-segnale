package it.trekking.segnale

/**
 * Livelli di copertura, dal migliore al peggiore.
 * coloreKml è nel formato KML aabbggrr.
 */
enum class Copertura(
    val etichetta: String,
    val breve: String,
    val coloreUi: Int,
    val coloreKml: String,
) {
    INTERNET("Internet + chiamate", "Internet", 0xFF2E7D32.toInt(), "ff327d2e"),
    VOCE("Solo chiamate (niente internet)", "Solo chiamate", 0xFF1E88E5.toInt(), "ffe5881e"),
    SOLO_SOCCORSI("Solo chiamate di emergenza (112)", "Solo soccorsi", 0xFFFB8C00.toInt(), "ff008cfb"),
    NESSUNA("Nessuna rete: neanche il 112", "Nessun soccorso", 0xFFE53935.toInt(), "ff3539e5"),
    SCONOSCIUTA("Stato sconosciuto (modalità aereo?)", "Sconosciuto", 0xFF9E9E9E.toInt(), "ff9e9e9e");

    val critica: Boolean get() = this == SOLO_SOCCORSI || this == NESSUNA
}
