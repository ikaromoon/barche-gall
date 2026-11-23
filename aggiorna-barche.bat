@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

rem =====================================================================
rem  Aggiorna l'app Barche Gallipoli: scarica l'ultima versione da GitHub
rem  e la installa sull'emulatore o sulla Fire TV.
rem
rem  Fire TV: scrivi qui sotto il suo indirizzo IP
rem  (Fire TV: Impostazioni > La mia Fire TV > Informazioni > Rete)
rem  e attiva "Debug ADB" in Impostazioni > La mia Fire TV > Opzioni sviluppatore.
rem =====================================================================
set "FIRETV_IP=192.168.1.13"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "URL=https://github.com/ikaromoon/barche-gall/releases/download/barche/barche-gallipoli.apk"
set "APK=%TEMP%\barche-gallipoli.apk"

if not exist "%ADB%" (
  echo Non trovo adb in "%ADB%". Installa Android Studio o correggi il percorso in questo file.
  goto fine
)

echo.
echo  Dove installo l'app?
echo    1 = Emulatore
echo    2 = Fire TV (%FIRETV_IP%)
echo.
choice /c 12 /n /m " Scegli 1 o 2: "
if errorlevel 2 (set "DOVE=tv") else (set "DOVE=emu")

echo.
echo Scarico l'ultima versione da GitHub...
if exist "%APK%" del "%APK%"
curl -sSL --fail -o "%APK%" "%URL%"
if errorlevel 1 (
  echo Download non riuscito. Controlla la connessione e che su GitHub l'ultima compilazione sia verde.
  goto fine
)

"%ADB%" start-server >nul 2>&1

if "%DOVE%"=="emu" (
  set "BERSAGLIO=-e"
) else (
  set "BERSAGLIO=-s %FIRETV_IP%:5555"
)

if "%DOVE%"=="tv" call :collega_tv
if "%DOVE%"=="tv" if not "%COLLEGATA%"=="si" goto fine

echo Installo...
"%ADB%" %BERSAGLIO% install -r "%APK%" > "%TEMP%\barche-installa.txt" 2>&1
type "%TEMP%\barche-installa.txt"
findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%TEMP%\barche-installa.txt" >nul
if not errorlevel 1 (
  echo.
  echo La versione installata ha una firma diversa: la tolgo e installo quella nuova.
  "%ADB%" %BERSAGLIO% uninstall it.gallipoli.barchetv
  "%ADB%" %BERSAGLIO% install -r "%APK%"
)

echo Avvio l'app...
"%ADB%" %BERSAGLIO% shell monkey -p it.gallipoli.barchetv 1 >nul 2>&1
echo Fatto.

:fine
echo.
pause
exit /b

rem ---------------------------------------------------------------------
:collega_tv
set "COLLEGATA=no"
for /l %%t in (1,1,3) do (
  if not "!COLLEGATA!"=="si" call :prova_tv %%t
)
if not "%COLLEGATA%"=="si" (
  echo.
  echo Non riesco a collegarmi alla Fire TV %FIRETV_IP%. Controlla:
  echo  - che l'IP sia giusto ^(Impostazioni ^> La mia Fire TV ^> Informazioni ^> Rete^)
  echo  - che "Debug ADB" sia attivo ^(Impostazioni ^> La mia Fire TV ^> Opzioni sviluppatore^)
  echo  - che la TV sia accesa e sulla stessa rete del PC
)
exit /b

:prova_tv
echo Mi collego alla Fire TV %FIRETV_IP% ^(tentativo %1 di 3^)...
"%ADB%" disconnect %FIRETV_IP%:5555 >nul 2>&1
"%ADB%" connect %FIRETV_IP%:5555
"%ADB%" -s %FIRETV_IP%:5555 get-state > "%TEMP%\barche-stato.txt" 2>&1
findstr /b /c:"device" "%TEMP%\barche-stato.txt" >nul
if not errorlevel 1 (
  set "COLLEGATA=si"
  echo Collegata.
  exit /b
)
findstr /c:"unauthorized" "%TEMP%\barche-stato.txt" >nul
if not errorlevel 1 (
  echo.
  echo  ^>^>^> Guarda la TV: alla domanda "Consentire debug USB?" spunta
  echo  ^>^>^> "Consenti sempre da questo computer" e premi OK, poi premi un tasto qui.
  pause >nul
) else (
  type "%TEMP%\barche-stato.txt"
  timeout /t 3 >nul
)
exit /b
