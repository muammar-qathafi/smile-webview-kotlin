/**
 * Status: PENDUKUNG
 * Flow: Melaporkan ke native apakah konten SPA sudah di paling atas, agar pull-to-refresh tidak aktif saat user sedang scroll.
 * Takeout: jangan selama pull-to-refresh dipakai.
 */
/**
 * Lacak apakah konten di paling atas → SmileNative.setContentAtTop
 * agar SwipeRefreshLayout native hanya aktif saat mentok atas.
 */
(function () {
  if (window.__SMILE_PTR_SCROLL__) return;
  window.__SMILE_PTR_SCROLL__ = true;

  function isNearTop(el, threshold) {
    try {
      return !el || (el.scrollTop || 0) <= (threshold || 2);
    } catch (e) {
      return true;
    }
  }

  function contentAtTop() {
    try {
      var winTop = (window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0) <= 2;
      if (!winTop) return false;

      var selectors = [
        '[data-ptr-root]',
        'main',
        '.overflow-y-auto',
        '.overflow-auto',
        '.overflow-y-scroll',
        '[class*="overflow-y-auto"]',
        '#root',
        '#app',
      ];
      for (var i = 0; i < selectors.length; i++) {
        var nodes = document.querySelectorAll(selectors[i]);
        for (var j = 0; j < nodes.length; j++) {
          var el = nodes[j];
          if (!el || el.scrollHeight <= el.clientHeight + 4) continue;
          if (!isNearTop(el, 2)) return false;
        }
      }
      return true;
    } catch (e) {
      return true;
    }
  }

  function notify() {
    try {
      var atTop = contentAtTop();
      if (window.SmileNative && typeof window.SmileNative.setContentAtTop === 'function') {
        window.SmileNative.setContentAtTop(!!atTop);
      }
    } catch (e) {}
  }

  document.addEventListener('scroll', notify, true);
  window.addEventListener('scroll', notify, true);
  document.addEventListener('touchend', function () { setTimeout(notify, 50); }, true);
  notify();
})();
