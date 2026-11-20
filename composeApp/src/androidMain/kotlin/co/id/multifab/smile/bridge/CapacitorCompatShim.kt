/**
 * Status: COMPAT
 * Flow: Shim agar web production (masih API Capacitor) jalan di shell Kotlin tanpa ubah repo web. Mengarah ke CapacitorBridge dan event native.
 * Takeout: jangan selama web masih memanggil Capacitor.Plugins.
 */
package co.id.multifab.smile.bridge

import co.id.multifab.smile.config.AppConfig

/**
 * Script injeksi untuk kompatibilitas web production tanpa ubah mfg-datacore-smile.
 */
object CapacitorCompatShim {

  val documentStartScript: String = """
    (function() {
      if (window.__smileCapacitorBridge) return;
      window.__smileCapacitorBridge = true;
      window.__SMILE_WEBVIEW__ = true;
      window.__SMILE_DISABLE_WEB_FAKE_GPS_OVERLAY__ = true;

      function smileIsSignInPage() {
        try {
          var p = (location.pathname + location.hash).toLowerCase();
          return p.indexOf('signin') >= 0 || p.indexOf('/login') >= 0 || p.indexOf('sign-in') >= 0;
        } catch (e) { return false; }
      }
      window.__smileIsSignInPage = smileIsSignInPage;

      function smileInPermissionGrace() {
        try {
          return typeof window.__smilePermissionGraceUntil === 'number' &&
            Date.now() < window.__smilePermissionGraceUntil;
        } catch (e) { return false; }
      }
      window.__smileInPermissionGrace = smileInPermissionGrace;

      // Native owns bottom safe-area (WebView bottomMargin). Neutralize CSS early
      // sebelum first paint agar Honor/Advan tidak flash double-pad.
      try {
        document.documentElement.classList.add('smile-native-safe-bottom');
        var early = document.createElement('style');
        early.id = 'smile-native-safe-bottom-early';
        early.textContent =
          'html.smile-native-safe-bottom .smile-bottom-nav{padding-bottom:0.25rem!important}' +
          'html.smile-native-safe-bottom .pb-safe,' +
          'html.smile-native-safe-bottom .safe-area-bottom,' +
          'html.smile-native-safe-bottom .safe-bottom{padding-bottom:0!important}' +
          'html.keyboard-open{--shell-bottom-chrome:0px!important}' +
          'html.keyboard-open .smile-bottom-nav{' +
          'transform:translateY(120%)!important;opacity:0!important;' +
          'pointer-events:none!important;visibility:hidden!important}';
        (document.head || document.documentElement).appendChild(early);
      } catch (e) {}

      function bridgeCall(plugin, method, options) {
        return new Promise(function(resolve, reject) {
          function invoke(bridge) {
            try {
              var opts = options || {};
              // Date → atMs agar native Kotlin parse andal.
              if (plugin === 'LocalNotifications' && method === 'schedule' && opts.notifications) {
                opts = JSON.parse(JSON.stringify(opts, function(_k, v) {
                  if (Object.prototype.toString.call(v) === '[object Date]') return v.toISOString();
                  return v;
                }));
                opts.notifications = (opts.notifications || []).map(function(n) {
                  n = Object.assign({}, n);
                  if (n.schedule && n.schedule.at != null) {
                    var ms = typeof n.schedule.at === 'number' ? n.schedule.at : Date.parse(n.schedule.at);
                    if (!isNaN(ms)) {
                      n.schedule = Object.assign({}, n.schedule, {
                        atMs: ms,
                        at: new Date(ms).toISOString()
                      });
                    }
                  }
                  return n;
                });
              }
              var raw = bridge.capacitorInvoke(plugin, method, JSON.stringify(opts || {}));
              var parsed = JSON.parse(raw || '{}');
              if (parsed.error) reject(new Error(parsed.error));
              else resolve(parsed.data);
            } catch (e) { reject(e); }
          }
          var bridge = window.${AppConfig.JS_BRIDGE_NAME};
          if (bridge && bridge.capacitorInvoke) {
            invoke(bridge);
            return;
          }
          var tries = 0;
          var timer = setInterval(function() {
            tries++;
            bridge = window.${AppConfig.JS_BRIDGE_NAME};
            if (bridge && bridge.capacitorInvoke) {
              clearInterval(timer);
              invoke(bridge);
            } else if (tries > 80) {
              clearInterval(timer);
              reject(new Error('SmileNative unavailable'));
            }
          }, 50);
        });
      }

      // Capacitor core mendeteksi Android via window.androidBridge + PluginHeaders.
      // Tanpa ini, LocalNotifications.schedule() → web → "not supported in this browser".
      (function installCapacitorAndroidFacade() {
        if (window.__smileCapAndroidFacade) return;
        window.__smileCapAndroidFacade = true;

        window.androidBridge = window.androidBridge || {
          postMessage: function() { /* detection only — nativePromise di bawah */ }
        };

        function hdr(name, methods) {
          return {
            name: name,
            methods: methods.map(function(m) {
              if (typeof m === 'string') return { name: m, rtype: 'promise' };
              return m;
            })
          };
        }

        window.Capacitor = window.Capacitor || {};
        window.Capacitor.PluginHeaders = [
          hdr('LocalNotifications', [
            'schedule', 'cancel', 'getPending', 'createChannel',
            'checkPermissions', 'requestPermissions',
            'checkExactNotificationSetting', 'changeExactNotificationSetting',
            { name: 'addListener', rtype: 'callback' },
            { name: 'removeListener', rtype: 'callback' }
          ]),
          hdr('Preferences', ['get', 'set', 'remove']),
          hdr('Filesystem', ['writeFile', 'readFile', 'stat', 'mkdir']),
          hdr('PushNotifications', [
            'register', 'checkPermissions', 'requestPermissions', 'openSettings',
            { name: 'addListener', rtype: 'callback' },
            { name: 'removeListener', rtype: 'callback' }
          ]),
          hdr('App', [
            { name: 'addListener', rtype: 'callback' },
            { name: 'removeListener', rtype: 'callback' }
          ])
        ];

        window.Capacitor.nativePromise = function(plugin, method, options) {
          // PushPermissions tetap lewat path async dialog.
          if ((plugin === 'PushNotifications' || plugin === 'LocalNotifications') &&
              (method === 'requestPermissions' || method === 'checkPermissions')) {
            var b = window.${AppConfig.JS_BRIDGE_NAME};
            if (method === 'checkPermissions') {
              var cur = 'prompt';
              try { if (b && b.getPushPermission) cur = b.getPushPermission(); } catch (e) {}
              return Promise.resolve(plugin === 'LocalNotifications'
                ? { display: cur === 'granted' ? 'granted' : (cur === 'denied' ? 'denied' : 'prompt') }
                : { receive: cur });
            }
            if (window.Capacitor.Plugins && window.Capacitor.Plugins.PushNotifications &&
                window.Capacitor.Plugins.PushNotifications.requestPermissions) {
              return window.Capacitor.Plugins.PushNotifications.requestPermissions().then(function(r) {
                var receive = (r && r.receive) || 'denied';
                return plugin === 'LocalNotifications'
                  ? { display: receive === 'granted' ? 'granted' : (receive === 'denied' ? 'denied' : 'prompt') }
                  : { receive: receive };
              });
            }
          }
          return bridgeCall(plugin, method, options);
        };

        window.Capacitor.nativeCallback = function(plugin, method, options, callback) {
          if (method === 'addListener') {
            var eventName = (options && options.eventName) || '';
            if (plugin === 'LocalNotifications') {
              document.addEventListener('smile-local-notification', function(e) {
                if (typeof callback === 'function' && e.detail && e.detail.notification) {
                  callback(e.detail.notification);
                }
              });
              return 'smile-ln-listener';
            }
            if (plugin === 'App') {
              document.addEventListener('smile-app-state', function(e) {
                if (typeof callback === 'function') callback(e.detail || { isActive: true });
              });
              return 'smile-app-listener';
            }
            if (plugin === 'PushNotifications') {
              if (eventName === 'registration') {
                document.addEventListener('smile-fcm-token', function(e) {
                  if (typeof callback === 'function') callback({ value: e.detail || '' });
                });
              } else {
                document.addEventListener('smile-native-push-received', function(e) {
                  if (typeof callback === 'function') callback(e.detail || {});
                });
              }
              return 'smile-push-listener';
            }
          }
          window.Capacitor.nativePromise(plugin, method, options).then(
            function(d) { if (typeof callback === 'function') callback(d); },
            function() {}
          );
          return 'smile-cb';
        };
      })();

      function pluginProxy(name) {
        return {
          checkPermissions: function(o) { return bridgeCall(name, 'checkPermissions', o); },
          requestPermissions: function(o) {
            // Samakan path izin notifikasi dengan PushNotifications (async + dialog sistem).
            if (name === 'LocalNotifications' && window.Capacitor.Plugins.PushNotifications) {
              return window.Capacitor.Plugins.PushNotifications.requestPermissions().then(function(r) {
                var receive = (r && r.receive) || 'denied';
                var display = receive === 'granted' ? 'granted'
                  : (receive === 'denied' ? 'denied' : 'prompt');
                return { display: display };
              });
            }
            return bridgeCall(name, 'requestPermissions', o);
          },
          createChannel: function(o) { return bridgeCall(name, 'createChannel', o); },
          schedule: function(o) { return bridgeCall(name, 'schedule', o); },
          cancel: function(o) { return bridgeCall(name, 'cancel', o); },
          getPending: function(o) { return bridgeCall(name, 'getPending', o); },
          checkExactNotificationSetting: function(o) { return bridgeCall(name, 'checkExactNotificationSetting', o); },
          changeExactNotificationSetting: function(o) { return bridgeCall(name, 'changeExactNotificationSetting', o); },
          get: function(o) { return bridgeCall(name, 'get', o); },
          set: function(o) { return bridgeCall(name, 'set', o); },
          remove: function(o) { return bridgeCall(name, 'remove', o); },
          writeFile: function(o) { return bridgeCall(name, 'writeFile', o); },
          readFile: function(o) { return bridgeCall(name, 'readFile', o); },
          stat: function(o) { return bridgeCall(name, 'stat', o); },
          mkdir: function(o) { return bridgeCall(name, 'mkdir', o); },
          addListener: function(event, handler) {
            if (name === 'LocalNotifications' && event === 'localNotificationReceived') {
              document.addEventListener('smile-local-notification', function(e) {
                if (e.detail && e.detail.event === event) handler(e.detail.notification);
              });
            }
            return Promise.resolve({ remove: function() {} });
          }
        };
      }

      window.Capacitor = window.Capacitor || {};
      window.Capacitor.isNativePlatform = function() { return true; };
      window.Capacitor.getPlatform = function() { return 'android'; };
      window.Capacitor.Plugins = window.Capacitor.Plugins || {};

      function installPushNotificationsPlugin() {
        function getBridge() { return window.${AppConfig.JS_BRIDGE_NAME}; }
        window.Capacitor.Plugins.PushNotifications = {
          checkPermissions: function() {
            var b = getBridge();
            var receive = 'prompt';
            try { if (b && b.getPushPermission) receive = b.getPushPermission(); } catch (e) {}
            return Promise.resolve({ receive: receive });
          },
          requestPermissions: function() {
            var b = getBridge();
            if (b && b.requestPushPermissionAsync) {
              return new Promise(function(resolve) {
                if (smileIsSignInPage() || smileInPermissionGrace()) {
                  resolve({ receive: 'prompt' });
                  return;
                }
                var done = false;
                var timer = 0;
                function finish(receive) {
                  if (done) return;
                  done = true;
                  if (timer) clearTimeout(timer);
                  document.removeEventListener('smile-push-permission-result', onResult);
                  if (receive === 'granted') {
                    try {
                      localStorage.setItem('smile_push_notifications_enabled', '1');
                      window.dispatchEvent(new CustomEvent('smile-push-notifications-preference-changed', {
                        detail: { enabled: true }
                      }));
                      if (b.onNotificationPermissionGranted) b.onNotificationPermissionGranted();
                    } catch (e) {}
                  }
                  resolve({ receive: receive || 'denied' });
                }
                function onResult(e) {
                  var d = (e && e.detail) || {};
                  finish(d.receive || 'denied');
                }
                document.addEventListener('smile-push-permission-result', onResult);
                timer = setTimeout(function() {
                  try { finish(b.getPushPermission()); } catch (err) { finish('denied'); }
                }, 120000);
                try {
                  // Sudah granted → enable toggle tanpa panggil FCM register.
                  var cur = b.getPushPermission ? b.getPushPermission() : 'prompt';
                  if (cur === 'granted') {
                    finish('granted');
                    return;
                  }
                  b.requestPushPermissionAsync();
                } catch (err) {
                  try { finish(b.requestPushPermission()); } catch (e2) { finish('denied'); }
                }
              });
            }
            try {
              return Promise.resolve({ receive: b.requestPushPermission() });
            } catch (e) {
              return Promise.resolve({ receive: 'denied' });
            }
          },
          register: function() {
            // No-op aman: jangan sync getFcmToken / Firebase dari path register.
            try {
              var b = getBridge();
              if (b && b.registerPush) b.registerPush();
            } catch (e) {}
            return Promise.resolve();
          },
          openSettings: function() {
            // No-op: web production memanggil ini otomatis saat deny.
            // startActivity(Settings) dari Application di Honor sering melempar ke launcher.
            // Buka Settings hanya lewat SmileNative.openNotificationSettings (aksi eksplisit).
            return Promise.resolve({ opened: false });
          },
          addListener: function(event, handler) {
            if (event === 'registration') {
              try {
                var b = getBridge();
                // Hanya baca cache — jangan trigger Firebase.
                var token = b && b.getFcmToken ? b.getFcmToken() : '';
                if (token) handler({ value: token });
              } catch (e) {}
              document.addEventListener('smile-fcm-token', function(e) {
                handler({ value: e.detail || '' });
              });
            }
            if (event === 'pushNotificationReceived' || event === 'pushNotificationActionPerformed') {
              document.addEventListener('smile-native-push-received', function(e) {
                var d = e.detail || {};
                handler({ title: d.title || '', body: d.body || '', data: d.data || {} });
              });
            }
            return Promise.resolve({ remove: function() {} });
          }
        };
      }

      installPushNotificationsPlugin();

      // Production web sering men-set toggle OFF jika FCM token gagal.
      // Shell memaksa ON selama ~3s setelah izin OS granted.
      (function reinforcePushPref() {
        if (window.__smilePushPrefHook) return;
        window.__smilePushPrefHook = true;
        var KEY = 'smile_push_notifications_enabled';
        function enablePref() {
          try {
            localStorage.setItem(KEY, '1');
            window.dispatchEvent(new CustomEvent('smile-push-notifications-preference-changed', {
              detail: { enabled: true }
            }));
          } catch (e) {}
        }
        function reinforce() {
          var n = 0;
          var id = setInterval(function() {
            enablePref();
            if (++n >= 12) clearInterval(id);
          }, 250);
        }
        document.addEventListener('smile-push-permission-result', function(e) {
          if (e && e.detail && e.detail.receive === 'granted') {
            enablePref();
            reinforce();
          }
        });
      })();

      window.Capacitor.registerPlugin = function(name) {
        if (name === 'PushNotifications') {
          installPushNotificationsPlugin();
          return window.Capacitor.Plugins.PushNotifications;
        }
        if (!window.Capacitor.Plugins[name]) {
          window.Capacitor.Plugins[name] = pluginProxy(name);
        }
        return window.Capacitor.Plugins[name];
      };
      ['LocalNotifications','Preferences','Filesystem'].forEach(function(p) {
        window.Capacitor.Plugins[p] = pluginProxy(p);
      });
      window.Capacitor.Plugins.App = {
        addListener: function(event, handler) {
          if (event === 'appStateChange') {
            document.addEventListener('smile-app-state', function(e) {
              handler(e.detail || { isActive: true });
            });
          }
          return Promise.resolve({ remove: function() {} });
        }
      };
      window.Capacitor.toNative = function(plugin, method, options, callbacks) {
        try {
          var bridge = window.${AppConfig.JS_BRIDGE_NAME};
          if (plugin === 'PushNotifications' || plugin === 'LocalNotifications') {
            if (method === 'requestPermissions' || method === 'checkPermissions') {
              if (method === 'checkPermissions') {
                var cur = bridge && bridge.getPushPermission ? bridge.getPushPermission() : 'prompt';
                callbacks.resolve(plugin === 'LocalNotifications'
                  ? { display: cur === 'granted' ? 'granted' : (cur === 'denied' ? 'denied' : 'prompt') }
                  : { receive: cur });
                return;
              }
              if (bridge && bridge.requestPushPermissionAsync) {
                var done = false;
                function finish(receive) {
                  if (done) return;
                  done = true;
                  document.removeEventListener('smile-push-permission-result', onResult);
                  var mapped = receive || 'denied';
                  callbacks.resolve(plugin === 'LocalNotifications'
                    ? { display: mapped === 'granted' ? 'granted' : (mapped === 'denied' ? 'denied' : 'prompt') }
                    : { receive: mapped });
                }
                function onResult(e) { finish((e.detail && e.detail.receive) || 'denied'); }
                document.addEventListener('smile-push-permission-result', onResult);
                try { bridge.requestPushPermissionAsync(); }
                catch (err) { finish('denied'); }
                return;
              }
            }
          }
          var raw = bridge.capacitorInvoke(plugin, method, JSON.stringify(options || {}));
          var parsed = JSON.parse(raw || '{}');
          if (parsed.error) callbacks.reject(parsed.error);
          else callbacks.resolve(parsed.data);
        } catch (e) { callbacks.reject(e); }
      };

      // Capacitor tanpa PluginHeaders jatuh ke Notification.requestPermission (browser),
      // yang TIDAK menampilkan dialog POST_NOTIFICATIONS di Android WebView.
      (function patchNotificationPermission() {
        if (window.__smileNotifPermPatch) return;
        if (typeof Notification === 'undefined' || !Notification.requestPermission) return;
        window.__smileNotifPermPatch = true;
        var orig = Notification.requestPermission.bind(Notification);
        Notification.requestPermission = function(cb) {
          var b = window.${AppConfig.JS_BRIDGE_NAME};
          function map(v) {
            return v === 'granted' ? 'granted' : (v === 'denied' ? 'denied' : 'default');
          }
          if (b && b.requestPushPermissionAsync) {
            return new Promise(function(resolve) {
              if (smileIsSignInPage() || smileInPermissionGrace()) {
                var deferred = 'default';
                if (typeof cb === 'function') try { cb(deferred); } catch (e) {}
                resolve(deferred);
                return;
              }
              var done = false;
              function finish(v) {
                if (done) return;
                done = true;
                document.removeEventListener('smile-push-permission-result', onResult);
                var out = map(v);
                if (typeof cb === 'function') try { cb(out); } catch (e) {}
                resolve(out);
              }
              function onResult(e) { finish((e.detail && e.detail.receive) || 'denied'); }
              document.addEventListener('smile-push-permission-result', onResult);
              try { b.requestPushPermissionAsync(); }
              catch (err) {
                try { finish(b.requestPushPermission()); } catch (e2) { finish('denied'); }
              }
            });
          }
          if (b && b.requestPushPermission) {
            try {
              var out = map(b.requestPushPermission());
              if (typeof cb === 'function') try { cb(out); } catch (e) {}
              return Promise.resolve(out);
            } catch (e) {}
          }
          return orig(cb);
        };
      })();
    })();
  """.trimIndent()

