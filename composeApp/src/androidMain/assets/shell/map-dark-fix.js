/**
 * Status: PENDUKUNG
 * Flow: Memaksa tile peta OpenStreetMap terang supaya peta di web tidak ikut mode gelap sistem.
 * Takeout: kandidat hanya jika layar peta tidak lagi dipakai.
 */
;(function () {
  if (window.__smileMapDarkFix) return
  window.__smileMapDarkFix = true

  var OSM_TILE = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'

  var style = document.createElement('style')
  style.id = 'smile-map-dark-fix'
  style.textContent =
    '.leaflet-container.smile-map--dark .leaflet-tile-pane{' +
    'filter:invert(100%) hue-rotate(180deg) brightness(0.92) contrast(0.88)}'
  document.head.appendChild(style)

  function isDarkTheme() {
    return document.documentElement.classList.contains('dark')
  }

  function isSatelliteUrl(url) {
    return typeof url === 'string' && url.indexOf('arcgisonline.com') >= 0
  }

  function rewriteTileUrl(url) {
    if (!url || typeof url !== 'string') return url
    if (url.indexOf('cartocdn.com') >= 0 || url.indexOf('basemaps.carto') >= 0) return OSM_TILE
    return url
  }

  function syncMapContainer(container, tileUrl) {
    if (!container) return
    var satellite = isSatelliteUrl(tileUrl)
    container.classList.toggle('smile-map--dark', isDarkTheme() && !satellite)
  }

  function syncAllMaps() {
    document.querySelectorAll('.leaflet-container').forEach(function (el) {
      syncMapContainer(el, el._smileTileUrl || '')
    })
  }

  function patchLeaflet() {
    var L = window.L
    if (!L || !L.TileLayer || L.TileLayer.prototype.__smileMapDarkFix) return false

    var proto = L.TileLayer.prototype
    proto.__smileMapDarkFix = true

    var origInitialize = proto.initialize
    proto.initialize = function (url, options) {
      return origInitialize.call(this, rewriteTileUrl(url), options)
    }

    var origSetUrl = proto.setUrl
    proto.setUrl = function (url, noRedraw) {
      url = rewriteTileUrl(url)
      this._smileTileUrl = url
      if (this._map) syncMapContainer(this._map.getContainer(), url)
      return origSetUrl.call(this, url, noRedraw)
    }

    return true
  }

  var themeObserver = new MutationObserver(syncAllMaps)
  if (document.documentElement) {
    themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    })
  }

  var leafletTimer = window.setInterval(function () {
    if (patchLeaflet()) window.clearInterval(leafletTimer)
  }, 250)

  document.addEventListener('DOMContentLoaded', syncAllMaps)
  syncAllMaps()
})()
