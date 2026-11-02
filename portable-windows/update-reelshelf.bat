@echo off
setlocal
set "BUNDLE_DIR=%~dp0"
set "UPDATE_DIR=%LOCALAPPDATA%\ReelShelf\updates"
if not defined LOCALAPPDATA set "UPDATE_DIR=%USERPROFILE%\.cache\ReelShelf\updates"
if not exist "%UPDATE_DIR%" mkdir "%UPDATE_DIR%" >nul 2>nul
set "RUN_COPY=%UPDATE_DIR%\updater-run-%RANDOM%-%RANDOM%.jar"
copy /b /y "%BUNDLE_DIR%updater\reelshelf-updater.jar" "%RUN_COPY%" >nul
if errorlevel 1 (
  echo Could not prepare the ReelShelf updater. Re-extract the full portable zip.
  pause
  exit /b 1
)
set "JAVA=%BUNDLE_DIR%runtime\bin\javaw.exe"
if not exist "%JAVA%" set "JAVA=%BUNDLE_DIR%runtime\bin\java.exe"
if not exist "%JAVA%" (
  echo The bundled Java runtime is missing. Re-extract the full portable zip.
  pause
  exit /b 1
)
start "" "%JAVA%" -jar "%RUN_COPY%" --bundle-dir "%BUNDLE_DIR%" --platform windows
exit /b 0
