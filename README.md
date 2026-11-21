# Segnale Trek

App Android che registra durante il trekking **quanta rete c'è e di che tipo**, ed esporta un file **KML** con i tratti colorati:

| Colore | Significato |
|---|---|
| 🟢 Verde | Internet funzionante (e chiamate) |
| 🔵 Blu | Solo chiamate/SMS, niente internet |
| 🟠 Arancione | **Solo chiamate di emergenza (112)** |
| 🔴 Rosso | **Nessuna rete, neanche il 112** |
| ⚪ Grigio | Sconosciuto (es. modalità aereo) |

Nel KML ci sono tre cartelle:
- **Tratti per copertura**: la traccia divisa in linee colorate, ognuna con ora, durata, lunghezza, quota, segnale (dBm), tipo di rete e operatore.
- **Solo soccorsi: inizio e fine**: segnaposto arancioni, un pallino per l'inizio e un quadrato per la fine di ogni tratto.
- **Nessun soccorso: inizio e fine**: lo stesso, in rosso.

Nell'app c'è anche una **mappa OpenStreetMap** (pulsante *Mostra sulla mappa…*) con la traccia colorata allo stesso modo, i segnaposto di inizio (●) e fine (■) dei tratti critici e una legenda con i km per tipo di copertura. Si può passare dalla mappa topografica (OpenTopoMap) a quella standard. Toccando una linea o un segnaposto compaiono orari, durata, lunghezza e segnale. Se apri la traccia in registrazione, la mappa si aggiorna da sola. Le tessere della mappa si scaricano da internet: le zone già visualizzate restano in cache e si vedono anche offline.

La descrizione del documento KML contiene un riepilogo: distanza e tempo per ogni tipo di copertura, numero di tratti critici e il più lungo.

---

## Come ottenere l'APK

### Opzione A: GitHub, senza installare nulla (consigliata)
1. Crea un repository su github.com (anche privato).
2. Carica tutto il contenuto di questa cartella (compresa la cartella nascosta `.github`). Dal sito: *Add file → Upload files*, trascinando i file.
3. Vai nella scheda **Actions**: parte da sola la build "Build APK" (circa 3-5 minuti).
4. Quando finisce, apri l'esecuzione e scarica l'artefatto **SegnaleTrek-apk** in fondo alla pagina. Dentro c'è `app-debug.apk`.
5. Copia l'APK sul telefono e aprilo. Android ti chiederà di consentire l'installazione da "origini sconosciute".

### Opzione B: Android Studio
Apri la cartella con Android Studio, poi *Build → Build APK(s)*.
Da riga di comando, con l'Android SDK installato: `./gradlew assembleDebug`.

Richiede Android 8.0 o successivo.

---

## Uso
1. Prima di partire: **Avvia registrazione** e concedi posizione precisa, stato del telefono e notifiche.
2. Premi **Escludi dal risparmio batteria** e scegli "Non ottimizzare". Su Xiaomi, Huawei, Oppo e Samsung controlla anche le impostazioni di avvio automatico e attività in background, altrimenti il sistema può chiudere l'app.
3. Tieni la **rete dati attiva** e il **Wi-Fi spento**: l'app verifica internet sulla rete cellulare.
4. A fine giro: **Ferma**, poi **Esporta KML…** e scegli la traccia. Il file viene salvato in `Download/SegnaleTrek/` e si apre il menu di condivisione (Google Earth, OsmAnd, Locus, email…).

Puoi esportare anche mentre registri.

## Come viene stabilita la copertura
- **Internet**: la rete dati cellulare risulta validata da Android **e** ogni 30 s l'app scarica una risposta vuota da `google.com/generate_204` (circa 5-6 MB in 8 ore).
- **Solo chiamate**: il telefono è registrato sulla rete del tuo operatore, ma internet non funziona.
- **Solo soccorsi**: non sei registrato sul tuo operatore, ma il telefono vede almeno una cella di un qualsiasi operatore (o Android segnala "solo emergenze"). Il 112 può passare su qualunque rete disponibile.
- **Nessun soccorso**: nessuna cella visibile.

Un punto viene registrato ogni 5 m percorsi (fix GPS ogni 5 s), comunque almeno ogni 60 s, e sempre quando cambia la copertura. Un cambio che dura un solo punto viene considerato rumore e assorbito dal tratto precedente (`Kml.MIN_CAMPIONI`).

## Limiti da conoscere
- "Solo soccorsi" è una **stima**: vedere una cella non garantisce che la chiamata al 112 vada a buon fine. Non usare l'app come unico strumento di sicurezza.
- Con il doppio SIM viene letta la SIM predefinita per i dati.
- Il GPS acceso per ore consuma batteria: porta un powerbank.

## Parametri modificabili
- `TrackingService.kt`: intervallo GPS, distanza minima, precisione massima accettata.
- `NetworkMonitor.kt`: intervallo del test internet, età massima delle informazioni sulle celle.
- `Kml.kt`: soglia per filtrare i cambi spuri, colori, icone.
