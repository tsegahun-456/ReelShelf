(() => {
  const shell = document.getElementById('cinemaPlayer');
  if (!shell) return;

  const video = document.getElementById('mediaVideo');
  const stage = document.getElementById('videoStage');
  const frame = stage.closest('.cinema-frame');
  const playButton = document.getElementById('playToggle');
  const playIcon = document.getElementById('playIcon');
  const seek = document.getElementById('seekBar');
  const volume = document.getElementById('volumeBar');
  const mute = document.getElementById('muteToggle');
  const captions = document.getElementById('subtitleSelect');
  const subtitleSize = document.getElementById('subtitleSizeSelect');
  const speed = document.getElementById('speedSelect');
  const speedBoost = document.getElementById('speedBoost');
  const status = document.getElementById('subtitleStatus');
  const message = document.getElementById('videoMessage');
  const resumePrompt = document.getElementById('resumePrompt');
  const progressKey = `movie-watchlist-playback-${shell.dataset.mediaId}`;
  const speedKey = 'movie-watchlist-playback-speed';
  const subtitleSizeKey = 'movie-watchlist-subtitle-size';
  const nextKey = 'movie-watchlist-auto-next';
  let messageTimer;
  let hideControlsTimer;
  let resumePromptTimer;
  let speedBoostActive = false;
  let speedBeforeBoost = 1;
  let lastSavedAt = 0;
  let generatedTrackUrl;
  let subtitleTracks = [];
  let subtitleSelectionRevision = 0;
  let lastStageFit = '';

  const fitStageToVideo = () => {
    if (!video.videoWidth || !video.videoHeight || document.fullscreenElement === stage) return;
    const aspectRatio = video.videoWidth / video.videoHeight;
    const maxFrameWidth = Math.max(1, shell.clientWidth - 4);
    const compactViewport = window.matchMedia('(max-width: 720px)').matches;
    const maxVideoHeight = Math.max(120, Math.min(window.innerHeight * (compactViewport ? .65 : .76), 870));
    const videoWidth = Math.floor(Math.min(maxFrameWidth, maxVideoHeight * aspectRatio));
    const videoHeight = Math.round(videoWidth / aspectRatio);
    const fitKey = `${maxFrameWidth}:${maxVideoHeight}:${video.videoWidth}:${video.videoHeight}`;
    if (fitKey === lastStageFit) return;
    lastStageFit = fitKey;

    // Match the frame to the source while keeping it within the visible screen area.
    frame.style.width = `${videoWidth + 4}px`;
    frame.style.maxWidth = '100%';
    stage.style.height = `${videoHeight}px`;
    stage.style.aspectRatio = `${video.videoWidth} / ${video.videoHeight}`;
    stage.classList.toggle('is-compact-controls', videoWidth < 700);
  };

  const scheduleStageFit = () => window.requestAnimationFrame(fitStageToVideo);

  const formatTime = (seconds) => {
    if (!Number.isFinite(seconds) || seconds < 0) return '--:--';
    const total = Math.floor(seconds);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const remainder = total % 60;
    return hours > 0
      ? `${hours}:${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`
      : `${minutes}:${String(remainder).padStart(2, '0')}`;
  };

  const showMessage = (text) => {
    message.textContent = text;
    message.hidden = false;
    window.clearTimeout(messageTimer);
    messageTimer = window.setTimeout(() => { message.hidden = true; }, 3800);
  };

  const setPlayingState = (playing) => {
    stage.classList.toggle('is-playing', playing);
    stage.classList.toggle('is-paused', !playing);
    playIcon.textContent = playing ? 'Ⅱ' : '▶';
    playButton.setAttribute('aria-label', playing ? 'Pause' : 'Play');
  };

  const togglePlayback = async () => {
    if (video.paused) {
      try { await video.play(); } catch { showMessage('Playback was blocked. Press Play to start the video.'); }
    } else {
      video.pause();
    }
  };

  const saveProgress = (force = false) => {
    if (!Number.isFinite(video.currentTime) || video.currentTime < 5) return;
    const now = Date.now();
    if (!force && now - lastSavedAt < 5000) return;
    lastSavedAt = now;
    try {
      if (Number.isFinite(video.duration) && video.duration - video.currentTime < 30) {
        localStorage.removeItem(progressKey);
      } else {
        localStorage.setItem(progressKey, String(Math.floor(video.currentTime)));
      }
    } catch { /* Playback still works when browser storage is unavailable. */ }
  };

  const updateTimeline = () => {
    const duration = video.duration;
    document.getElementById('currentTime').textContent = formatTime(video.currentTime);
    document.getElementById('duration').textContent = formatTime(duration);
    if (Number.isFinite(duration) && duration > 0) {
      const played = Math.min(100, (video.currentTime / duration) * 100);
      seek.value = String(Math.round((video.currentTime / duration) * 1000));
      seek.style.setProperty('--played', `${played}%`);
    }
    if (video.buffered.length && Number.isFinite(duration) && duration > 0) {
      const bufferedTo = video.buffered.end(video.buffered.length - 1);
      document.getElementById('bufferProgress').style.width = `${Math.min(100, (bufferedTo / duration) * 100)}%`;
    }
    saveProgress();
  };

  const restoreSpeed = () => {
    try {
      const saved = localStorage.getItem(speedKey);
      if ([...speed.options].some(option => option.value === saved)) speed.value = saved;
    } catch { /* Ignore unavailable local storage. */ }
    video.playbackRate = Number(speed.value);
    speedBeforeBoost = video.playbackRate;
  };

  const syncSpeedBoostButton = () => {
    speedBoost.setAttribute('aria-pressed', String(speedBoostActive));
    speedBoost.setAttribute('aria-label', `${speedBoostActive ? 'Disable' : 'Enable'} 2× playback (X)`);
    speedBoost.title = `${speedBoostActive ? 'Turn off' : 'Turn on'} 2× playback (X)`;
  };

  const toggleSpeedBoost = () => {
    if (speedBoostActive) {
      speedBoostActive = false;
      video.playbackRate = speedBeforeBoost;
      speed.value = String(speedBeforeBoost);
      showMessage(`Playback speed restored · ${speed.options[speed.selectedIndex].text}`);
    } else {
      speedBeforeBoost = video.playbackRate;
      speedBoostActive = true;
      video.playbackRate = 2;
      showMessage('2× playback on · press 2× or X to restore');
    }
    syncSpeedBoostButton();
  };

  const applySubtitleSize = (value, persist = false) => {
    if (!subtitleSize) return;
    const valid = [...subtitleSize.options].some(option => option.value === value);
    const selected = valid ? value : 'standard';
    video.classList.remove('caption-size-small', 'caption-size-standard', 'caption-size-large', 'caption-size-xlarge');
    video.classList.add(`caption-size-${selected}`);
    subtitleSize.value = selected;
    if (persist) {
      try { localStorage.setItem(subtitleSizeKey, selected); } catch { /* The current selection still works without storage. */ }
    }
  };

  const savedPosition = () => {
    try { return Number(localStorage.getItem(progressKey)) || 0; } catch { return 0; }
  };

  const enableTrack = (trackElement) => {
    const show = () => {
      for (const textTrack of video.textTracks) textTrack.mode = textTrack === trackElement.track ? 'showing' : 'disabled';
    };
    trackElement.addEventListener('load', show, { once: true });
    trackElement.addEventListener('error', () => {
      captions.value = 'off';
      status.textContent = 'This subtitle track could not be loaded.';
      status.classList.add('has-error');
    }, { once: true });
    video.appendChild(trackElement);
    window.setTimeout(show, 250);
  };

  const clearPlayerTracks = () => {
    for (const element of [...video.querySelectorAll('track')]) element.remove();
    for (const textTrack of video.textTracks) textTrack.mode = 'disabled';
    if (generatedTrackUrl) {
      URL.revokeObjectURL(generatedTrackUrl);
      generatedTrackUrl = undefined;
    }
  };

  const setCaptionSelection = async (value) => {
    const revision = ++subtitleSelectionRevision;
    clearPlayerTracks();
    status.classList.remove('has-error');
    if (value === 'off') {
      status.textContent = subtitleTracks.length ? 'Subtitles are off. Press C to toggle captions.' : status.textContent;
      return;
    }

    let source;
    let label;
    if (value.startsWith('external:')) {
      source = value.slice('external:'.length);
      label = 'External subtitle';
    } else if (value.startsWith('embedded:')) {
      const streamIndex = value.slice('embedded:'.length);
      source = `/library/${encodeURIComponent(shell.dataset.mediaId)}/subtitle/embedded/${encodeURIComponent(streamIndex)}`;
      label = 'Embedded subtitle';
      status.textContent = 'Extracting the embedded subtitle track…';
    }
    if (!source) return;

    try {
      const response = await fetch(source, { headers: { Accept: 'text/vtt' } });
      if (!response.ok) {
        const reason = (await response.text()).trim();
        throw new Error(reason || 'This subtitle track could not be loaded.');
      }
      const blob = await response.blob();
      if (revision !== subtitleSelectionRevision) return;
      generatedTrackUrl = URL.createObjectURL(blob);
      const track = document.createElement('track');
      track.kind = 'subtitles';
      track.label = label;
      track.srclang = 'und';
      track.src = generatedTrackUrl;
      track.default = true;
      enableTrack(track);
      status.textContent = `${label} enabled. Press C to turn captions off.`;
    } catch (error) {
      if (revision !== subtitleSelectionRevision) return;
      captions.value = 'off';
      status.textContent = error.message || 'Could not load this subtitle track.';
      status.classList.add('has-error');
    }
  };

  const loadSubtitleTracks = async () => {
    try {
      const response = await fetch(shell.dataset.tracksUrl, { headers: { Accept: 'application/json' }, cache: 'no-store' });
      if (!response.ok) throw new Error('Subtitle track information is unavailable.');
      const result = await response.json();
      captions.replaceChildren(new Option('Subtitles off', 'off'));
      if (result.external) captions.add(new Option(result.external.label, `external:${result.external.url}`));
      subtitleTracks = result.embedded || [];
      const extractableTracks = subtitleTracks.filter(track => track.extractable);
      const unsupportedTracks = subtitleTracks.filter(track => !track.extractable);
      for (const track of extractableTracks) {
        const details = [track.title, track.language, track.codec].filter(Boolean).join(' · ');
        captions.add(new Option(`Embedded · ${details || `Track ${track.streamIndex + 1}`}`, `embedded:${track.streamIndex}`));
      }
      for (const track of unsupportedTracks) {
        const details = [track.title, track.language, track.codec].filter(Boolean).join(' · ');
        const option = new Option(`Image subtitles · ${details || `Track ${track.streamIndex + 1}`} (not supported)`, `unsupported:${track.streamIndex}`);
        option.disabled = true;
        captions.add(option);
      }

      let preferred = null;
      try {
        const saved = localStorage.getItem(`movie-watchlist-subtitle-${shell.dataset.mediaId}`);
        if ([...captions.options].some(option => option.value === saved && !option.disabled)) preferred = saved;
      } catch { /* Use the external sidecar when no preference is stored. */ }
      if (preferred === null) preferred = result.external ? `external:${result.external.url}` : 'off';

      if (!result.toolsAvailable) {
        if (preferred !== 'off') await setCaptionSelection(preferred);
        const selectedExternal = preferred.startsWith('external:') ? 'External subtitle enabled. ' : '';
        status.textContent = result.external
          ? `${selectedExternal}Embedded subtitle detection requires FFmpeg in the app runtime.`
          : 'Embedded subtitle detection requires FFmpeg (ffprobe and ffmpeg) in the app runtime.';
        return;
      }
      if (subtitleTracks.length) {
        status.textContent = `${subtitleTracks.length} embedded subtitle ${subtitleTracks.length === 1 ? 'track' : 'tracks'} found${unsupportedTracks.length ? ` · ${unsupportedTracks.length} image-based` : ''}. Choose captions above.`;
      } else if (result.external) {
        status.textContent = `${result.external.label} found. No embedded subtitle tracks were found.`;
      } else {
        status.textContent = 'No external or embedded subtitle tracks were found in this video.';
      }

      captions.value = preferred;
      if (preferred !== 'off') await setCaptionSelection(preferred);
    } catch (error) {
      status.textContent = error.message || 'Could not inspect subtitle tracks.';
      status.classList.add('has-error');
    }
  };

  const seekBy = (seconds) => {
    if (Number.isFinite(video.duration)) video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + seconds));
  };

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await stage.requestFullscreen();
    } catch { showMessage('Fullscreen is not available in this browser.'); }
  };

  const toggleCaptions = () => {
    const enabled = [...captions.options].find(option => option.value !== 'off' && !option.disabled);
    if (!enabled) {
      showMessage('No browser-compatible subtitle tracks were found.');
      return;
    }
    const selection = captions.value === 'off' ? enabled.value : 'off';
    captions.value = selection;
    try { localStorage.setItem(`movie-watchlist-subtitle-${shell.dataset.mediaId}`, selection); } catch { /* Optional preference. */ }
    setCaptionSelection(selection);
  };

  const revealControls = () => {
    stage.classList.remove('controls-hidden');
    window.clearTimeout(hideControlsTimer);
    if (!video.paused) hideControlsTimer = window.setTimeout(() => stage.classList.add('controls-hidden'), 2700);
  };

  playButton.addEventListener('click', togglePlayback);
  document.getElementById('centerPlay').addEventListener('click', togglePlayback);
  video.addEventListener('click', togglePlayback);
  document.getElementById('backTen').addEventListener('click', () => seekBy(-10));
  document.getElementById('forwardTen').addEventListener('click', () => seekBy(10));
  speedBoost.addEventListener('click', toggleSpeedBoost);
  seek.addEventListener('input', () => {
    if (Number.isFinite(video.duration)) video.currentTime = Number(seek.value) / 1000 * video.duration;
  });
  volume.addEventListener('input', () => {
    video.volume = Number(volume.value);
    video.muted = video.volume === 0;
  });
  mute.addEventListener('click', () => { video.muted = !video.muted; });
  video.addEventListener('volumechange', () => {
    volume.value = video.muted ? '0' : String(video.volume);
    mute.textContent = video.muted || video.volume === 0 ? '◖×' : video.volume < .45 ? '◖)' : '◖))';
    mute.setAttribute('aria-label', video.muted ? 'Unmute' : 'Mute');
  });
  captions.addEventListener('change', () => {
    if (captions.value.startsWith('unsupported:')) return;
    try { localStorage.setItem(`movie-watchlist-subtitle-${shell.dataset.mediaId}`, captions.value); } catch { /* Optional preference. */ }
    setCaptionSelection(captions.value);
  });
  speed.addEventListener('change', () => {
    speedBoostActive = false;
    speedBeforeBoost = Number(speed.value);
    video.playbackRate = speedBeforeBoost;
    syncSpeedBoostButton();
    try { localStorage.setItem(speedKey, speed.value); } catch { /* Optional preference. */ }
    showMessage(`Playback speed · ${speed.options[speed.selectedIndex].text}`);
  });
  if (subtitleSize) {
    let savedSubtitleSize = null;
    try { savedSubtitleSize = localStorage.getItem(subtitleSizeKey); } catch { /* Keep the default size. */ }
    applySubtitleSize(savedSubtitleSize || subtitleSize.value);
    subtitleSize.addEventListener('change', () => applySubtitleSize(subtitleSize.value, true));
  }
  document.getElementById('fullscreenToggle').addEventListener('click', toggleFullscreen);
  document.getElementById('theaterToggle').addEventListener('click', () => {
    shell.classList.toggle('theater-mode');
    document.getElementById('theaterToggle').setAttribute('aria-pressed', String(shell.classList.contains('theater-mode')));
  });
  document.getElementById('pipToggle').addEventListener('click', async () => {
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else if (document.pictureInPictureEnabled) await video.requestPictureInPicture();
      else showMessage('Picture in picture is not supported in this browser.');
    } catch { showMessage('Picture in picture could not be opened.'); }
  });

  window.addEventListener('resize', scheduleStageFit);
  if ('ResizeObserver' in window) new ResizeObserver(scheduleStageFit).observe(shell);
  document.addEventListener('fullscreenchange', () => {
    if (document.fullscreenElement !== stage) {
      lastStageFit = '';
      scheduleStageFit();
    }
  });

  video.addEventListener('loadedmetadata', () => {
    lastStageFit = '';
    fitStageToVideo();
    updateTimeline();
    const resumeAt = savedPosition();
    if (resumeAt > 5 && (!Number.isFinite(video.duration) || video.duration - resumeAt > 30)) {
      document.getElementById('resumePromptText').textContent = `Resume from ${formatTime(resumeAt)}?`;
      resumePrompt.hidden = false;
      video.pause();
      window.clearTimeout(resumePromptTimer);
      resumePromptTimer = window.setTimeout(() => {
        resumePrompt.hidden = true;
        resumePromptTimer = undefined;
        video.currentTime = resumeAt;
        video.play().catch(() => { /* The play control remains available if autoplay is blocked. */ });
      }, 5000);
    }
  });
  document.getElementById('resumeButton').addEventListener('click', () => {
    window.clearTimeout(resumePromptTimer);
    resumePromptTimer = undefined;
    video.currentTime = savedPosition();
    resumePrompt.hidden = true;
    video.play().catch(() => { /* The play control remains available if autoplay is blocked. */ });
  });
  document.getElementById('startOverButton').addEventListener('click', () => {
    window.clearTimeout(resumePromptTimer);
    resumePromptTimer = undefined;
    try { localStorage.removeItem(progressKey); } catch { /* Optional storage. */ }
    video.currentTime = 0;
    resumePrompt.hidden = true;
    video.play().catch(() => { /* The play control remains available if autoplay is blocked. */ });
  });
  video.addEventListener('durationchange', updateTimeline);
  video.addEventListener('timeupdate', updateTimeline);
  video.addEventListener('progress', updateTimeline);
  video.addEventListener('playing', () => {
    setPlayingState(true);
    stage.classList.remove('is-loading');
    revealControls();
  });
  video.addEventListener('pause', () => {
    setPlayingState(false);
    stage.classList.remove('controls-hidden');
    saveProgress(true);
  });
  video.addEventListener('waiting', () => stage.classList.add('is-loading'));
  video.addEventListener('canplay', () => stage.classList.remove('is-loading'));
  video.addEventListener('ended', () => {
    try { localStorage.removeItem(progressKey); } catch { /* Optional storage. */ }
    const autoNext = document.getElementById('autoNext');
    if (shell.dataset.nextUrl && autoNext?.checked) window.location.assign(shell.dataset.nextUrl);
  });
  video.addEventListener('error', () => {
    stage.classList.remove('is-loading');
    document.getElementById('codecNote').hidden = false;
    showMessage('This browser could not decode the video. The local file is safe; try another browser or a browser-compatible format.');
  });
  stage.addEventListener('pointermove', revealControls);
  stage.addEventListener('pointerleave', () => {
    if (!video.paused) hideControlsTimer = window.setTimeout(() => stage.classList.add('controls-hidden'), 900);
  });
  stage.addEventListener('dblclick', (event) => {
    if (event.target.closest('.player-controls')) return;
    toggleFullscreen();
  });
  document.addEventListener('keydown', (event) => {
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
    if (['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(document.activeElement?.tagName)) return;
    if (event.code === 'Space' || event.code === 'KeyK') { event.preventDefault(); togglePlayback(); }
    else if (event.code === 'ArrowLeft') { event.preventDefault(); seekBy(event.shiftKey ? -5 : -10); }
    else if (event.code === 'ArrowRight') { event.preventDefault(); seekBy(event.shiftKey ? 5 : 10); }
    else if (event.code === 'ArrowUp') { event.preventDefault(); video.volume = Math.min(1, video.volume + .05); video.muted = false; }
    else if (event.code === 'ArrowDown') { event.preventDefault(); video.volume = Math.max(0, video.volume - .05); }
    else if (event.code === 'KeyM') { video.muted = !video.muted; }
    else if (event.code === 'KeyF') { toggleFullscreen(); }
    else if (event.code === 'KeyC') { toggleCaptions(); }
    else if (event.code === 'KeyX') { event.preventDefault(); toggleSpeedBoost(); }
    revealControls();
  });
  window.addEventListener('pagehide', () => saveProgress(true));
  document.addEventListener('visibilitychange', () => { if (document.hidden) saveProgress(true); });

  const autoNext = document.getElementById('autoNext');
  if (autoNext) {
    try { autoNext.checked = localStorage.getItem(nextKey) === 'true'; } catch { /* Optional setting. */ }
    autoNext.addEventListener('change', () => {
      try { localStorage.setItem(nextKey, String(autoNext.checked)); } catch { /* Optional setting. */ }
    });
  }
  restoreSpeed();
  syncSpeedBoostButton();
  if (video.readyState >= HTMLMediaElement.HAVE_METADATA) fitStageToVideo();
  setPlayingState(false);
  loadSubtitleTracks();
  revealControls();
  video.play().catch(() => { /* User starts playback with the visible control. */ });
})();
