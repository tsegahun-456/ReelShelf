package com.segi.watchlist.updater;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Small, self-contained updater for the Linux and Windows portable bundles. */
public final class ReelShelfUpdater {
    private static final String OWNER = "tsegahun-456";
    private static final String REPOSITORY = "ReelShelf";
    private static final String LINUX_ASSET = "Movie-Watchlist-Linux-Portable.tar.gz";
    private static final String WINDOWS_ASSET = "Movie-Watchlist-Windows-Portable.zip";
    private static final Pattern TAG_PATTERN = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-.]([A-Za-z0-9.-]+))?$");

    private ReelShelfUpdater() {}

    public static void main(String[] args) {
        try {
            Path bundle = Paths.get(argumentValue(args, "--bundle-dir")).toAbsolutePath().normalize();
            String platform = argumentValue(args, "--platform");
            Path cache = userCacheDirectory();
            Files.createDirectories(cache);
            InstanceLock lock = InstanceLock.acquire(cache.resolve("updater.lock"));
            if (lock == null) return;
            SwingUtilities.invokeLater(() -> {
                UpdaterWindow window = new UpdaterWindow(bundle, platform, cache, lock);
                window.setVisible(true);
                lock.attach(window);
                window.checkForUpdate();
            });
        } catch (Exception exception) {
            showFatal("Could not open the ReelShelf updater", exception.getMessage());
        }
    }

    private static String argumentValue(String[] args, String name) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (name.equals(args[index])) return args[index + 1];
        }
        throw new IllegalArgumentException("Missing " + name + " argument.");
    }

    private static Path userCacheDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return Paths.get(local == null || local.isBlank() ? System.getProperty("user.home") : local,
                    "ReelShelf", "updates");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        return Paths.get(xdg == null || xdg.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".cache").toString() : xdg,
                "reelshelf", "updates");
    }

    private static void showFatal(String title, String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                message == null ? "An unexpected error occurred." : message, title, JOptionPane.ERROR_MESSAGE));
    }

    private record Release(String tag, String version, String assetName, URI archiveUri, URI checksumUri) {}
    private record Progress(long bytes, long total, long bytesPerSecond) {}
    private enum DownloadResult { READY, PAUSED, CANCELED }

    private static final class UpdaterWindow extends JFrame {
        private final Path bundleDir;
        private final String platform;
        private final Path cache;
        private final InstanceLock lock;
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20)).build();
        private final JLabel status = new JLabel("Connecting to ReelShelf updates…");
        private final JLabel versions = new JLabel(" ");
        private final JLabel progressText = new JLabel(" ");
        private final JProgressBar progress = new JProgressBar(0, 100);
        private final JButton download = new JButton("Download update");
        private final JButton pause = new JButton("Pause and keep progress");
        private final JButton install = new JButton("Install and restart");
        private final JButton quit = new JButton("Quit and delete partial download");
        private final AtomicBoolean pauseRequested = new AtomicBoolean();
        private final AtomicBoolean deleteRequested = new AtomicBoolean();
        private volatile InputStream responseStream;
        private volatile SwingWorker<DownloadResult, Progress> downloadWorker;
        private volatile boolean workerActive;
        private volatile boolean installing;
        private volatile boolean closeAfterPause;
        private volatile Release latestRelease;
        private volatile Path partFile;
        private volatile Path metadataFile;
        private volatile Path readyFile;
        private String currentVersion = "unknown";

        private UpdaterWindow(Path bundleDir, String platform, Path cache, InstanceLock lock) {
            super("ReelShelf updater");
            this.bundleDir = bundleDir;
            this.platform = platform.toLowerCase(Locale.ROOT);
            this.cache = cache;
            this.lock = lock;
            String asset = "windows".equals(this.platform) ? WINDOWS_ASSET : LINUX_ASSET;
            this.partFile = cache.resolve(asset + ".part");
            this.metadataFile = cache.resolve(asset + ".download.properties");
            this.readyFile = cache.resolve(asset);
            buildWindow();
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) {
                    if (installing) {
                        status.setText("Installation is in progress. Please wait for it to finish.");
                        return;
                    }
                    closeWindowSafely();
                }
                @Override public void windowClosed(WindowEvent event) { lock.close(); }
            });
        }

        private void buildWindow() {
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setMinimumSize(new Dimension(520, 260));
            setSize(610, 300);
            setLocationRelativeTo(null);

            JLabel heading = new JLabel("ReelShelf update", SwingConstants.LEFT);
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, 21f));
            versions.setForeground(new java.awt.Color(100, 100, 100));
            progress.setStringPainted(true);
            progress.setString("Waiting for update information");
            progress.setValue(0);

            JPanel information = new JPanel(new GridLayout(0, 1, 0, 8));
            information.add(heading);
            information.add(versions);
            information.add(status);
            information.add(progress);
            information.add(progressText);
            information.setBorder(BorderFactory.createEmptyBorder(18, 20, 10, 20));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            buttons.add(quit);
            buttons.add(pause);
            buttons.add(download);
            buttons.add(install);
            buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

            setLayout(new BorderLayout());
            add(information, BorderLayout.CENTER);
            add(buttons, BorderLayout.SOUTH);
            download.setEnabled(false);
            pause.setEnabled(false);
            install.setVisible(false);
            download.addActionListener(event -> beginDownload());
            pause.addActionListener(event -> pauseDownload());
            install.addActionListener(event -> installUpdate());
            quit.addActionListener(event -> cancelAndQuit());
        }

        private void checkForUpdate() {
            status.setText("Checking the latest release…");
            SwingWorker<Release, Void> worker = new SwingWorker<>() {
                @Override protected Release doInBackground() throws Exception {
                    currentVersion = readAppVersion(bundleDir, platform);
                    return fetchLatestRelease(client, platform);
                }

                @Override protected void done() {
                    try {
                        latestRelease = get();
                        versions.setText("Installed " + currentVersion + "   ·   Latest " + latestRelease.version());
                        if (compareVersions(latestRelease.version(), currentVersion) <= 0) {
                            status.setText("You already have the latest version.");
                            progress.setValue(100);
                            progress.setString("Up to date");
                            progressText.setText(" ");
                            download.setEnabled(false);
                            return;
                        }
                        status.setText("A new ReelShelf version is available.");
                        boolean verifyingReadyFile = Files.exists(readyFile);
                        prepareDownloadState();
                        if (!verifyingReadyFile) download.setEnabled(true);
                        download.setText(Files.exists(partFile) && fileSize(partFile) > 0
                                ? "Resume download" : "Download update");
                    } catch (Exception exception) {
                        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                        status.setText("Could not check for updates.");
                        progress.setString("Check your internet connection and retry");
                        download.setText("Retry check");
                        download.setEnabled(true);
                        progressText.setText(message(cause));
                    }
                }
            };
            worker.execute();
        }

        private void prepareDownloadState() throws IOException {
            Properties saved = readProperties(metadataFile);
            String savedTag = saved.getProperty("tag", "");
            if (!savedTag.equals(latestRelease.tag())) {
                Files.deleteIfExists(partFile);
                Files.deleteIfExists(metadataFile);
            }
            if (Files.exists(readyFile)) {
                status.setText("Checking the saved update download…");
                download.setEnabled(false);
                new SwingWorker<Boolean, Void>() {
                    @Override protected Boolean doInBackground() throws Exception {
                        return verifyArchive(readyFile, latestRelease);
                    }
                    @Override protected void done() {
                        try {
                            if (get()) {
                                showReadyToInstall();
                            } else {
                                Files.deleteIfExists(readyFile);
                                status.setText("A new ReelShelf version is available.");
                                download.setEnabled(true);
                            }
                        } catch (Exception exception) {
                            status.setText("Could not verify the saved download. Download it again.");
                            download.setEnabled(true);
                        }
                    }
                }.execute();
            } else {
                long bytes = fileSize(partFile);
                if (bytes > 0) {
                    Properties metadata = readProperties(metadataFile);
                    long total = parseLong(metadata.getProperty("totalBytes"), -1);
                    progress.setIndeterminate(total <= 0);
                    if (total > 0) {
                        progress.setValue((int) Math.min(99, bytes * 100 / total));
                        progress.setString(formatBytes(bytes) + " of " + formatBytes(total));
                    } else {
                        progress.setString(formatBytes(bytes) + " downloaded");
                    }
                    progressText.setText("Saved progress is ready to resume.");
                } else {
                    progress.setIndeterminate(false);
                    progress.setValue(0);
                    progress.setString("Ready to download");
                    progressText.setText(" ");
                }
            }
        }

        private void beginDownload() {
            if (latestRelease == null) {
                checkForUpdate();
                return;
            }
            if (workerActive) return;
            pauseRequested.set(false);
            deleteRequested.set(false);
            closeAfterPause = false;
            workerActive = true;
            download.setEnabled(false);
            pause.setText("Pause and keep progress");
            pause.setEnabled(true);
            install.setVisible(false);
            quit.setEnabled(true);
            status.setText("Downloading ReelShelf " + latestRelease.version() + "…");

            downloadWorker = new SwingWorker<>() {
                @Override protected DownloadResult doInBackground() throws Exception {
                    return downloadAndVerify(latestRelease, this::publish);
                }

                @Override protected void process(List<Progress> updates) {
                    if (updates.isEmpty()) return;
                    Progress latest = updates.get(updates.size() - 1);
                    if (latest.total() > 0) {
                        progress.setIndeterminate(false);
                        progress.setValue((int) Math.min(100, latest.bytes() * 100 / latest.total()));
                        progress.setString(formatBytes(latest.bytes()) + " / " + formatBytes(latest.total()));
                    } else {
                        progress.setIndeterminate(true);
                        progress.setString(formatBytes(latest.bytes()) + " downloaded");
                    }
                    progressText.setText(formatBytesPerSecond(latest.bytesPerSecond()) + "  ·  "
                            + remainingText(latest.bytes(), latest.total(), latest.bytesPerSecond()));
                }

                @Override protected void done() {
                    workerActive = false;
                    pause.setEnabled(false);
                    try {
                        DownloadResult result = get();
                        if (result == DownloadResult.READY) showReadyToInstall();
                        else if (result == DownloadResult.PAUSED) {
                            status.setText("Download paused. Your progress is saved for later.");
                            download.setText("Resume download");
                            download.setEnabled(true);
                            progressText.setText("You can close this window or resume now.");
                            progress.setString("Paused · " + formatBytes(fileSize(partFile)) + " saved");
                        } else {
                            deletePartialFiles();
                            dispose();
                        }
                        if (closeAfterPause) {
                            closeAfterPause = false;
                            dispose();
                        }
                    } catch (Exception exception) {
                        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                        status.setText("The update download could not be completed.");
                        progress.setIndeterminate(false);
                        progress.setString("Retry or resume the download later");
                        progressText.setText(message(cause));
                        download.setText(fileSize(partFile) > 0 ? "Resume download" : "Retry download");
                        download.setEnabled(true);
                        if (closeAfterPause) {
                            closeAfterPause = false;
                            dispose();
                        }
                    }
                }
            };
            downloadWorker.execute();
        }

        private DownloadResult downloadAndVerify(Release release,
                                                  java.util.function.Consumer<Progress> progressPublisher) throws Exception {
            if (Files.exists(readyFile) && verifyArchive(readyFile, release)) return DownloadResult.READY;
            Properties metadata = readProperties(metadataFile);
            if (!release.tag().equals(metadata.getProperty("tag", ""))) {
                Files.deleteIfExists(partFile);
                metadata.clear();
            }
            String savedEtag = metadata.getProperty("etag", "");
            long offset = fileSize(partFile);
            long savedTotal = parseLong(metadata.getProperty("totalBytes"), -1);
            if (savedTotal > 0 && offset == savedTotal) {
                if (verifyArchive(partFile, release)) {
                    ReelShelfUpdater.move(partFile, readyFile);
                    Files.deleteIfExists(metadataFile);
                    return DownloadResult.READY;
                }
                offset = 0;
                Files.deleteIfExists(partFile);
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(release.archiveUri())
                    .timeout(Duration.ofHours(3)).header("User-Agent", "ReelShelf-Updater")
                    .header("Accept", "application/octet-stream").GET();
            if (offset > 0) {
                requestBuilder.header("Range", "bytes=" + offset + "-");
                if (!savedEtag.isBlank()) requestBuilder.header("If-Range", savedEtag);
            }
            HttpResponse<InputStream> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            responseStream = response.body();
            if (deleteRequested.get()) {
                closeResponse();
                return DownloadResult.CANCELED;
            }
            if (pauseRequested.get()) {
                closeResponse();
                return DownloadResult.PAUSED;
            }
            int code = response.statusCode();
            if (code == 416 && offset == savedTotal && offset > 0) {
                closeResponse();
                if (verifyArchive(partFile, release)) {
                    ReelShelfUpdater.move(partFile, readyFile);
                    Files.deleteIfExists(metadataFile);
                    return DownloadResult.READY;
                }
                Files.deleteIfExists(partFile);
                return downloadAndVerify(release, progressPublisher);
            }
            if (code != 200 && code != 206) {
                closeResponse();
                throw new IOException("The release server returned HTTP " + code + ".");
            }
            boolean append = offset > 0 && code == 206;
            if (offset > 0 && code == 200) offset = 0;
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            long total = response.headers().firstValue("Content-Range").map(ReelShelfUpdater::rangeTotal)
                    .orElse(contentLength > 0 ? offset + contentLength : -1);
            String etag = response.headers().firstValue("ETag").orElse(savedEtag);
            metadata = new Properties();
            metadata.setProperty("tag", release.tag());
            metadata.setProperty("url", release.archiveUri().toString());
            if (total > 0) metadata.setProperty("totalBytes", Long.toString(total));
            if (!etag.isBlank()) metadata.setProperty("etag", etag);
            writeProperties(metadataFile, metadata);

            long bytes = offset;
            long lastSampleBytes = bytes;
            long lastSampleAt = System.nanoTime();
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(partFile, StandardOpenOption.CREATE,
                         append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                responseStream = input;
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (deleteRequested.get()) return DownloadResult.CANCELED;
                    if (pauseRequested.get()) return DownloadResult.PAUSED;
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    output.flush();
                    bytes += read;
                    long now = System.nanoTime();
                    if (now - lastSampleAt >= 500_000_000L) {
                        long speed = (long) ((bytes - lastSampleBytes) / ((now - lastSampleAt) / 1_000_000_000d));
                        progressPublisher.accept(new Progress(bytes, total, Math.max(speed, 0)));
                        lastSampleBytes = bytes;
                        lastSampleAt = now;
                    }
                }
            } catch (IOException exception) {
                if (deleteRequested.get()) return DownloadResult.CANCELED;
                if (pauseRequested.get()) return DownloadResult.PAUSED;
                throw exception;
            } finally {
                responseStream = null;
            }
            if (deleteRequested.get()) return DownloadResult.CANCELED;
            if (pauseRequested.get()) return DownloadResult.PAUSED;
            progressPublisher.accept(new Progress(bytes, total, 0));
            if (total > 0 && bytes != total) throw new IOException("The download ended before the full archive arrived.");
            statusText("Checking the release checksum…");
            if (deleteRequested.get()) return DownloadResult.CANCELED;
            if (pauseRequested.get()) return DownloadResult.PAUSED;
            if (!verifyArchive(partFile, release)) {
                Files.deleteIfExists(partFile);
                Files.deleteIfExists(metadataFile);
                throw new IOException("The downloaded archive did not match the published SHA-256 checksum.");
            }
            if (deleteRequested.get()) return DownloadResult.CANCELED;
            if (pauseRequested.get()) return DownloadResult.PAUSED;
            ReelShelfUpdater.move(partFile, readyFile);
            Files.deleteIfExists(metadataFile);
            return DownloadResult.READY;
        }

        private boolean verifyArchive(Path archive, Release release) throws Exception {
            if (!Files.isRegularFile(archive)) return false;
            HttpRequest request = HttpRequest.newBuilder(release.checksumUri()).timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "ReelShelf-Updater").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Could not download the release checksum (HTTP " + response.statusCode() + ").");
            Matcher matcher = Pattern.compile("(?i)\\b([a-f0-9]{64})\\b").matcher(response.body());
            if (!matcher.find()) throw new IOException("The release checksum file is invalid.");
            String expected = matcher.group(1).toLowerCase(Locale.ROOT);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return expected.equals(hex(digest.digest()));
        }

        private void installUpdate() {
            if (workerActive || latestRelease == null || !Files.isRegularFile(readyFile)) return;
            workerActive = true;
            installing = true;
            install.setEnabled(false);
            download.setEnabled(false);
            quit.setEnabled(false);
            status.setText("Preparing the update. ReelShelf may close briefly…");
            progress.setIndeterminate(true);
            progress.setString("Verifying and installing");
            new SwingWorker<Void, String>() {
                @Override protected Void doInBackground() throws Exception {
                    installBundle(readyFile, latestRelease);
                    return null;
                }

                @Override protected void done() {
                    workerActive = false;
                    installing = false;
                    try {
                        get();
                        status.setText("ReelShelf " + latestRelease.version() + " is installed and running.");
                        progress.setIndeterminate(false);
                        progress.setValue(100);
                        progress.setString("Update complete");
                        progressText.setText("Your library and settings were preserved.");
                        install.setEnabled(false);
                        download.setEnabled(false);
                        quit.setText("Close");
                        quit.setEnabled(true);
                        quit.addActionListener(event -> dispose());
                    } catch (Exception exception) {
                        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                        status.setText("The update could not be installed.");
                        progress.setIndeterminate(false);
                        progress.setString("Recovery files are kept; details are shown below");
                        progressText.setText(message(cause));
                        install.setText("Retry install");
                        install.setEnabled(true);
                        quit.setText("Quit and keep verified download");
                        quit.setEnabled(true);
                    }
                }
            }.execute();
        }

        private void installBundle(Path archive, Release release) throws Exception {
            if (!verifyArchive(archive, release)) throw new IOException("The release checksum no longer matches.");
            Path parent = bundleDir.getParent();
            if (parent == null || !Files.isWritable(parent)) throw new IOException("The portable app folder is not writable.");
            String bundleName = "windows".equals(platform) ? "Movie-Watchlist-Windows-Portable" : "Movie-Watchlist-Linux-Portable";
            Path stage = parent.resolve("." + bundleDir.getFileName() + ".update-stage-" + release.version());
            Path backup = parent.resolve("." + bundleDir.getFileName() + ".update-backup-" + System.currentTimeMillis());
            deleteTree(stage);
            Files.createDirectories(stage);
            try {
                extractArchive(archive, stage, bundleName);
            } catch (Exception exception) {
                deleteTree(stage);
                throw exception;
            }
            Path stagedBundle = stage.resolve(bundleName);
            List<String> replace = "windows".equals(platform)
                    ? List.of("updater", "movie-watchlist-web.jar", "start-movie-watchlist.bat",
                        "update-reelshelf.bat", "README.txt", "movie-watchlist.env.example")
                    : List.of("app-image/lib/app", "updater", "scripts", "docs", "movie-watchlist.env.example");
            try {
                stopRunningApp();
            } catch (Exception exception) {
                deleteTree(stage);
                throw exception;
            }
            Files.createDirectories(backup);
            List<String> movedOld = new ArrayList<>();
            List<String> movedNew = new ArrayList<>();
            try {
                for (String item : replace) {
                    Path target = bundleDir.resolve(item);
                    Path saved = backup.resolve(item);
                    if (Files.exists(target)) {
                        Files.createDirectories(saved.getParent());
                        ReelShelfUpdater.move(target, saved);
                        movedOld.add(item);
                    }
                    Path source = stagedBundle.resolve(item);
                    if (!Files.exists(source)) throw new IOException("Update package is missing " + item + ".");
                    Files.createDirectories(target.getParent());
                    ReelShelfUpdater.move(source, target);
                    movedNew.add(item);
                }
            } catch (Exception exception) {
                try {
                    for (String item : movedNew) deleteTree(bundleDir.resolve(item));
                    for (String item : movedOld) {
                        Path saved = backup.resolve(item);
                        if (Files.exists(saved)) ReelShelfUpdater.move(saved, bundleDir.resolve(item));
                    }
                    deleteTree(backup);
                } catch (Exception rollbackException) {
                    exception.addSuppressed(rollbackException);
                    throw new IOException("The update failed and the previous files are saved at " + backup, exception);
                }
                throw exception;
            } finally {
                deleteTree(stage);
            }
            try {
                startUpdatedApp();
            } catch (Exception exception) {
                throw new IOException("The update files were installed, but ReelShelf could not be started. The previous files are saved at " + backup, exception);
            }
            Files.deleteIfExists(readyFile);
            try { deleteTree(backup); } catch (IOException ignored) {}
        }

        private void stopRunningApp() throws Exception {
            int port = configuredPort();
            if (!isHealthy(port)) return;
            if ("linux".equals(platform)) {
                Path stopScript = bundleDir.resolve("scripts/stop-movie-watchlist-portable.sh");
                if (!Files.isRegularFile(stopScript)) throw new IOException("The ReelShelf stop script is missing.");
                Process process = new ProcessBuilder("bash", stopScript.toString()).redirectErrorStream(true).start();
                String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!process.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("ReelShelf did not stop in time.");
                }
                if (process.exitValue() != 0) throw new IOException("Could not stop ReelShelf: " + result.strip());
            } else {
                throw new IOException("Close the ReelShelf command window to stop it, then choose Retry install.");
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (isHealthy(port) && System.nanoTime() < deadline) Thread.sleep(300);
            if (isHealthy(port)) throw new IOException("ReelShelf is still running. Close it and retry the update.");
        }

        private void startUpdatedApp() throws Exception {
            if ("linux".equals(platform)) {
                Path launcher = bundleDir.resolve("scripts/launch-movie-watchlist-portable.sh");
                Process process = new ProcessBuilder("bash", launcher.toString()).redirectErrorStream(true).start();
                if (!process.waitFor(100, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw new IOException("The update installed, but ReelShelf did not start. Run the bundled launcher to retry.");
                }
            } else {
                Path launcher = bundleDir.resolve("start-movie-watchlist.bat");
                new ProcessBuilder("cmd.exe", "/c", "start", "", launcher.toString())
                        .directory(bundleDir.toFile()).start();
                long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                while (!isHealthy(configuredPort()) && System.nanoTime() < deadline) Thread.sleep(500);
                if (!isHealthy(configuredPort())) throw new IOException("The update installed, but ReelShelf did not start. Run start-movie-watchlist.bat to retry.");
            }
        }

        private int configuredPort() {
            Path config = "windows".equals(platform)
                    ? Paths.get(System.getProperty("user.home"), ".config", "movie-watchlist", "movie-watchlist.env")
                    : Paths.get(System.getenv().getOrDefault("XDG_CONFIG_HOME", Paths.get(System.getProperty("user.home"), ".config").toString()),
                            "movie-watchlist", "movie-watchlist.env");
            try {
                for (String line : Files.readAllLines(config)) {
                    if (line.stripLeading().startsWith("PORT=")) {
                        int parsed = Integer.parseInt(line.substring(line.indexOf('=') + 1).replaceAll("[\\\"'\\s]", ""));
                        if (parsed > 0 && parsed <= 65535) return parsed;
                    }
                }
            } catch (Exception ignored) {}
            return 8080;
        }

        private boolean isHealthy(int port) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
            } catch (Exception ignored) { return false; }
        }

        private void extractArchive(Path archive, Path stage, String expectedRoot) throws Exception {
            if ("windows".equals(platform)) {
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        Path name = Paths.get(entry.getName()).normalize();
                        if (name.isAbsolute() || name.startsWith("..") || !name.getName(0).toString().equals(expectedRoot)) {
                            throw new IOException("The update archive contains an unexpected path.");
                        }
                    }
                }
                runProcess(List.of("tar.exe", "-xf", archive.toString(), "-C", stage.toString()), stage, Duration.ofMinutes(8));
            } else {
                runProcess(List.of("tar", "--extract", "--gzip", "--file", archive.toString(), "--directory", stage.toString(), "--no-same-owner"),
                        stage, Duration.ofMinutes(8));
            }
            if (!Files.isDirectory(stage.resolve(expectedRoot))) throw new IOException("The update archive has the wrong folder layout.");
        }

        private void pauseDownload() {
            if (!workerActive) return;
            pauseRequested.set(true);
            status.setText("Pausing and saving your progress…");
            pause.setEnabled(false);
            closeResponse();
        }

        private void cancelAndQuit() {
            if (workerActive) {
                deleteRequested.set(true);
                pauseRequested.set(false);
                status.setText("Deleting the unfinished download…");
                quit.setEnabled(false);
                download.setEnabled(false);
                pause.setEnabled(false);
                closeResponse();
                if (downloadWorker == null) {
                    deletePartialFiles();
                    dispose();
                }
                return;
            }
            if (Files.exists(partFile) || Files.exists(metadataFile)) {
                int answer = JOptionPane.showConfirmDialog(this,
                        "Delete the unfinished update download and close the updater?",
                        "Delete saved download", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
                deletePartialFiles();
            }
            dispose();
        }

        private void closeWindowSafely() {
            if (workerActive) {
                closeAfterPause = true;
                pauseRequested.set(true);
                status.setText("Pausing and saving progress before closing…");
                closeResponse();
            } else {
                dispose();
            }
        }

        private void showReadyToInstall() {
            status.setText("Download complete and checksum verified.");
            progress.setIndeterminate(false);
            progress.setValue(100);
            progress.setString("Ready to install " + latestRelease.version());
            progressText.setText("ReelShelf will restart briefly; your library and settings stay in place.");
            download.setEnabled(false);
            pause.setEnabled(false);
            install.setVisible(true);
            install.setText("Install and restart");
            install.setEnabled(true);
            quit.setText("Quit and keep verified download");
            quit.setEnabled(true);
        }

        private void statusText(String value) {
            SwingUtilities.invokeLater(() -> status.setText(value));
        }

        private void closeResponse() {
            InputStream stream = responseStream;
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
            }
        }

        private void deletePartialFiles() {
            try { Files.deleteIfExists(partFile); } catch (IOException ignored) {}
            try { Files.deleteIfExists(metadataFile); } catch (IOException ignored) {}
        }
    }

    private static Release fetchLatestRelease(HttpClient client, String platform) throws Exception {
        URI api = URI.create("https://api.github.com/repos/" + OWNER + "/" + REPOSITORY + "/releases/latest");
        HttpRequest request = HttpRequest.newBuilder(api).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "ReelShelf-Updater")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GitHub returned HTTP " + response.statusCode() + " while checking for the latest release.");
        Matcher matcher = TAG_PATTERN.matcher(response.body());
        if (!matcher.find()) throw new IOException("GitHub did not return a release tag.");
        String tag = matcher.group(1);
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!VERSION_PATTERN.matcher(version).matches()) throw new IOException("The latest release tag has an unsupported version format.");
        String asset = "windows".equalsIgnoreCase(platform) ? WINDOWS_ASSET : LINUX_ASSET;
        String encodedTag = java.net.URLEncoder.encode(tag, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        URI base = URI.create("https://github.com/" + OWNER + "/" + REPOSITORY + "/releases/download/" + encodedTag + "/");
        return new Release(tag, version, asset, base.resolve(asset), base.resolve(asset + ".sha256"));
    }

    private static String readAppVersion(Path bundle, String platform) throws IOException {
        Path jar;
        if ("windows".equalsIgnoreCase(platform)) jar = bundle.resolve("movie-watchlist-web.jar");
        else {
            Path appDir = bundle.resolve("app-image/lib/app");
            try (var files = Files.list(appDir)) {
                jar = files.filter(path -> path.getFileName().toString().startsWith("movie-watchlist-web")
                                && path.getFileName().toString().endsWith(".jar"))
                        .findFirst().orElseThrow(() -> new IOException("The installed ReelShelf application JAR is missing."));
            }
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/build-info.properties");
            if (entry == null) throw new IOException("The installed app version could not be read.");
            Properties properties = new Properties();
            try (InputStream input = zip.getInputStream(entry)) { properties.load(input); }
            return properties.getProperty("build.version", "unknown");
        }
    }

    private static int compareVersions(String left, String right) {
        Matcher a = VERSION_PATTERN.matcher(left);
        Matcher b = VERSION_PATTERN.matcher(right);
        if (!a.matches() || !b.matches()) return left.compareToIgnoreCase(right);
        for (int part = 1; part <= 3; part++) {
            int comparison = Integer.compare(Integer.parseInt(a.group(part)), Integer.parseInt(b.group(part)));
            if (comparison != 0) return comparison;
        }
        String aSuffix = a.group(4);
        String bSuffix = b.group(4);
        if (aSuffix == null && bSuffix != null) return 1;
        if (aSuffix != null && bSuffix == null) return -1;
        return aSuffix == null ? 0 : aSuffix.compareToIgnoreCase(bSuffix);
    }

    private static long rangeTotal(String header) {
        Matcher matcher = Pattern.compile("bytes\\s+\\d+-\\d+/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(header);
        return matcher.find() ? parseLong(matcher.group(1), -1) : -1;
    }

    private static Properties readProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) return properties;
        try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        catch (IOException ignored) {}
        return properties;
    }

    private static void writeProperties(Path file, Properties properties) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "ReelShelf resumable update download");
        }
        move(temp, file);
    }

    private static void runProcess(List<String> command, Path directory, Duration timeout) throws Exception {
        Path log = directory.resolve(".reelshelf-unpack.log");
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("The update archive could not be unpacked in time.");
        }
        if (process.exitValue() != 0) {
            String detail = Files.exists(log) ? Files.readString(log).strip() : "";
            Files.deleteIfExists(log);
            throw new IOException(detail.isBlank() ? "The update archive could not be unpacked." : detail);
        }
        Files.deleteIfExists(log);
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                if (exception != null) throw exception;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024d);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024d));
    }

    private static String formatBytesPerSecond(long bytes) {
        return bytes <= 0 ? "Speed calculating…" : formatBytes(bytes) + "/s";
    }

    private static String remainingText(long bytes, long total, long speed) {
        if (total <= 0 || speed <= 0 || bytes >= total) return "Time remaining calculating…";
        long seconds = (total - bytes) / speed;
        return "About " + (seconds < 60 ? seconds + " sec" : (seconds / 60) + " min") + " remaining";
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }

    private static long fileSize(Path file) {
        try { return Files.size(file); } catch (IOException ignored) { return 0; }
    }

    private static String message(Throwable throwable) {
        if (throwable == null) return " ";
        String text = throwable.getMessage();
        return text == null || text.isBlank() ? throwable.getClass().getSimpleName() : text;
    }

    private static final class InstanceLock {
        private final FileChannel channel;
        private final FileLock lock;
        private volatile ServerSocket server;
        private volatile JFrame frame;

        private InstanceLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        private static InstanceLock acquire(Path path) throws IOException {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException exception) { lock = null; }
            if (lock == null) {
                try {
                    channel.position(0);
                    byte[] portBytes = new byte[32];
                    int length = channel.read(java.nio.ByteBuffer.wrap(portBytes));
                    if (length > 0) {
                        int port = Integer.parseInt(new String(portBytes, 0, length, java.nio.charset.StandardCharsets.UTF_8).trim());
                        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                             OutputStream output = socket.getOutputStream()) { output.write("FOCUS\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                    }
                } catch (Exception ignored) {}
                channel.close();
                return null;
            }
            InstanceLock instance = new InstanceLock(channel, lock);
            instance.startFocusServer();
            return instance;
        }

        private void attach(JFrame frame) { this.frame = frame; }

        private void startFocusServer() throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            channel.truncate(0);
            channel.position(0);
            channel.write(java.nio.ByteBuffer.wrap(Integer.toString(server.getLocalPort()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Thread listener = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept(); BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                        if ("FOCUS".equals(reader.readLine())) SwingUtilities.invokeLater(() -> {
                            JFrame current = frame;
                            if (current != null) { current.setVisible(true); current.setState(JFrame.NORMAL); current.toFront(); current.requestFocus(); }
                        });
                    } catch (IOException ignored) { if (server.isClosed()) return; }
                }
            }, "reelshelf-updater-focus");
            listener.setDaemon(true);
            listener.start();
        }

        private void close() {
            try { if (server != null) server.close(); } catch (IOException ignored) {}
            try { lock.release(); } catch (IOException ignored) {}
            try { channel.close(); } catch (IOException ignored) {}
        }
    }
}
