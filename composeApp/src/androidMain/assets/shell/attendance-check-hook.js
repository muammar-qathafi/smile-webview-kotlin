(function () {
  if (window.__smileAttendanceCheckHook) return;
  window.__smileAttendanceCheckHook = true;

  var ATTENDANCE_CHECK_PATH = '/api/v1/home/attendance-check';
  var ATTENDANCE_CONTEXT_PATH = '/api/v1/home/attendance-context';

  function isAttendanceCheckPost(url, method) {
    if (!url || String(method || 'GET').toUpperCase() !== 'POST') return false;
    try {
      var path = url;
      if (url.indexOf('://') >= 0) {
        path = new URL(url, window.location.origin).pathname || url;
      }
      return path.indexOf(ATTENDANCE_CHECK_PATH) >= 0;
    } catch (e) {
      return String(url).indexOf(ATTENDANCE_CHECK_PATH) >= 0;
    }
  }

  function isCheckInPayload(payload) {
    if (!payload || typeof payload !== 'object') return false;
    var data = payload.data != null ? payload.data : payload;
    if (!data || typeof data !== 'object') return false;
    var action = data.action;
    if (action === 'check_in') return true;
    if (action === 'check_out') return false;
    if (data.time_out) return false;
    return Boolean(data.time_in);
  }

  function isAttendanceContextGet(url, method) {
    if (!url || String(method || 'GET').toUpperCase() !== 'GET') return false;
    return String(url).indexOf(ATTENDANCE_CONTEXT_PATH) >= 0;
  }

  function notifyNativeCheckIn() {
    try {
      var sn = window.SmileNative;
      if (sn && typeof sn.onAttendanceCheckInSuccess === 'function') {
        sn.onAttendanceCheckInSuccess();
      }
    } catch (e) {}
  }

  function notifyNativeShift(payload) {
    var data = payload && payload.data != null ? payload.data : payload;
    if (!data || typeof data !== 'object') return;
    var label = String(data.shift_label || '');
    var match = label.match(/\[(\d{2}:\d{2})\s*-\s*(\d{2}:\d{2})\]/);
    if (!match) return;
    try {
      var sn = window.SmileNative;
      if (sn && typeof sn.onAttendanceShift === 'function') {
        sn.onAttendanceShift(match[1], match[2], label);
      }
    } catch (e) {}
  }

  function detectLeaveExemption(data) {
    if (!data || typeof data !== 'object') return false;
    var flags = [
      data.has_leave_exemption, data.hasLeaveExemption,
      data.has_leave, data.hasLeave, data.on_leave, data.onLeave,
      data.is_on_leave, data.isOnLeave, data.active_leave, data.activeLeave,
      data.has_office_permit, data.hasOfficePermit, data.office_permit,
      data.has_izin, data.hasIzin, data.leave_approved, data.leaveApproved
    ];
    for (var i = 0; i < flags.length; i++) {
      var v = flags[i];
      if (v === true || v === 1 || v === '1') return true;
    }
    var lists = [data.leaves, data.leave_requests, data.permits, data.office_permits, data.izin];
    for (var j = 0; j < lists.length; j++) {
      if (Array.isArray(lists[j]) && lists[j].length > 0) return true;
    }
    return false;
  }

  function syncGeofenceFromPayload(payload) {
    var data = payload && payload.data != null ? payload.data : payload;
    if (!data || typeof data !== 'object') return;
    try {
      var sn = window.SmileNative;
      if (!sn) return;
      var areas = Array.isArray(data.areas) ? data.areas : [];
      var hasLeave = detectLeaveExemption(data);
      if (typeof sn.updateGeofenceConfig === 'function') {
        sn.updateGeofenceConfig(JSON.stringify({ areas: areas, hasLeaveExemption: hasLeave }));
      }
      if (typeof sn.setLeaveExemption === 'function') {
        sn.setLeaveExemption(hasLeave);
      }
    } catch (e) {}
  }

  function onAttendanceContextPayload(payload) {
    notifyNativeShift(payload);
    syncGeofenceFromPayload(payload);
  }

  function inspectResponse(url, method, response) {
    if (!response || !response.ok) return;
    if (isAttendanceCheckPost(url, method)) {
      response.clone().json().then(function (payload) {
        if (isCheckInPayload(payload)) notifyNativeCheckIn();
      }).catch(function () {});
    }
    if (isAttendanceContextGet(url, method)) {
      response.clone().json().then(onAttendanceContextPayload).catch(function () {});
    }
  }

  var origFetch = window.fetch;
  if (typeof origFetch === 'function') {
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var method = (init && init.method) || (input && input.method) || 'GET';
      return origFetch.apply(this, arguments).then(function (res) {
        inspectResponse(url, method, res);
        return res;
      });
    };
  }
})();
