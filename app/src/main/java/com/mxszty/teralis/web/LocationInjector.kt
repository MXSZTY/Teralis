package com.mxszty.teralis.web

import com.mxszty.teralis.data.LocationEntry

/**
 * 虚拟位置 JS 注入器：重写 navigator.geolocation 与（深度模式下）Permissions API。
 * 在 onPageStarted / onPageFinished 各注入一次，确保先于或追平页面脚本执行。
 */
@Suppress("SpellCheckingInspection") // coords 是 W3C GeolocationPosition 标准属性名，非拼写错误
object LocationInjector {

    fun script(loc: LocationEntry, deep: Boolean): String {
        // Double.toString 始终使用 '.' 作为小数点，可直接嵌入 JS 数字字面量
        val lat = loc.latitude.toString()
        val lng = loc.longitude.toString()
        val acc = loc.accuracyMeters.toDouble().coerceAtLeast(0.0).toString()
        val deepPart = if (deep) DEEP_JS else ""

        return """
(function () {
  try {
    var LAT = $lat, LNG = $lng, ACC = $acc;
    function fakePosition() {
      return {
        coords: {
          latitude: LAT, longitude: LNG, accuracy: ACC,
          altitude: null, altitudeAccuracy: null, heading: null, speed: null
        },
        timestamp: Date.now()
      };
    }
    var fakeGeo = {};
    fakeGeo.getCurrentPosition = function (onSuccess, onError, options) {
      setTimeout(function () {
        if (typeof onSuccess === 'function') { onSuccess(fakePosition()); }
      }, 24);
    };
    fakeGeo.watchPosition = function (onSuccess, onError, options) {
      if (typeof onSuccess === 'function') { onSuccess(fakePosition()); }
      return setInterval(function () {
        if (typeof onSuccess === 'function') { onSuccess(fakePosition()); }
      }, 4000);
    };
    fakeGeo.clearWatch = function (id) { clearInterval(id); };
    var NATIVE = 'function () { [native code] }';
    fakeGeo.getCurrentPosition.toString = function () { return NATIVE; };
    fakeGeo.watchPosition.toString = function () { return NATIVE; };
    fakeGeo.clearWatch.toString = function () { return NATIVE; };
    Object.defineProperty(navigator, 'geolocation', {
      value: fakeGeo, writable: false, configurable: true
    });
    $deepPart
  } catch (e) {}
})();
"""
    }

    private const val DEEP_JS = """
    if (navigator.permissions && navigator.permissions.query) {
      var originalQuery = navigator.permissions.query.bind(navigator.permissions);
      navigator.permissions.query = function (descriptor) {
        try {
          if (descriptor && String(descriptor.name).toLowerCase() === 'geolocation') {
            return Promise.resolve({ state: 'granted', onchange: null });
          }
        } catch (e) {}
        return originalQuery(descriptor);
      };
    }
"""
}
