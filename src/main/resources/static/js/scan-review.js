(() => {
  const panel = document.getElementById('scanStatus');
  if (!panel) return;
  const statusUrl = panel.dataset.statusUrl;
  let wasRunning = panel.querySelector('.scan-running-badge')?.classList.contains('is-running') || false;
  const setText = (id, value) => { const node = document.getElementById(id); if (node) node.textContent = value; };
  const update = async () => {
    try {
      const response = await fetch(statusUrl, { headers: { Accept: 'application/json' }, cache: 'no-store' });
      if (!response.ok) return;
      const scan = await response.json();
      if (wasRunning && !scan.running) {
        window.location.reload();
        return;
      }
      wasRunning ||= scan.running;
      const heading = panel.querySelector('.scan-status-heading h2');
      if (heading) heading.textContent = scan.running ? 'Scanning your folders…' : 'Scan status';
      const badge = panel.querySelector('.scan-running-badge');
      if (badge) { badge.textContent = scan.running ? 'Running' : 'Idle'; badge.classList.toggle('is-running', scan.running); }
      const bar = document.getElementById('scanProgressBar');
      if (bar) bar.style.width = `${scan.progressPercent}%`;
      setText('scanProgressCopy', `${scan.rootsCompleted} of ${scan.rootsTotal} folders complete · ${scan.pathsVisited} paths checked`);
      setText('scanVideosFound', scan.videosFound);
      setText('scanAdded', scan.added);
      setText('scanUpdated', scan.updated);
      setText('scanRemoved', scan.removed);
      setText('scanIssueCount', scan.issueCount);
      if (!scan.running) window.clearInterval(timer);
    } catch { /* Local scan review remains usable during a brief page/network hiccup. */ }
  };
  const timer = window.setInterval(update, 1800);
  update();
})();
