/*
This glue file implements a bunch of Pixelblaze compatibility APIs and parts of the framework.
 */
var Glue = Java.type("titanicsend.pattern.pixelblaze.Glue");
var LXColor = Java.type("heronarts.lx.color.LXColor");
var ColorType = Java.type("titanicsend.pattern.TEPattern.ColorType");
var Noise = Java.type("heronarts.lx.utils.Noise");
var System = Java.type("java.lang.System");

/* Globals available in pattern code */
var global = this;
var point;

/* Internal globals used by glue */
var __now, __points, __colors;
var __lastControls = {};

/* Math functions and constants as globals */
["E", "LN2", "LN10", "LOG2E", "LOG10E", "PI", "SQRT1_2", "SQRT2", "abs", "acos", "acosh", "asin", "asinh",
"atan", "atanh", "atan2", "cbrt", "ceil", "clz32", "cos", "cosh", "exp", "expm1", "floor", "fround",
"imul", "log", "log1p", "log10", "log2", "max", "min", "pow", "round", "sign", "sin", "sinh", "sqrt",
"tan", "tanh", "trunc"].forEach(k => global[k] = Math[k])

var PI2 = Math.PI * 2;

/* Constants */
var PRIMARY = ColorType.PRIMARY;
var SECONDARY = ColorType.SECONDARY;
var BACKGROUND = ColorType.BACKGROUND;

/* Point / coordinate API */
function isEdgePoint() {
  return __pattern.model.isEdgePoint(point.index)
}

/* Pixelblaze compatibility API */
function random(v) {
  return Math.random() * v
}

function array(n) {
  var a = new Array(n);
  for (var i = 0; i < n; i++) a[i] = 0.0;
  return a;
}

function time(interval) {
  return ((__now / 65536) % interval) / interval
}

function wave(v) {
  return (sin(v*PI*2) + 1)/2
}

function triangle(v) {
  v = v * 2 % 2;
  if (v < 0)
    v += 2
  return v < 1 ? v : 2 - v
}

function clamp(v, min, max) {
  return Math.min(max, Math.max(min, v))
}

function hypot(x, y) {
  return sqrt(x*x + y*y)
}

/* Color & Painting API */

function hsv(h, s, v) {
  return __color = Glue.hsv(h, s, v);
}
function rgb(r, g, b) {
  return __color = Glue.rgb(r, g, b);
}
function rgba(r, g, b, a) {
  return __color = Glue.rgba(r, g, b, a);
}

function paint(v) {
  return __color = __pattern.getGradientColor(v);
}

function swatch(v) {
  return __color = __pattern.getSwatchColor(v);
}

function getHue() {
  return LXColor.h(__color)/360
}

function getSaturation() {
  return LXColor.s(__color)/100
}

function getBrightness() {
  return LXColor.b(__color)/100
}

function setAlpha(v) {
  __color = Glue.setAlpha(__color, v);
}

/* Sound reactive API */

function isBeat() {
  return __pattern.getLX().engine.tempo.beat();
}

function measure() {
  return __pattern.measure()
}
function wholeNote() {
  return __pattern.wholeNote();
}
function phrase() {
  return __pattern.phrase();
}

function getBassLevel() {
  return __pattern.getBassLevel();
}

function getTrebleLevel() {
  return __pattern.getTrebleLevel();
}

function getBassRatio() {
  return __pattern.getBassRatio();
}

function getTrebleRatio() {
  return __pattern.getTrebleRatio();
}

// getter functions for the common controls
function getRotationAngleFromSpeed() {
    return __pattern.getRotationAngleFromSpeed();
}

function getRotationAngleFromSpin() {
    return __pattern.getRotationAngleFromSpin();
}

function getStaticRotationAngle() {
    return __pattern.getStaticRotationAngle();
}

function getCurrentColor() {
   return __pattern.getCurrentColor();
}

function getTime() {
    return __pattern.getTime();
}

function getTimeMs() {
    return __pattern.getTimeMs();
}

function getSpeed() {
    return __pattern.getSpeed();
}

function getXPos() {
    return __pattern.getXPos();
}

function getYPos() {
    return __pattern.getYPos();
}

function getSize() {
    return __pattern.getSize();
}

function getQuantity() {
    return __pattern.getQuantity();
}

/**
 * As in Java, for for most uses, getRotationAngle() is recommended, but if you
 * need direct access to the spin control value, here it is.
 */
function getSpin() {
    return __pattern.getSpin();
}

function getWow1() {
    return __pattern.getWow1();
}

function getWow2() {
    return __pattern.getWow2();
}

function getWowTrigger() {
    return __pattern.getWowTrigger();
}

/* Pixelblaze compatibility framework glue */

function sentenceCase(text) {
  var result = text.replace(/([A-Z])/g, " $1");
  result = result.replace(/_/g, " ");
  result = result.replace(/  /g, " ");
  result = result.trim();
  var words = result.split(" ").map(function (word) {
    return word.charAt(0).toUpperCase() + word.substring(1)
  });
  result = words.join(" ");
  return result;
}

function glueRegisterControls() {
  for (var key in global) {
    if (typeof global[key] == "function") {
      if (key.startsWith("slider")) {
        // System.out.println("found " + key);
        let label = sentenceCase(key.substring(6))
        __pattern.addSlider(key, label)
      }
    }
  }
}
function glueInvokeControls() {
  var value;
  for (var key in global) {
    if (typeof global[key] == "function") {
      if (key.startsWith("slider")) {
        value = __pattern.getSlider(key);
        if (__lastControls[key] !== value) {
          __lastControls[key] = value;
          try {
            global[key](value);
          } catch (err) {
            //ignore
          }
        }
      }
    }
  }
}

function glueBeforeRender(delta, now, points, colors) {
  pixelCount = points.length;
  __now = now;
  __points = points;
  __colors = colors;
  glueInvokeControls();
  if (typeof beforeRender === "function") {
    beforeRender(delta);
  }
}

function glueRender() {
  var r;
  if (typeof render3D !== 'undefined') {
    r = render3D;
  } else if (typeof render2D !== 'undefined') {
    r = render2D;
  } else {
    r = render;
  }
  var xOffs = __pattern.getXPos();
  var yOffs = -__pattern.getYPos();
  var i;
  for (i = 0; i < __points.length; i++) {
    __color = 0;
    point = __points[i];
    r(i, point.xn + xOffs, point.yn + yOffs, point.zn);
    __colors[point.index] = __color;
  }
}

