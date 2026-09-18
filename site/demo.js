(function () {
  "use strict";

  var chapters = Array.prototype.slice.call(document.querySelectorAll(".chapter"));
  var demo = document.querySelector(".demo");
  var video = document.querySelector(".demo-video");
  var empty = document.querySelector(".demo-empty");
  var caption = document.querySelector(".caption");

  if (!chapters.length || !video || !demo) return;

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var current = 0;
  var inView = true;
  var missingTimer = null;

  function at(index) {
    return chapters[(index + chapters.length) % chapters.length];
  }

  function setFill(btn, ratio) {
    var fill = btn.querySelector(".fill");
    if (fill) fill.style.width = Math.max(0, Math.min(1, ratio)) * 100 + "%";
  }

  function activate(index) {
    current = (index + chapters.length) % chapters.length;
    chapters.forEach(function (btn, i) {
      btn.classList.toggle("active", i === current);
      setFill(btn, 0);
    });
    caption.textContent = at(current).dataset.caption || "";
  }

  function showEmpty(fileName) {
    video.hidden = true;
    empty.hidden = false;
    empty.textContent = fileName;
  }

  function showVideo() {
    empty.hidden = true;
    video.hidden = false;
  }

  function clearMissingTimer() {
    if (missingTimer) {
      clearTimeout(missingTimer);
      missingTimer = null;
    }
  }

  function play() {
    if (reduceMotion || !inView || document.hidden) return;
    video.play().catch(function () {});
  }

  function load(index, autoplay) {
    clearMissingTimer();
    activate(index);
    showVideo();
    var btn = at(current);
    video.poster = btn.dataset.poster || "";
    video.src = btn.dataset.src || "";
    if (reduceMotion) {
      video.pause();
      return;
    }
    if (autoplay) play();
  }

  function advance() {
    load(current + 1, true);
  }

  video.addEventListener("error", function () {
    var src = at(current).dataset.src || "";
    showEmpty(src.split("/").pop());
    clearMissingTimer();
    missingTimer = setTimeout(advance, 5000);
  });

  video.addEventListener("ended", advance);

  chapters.forEach(function (btn, i) {
    btn.addEventListener("click", function () {
      load(i, true);
    });
  });

  (function tick() {
    var btn = at(current);
    if (video.duration) setFill(btn, video.currentTime / video.duration);
    requestAnimationFrame(tick);
  })();

  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(
      function (entries) {
        inView = entries[0].isIntersecting;
        if (!inView) video.pause();
        else play();
      },
      { threshold: 0.25 }
    );
    io.observe(demo);
  }

  document.addEventListener("visibilitychange", function () {
    if (document.hidden) video.pause();
    else play();
  });

  if (reduceMotion) {
    activate(0);
    showVideo();
    var first = at(0);
    video.poster = first.dataset.poster || "";
    video.addEventListener("click", function () {
      if (!video.src) video.src = first.dataset.src || "";
      video.play().catch(function () {});
    });
  } else {
    load(0, true);
  }
})();
