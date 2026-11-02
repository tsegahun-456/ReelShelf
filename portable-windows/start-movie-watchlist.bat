@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem First run: create the personal config file next to your Windows profile.
set "CONFIG_DIR=%USERPROFILE%\.config\movie-watchlist"
set "CONFIG_FILE=%CONFIG_DIR%\movie-watchlist.env"
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%"
if not exist "%CONFIG_FILE%" (
  copy /y "movie-watchlist.env.example" "%CONFIG_FILE%" >nul
  echo Created your config file: "%CONFIG_FILE%"
)

rem Load KEY=VALUE lines from the config (lines starting with # are comments).
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%CONFIG_FILE%") do set "%%A=%%B"
if not defined PORT set "PORT=8080"

set "BUNDLED_JAVA=%~dp0runtime\bin\java.exe"
echo ReelShelf is starting. When the scrolling stops, open this address in Chrome:
echo     http://127.0.0.1:%PORT%/
echo Close this window, or press Ctrl+C, to stop the app.
echo.

if exist "%BUNDLED_JAVA%" (
  "%BUNDLED_JAVA%" -Dspring.profiles.active=portable -Dfile.encoding=UTF-8 -jar "movie-watchlist-web.jar" --server.port=%PORT%
) else (
  where java >nul 2>nul
  if errorlevel 1 (
    echo The bundled runtime\ folder is missing and no Java was found on this PC.
    echo Re-extract the FULL ReelShelf zip - it includes Java, nothing to install.
    echo.
    pause
    exit /b 1
  )
  echo Bundled runtime\ not found - using the Java installed on this PC.
  java -Dspring.profiles.active=portable -Dfile.encoding=UTF-8 -jar "movie-watchlist-web.jar" --server.port=%PORT%
)
echo.
echo ReelShelf stopped.
pause
