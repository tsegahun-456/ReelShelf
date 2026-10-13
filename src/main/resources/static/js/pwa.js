if ('serviceWorker' in navigator) {
  let refreshedForNewWorker = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (refreshedForNewWorker) return;
    refreshedForNewWorker = true;
    window.location.reload();
  });
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // The website remains usable if the browser does not allow PWA features.
    });
  });
}
