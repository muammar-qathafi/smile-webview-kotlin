/**
 * Status: AKTIF
 * Flow: Menyembunyikan panel uji pengingat di web production dan membantu permintaan izin notifikasi sistem.
 * Takeout: jangan untuk rilis production.
 */
/**
 * Production UX: sembunyikan panel "Pengingat Absensi (Testing)" +
 * helper aktifkan notifikasi sistem tanpa menunggu token FCM.
 *
 * Opt-in panel testing: localStorage.smile_reminder_test_panel=1 atau ?smile_test=1
 */
(function () {
  if (window.__smileProductionUx) return;
  window.__smileProductionUx = true;

  function debounce(fn, ms) {
    var t = 0;
    return function () {
      var args = arguments;
      var self = this;
      if (t) clearTimeout(t);
      t = setTimeout(function () {
        t = 0;
        fn.apply(self, args);
      }, ms);
    };
  }

  function allowTestPanel() {
    try {
      if (/(?:\?|&)smile_test=1(?:&|$)/.test(String(location.search || ''))) return true;
      var ls = String(localStorage.getItem('smile_reminder_test_panel') || '').toLowerCase();
      if (ls === '1' || ls === 'true' || ls === 'yes') return true;
    } catch (e) {}
    return false;
  }

  function hideTestingCard() {
    if (allowTestPanel()) return;
    var nodes = document.querySelectorAll('p, h2, h3, span, div, strong');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var text = String(el.textContent || '').replace(/\s+/g, ' ').trim();
      if (text !== 'Pengingat Absensi (Testing)' && text.indexOf('Pengingat Absensi (Testing)') !== 0) {
        continue;
      }
      var card = el;
      for (var d = 0; d < 10 && card; d++) {
        var cls = String(card.className || '');
        if (
          cls.indexOf('rounded') >= 0 &&
          (cls.indexOf('border') >= 0 || cls.indexOf('bg-') >= 0 || cls.indexOf('shadow') >= 0)
        ) {
          card.style.setProperty('display', 'none', 'important');
          card.setAttribute('data-smile-hidden-test-panel', '1');
          break;
        }
        card = card.parentElement;
      }
    }
  }

  hideTestingCard();
  try {
    new MutationObserver(debounce(hideTestingCard, 450)).observe(document.documentElement, {
      childList: true,
      subtree: true,
    });
  } catch (e) {}

  function enablePref() {
    try {
      localStorage.setItem('smile_push_notifications_enabled', '1');
      window.dispatchEvent(
        new CustomEvent('smile-push-notifications-preference-changed', {
          detail: { enabled: true },
        }),
      );
    } catch (e) {}
  }

  window.__smileEnableSystemNotifications = function () {
    return new Promise(function (resolve) {
      var b = window.SmileNative;
      if (!b) {
        resolve({
          ok: false,
          reason: 'no_bridge',
          message: 'Native bridge tidak tersedia.',
        });
        return;
      }

      function finishGranted() {
        enablePref();
        try {
          if (b.onNotificationPermissionGranted) b.onNotificationPermissionGranted();
        } catch (e) {}
        resolve({
          ok: true,
          message: 'Notifikasi sistem aktif.',
        });
      }

      try {
        if (b.getPushPermission && b.getPushPermission() === 'granted') {
          finishGranted();
          return;
        }
      } catch (e) {}

      if (!b.requestPushPermissionAsync) {
        try {
          var sync = b.requestPushPermission ? b.requestPushPermission() : 'denied';
          if (sync === 'granted') finishGranted();
          else {
            resolve({
              ok: false,
              reason: 'permission_denied',
              message: 'Izin notifikasi belum diberikan.',
            });
          }
        } catch (err) {
          resolve({
            ok: false,
            reason: 'error',
            message: err && err.message ? err.message : String(err),
          });
        }
        return;
      }

      var done = false;
      function finish(receive) {
        if (done) return;
        done = true;
        document.removeEventListener('smile-push-permission-result', onResult);
        if (timer) clearTimeout(timer);
        if (receive === 'granted') finishGranted();
        else {
          resolve({
            ok: false,
            reason: 'permission_denied',
            message:
              receive === 'denied'
                ? 'Izin notifikasi ditolak. Aktifkan di Pengaturan → Apps → Smile Multifab → Notifikasi.'
                : 'Izin notifikasi belum diberikan. Izinkan saat popup sistem muncul.',
          });
        }
      }
      function onResult(e) {
        finish((e && e.detail && e.detail.receive) || 'denied');
      }
      document.addEventListener('smile-push-permission-result', onResult);
      var timer = setTimeout(function () {
        try {
          finish(b.getPushPermission ? b.getPushPermission() : 'denied');
        } catch (err) {
          finish('denied');
        }
      }, 120000);
      try {
        b.requestPushPermissionAsync();
      } catch (err) {
        finish('denied');
      }
    });
  };

  // Production web lama: Settings toggle FCM sering gagal sync token → shell paksa ON.
  document.addEventListener(
    'click',
    function (ev) {
      try {
        var path = String(location.pathname || '') + String(location.hash || '');
        if (path.toLowerCase().indexOf('setting') < 0) return;
        var el = ev.target;
        if (!el || !el.closest) return;
        var sw = el.closest('[role="switch"]');
        if (!sw) return;
        var row = sw.closest('div');
        var label = row && row.parentElement ? String(row.parentElement.textContent || '') : '';
        if (label.indexOf('Notifikasi sistem') < 0) return;
        if (sw.getAttribute('aria-checked') === 'true') {
          // User mematikan — hormati
          try {
            localStorage.setItem('smile_push_notifications_enabled', '0');
            window.dispatchEvent(
              new CustomEvent('smile-push-notifications-preference-changed', {
                detail: { enabled: false },
              }),
            );
          } catch (e) {}
          return;
        }
        setTimeout(function () {
          window.__smileEnableSystemNotifications().then(function (r) {
            if (r && r.ok) enablePref();
          });
        }, 80);
      } catch (e) {}
    },
    true,
  );

  // Panel izin native (lokasi + notifikasi) di halaman Settings.
  (function nativePermissionsSettingsPanel() {
    function onSettingsPage() {
      var path = String(location.pathname || '') + String(location.hash || '');
      return path.toLowerCase().indexOf('setting') >= 0;
    }

    function readStatus() {
      return new Promise(function (resolve) {
        var b = window.SmileNative;
        if (!b || !b.getNativePermissionsStatus) {
          resolve(null);
          return;
        }
        try {
          resolve(JSON.parse(b.getNativePermissionsStatus() || '{}'));
        } catch (e) {
          resolve(null);
        }
      });
    }

    function badge(active) {
      return active
        ? '<span style="color:#047857;font-weight:700">Aktif</span>'
        : '<span style="color:#b45309;font-weight:700">Tidak aktif</span>';
    }

    function findMountHost() {
      var mount = document.getElementById('smile-native-permissions-mount');
      if (mount) return mount;
      var host = document.querySelector('main') || document.querySelector('#root') || document.body;
      return host;
    }

    function renderPanel(status) {
      if (!onSettingsPage()) {
        var old = document.getElementById('smile-native-permissions-panel');
        if (old) old.remove();
        return;
      }
      if (document.getElementById('smile-native-permissions-panel')) {
        var locEl = document.getElementById('smile-perm-loc-status');
        var notifEl = document.getElementById('smile-perm-notif-status');
        if (locEl && status) locEl.innerHTML = badge(!!status.locationGranted) + ' — ' + (status.locationStatus || '');
        if (notifEl && status) notifEl.innerHTML = badge(!!status.notificationGranted) + ' — ' + (status.notificationStatus || '');
        return;
      }
      var host = findMountHost();
      if (!host) return;
      var wrap = document.createElement('section');
      wrap.id = 'smile-native-permissions-panel';
      wrap.className = 'rounded-2xl bg-white p-4 shadow-sm dark:bg-[#2a211d] dark:shadow-none';
      wrap.innerHTML =
        '<h2 class="mb-3 text-xs font-semibold uppercase tracking-wide text-orange-500 dark:text-orange-400">Izin perangkat</h2>' +
        '<p class="mb-4 text-xs leading-relaxed text-gray-500 dark:text-gray-400">Lokasi untuk absensi dan notifikasi untuk pengingat atau push Smile.</p>' +
        '<div class="space-y-4">' +
        '<div class="border-b border-gray-100 pb-4 dark:border-white/5">' +
        '<p class="text-sm font-semibold text-gray-900 dark:text-white">Lokasi</p>' +
        '<p id="smile-perm-loc-status" class="mt-1 text-xs text-gray-500 dark:text-gray-400"></p>' +
        '<button type="button" id="smile-perm-loc-btn" class="mt-3 w-full rounded-xl bg-orange-500 px-3 py-2.5 text-sm font-semibold text-white">Izinkan lokasi</button>' +
        '</div>' +
        '<div class="pb-0">' +
        '<p class="text-sm font-semibold text-gray-900 dark:text-white">Notifikasi</p>' +
        '<p id="smile-perm-notif-status" class="mt-1 text-xs text-gray-500 dark:text-gray-400"></p>' +
        '<button type="button" id="smile-perm-notif-btn" class="mt-3 w-full rounded-xl bg-orange-500 px-3 py-2.5 text-sm font-semibold text-white">Izinkan notifikasi</button>' +
        '<button type="button" id="smile-perm-notif-settings" class="mt-2 w-full rounded-xl border border-gray-200 bg-white px-3 py-2.5 text-sm font-semibold text-gray-700 dark:border-white/10 dark:bg-transparent dark:text-gray-200">Buka pengaturan notifikasi</button>' +
        '</div></div>';
      if (host.id === 'smile-native-permissions-mount') {
        host.appendChild(wrap);
      } else {
        host.insertBefore(wrap, host.firstChild);
      }

      wrap.querySelector('#smile-perm-loc-btn').addEventListener('click', function () {
        var b = window.SmileNative;
        if (b && b.requestNativeLocationPermission) b.requestNativeLocationPermission(true);
        setTimeout(refresh, 600);
      });
      wrap.querySelector('#smile-perm-notif-btn').addEventListener('click', function () {
        if (window.__smileEnableSystemNotifications) {
          window.__smileEnableSystemNotifications().then(function () {
            setTimeout(refresh, 600);
          });
        } else if (window.SmileNative && window.SmileNative.requestNativeNotificationPermission) {
          window.SmileNative.requestNativeNotificationPermission();
          setTimeout(refresh, 600);
        }
      });
      wrap.querySelector('#smile-perm-notif-settings').addEventListener('click', function () {
        var b = window.SmileNative;
        if (b && b.openNotificationSettings) b.openNotificationSettings();
        else if (b && b.openNativeAppSettings) b.openNativeAppSettings();
        setTimeout(refresh, 800);
      });

      if (status) {
        var locStatus = wrap.querySelector('#smile-perm-loc-status');
        var notifStatus = wrap.querySelector('#smile-perm-notif-status');
        if (locStatus) {
          locStatus.innerHTML = badge(!!status.locationGranted) + ' — ' + (status.locationStatus || '');
        }
        if (notifStatus) {
          notifStatus.innerHTML = badge(!!status.notificationGranted) + ' — ' + (status.notificationStatus || '');
        }
      }
    }

    function refresh() {
      readStatus().then(function (st) {
        renderPanel(st || {});
      });
    }

    var refreshDebounced = debounce(refresh, 500);
    document.addEventListener('smile-native-permissions-status', refreshDebounced);
    window.addEventListener('hashchange', refreshDebounced);
    window.addEventListener('popstate', refreshDebounced);
    window.addEventListener('smile-shell-route', refreshDebounced);
    refreshDebounced();
    // Jangan MutationObserver di Settings — React + panel native = loop & freeze.
  })();
})();
