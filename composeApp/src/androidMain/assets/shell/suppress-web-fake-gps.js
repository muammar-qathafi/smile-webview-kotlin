/**
 * Status: AKTIF
 * Flow: Menghapus popup Fake GPS HTML di dalam WebView. Overlay yang boleh tampil hanya FakeGpsBlockScreen native.
 * Takeout: jangan selama deteksi Fake GPS native yang jadi sumber kebenaran.
 */
/**
 * APK Kotlin: tekan habis popup Fake GPS HTML di dalam WebView.
 * Overlay yang boleh tampil hanya FakeGpsBlockScreen native (Dialog Compose).
 */
(function () {
  window.__SMILE_WEBVIEW__ = true;
  window.__SMILE_DISABLE_WEB_FAKE_GPS_OVERLAY__ = true;

  function clearBlockKeys() {
    try { sessionStorage.removeItem('smile_fake_gps_block_v1'); } catch (e) {}
    try { localStorage.removeItem('smile_fake_gps_block_v1'); } catch (e2) {}
  }
  clearBlockKeys();

  function stubMockFns(host) {
    if (!host || typeof host !== 'object') return;
    try { host.isMockLocation = function () { return false; }; } catch (e) {}
    try { host.isFakeGps = function () { return false; }; } catch (e2) {}
  }
  stubMockFns(window.SmileNative);
  stubMockFns(window.Android);
  stubMockFns(window.AndroidInterface);
  window.__SMILE_MOCK_LOCATION__ = false;
  window.__SMILE_IS_MOCK_GPS__ = false;

  try {
    var proto = Storage.prototype;
    if (proto && !proto.__smileFakeGpsSetItemPatched) {
      var origSet = proto.setItem;
      proto.setItem = function (key, value) {
        if (String(key) === 'smile_fake_gps_block_v1') return;
        return origSet.apply(this, arguments);
      };
      proto.__smileFakeGpsSetItemPatched = true;
    }
  } catch (e) {}

  function textOf(el) {
    return String((el && el.textContent) || '').replace(/\s+/g, ' ');
  }

  function isFakeGpsCopy(text) {
    var t = String(text || '').toLowerCase();
    if (!t) return false;
    return (
      t.indexOf('fake gps') >= 0 ||
      t.indexOf('fakegps') >= 0 ||
      t.indexOf('mock location') >= 0 ||
      t.indexOf('mock gps') >= 0 ||
      t.indexOf('lokasi palsu') >= 0
    );
  }

  function isOverlayRoot(el) {
    if (!el || el.nodeType !== 1) return false;
    var cls = String(el.className || '');
    var pos = '';
    var z = '';
    try {
      var cs = window.getComputedStyle(el);
      pos = String(cs.position || '').toLowerCase();
      z = String(cs.zIndex || '');
    } catch (e) {}
    var zNum = parseInt(z, 10);
    return (
      pos === 'fixed' ||
      pos === 'sticky' ||
      cls.indexOf('fixed') >= 0 ||
      cls.indexOf('inset-0') >= 0 ||
      cls.indexOf('z-[') >= 0 ||
      (Number.isFinite(zNum) && zNum >= 40)
    );
  }

  function hideEl(el) {
    if (!el || el.nodeType !== 1) return;
    if (el.getAttribute('data-smile-hidden-web-fake-gps') === '1') return;
    el.setAttribute('data-smile-hidden-web-fake-gps', '1');
    try {
      el.style.setProperty('display', 'none', 'important');
      el.style.setProperty('visibility', 'hidden', 'important');
      el.style.setProperty('opacity', '0', 'important');
      el.style.setProperty('pointer-events', 'none', 'important');
      el.setAttribute('aria-hidden', 'true');
    } catch (e) {}
    try {
      if (el.parentNode) el.parentNode.removeChild(el);
    } catch (e2) {}
  }

  function hideOverlayFrom(el) {
    var cur = el;
    var best = null;
    while (cur && cur !== document.body && cur !== document.documentElement) {
      if (isOverlayRoot(cur) && isFakeGpsCopy(textOf(cur))) best = cur;
      cur = cur.parentElement;
    }
    hideEl(best || el);
  }

  function scan(root) {
    var base = root || document;
    if (!base) return;
    if ((base === document || base === document.documentElement) &&
        document.body && !isFakeGpsCopy(textOf(document.body))) {
      return;
    }
    if (base.nodeType === 1 && isFakeGpsCopy(textOf(base)) && isOverlayRoot(base)) {
      hideOverlayFrom(base);
    }
    if (!base.querySelectorAll) return;
    var nodes = base.querySelectorAll('div,section,aside,dialog,h1,h2,h3,p,button');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!isFakeGpsCopy(textOf(el))) continue;
      hideOverlayFrom(el);
    }
  }

  window.__smileScanWebFakeGps = scan;

  function bootObserver() {
    scan(document);
    if (window.__smileFakeGpsObserverReady) return;
    window.__smileFakeGpsObserverReady = true;
    try {
      new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var added = muts[i].addedNodes;
          for (var j = 0; j < added.length; j++) {
            var n = added[j];
            if (n && n.nodeType === 1) scan(n);
          }
        }
      }).observe(document.documentElement || document.body, {
        childList: true,
        subtree: true,
      });
    } catch (e) {}
    // Tanpa setInterval scan penuh — hemat CPU di HP lemot.
    // Node baru ditangani MutationObserver; ganti route lewat smile-shell-route.
  }

  if (document.documentElement || document.body) bootObserver();
  else document.addEventListener('DOMContentLoaded', bootObserver);

  document.addEventListener('smile-shell-route', function () {
    clearBlockKeys();
    stubMockFns(window.SmileNative);
    stubMockFns(window.Android);
    stubMockFns(window.AndroidInterface);
    scan(document);
  });
})();
