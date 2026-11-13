(function () {
  "use strict";

  document.documentElement.classList.add("has-js");

  var motionPreference = window.matchMedia("(prefers-reduced-motion: reduce)");
  var connection = navigator.connection || {};
  var dataSaver = connection.saveData === true || /(^|-)2g$/.test(connection.effectiveType || "");
  var canAutoplay = function () { return !motionPreference.matches && !dataSaver; };

  var screens = {
    home: {
      src: "./images/experience-home.webp",
      alt: "PrismAura 首页：继续播放、最近播放与音乐内容卡片",
      tag: "TV · HOME",
      title: "回到首页，接着听。",
      description: "继续播放、最近播放和平台内容都从这里开始。正在播放的经典曲目会留在视线中央。",
      note: "首页内容会随你的本机播放记录变化。",
      portrait: false
    },
    discover: {
      src: "./images/experience-discover.webp",
      alt: "PrismAura 发现页：分类入口、歌单与音乐榜单",
      tag: "TV · DISCOVER",
      title: "从分类和榜单找下一首。",
      description: "浏览歌单、常听歌手和音乐榜单，用方向键继续探索。",
      note: "推荐结果由已安装的音源提供，内容会因插件而异。",
      portrait: false
    },
    library: {
      src: "./images/experience-library.webp",
      alt: "PrismAura 我的页面：歌曲收藏与最近播放分类",
      tag: "TV · LIBRARY",
      title: "收藏和最近播放，分区收好。",
      description: "歌曲、专辑、歌手、歌单与最近播放各自归类，长按内容可打开对应操作。",
      note: "本机收藏、历史和歌单保存在设备上。",
      portrait: false
    },
    settings: {
      src: "./images/experience-settings.webp",
      alt: "PrismAura 设置页：主题选择与播放界面偏好",
      tag: "TV · SETTINGS",
      title: "常用偏好，遥控器可达。",
      description: "选择主题，调整播放界面与音源偏好；设置项按电视遥控操作安排。",
      note: "截图展示外观设置。",
      portrait: false
    },
    qr: {
      src: "./images/experience-qr.webp",
      alt: "PrismAura 手机遥控二维码弹窗预览，二维码已替换为不可连接图案",
      tag: "LOCAL · QR",
      title: "扫码，让手机接上电视。",
      description: "通过同一局域网打开手机遥控页面，搜索、输入和管理内容更方便。",
      note: "为保护本机连接信息，截图中的二维码已替换，不能用于连接。",
      portrait: false
    },
    phone: {
      src: "./images/experience-phone.webp",
      alt: "PrismAura 手机遥控页面预览：可输入搜索内容并控制播放",
      tag: "MOBILE · REMOTE",
      title: "输入交给手机，播放留在大屏。",
      description: "用手机键盘搜索歌曲，也能控制播放、进度、收藏和队列。",
      note: "手机界面为功能预览。",
      portrait: true
    }
  };

  /* The feature strip is manual: visitors control the pace and can use arrow keys. */
  var tabs = Array.prototype.slice.call(document.querySelectorAll(".screen-tab"));
  var panel = document.getElementById("screen-panel");
  var image = document.getElementById("screen-image");
  var frame = document.querySelector(".screen-panel__frame");
  var tag = document.getElementById("screen-tag");
  var index = document.getElementById("screen-index");
  var heading = document.getElementById("screen-heading");
  var description = document.getElementById("screen-description");
  var note = document.getElementById("screen-note");
  var screenSelection = 0;

  function selectScreen(tab, moveFocus) {
    var data = screens[tab && tab.dataset.screen];
    if (!data || !image || !frame) return;
    var selection = ++screenSelection;

    tabs.forEach(function (item) {
      var selected = item === tab;
      item.classList.toggle("is-active", selected);
      item.setAttribute("aria-selected", String(selected));
      item.tabIndex = selected ? 0 : -1;
    });
    if (moveFocus) tab.focus();

    image.classList.add("is-changing");
    var next = new Image();
    next.onload = function () {
      if (selection !== screenSelection) return;
      image.src = data.src;
      image.alt = data.alt;
      frame.classList.toggle("is-portrait", data.portrait);
      image.classList.remove("is-changing");
    };
    next.onerror = function () {
      if (selection !== screenSelection) return;
      image.src = data.src;
      image.alt = data.alt;
      frame.classList.toggle("is-portrait", data.portrait);
      image.classList.remove("is-changing");
    };
    next.src = data.src;

    if (tag) tag.textContent = data.tag;
    if (index) index.textContent = String(tabs.indexOf(tab) + 1).padStart(2, "0") + " / 06";
    if (heading) heading.textContent = data.title;
    if (description) description.textContent = data.description;
    if (note) note.textContent = data.note;
    if (panel) panel.setAttribute("aria-labelledby", tab.id);
  }

  tabs.forEach(function (tab) {
    tab.addEventListener("click", function () { selectScreen(tab, false); });
    tab.addEventListener("keydown", function (event) {
      var current = tabs.indexOf(tab);
      var target = null;
      if (event.key === "ArrowRight" || event.key === "ArrowDown") target = tabs[(current + 1) % tabs.length];
      if (event.key === "ArrowLeft" || event.key === "ArrowUp") target = tabs[(current - 1 + tabs.length) % tabs.length];
      if (event.key === "Home") target = tabs[0];
      if (event.key === "End") target = tabs[tabs.length - 1];
      if (target) {
        event.preventDefault();
        selectScreen(target, true);
      }
    });
  });

  /* Keep demo video playback local to the viewport and respect motion/data preferences. */
  function bindPreview(video, button) {
    if (!video || !button) return;
    var visible = false;
    var manuallyPaused = false;
    var manuallyStarted = false;
    var resumeOnReturn = false;
    var label = video.dataset.label || "演示视频";

    function paint(playing) {
      button.dataset.state = playing ? "playing" : "paused";
      button.setAttribute("aria-pressed", String(playing));
      button.setAttribute("aria-label", (playing ? "暂停" : "播放") + label);
    }
    function play() {
      if (!visible || document.hidden) return;
      var result = video.play();
      if (result && typeof result.then === "function") result.then(function () { paint(true); }).catch(function () { paint(false); });
      else paint(true);
    }
    function autoplayIfAllowed() {
      if ((canAutoplay() || manuallyStarted) && !manuallyPaused) play();
    }

    paint(false);
    if ("IntersectionObserver" in window) {
      var observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          visible = entry.isIntersecting;
          if (visible) autoplayIfAllowed();
          else {
            resumeOnReturn = !video.paused;
            video.pause();
          }
        });
      }, { threshold: .18, rootMargin: "80px 0px" });
      observer.observe(video);
    } else {
      visible = true;
      autoplayIfAllowed();
    }

    button.addEventListener("click", function () {
      if (video.paused) {
        manuallyPaused = false;
        manuallyStarted = true;
        play();
      } else {
        manuallyPaused = true;
        manuallyStarted = false;
        video.pause();
      }
    });
    video.addEventListener("play", function () { paint(true); });
    video.addEventListener("pause", function () { paint(false); });
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) {
        resumeOnReturn = !video.paused;
        video.pause();
      } else if (visible && (resumeOnReturn || (canAutoplay() && !manuallyPaused))) {
        resumeOnReturn = false;
        autoplayIfAllowed();
      }
    });
    if (motionPreference.addEventListener) {
      motionPreference.addEventListener("change", function () {
        if (!canAutoplay() && !manuallyStarted) video.pause();
        else autoplayIfAllowed();
      });
    }
  }

  bindPreview(document.getElementById("lyrics-video"), document.getElementById("lyrics-toggle"));

  /* Bring content in as it enters view; show everything when motion is reduced. */
  var revealItems = Array.prototype.slice.call(document.querySelectorAll("[data-reveal]"));
  if (!("IntersectionObserver" in window) || motionPreference.matches) {
    revealItems.forEach(function (item) { item.classList.add("is-visible"); });
  } else {
    var revealObserver = new IntersectionObserver(function (entries, observer) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: "0px 0px -30px 0px" });
    revealItems.forEach(function (item) { revealObserver.observe(item); });
  }
})();
