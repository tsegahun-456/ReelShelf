ReelShelf (Movie Watchlist) - Windows portable
================================================

Your local movie & TV library: points at folders you choose, shows them like a
streaming service, keeps your Watchlist/Watched lists signed-in-synced when you
want that. Everything stays on this PC unless you sign in.


REQUIREMENTS
------------
- 64-bit Windows 10 or 11.
- Nothing else: Java 21 is INCLUDED in this zip (the runtime\ folder,
  Eclipse Temurin JRE - no installer, no admin rights needed).
- A local video folder (C:\Movies, an external drive, ...).
- Optional: internet for TMDB posters/descriptions and for sign-in sync.


INSTALL & RUN
-------------
1. Unzip this archive to any folder you keep (path may contain spaces).
2. Double-click  start-movie-watchlist.bat
   - First run creates your personal config file at
     %USERPROFILE%\.config\movie-watchlist\movie-watchlist.env
   - It then starts the app and prints the address: http://127.0.0.1:8080/
3. Open that address in Chrome (or any browser). Pin the tab like an app.
4. To STOP the app: close its window (or press Ctrl+C there).

If the port is taken, edit PORT in the config file above and start again.


PICK YOUR FOLDERS (Settings)
----------------------------
After first launch open  Settings  in the app:
- It auto-detects Videos, Downloads, Movies and mounted drives - click Add.
- Or paste any folder path and press Add folder.
- Remove a folder and its titles are removed from the library too.
- "Scan now" refreshes instantly; otherwise the list refreshes every 5 minutes.
Only what you add is scanned; the list lives in the app, not in config files.
(MEDIA_ROOTS in the config file only seeds the very first run.)


SIGN-IN, WATCHLIST / WATCHED
----------------------------
- TMDB_API_KEY in the config file enables posters, search and details
  (get a free key at https://www.themoviedb.org/settings/api).
- Sign in (or create an account) to sync Watchlist/Watched with your Firebase
  account between devices. Local library browsing works without sign-in.
- Tick "Keep me signed in" on the sign-in page to stay signed in across
  restarts. The sign-in key is stored encrypted on this PC only; "Sign out"
  removes it.


YOUR DATA (not inside this folder - safe to delete/replace the app)
-------------------------------------------------------------------
- Library database:  %USERPROFILE%\.local\share\movie-watchlist\database\
- Sign-in key:       %USERPROFILE%\.local\share\movie-watchlist\remember-me.key
- Config file:       %USERPROFILE%\.config\movie-watchlist\movie-watchlist.env
Deleting this app folder does not delete your lists or library.


TROUBLESHOOTING
---------------
- "bundled runtime\ folder is missing" -> you copied only the bat/jar; extract
  the FULL zip to a folder and run start-movie-watchlist.bat from there.
- Port already in use  -> change PORT in the config file.
- No posters           -> check TMDB_API_KEY and internet; playback never
  depends on them.
- Nothing in My media  -> add your folders in Settings, press Scan now.
- Windows Firewall prompt on first run -> Allow access on your private network;
  the app only listens on this PC (127.0.0.1) anyway.


CREDITS
-------
Bundled Java: Eclipse Temurin JRE 21 (GPLv2 with Classpath Exception) - license
texts under runtime\legal\. ReelShelf itself stays on this PC; TMDB and Firebase
are only contacted if you configure their keys.
