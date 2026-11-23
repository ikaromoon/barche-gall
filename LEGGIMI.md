# Barche Gallipoli per Fire TV

Versione per Fire TV di `vesselfinder_tv.py`: funziona da sola, senza PC acceso.

## Come crearla
1. Crea un nuovo repository pubblico su GitHub chiamato `barche-gall`.
2. Carica tutti i file di questa cartella (mantenendo le sottocartelle) e fai "Commit changes".
3. GitHub crea l'APK da solo (scheda "Actions"). Indirizzo fisso:
   https://github.com/ikaromoon/barche-gall/releases/download/barche/barche-gallipoli.apk
4. Installa con `aggiorna-barche.bat` (opzione 2 = TV), come per l'app delle webcam.

## Telecomando
- Frecce: scegli una barca (bordo giallo)
- OK: foto grande della barca (OK di nuovo per chiuderla)
- Play/Pausa: zoom sulla barca scelta / torna alla mappa intera
- Avanti/Indietro veloce: zoom avanti/indietro
- Menu: mostra/nasconde il pannello "Barche in scala"
- Indietro: chiude la foto -> torna alla mappa intera -> toglie la selezione -> esce

## Note
- I dati arrivano da endpoint interni di VesselFinder e MarineTraffic, non ufficiali:
  possono cambiare o essere bloccati senza preavviso.
- Se MarineTraffic blocca le richieste semplici, l'app apre una pagina MarineTraffic invisibile
  e legge i dati da li' (come il browser automatico dello script Python).
- Le impostazioni (zona, tempi, arrivi) sono in cima a `Motore.java`.
