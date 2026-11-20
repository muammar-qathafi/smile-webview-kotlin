/**
 * Status: PENDUKUNG
 * Flow: Menyesuaikan layout web saat keyboard Android terbuka, disuntik ShellScriptInjector.
 * Takeout: jangan.
 */
;(function () {
  try { window.__SMILE_WEBVIEW__ = true } catch (e) { /* ignore */ }

  function boot() {
    // Versi dinaikkan agar inject ulang mengganti style lama di WebView.
    if (window.__smileKeyboardInject === 7) return
    window.__smileKeyboardInject = 7

    var FOCUSABLE = { INPUT: 1, TEXTAREA: 1, SELECT: 1 }
    var SKIP_TYPES = {
      checkbox: 1,
      radio: 1,
      button: 1,
      submit: 1,
      reset: 1,
      file: 1,
      hidden: 1,
      image: 1,
      range: 1,
      color: 1,
    }

    var root = document.documentElement
    var activeField = null
    var focusTimer = 0
    var revealTimer = 0
    var nativeOwnsIme = false
    var nativeKbOpen = false

    root.classList.add('smile-native-safe-bottom')
    root.style.setProperty('--smile-safe-bottom', '0px')

    var oldStyle = document.getElementById('smile-keyboard-styles')
    if (oldStyle && oldStyle.parentNode) oldStyle.parentNode.removeChild(oldStyle)

    var style = document.createElement('style')
    style.id = 'smile-keyboard-styles'
    style.textContent =
      'header.sticky{padding-top:0!important}' +
      '.relative.min-h-screen>button.absolute.top-4{top:1rem!important}' +
      '.smile-bottom-nav,' +
      'html.smile-native-safe-bottom .smile-bottom-nav,' +
      'html.smile-native-safe-bottom nav.smile-bottom-nav{' +
      'padding-bottom:0.25rem!important;' +
      'box-sizing:border-box!important}' +
      'html.smile-native-safe-bottom .pb-safe,' +
      'html.smile-native-safe-bottom .safe-area-bottom,' +
      'html.smile-native-safe-bottom .safe-bottom{padding-bottom:0!important}' +
      'html.keyboard-open{--shell-bottom-chrome:0px!important;--keyboard-inset:0px}' +
      'html.keyboard-open .signin-shell{' +
      'justify-content:flex-start!important;' +
      'max-height:var(--viewport-visible-h,100dvh)!important;' +
      'overflow-y:auto!important;-webkit-overflow-scrolling:touch}' +
      'html.keyboard-open .signin-shell>section:last-child{margin-top:0!important}' +
      'html.keyboard-open .signin-hero{flex:0 0 auto!important;padding-top:0.75rem!important;padding-bottom:0!important}' +
      'html.keyboard-open .signin-hero h1,html.keyboard-open .signin-hero p:last-child{display:none!important}' +
      'html.keyboard-open .signin-hero img{height:2.5rem!important}' +
      // Sembunyikan bottom nav saat input — kritis agar textarea tidak terpotong.
      'html.keyboard-open .smile-bottom-nav{' +
      'transform:translateY(120%)!important;opacity:0!important;' +
      'pointer-events:none!important;visibility:hidden!important}' +
      'html.keyboard-open body{overflow:hidden!important}' +
      'html.keyboard-open main.smile-form-scroll{' +
      'overflow-y:auto!important;-webkit-overflow-scrolling:touch;' +
      'max-height:var(--viewport-visible-h,100dvh)!important;' +
      'padding-bottom:1rem!important;' +
      'scroll-padding-bottom:1.25rem!important;' +
      'scroll-padding-top:4.5rem!important}' +
      'html.keyboard-open .smile-keyboard-scroll{' +
      'overflow-y:auto!important;-webkit-overflow-scrolling:touch;' +
      'max-height:var(--viewport-visible-h,100dvh)!important;' +
      'scroll-padding-bottom:1.25rem!important}' +
      'html.keyboard-open textarea,' +
      'html.keyboard-open input:not([type]),' +
      'html.keyboard-open input[type="text"],' +
      'html.keyboard-open input[type="search"],' +
      'html.keyboard-open input[type="email"],' +
      'html.keyboard-open input[type="tel"],' +
      'html.keyboard-open input[type="url"],' +
      'html.keyboard-open input[type="password"],' +
      'html.keyboard-open input[type="number"]{' +
      'scroll-margin-top:4.5rem!important;' +
      'scroll-margin-bottom:1.25rem!important}'
    ;(document.head || document.documentElement).appendChild(style)

    function isFormField(el) {
      if (!el || el.nodeType !== 1) return false
      if (el.isContentEditable) return true
      if (!FOCUSABLE[el.tagName]) return false
      if (el.tagName === 'INPUT') {
        var type = String(el.getAttribute('type') || 'text').toLowerCase()
        if (SKIP_TYPES[type]) return false
      }
      return !el.disabled && !el.readOnly
    }

    function vvKeyboardInset() {
      var vv = window.visualViewport
      if (!vv) return 0
      return Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop))
    }

    function keyboardOpen() {
      // Jika Android sudah mengambil alih deteksi IME, tinggi native adalah
      // sumber kebenaran. Field dapat tetap fokus setelah tombol Back menutup
      // keyboard, jadi fokus saja tidak boleh mempertahankan nav tersembunyi.
      if (nativeOwnsIme) return nativeKbOpen
      if (nativeKbOpen) return true
      if (vvKeyboardInset() > 80) return true
      // Fallback Honor: WebView sudah di-shrink native, VV inset ~0, tapi field fokus.
      return !!(activeField && document.activeElement === activeField)
    }

    function setKeyboardChrome(open) {
      if (open) {
        root.classList.add('keyboard-open')
        root.style.setProperty('--shell-bottom-chrome', '0px')
        root.style.setProperty('--keyboard-inset', '0px')
      } else {
        root.classList.remove('keyboard-open')
        root.style.removeProperty('--shell-bottom-chrome')
        if (!nativeOwnsIme) {
          root.style.setProperty('--keyboard-inset', '0px')
        }
      }
      var vv = window.visualViewport
      if (open && vv) {
        root.style.setProperty('--viewport-visible-h', Math.round(vv.height) + 'px')
      } else if (!open) {
        root.style.removeProperty('--viewport-visible-h')
      }
    }

    function applyKeyboardInset(kb) {
      var height = Math.max(0, Math.round(Number(kb) || 0))
      if (height > 0) {
        var px = height + 'px'
        root.style.setProperty('--keyboard-inset-native', px)
        root.style.setProperty('--keyboard-inset', px)
      } else {
        root.style.removeProperty('--keyboard-inset-native')
        root.style.setProperty('--keyboard-inset', '0px')
      }
    }

    function clearCssInset() {
      root.style.removeProperty('--keyboard-inset-native')
      root.style.setProperty('--keyboard-inset', '0px')
    }

    function syncViewportVars() {
      var vv = window.visualViewport
      var open = keyboardOpen()

      if (nativeOwnsIme) {
        clearCssInset()
        setKeyboardChrome(open)
        if (open && vv) {
          root.style.setProperty('--viewport-visible-h', Math.round(vv.height) + 'px')
        }
        return 0
      }

      var kb = Math.max(vvKeyboardInset(), 0)
      var native = parseInt(root.style.getPropertyValue('--keyboard-inset-native') || '0', 10)
      kb = Math.max(kb, native > 80 ? native : 0)
      applyKeyboardInset(kb)
      setKeyboardChrome(open || kb > 80)
      return kb
    }

    function visibleBottom() {
      var vv = window.visualViewport
      // Sisakan ruang aman di atas tepi viewport (bukan di atas bottom-nav — nav sudah di-hide).
      return (vv ? vv.height + vv.offsetTop : window.innerHeight) - 24
    }

    function visibleTop() {
      var vv = window.visualViewport
      var header = document.querySelector('header.sticky')
      var headerH = header ? Math.ceil(header.getBoundingClientRect().height) : 56
      return (vv ? vv.offsetTop : 0) + headerH + 8
    }

    function getScrollParent(el) {
      if (el && el.closest) {
        var mainScroll = el.closest('main.smile-form-scroll')
        if (mainScroll) return mainScroll
        var shell = el.closest('.signin-shell')
        if (shell) return shell
        var kbScroll = el.closest('.smile-keyboard-scroll')
        if (kbScroll) return kbScroll
      }
      var node = el.parentElement
      while (node) {
        if (node.classList && node.classList.contains('signin-shell')) return node
        if (node.classList && node.classList.contains('smile-form-scroll')) return node
        var oy = window.getComputedStyle(node).overflowY
        if (/auto|scroll|overlay/.test(oy) && node.scrollHeight > node.clientHeight + 1) return node
        if (node === document.body) break
        node = node.parentElement
      }
      return document.scrollingElement || document.documentElement
    }

    function scrollBy(container, delta) {
      if (Math.abs(delta) < 2) return
      var sc = document.scrollingElement || document.documentElement
      if (container === sc || container === document.body || container === document.documentElement) {
        window.scrollBy({ top: delta, left: 0, behavior: 'auto' })
      } else {
        container.scrollBy({ top: delta, left: 0, behavior: 'auto' })
      }
    }

    /**
     * Naikkan field agar frame penuh masuk viewport terlihat
     * (di atas keyboard, di bawah header, tanpa terhalang bottom-nav).
     */
    function revealField(el) {
      setKeyboardChrome(true)
      syncViewportVars()

      var top = visibleTop()
      var bottom = visibleBottom()
      var visibleH = Math.max(80, bottom - top)
      var parent = getScrollParent(el)
      var rect = el.getBoundingClientRect()

      // Textarea tinggi: usahakan top field di bawah header; biarkan scroll dalam textarea.
      if (el.tagName === 'TEXTAREA' || el.isContentEditable) {
        var targetTop = top + 8
        if (rect.height <= visibleH - 16) {
          // Muat penuh: geser sampai bottom & top masuk frame.
          if (rect.bottom > bottom) scrollBy(parent, rect.bottom - bottom + 16)
          rect = el.getBoundingClientRect()
          if (rect.top < top) scrollBy(parent, rect.top - top - 8)
        } else {
          // Tidak muat penuh: pin bagian atas textarea ke frame atas.
          scrollBy(parent, rect.top - targetTop)
        }
      } else {
        if (rect.bottom > bottom) scrollBy(parent, rect.bottom - bottom + 16)
        rect = el.getBoundingClientRect()
        if (rect.top < top) scrollBy(parent, rect.top - top - 8)
      }

      // Pass kedua setelah layout settle.
      rect = el.getBoundingClientRect()
      bottom = visibleBottom()
      top = visibleTop()
      if (el.tagName === 'TEXTAREA' || el.isContentEditable) {
        if (rect.height <= bottom - top - 16) {
          if (rect.bottom > bottom + 2) scrollBy(parent, rect.bottom - bottom + 16)
          if (rect.top < top - 2) scrollBy(parent, rect.top - top - 8)
        } else if (Math.abs(rect.top - (top + 8)) > 6) {
          scrollBy(parent, rect.top - (top + 8))
        }
      } else {
        if (rect.bottom > bottom + 2) scrollBy(parent, rect.bottom - bottom + 16)
        if (rect.top < top - 2) scrollBy(parent, rect.top - top - 8)
      }
    }

    function scheduleReveal(el, delay) {
      clearTimeout(focusTimer)
      focusTimer = setTimeout(function () {
        revealField(el)
        setTimeout(function () { revealField(el) }, 100)
        setTimeout(function () { revealField(el) }, 280)
      }, delay)
    }

    function setNativeInset(h, handledByNative) {
      var height = Math.max(0, Number(h) || 0)
      if (handledByNative) {
        nativeOwnsIme = true
        clearCssInset()
        nativeKbOpen = height > 0
        setKeyboardChrome(nativeKbOpen)
      } else {
        nativeOwnsIme = false
        nativeKbOpen = height > 0
        applyKeyboardInset(height)
        setKeyboardChrome(nativeKbOpen || height > 80)
      }
      syncViewportVars()
      if (activeField && document.activeElement === activeField) {
        scheduleReveal(activeField, 40)
      }
    }

    function notifyNativeFormFocus(active) {
      try {
        var b = window.SmileNative
        if (b && b.setFormInputActive) b.setFormInputActive(!!active)
      } catch (err) {}
    }

    function onFocusIn(e) {
      var el = e.target
      if (!isFormField(el)) return
      activeField = el
      notifyNativeFormFocus(true)
      // Langsung hide bottom-nav sebelum event IME native (Honor sering lambat).
      setKeyboardChrome(true)
      scheduleReveal(el, 50)
      scheduleReveal(el, 220)
      scheduleReveal(el, 450)
    }

    function onFocusOut() {
      setTimeout(function () {
        if (document.activeElement && isFormField(document.activeElement)) return
        activeField = null
        notifyNativeFormFocus(false)
        if (!nativeKbOpen && vvKeyboardInset() <= 80) {
          setKeyboardChrome(false)
        }
        syncViewportVars()
      }, 450)
    }

    function onViewportChange() {
      syncViewportVars()
      if (activeField && document.activeElement === activeField) {
        clearTimeout(revealTimer)
        revealTimer = setTimeout(function () {
          revealField(activeField)
        }, 40)
      }
    }

    function bindCapacitorKeyboard() {
      var K = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Keyboard
      if (!K || !K.addListener) return
      ;['keyboardWillShow', 'keyboardDidShow'].forEach(function (ev) {
        K.addListener(ev, function (info) {
          setNativeInset(
            info && info.keyboardHeight,
            !!(info && info.handledByNative),
          )
        })
      })
      ;['keyboardWillHide', 'keyboardDidHide'].forEach(function (ev) {
        K.addListener(ev, function (info) {
          setNativeInset(0, !!(info && info.handledByNative) || nativeOwnsIme)
        })
      })
    }

    syncViewportVars()
    document.addEventListener('focusin', onFocusIn, true)
    document.addEventListener('focusout', onFocusOut, true)
    window.addEventListener('smile:keyboard', function (e) {
      var d = (e && e.detail) || {}
      setNativeInset(d.height, !!d.handledByNative)
    })
    window.addEventListener('smile:safe-bottom', function (e) {
      var handled = !!(e && e.detail && e.detail.handledByNative)
      if (handled) {
        root.classList.add('smile-native-safe-bottom')
        root.style.setProperty('--smile-safe-bottom', '0px')
      } else {
        root.classList.remove('smile-native-safe-bottom')
        root.style.removeProperty('--smile-safe-bottom')
      }
    })
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', onViewportChange)
      window.visualViewport.addEventListener('scroll', onViewportChange)
    }
    window.addEventListener('resize', onViewportChange)
    window.addEventListener('orientationchange', onViewportChange)

    if (window.Capacitor && window.Capacitor.Plugins) bindCapacitorKeyboard()
    else {
      var tries = 0
      var wait = setInterval(function () {
        tries++
        if (window.Capacitor && window.Capacitor.Plugins) {
          clearInterval(wait)
          bindCapacitorKeyboard()
        } else if (tries > 40) clearInterval(wait)
      }, 250)
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot)
  } else {
    boot()
  }
})()