  fun pageFinishedScript(): String = """
    (function() {
      // Pastikan facade Capacitor Android tetap aktif setelah bundle SPA load.
      (function ensureCapFacade() {
        try {
          window.androidBridge = window.androidBridge || { postMessage: function() {} };
          window.Capacitor = window.Capacitor || {};
          if (!window.Capacitor.nativePromise || !window.Capacitor.PluginHeaders) {
            window.__smileCapAndroidFacade = false;
          }
        } catch (e) {}
      })();

      // Patch ulang Notification.requestPermission → SmileNative (dialog Android).
      // Wajib: Capacitor web plugin memakai API ini, bukan PluginHeaders native.
      (function patchNotificationPermission() {
        if (typeof Notification === 'undefined' || !Notification.requestPermission) return;
        var orig = Notification.requestPermission.bind(Notification);
        Notification.requestPermission = function(cb) {
          var b = window.${AppConfig.JS_BRIDGE_NAME};
          function map(v) {
            return v === 'granted' ? 'granted' : (v === 'denied' ? 'denied' : 'default');
          }
          if (b && b.requestPushPermissionAsync) {
            return new Promise(function(resolve) {
              if (smileIsSignInPage() || (window.__smileInPermissionGrace && window.__smileInPermissionGrace())) {
                var deferred = 'default';
                if (typeof cb === 'function') try { cb(deferred); } catch (e) {}
                resolve(deferred);
                return;
              }
              var done = false;
              function finish(v) {
                if (done) return;
                done = true;
                document.removeEventListener('smile-push-permission-result', onResult);
                var out = map(v);
                if (typeof cb === 'function') try { cb(out); } catch (e) {}
                resolve(out);
              }
              function onResult(e) { finish((e.detail && e.detail.receive) || 'denied'); }
              document.addEventListener('smile-push-permission-result', onResult);
              try { b.requestPushPermissionAsync(); }
              catch (err) {
                try { finish(b.requestPushPermission()); } catch (e2) { finish('denied'); }
              }
            });
          }
          if (b && b.requestPushPermission) {
            try {
              var out = map(b.requestPushPermission());
              if (typeof cb === 'function') try { cb(out); } catch (e) {}
              return Promise.resolve(out);
            } catch (e) {}
          }
          return orig(cb);
        };
        window.__smileNotifPermPatch = true;
      })();

      if (window.__smileNativeReady) return;
      window.__smileNativeReady = true;

      window.__SMILE_WEBVIEW__ = true;
      window.__SMILE_DISABLE_WEB_FAKE_GPS_OVERLAY__ = true;
      window.SmileNative = window.SmileNative || {};
      window.Capacitor = window.Capacitor || {};
      window.Capacitor.isNativePlatform = function() { return true; };
      window.Capacitor.getPlatform = function() { return 'android'; };
      window.Capacitor.Plugins = window.Capacitor.Plugins || {};

      var bridge = window.${AppConfig.JS_BRIDGE_NAME};

      window.__smileKeyboardListeners = window.__smileKeyboardListeners || {
        keyboardWillShow: [],
        keyboardDidShow: [],
        keyboardWillHide: [],
        keyboardDidHide: []
      };
      window.Capacitor.Plugins.Keyboard = {
        addListener: function(event, handler) {
          var list = window.__smileKeyboardListeners[event];
          if (list) list.push(handler);
          return Promise.resolve({
            remove: function() {
              var idx = list ? list.indexOf(handler) : -1;
              if (idx >= 0) list.splice(idx, 1);
            }
          });
        }
      };

      if (!bridge) return;

      window.Capacitor.Plugins.PushNotifications = window.Capacitor.Plugins.PushNotifications || {};
      // Pastikan implementasi SmileNative (bukan stub Capacitor web).
      (function reinstallPush() {
        var b = window.${AppConfig.JS_BRIDGE_NAME};
        if (!b) return;
        window.Capacitor.Plugins.PushNotifications = {
          checkPermissions: function() {
            var receive = 'prompt';
            try { if (b.getPushPermission) receive = b.getPushPermission(); } catch (e) {}
            return Promise.resolve({ receive: receive });
          },
          requestPermissions: function() {
            return new Promise(function(resolve) {
              if ((window.__smileIsSignInPage && window.__smileIsSignInPage()) ||
                  (window.__smileInPermissionGrace && window.__smileInPermissionGrace())) {
                resolve({ receive: 'prompt' });
                return;
              }
              var done = false;
              var timer = 0;
              function finish(receive) {
                if (done) return;
                done = true;
                if (timer) clearTimeout(timer);
                document.removeEventListener('smile-push-permission-result', onResult);
                if (receive === 'granted') {
                  try {
                    localStorage.setItem('smile_push_notifications_enabled', '1');
                    window.dispatchEvent(new CustomEvent('smile-push-notifications-preference-changed', {
                      detail: { enabled: true }
                    }));
                  } catch (e) {}
                }
                resolve({ receive: receive || 'denied' });
              }
              function onResult(e) {
                finish(((e && e.detail) || {}).receive || 'denied');
              }
              document.addEventListener('smile-push-permission-result', onResult);
              timer = setTimeout(function() {
                try { finish(b.getPushPermission()); } catch (err) { finish('denied'); }
              }, 120000);
              try {
                var cur = b.getPushPermission ? b.getPushPermission() : 'prompt';
                if (cur === 'granted') {
                  finish('granted');
                  return;
                }
                if (b.requestPushPermissionAsync) b.requestPushPermissionAsync();
                else finish(b.requestPushPermission ? b.requestPushPermission() : 'denied');
              } catch (err) {
                finish('denied');
              }
            });
          },
          register: function() {
            try { if (b.registerPush) b.registerPush(); } catch (e) {}
            return Promise.resolve();
          },
          openSettings: function() {
            return Promise.resolve({ opened: false });
          },
          addListener: function(event, handler) {
            if (event === 'registration') {
              try {
                var token = b.getFcmToken ? b.getFcmToken() : '';
                if (token) handler({ value: token });
              } catch (e) {}
              document.addEventListener('smile-fcm-token', function(e) {
                handler({ value: e.detail || '' });
              });
            }
            if (event === 'pushNotificationReceived' || event === 'pushNotificationActionPerformed') {
              document.addEventListener('smile-native-push-received', function(e) {
                var d = e.detail || {};
                handler({ title: d.title || '', body: d.body || '', data: d.data || {} });
              });
            }
            return Promise.resolve({ remove: function() {} });
          }
        };
      })();

      window.Capacitor.Plugins.SmileReminderTts = {
        schedulePlayback: function(opts) {
          opts = opts || {};
          var raw = bridge.scheduleReminderTts(opts.id, opts.soundUri || '', opts.atMs || 0);
          return Promise.resolve(JSON.parse(raw));
        },
        playNow: function(opts) {
          opts = opts || {};
          // Native owner TTS — JS catch-up / buka-dari-notif tidak boleh putar ulang.
          var raw = bridge.playReminderTtsNow(opts.soundUri || '');
          try {
            var parsed = JSON.parse(raw);
            // Selalu ok agar playTtsForReminder tidak jatuh ke HTML Audio fallback.
            if (!parsed || parsed.ok !== true) {
              return Promise.resolve({ ok: true, skipped: true });
            }
            parsed.skipped = parsed.skipped !== false;
            return Promise.resolve(parsed);
          } catch (e) {
            return Promise.resolve({ ok: true, skipped: true });
          }
        },
        cancelPlayback: function(opts) {
          opts = opts || {};
          var raw = bridge.cancelReminderTts(opts.id || 0);
          return Promise.resolve(JSON.parse(raw));
        }
      };

      // Capture: sterilkan payload notifikasi agar listener React tidak memutar TTS lagi.
      document.addEventListener('smile-local-notification', function(e) {
        try {
          var n = e && e.detail && e.detail.notification;
          var extra = n && n.extra;
          if (!extra) return;
          if (extra.ttsHandledByNative === '1' || extra.ttsPlayed === '1') {
            extra.soundUri = '';
            extra.soundPath = '';
            extra.ttsText = '';
            extra.fireAt = '1970-01-01T00:00:00.000Z';
            extra.fireAtMs = 0;
          }
        } catch (err) {}
      }, true);

      // Pelacakan lokasi native hanya setelah izin (NativePermissionCoordinator).
      // Sync geofence dari attendance-context ada di attendance-check-hook.js (satu bungkus fetch).

      document.dispatchEvent(new CustomEvent('smile-native-ready'));
      // Jangan panggil getFcmToken di sini — dulu runBlocking Firebase bisa crash Honor.
      try {
        var cached = bridge && bridge.getFcmToken ? bridge.getFcmToken() : '';
        if (cached) {
          document.dispatchEvent(new CustomEvent('smile-fcm-token', { detail: cached }));
        }
      } catch (e) {}
    })();
  """.trimIndent()
}
