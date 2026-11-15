/* المصري TV — Samsung TV (Tizen) edition.
 * Plain ES2017 for the 2018 TV browser (Chromium 56): no ?. / ?? / object spread / CSS grid.
 * Screens: home (folders) → browser (categories + channels / posters) → player. Remote-only navigation.
 */
(function () {
  'use strict';

  var CATALOG_URL = 'https://github.com/kareemalmasry19-svg/Mashwar-TV/releases/download/catalog/arab.json';
  var UA = 'AlmasryTV-Tizen/1.0';
  var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27, BACKSPACE: 8,
    CH_UP: 427, CH_DOWN: 428, PLAY_PAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
    RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, INFO: 457, PREV_CH: 10190, CH_LIST: 10073,
    IME_DONE: 65376, IME_CANCEL: 65385 };

  // ------------------------------------------------------------------ small helpers
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function pad(n, w) { n = String(n); while (n.length < w) n = '0' + n; return n; }
  function or(v, d) { return v == null || v === '' ? d : v; }
  function load(k, d) { try { var v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch (e) { return d; } }
  function save(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* storage full */ } }
  function uid() { return 'p' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }
  function clock(ms) {
    var s = Math.max(0, Math.floor(ms / 1000));
    return s >= 3600 ? Math.floor(s / 3600) + ':' + pad(Math.floor(s / 60) % 60, 2) + ':' + pad(s % 60, 2) : Math.floor(s / 60) + ':' + pad(s % 60, 2);
  }
  function hhmm(t) { var d = new Date(t); return pad(d.getHours(), 2) + ':' + pad(d.getMinutes(), 2); }
  function b64(s) { try { return decodeURIComponent(escape(atob(s))); } catch (e) { return s; } }

  var toastTimer = null;
  function toast(msg) {
    var t = $('#toast'); t.textContent = msg; t.style.display = 'block';
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.style.display = 'none'; }, 3500);
  }

  /** XHR as a promise (fetch has no timeout on this browser). */
  function http(url, opts) {
    opts = opts || {};
    return new Promise(function (resolve, reject) {
      var x = new XMLHttpRequest();
      x.open('GET', url, true);
      x.timeout = opts.timeout || 30000;
      x.onload = function () { if (x.status >= 200 && x.status < 300) resolve(x.responseText); else reject(new Error('HTTP ' + x.status)); };
      x.onerror = function () { reject(new Error('مقدرتش أوصل للسيرفر')); };
      x.ontimeout = function () { reject(new Error('السيرفر اتأخر في الرد')); };
      x.send();
    });
  }
  function httpJson(url, opts) { return http(url, opts).then(function (t) { return JSON.parse(t); }); }

  // ------------------------------------------------------------------ data: playlists
  var FREE = { id: 'free', name: 'قنوات مجانية — مصر والعرب', type: 'free' };
  function playlists() {
    var list = load('playlists', null);
    if (!list) { list = [FREE]; save('playlists', list); }
    return list;
  }
  function savePlaylists(list) { save('playlists', list); }

  var COUNTRY = { EG: '🇪🇬 مصر', SA: '🇸🇦 السعودية', AE: '🇦🇪 الإمارات', KW: '🇰🇼 الكويت', QA: '🇶🇦 قطر', BH: '🇧🇭 البحرين',
    OM: '🇴🇲 عُمان', YE: '🇾🇪 اليمن', IQ: '🇮🇶 العراق', SY: '🇸🇾 سوريا', LB: '🇱🇧 لبنان', JO: '🇯🇴 الأردن', PS: '🇵🇸 فلسطين',
    SD: '🇸🇩 السودان', LY: '🇱🇾 ليبيا', TN: '🇹🇳 تونس', DZ: '🇩🇿 الجزائر', MA: '🇲🇦 المغرب', MR: '🇲🇷 موريتانيا',
    SO: '🇸🇴 الصومال', DJ: '🇩🇯 جيبوتي', KM: '🇰🇲 جزر القمر' };
  var CATS = { sports: 'رياضة', news: 'أخبار', general: 'عام', movies: 'أفلام', kids: 'أطفال', religious: 'ديني', music: 'موسيقى',
    entertainment: 'ترفيه', documentary: 'وثائقي', series: 'مسلسلات' };

  /** Free catalogue → categories [{id,name,items:[channel]}], Egypt first; sports & news shortcuts on top. */
  function loadFree(force) {
    var cached = load('free_cache', null);
    var fresh = cached && Date.now() - cached.at < 6 * 3600e3;
    var p = (fresh && !force) ? Promise.resolve(cached.data) :
      httpJson(CATALOG_URL, { timeout: 40000 }).then(function (d) { save('free_cache', { at: Date.now(), data: d }); return d; })
        .catch(function (e) { if (cached) return cached.data; throw e; });
    return p.then(function (d) {
      var groups = {}, order = [], sports = [], news = [];
      (d.channels || []).forEach(function (c) {
        var s = (c.s || [])[0]; if (!s || !/^https?:\/\//.test(s.u)) return;
        var ch = { name: c.n, url: s.u, logo: c.logo, ua: s.ua, status: c.st, quality: c.q, group: COUNTRY[c.c] || '🌍 عربي من الخارج' };
        if (!groups[ch.group]) { groups[ch.group] = []; order.push(ch.group); }
        groups[ch.group].push(ch);
        if ((c.cat || []).indexOf('sports') >= 0) sports.push(ch);
        if ((c.cat || []).indexOf('news') >= 0) news.push(ch);
      });
      var cats = [];
      if (sports.length) cats.push({ id: 'sports', name: '⚽ رياضة', items: sports });
      if (news.length) cats.push({ id: 'news', name: '📰 أخبار', items: news });
      order.forEach(function (g) { cats.push({ id: g, name: g, items: groups[g] }); });
      return cats;
    });
  }

  /** Plain M3U → categories in the file's own order. */
  function parseM3u(text) {
    var cats = [], byName = {}, cur = null;
    text.split(/\r?\n/).forEach(function (raw) {
      var line = raw.trim();
      if (line.indexOf('#EXTINF') === 0) {
        var attrs = {}; line.replace(/([\w-]+)="([^"]*)"/g, function (m, k, v) { attrs[k.toLowerCase()] = v; });
        var comma = line.lastIndexOf(',');
        cur = { name: (comma >= 0 ? line.slice(comma + 1) : '').trim() || 'قناة', logo: attrs['tvg-logo'], group: attrs['group-title'] || 'عام', ua: attrs['http-user-agent'] };
      } else if (line.indexOf('#EXTVLCOPT:http-user-agent=') === 0 && cur) {
        cur.ua = line.split('=').slice(1).join('=');
      } else if (/^https?:\/\//i.test(line)) {
        var ch = cur || { name: 'قناة', group: 'عام' }; ch.url = line;
        if (!byName[ch.group]) { byName[ch.group] = { id: ch.group, name: ch.group, items: [] }; cats.push(byName[ch.group]); }
        byName[ch.group].items.push(ch); cur = null;
      }
    });
    return cats;
  }

  // ------------------------------------------------------------------ data: Xtream (panel order, nothing filtered)
  var X = {
    api: function (p, action, extra) {
      return p.server + '/player_api.php?username=' + encodeURIComponent(p.user) + '&password=' + encodeURIComponent(p.pass) +
        (action ? '&action=' + action : '') + (extra || '');
    },
    login: function (p) {
      return httpJson(X.api(p), { timeout: 20000 }).then(function (j) {
        var u = j && j.user_info;
        if (!u) throw new Error('ده مش سيرفر Xtream، اتأكد من الرابط والبورت');
        if (String(u.auth) !== '1') throw new Error('اسم المستخدم أو الباسورد غلط');
        var exp = Number(u.exp_date) * 1000;
        if (String(u.status).toLowerCase() === 'expired' || (exp && exp < Date.now())) throw new Error('الاشتراك منتهي');
        return { exp: exp, max: u.max_connections };
      });
    },
    categories: function (p, kind) {
      var action = { live: 'get_live_categories', movies: 'get_vod_categories', series: 'get_series_categories' }[kind];
      return httpJson(X.api(p, action)).then(function (arr) {
        return (arr || []).map(function (c) { return { id: String(c.category_id), name: c.category_name || 'قسم' }; });
      });
    },
    items: function (p, kind, catId) {
      var action = { live: 'get_live_streams', movies: 'get_vod_streams', series: 'get_series' }[kind];
      return httpJson(X.api(p, action, '&category_id=' + encodeURIComponent(catId)), { timeout: 45000 }).then(function (arr) {
        var u = encodeURIComponent(p.user), w = encodeURIComponent(p.pass);
        return (arr || []).map(function (o) {
          if (kind === 'live') return { name: o.name, logo: o.stream_icon, id: o.stream_id, url: p.server + '/live/' + u + '/' + w + '/' + o.stream_id + '.' + (p.hls ? 'm3u8' : 'ts'), live: true };
          if (kind === 'movies') return { name: o.name, logo: o.stream_icon, rating: o.rating, url: p.server + '/movie/' + u + '/' + w + '/' + o.stream_id + '.' + (o.container_extension || 'mp4') };
          return { name: o.name, logo: o.cover, rating: o.rating, seriesId: o.series_id, plot: o.plot };
        });
      });
    },
    seriesInfo: function (p, s) {
      return httpJson(X.api(p, 'get_series_info', '&series_id=' + s.seriesId), { timeout: 30000 }).then(function (j) {
        var u = encodeURIComponent(p.user), w = encodeURIComponent(p.pass), seasons = {};
        function add(e, hint) {
          var sn = Number(e.season) || hint || 1;
          (seasons[sn] = seasons[sn] || []).push({ name: s.name + ' • م' + sn + ' ح' + (e.episode_num || ''), title: e.title, num: Number(e.episode_num) || 0,
            logo: (e.info && e.info.movie_image) || s.logo, url: p.server + '/series/' + u + '/' + w + '/' + e.id + '.' + (e.container_extension || 'mp4') });
        }
        var eps = j.episodes;
        if (Array.isArray(eps)) eps.forEach(function (list, i) { (list || []).forEach(function (e) { add(e, i + 1); }); });
        else if (eps) Object.keys(eps).forEach(function (k) { (eps[k] || []).forEach(function (e) { add(e, Number(k)); }); });
        Object.keys(seasons).forEach(function (k) { seasons[k].sort(function (a, b) { return a.num - b.num; }); });
        return { plot: (j.info && j.info.plot) || s.plot, seasons: seasons };
      });
    },
    shortEpg: function (p, streamId) {
      return httpJson(X.api(p, 'get_short_epg', '&stream_id=' + streamId + '&limit=2'), { timeout: 10000 }).then(function (j) {
        return (j.epg_listings || []).map(function (e) {
          return { title: b64(e.title), start: Number(e.start_timestamp) * 1000, stop: Number(e.stop_timestamp) * 1000 };
        });
      });
    },
  };

  // ------------------------------------------------------------------ navigation (spatial, remote arrows)
  var nav = {
    current: null,
    root: function () { return $('#app'); },
    all: function () { return $$('.f', nav.scope || nav.root()).filter(function (e) { return e.offsetParent !== null; }); },
    focus: function (el) {
      if (!el) return;
      if (nav.current) nav.current.classList.remove('focus');
      nav.current = el; el.classList.add('focus');
      ensureVisible(el);
      if (el.onfocus2) el.onfocus2();
    },
    first: function () { var a = nav.all(); nav.focus($('.f.start', nav.scope || nav.root()) || a[0]); },
    move: function (dir) {
      var cur = nav.current; if (!cur || !document.body.contains(cur)) { nav.first(); return; }
      var r = cur.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2, best = null, bestScore = 1e9;
      nav.all().forEach(function (el) {
        if (el === cur) return;
        var b = el.getBoundingClientRect(), main, side;
        // gap between the two boxes across the direction of travel (0 when they line up), so a wide
        // text box straight below wins over a small button further away
        function gap(a1, a2, b1, b2) { return a2 < b1 ? b1 - a2 : b2 < a1 ? a1 - b2 : 0; }
        if (dir === 'left') { if (b.right > r.left + 4) return; main = r.left - b.right; side = gap(r.top, r.bottom, b.top, b.bottom); }
        else if (dir === 'right') { if (b.left < r.right - 4) return; main = b.left - r.right; side = gap(r.top, r.bottom, b.top, b.bottom); }
        else if (dir === 'up') { if (b.bottom > r.top + 4) return; main = r.top - b.bottom; side = gap(r.left, r.right, b.left, b.right); }
        else { if (b.top < r.bottom - 4) return; main = b.top - r.bottom; side = gap(r.left, r.right, b.left, b.right); }
        var score = Math.max(0, main) + side * 3;
        if (score < bestScore) { bestScore = score; best = el; }
      });
      if (best) nav.focus(best);
      else if (nav.current && nav.current.onedge) nav.current.onedge(dir);
    },
  };

  /** Keeps a focused element inside its scrolling box (this browser has no scrollIntoView options). */
  function ensureVisible(el) {
    var box = el.parentNode;
    while (box && box !== document.body && !(box.classList && box.classList.contains('scroll'))) box = box.parentNode;
    if (!box || box === document.body) return;
    var top = el.offsetTop - box.offsetTop, bottom = top + el.offsetHeight;
    if (top < box.scrollTop + 20) box.scrollTop = Math.max(0, top - 20);
    else if (bottom > box.scrollTop + box.clientHeight - 20) box.scrollTop = bottom - box.clientHeight + 20;
    lazyImages(box);
    if (box.onscrolled) box.onscrolled();
  }
  function lazyImages(box) {
    $$('[data-bg]', box).forEach(function (el) {
      var t = el.offsetTop - box.offsetTop;
      if (t > box.scrollTop - 900 && t < box.scrollTop + box.clientHeight + 900) {
        el.style.backgroundImage = 'url("' + el.getAttribute('data-bg').replace(/"/g, '%22') + '")';
        el.removeAttribute('data-bg');
      }
    });
  }

  // ------------------------------------------------------------------ screens
  var screen = { name: 'home', back: null, key: null };
  function show(name, html, opts) {
    opts = opts || {};
    screen = { name: name, back: opts.back || null, key: opts.key || null };
    nav.scope = null; nav.current = null;
    $('#app').innerHTML = html;
    $$('.scroll').forEach(lazyImages);
  }
  function header(hint) {
    return '<div class="header"><div class="brand gold-text">المصري<span class="tv">TV</span></div>' +
      (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
  }
  var FOLDER_SVG = '<svg viewBox="0 0 24 20"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#9C7A1F"/><stop offset=".45" stop-color="#FFF4CC"/><stop offset="1" stop-color="#D4AF37"/></linearGradient></defs><path fill="url(#g)" d="M2 2h7l2 2.5h11a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H2a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"/></svg>';

  function kindOf(p) {
    if (p.type === 'free') return ['📡', 'قنوات مجانية'];
    if (p.type === 'xtream') return ['🎬', 'قنوات • أفلام • مسلسلات'];
    return ['📺', 'قنوات'];
  }

  // --- home: folders
  function home() {
    var list = playlists();
    var html = header('🔴 أحمر: مسح القائمة المحددة') + '<div class="folders">' + list.map(function (p, i) {
      var k = kindOf(p);
      return '<div class="folder f' + (i === 0 ? ' start' : '') + '" data-i="' + i + '">' + FOLDER_SVG + '<div class="emoji">' + k[0] + '</div>' +
        '<div class="name">' + esc(p.name) + '</div><div class="kind">' + k[1] + '</div></div>';
    }).join('') + '<div class="folder add f" data-add="1"><div class="plus">+</div><div class="name">قائمة جديدة</div><div class="kind">Xtream • M3U</div></div></div>';
    show('home', html, {
      key: function (code) {
        if (code === K.RED && nav.current && nav.current.getAttribute('data-i') != null) {
          var p = list[Number(nav.current.getAttribute('data-i'))];
          if (p.type === 'free') { toast('القنوات المجانية مش بتتمسح'); return true; }
          if (confirmTwice('del' + p.id, 'اضغط الأحمر تاني عشان تمسح "' + p.name + '"')) {
            savePlaylists(list.filter(function (x) { return x.id !== p.id; })); home();
          }
          return true;
        }
        return false;
      },
    });
    $$('.folder').forEach(function (el) {
      el.onenter = function () {
        if (el.getAttribute('data-add')) addForm();
        else browse(list[Number(el.getAttribute('data-i'))]);
      };
    });
    nav.first();
  }
  var confirmKey = null, confirmAt = 0;
  function confirmTwice(key, msg) {
    if (confirmKey === key && Date.now() - confirmAt < 4000) { confirmKey = null; return true; }
    confirmKey = key; confirmAt = Date.now(); toast(msg); return false;
  }

  // --- add playlist
  function addForm(kind) {
    kind = kind || 'xtream';
    var html = header() + '<div class="form"><div class="tabs" style="padding:0">' +
      '<div class="tab f' + (kind === 'xtream' ? ' on start' : '') + '" data-k="xtream">دخول Xtream</div>' +
      '<div class="tab f' + (kind === 'm3u' ? ' on start' : '') + '" data-k="m3u">رابط M3U</div></div>' +
      (kind === 'xtream'
        ? '<label>السيرفر (http://host:port)</label><input class="f" id="srv" placeholder="http://example.com:8080">' +
          '<label>Username</label><input class="f" id="usr"><label>Password</label><input class="f" id="pwd" type="password">'
        : '<label>رابط القائمة</label><input class="f" id="url" placeholder="https://.../playlist.m3u">') +
      '<label>الاسم (اختياري)</label><input class="f" id="nm" style="direction:rtl;text-align:right">' +
      '<div class="btns"><div class="btn primary f" id="ok">' + (kind === 'xtream' ? 'دخول' : 'ضيف') + '</div><div class="btn f" id="cancel">رجوع</div></div>' +
      '<div class="err" id="err"></div><div class="note">اضغط OK على أي خانة عشان الكيبورد يظهر.</div></div>';
    show('add', html, { back: home });
    $$('.tab').forEach(function (t) { t.onenter = function () { addForm(t.getAttribute('data-k')); }; });
    $('#cancel').onenter = home;
    $('#ok').onenter = function () {
      var err = $('#err'); err.textContent = '';
      var name = $('#nm').value.trim();
      if (kind === 'm3u') {
        var url = $('#url').value.trim();
        if (!/^https?:\/\//i.test(url)) { err.textContent = 'اكتب رابط يبدأ بـ http'; return; }
        err.textContent = 'بجرّب الرابط…';
        http(url, { timeout: 40000 }).then(function (t) {
          var n = parseM3u(t).reduce(function (a, c) { return a + c.items.length; }, 0);
          if (!n) throw new Error('الرابط مفيهوش قنوات');
          var list = playlists(); list.push({ id: uid(), name: name || 'قائمة M3U', type: 'm3u', url: url }); savePlaylists(list);
          toast('اتضافت ' + n + ' قناة ✓'); home();
        }).catch(function (e) { err.textContent = e.message; });
        return;
      }
      var p = { id: uid(), type: 'xtream', server: normalizeServer($('#srv').value), user: $('#usr').value.trim(), pass: $('#pwd').value.trim() };
      if (!p.server || !p.user || !p.pass) { err.textContent = 'اكتب السيرفر واليوزر والباسورد'; return; }
      err.textContent = 'بسجّل الدخول…';
      X.login(p).then(function (info) {
        p.name = name || p.server.replace(/^https?:\/\//, '');
        var list = playlists(); list.push(p); savePlaylists(list);
        toast('اتسجّل الدخول ✓' + (info.exp ? ' • ساري لحد ' + new Date(info.exp).toLocaleDateString('en-GB') : ''));
        home();
      }).catch(function (e) { err.textContent = e.message; });
    };
    nav.first();
  }
  function normalizeServer(s) {
    s = (s || '').trim(); if (!s) return '';
    if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
    var m = /^(https?:\/\/[^\/?#]+)/i.exec(s);
    return m ? m[1] : s;
  }

  // --- browser: categories (right) + content (left)
  function browse(p, kind) {
    kind = kind || 'live';
    var tabs = p.type === 'xtream' ? '<div class="tabs">' + [['live', '📡 بث مباشر'], ['movies', '🎬 أفلام'], ['series', '📺 مسلسلات']].map(function (t) {
      return '<div class="tab f' + (t[0] === kind ? ' on' : '') + '" data-k="' + t[0] + '">' + t[1] + '</div>';
    }).join('') + '</div>' : '';
    show('browse', header(esc(p.name)) + tabs + '<div class="browser" style="height:' + (tabs ? 850 : 930) + 'px">' +
      '<div class="cats scroll" style="height:100%"><div class="spinner"></div></div>' +
      '<div class="content scroll" style="height:100%"></div></div>', { back: home });
    $$('.tab').forEach(function (t) { t.onenter = function () { browse(p, t.getAttribute('data-k')); }; });

    var catsP = p.type === 'free' ? loadFree() : p.type === 'm3u' ? http(p.url, { timeout: 40000 }).then(parseM3u) : X.categories(p, kind);
    catsP.then(function (cats) {
      var box = $('.cats');
      if (!cats.length) { box.innerHTML = '<div class="empty">مفيش أقسام</div>'; return; }
      box.innerHTML = cats.map(function (c, i) {
        return '<div class="row f' + (i === 0 ? ' start' : '') + '" data-i="' + i + '">' + esc(c.name) +
          (c.items ? '<span class="count">' + c.items.length + '</span>' : '') + '</div>';
      }).join('');
      var selected = -1;
      $$('.cats .row').forEach(function (row) {
        var i = Number(row.getAttribute('data-i'));
        row.onfocus2 = function () {
          if (selected === i) return; selected = i;
          $$('.cats .row').forEach(function (r) { r.classList.remove('on'); }); row.classList.add('on');
          clearTimeout(browse.t); browse.t = setTimeout(function () { openCategory(p, kind, cats[i]); }, 250);
        };
        row.onenter = function () { var f = $('.content .f'); if (f) nav.focus(f); };
      });
      nav.focus($('.cats .row'));
    }).catch(function (e) { $('.cats').innerHTML = '<div class="empty">' + esc(e.message) + '<br><br>OK: جرّب تاني</div>'; $('.cats').firstChild.className += ' f'; $('.cats .empty').onenter = function () { browse(p, kind); }; nav.first(); });
  }

  /** Renders a category's channels (list) or movies/series (posters), in the source's order. */
  function openCategory(p, kind, cat) {
    var box = $('.content'); if (!box) return;
    box.scrollTop = 0;
    box.innerHTML = '<div class="spinner"></div>';
    var itemsP = cat.items ? Promise.resolve(cat.items) : X.items(p, kind, cat.id);
    itemsP.then(function (items) {
      if (!$('.content') || box !== $('.content')) return;
      if (!items.length) { box.innerHTML = '<div class="empty">القسم ده فاضي</div>'; return; }
      var poster = kind === 'movies' || kind === 'series';
      var CHUNK = poster ? 60 : 120, shown = 0;
      box.innerHTML = poster ? '<div class="posters"></div>' : '<div class="list"></div>';
      var holder = box.firstChild;
      function renderMore() {
        var html = '';
        for (var i = shown; i < Math.min(items.length, shown + CHUNK); i++) {
          var it = items[i];
          if (poster) {
            html += '<div class="poster f" data-i="' + i + '"><div class="img"' + (it.logo ? ' data-bg="' + esc(it.logo) + '"' : '') + '>' +
              (it.rating && it.rating !== '0' ? '<span class="rate">★ ' + esc(String(it.rating).slice(0, 3)) + '</span>' : '') + '</div><div class="t">' + esc(it.name) + '</div></div>';
          } else {
            var warn = it.status === 'blocked' ? '<span class="badge warn">ممكن مقفولة في بعض الدول</span>' :
              it.status === 'offline' ? '<span class="badge warn">واقعة في آخر فحص</span>' : '';
            html += '<div class="item f" data-i="' + i + '"><div class="num">' + pad(i + 1, 3) + '</div>' +
              '<div class="logo"' + (it.logo ? ' data-bg="' + esc(it.logo) + '"' : '') + '>' + (it.logo ? '' : esc((it.name || '?').charAt(0))) + '</div>' +
              '<div class="title">' + esc(it.name) + (it.quality ? '<span class="badge">' + esc(it.quality) + '</span>' : '') + warn + '</div></div>';
          }
        }
        holder.insertAdjacentHTML('beforeend', html);
        shown = Math.min(items.length, shown + CHUNK);
        $$('.f', holder).forEach(function (el) {
          if (el.onenter) return;
          var i = Number(el.getAttribute('data-i'));
          el.onenter = function () {
            if (kind === 'series') seriesPage(p, items[i], function () { browse(p, kind); });
            else if (kind === 'movies') play(items, i, { vod: true, back: function () { browse(p, kind); } });
            else play(items, i, { live: true, p: p, back: function () { browse(p, kind); } });
          };
          el.onedge = function (dir) { if (dir === 'right') { var r = $('.cats .row.on'); if (r) nav.focus(r); } };
        });
        lazyImages(box);
      }
      box.onscrolled = function () { if (shown < items.length && box.scrollTop + box.clientHeight > box.scrollHeight - 900) renderMore(); };
      renderMore();
    }).catch(function (e) { box.innerHTML = '<div class="empty">مقدرتش أحمّل: ' + esc(e.message) + '</div>'; });
  }

  // --- series page
  function seriesPage(p, s, back) {
    show('series', header(esc(s.name)) + '<div class="spinner"></div>', { back: back });
    X.seriesInfo(p, s).then(function (d) {
      var seasons = Object.keys(d.seasons).map(Number).sort(function (a, b) { return a - b; });
      var html = header() + '<div class="series-head"><div class="img"' + (s.logo ? ' style="background-image:url(\'' + esc(s.logo).replace(/'/g, '%27') + '\')"' : '') + '></div>' +
        '<div class="info"><h1>' + esc(s.name) + '</h1><p>' + esc(or(d.plot, '')) + '</p></div></div>' +
        '<div class="seasons">' + seasons.map(function (n, i) { return '<div class="tab f' + (i === 0 ? ' on start' : '') + '" data-s="' + n + '">الموسم ' + n + '</div>'; }).join('') + '</div>' +
        '<div class="episodes scroll"></div>';
      show('series', html, { back: back });
      function showSeason(n) {
        var eps = d.seasons[n] || [];
        $$('.seasons .tab').forEach(function (t) { t.classList.toggle('on', Number(t.getAttribute('data-s')) === n); });
        $('.episodes').innerHTML = eps.length ? eps.map(function (e, i) {
          return '<div class="item f" data-i="' + i + '"><div class="num">' + pad(e.num, 2) + '</div><div class="title">' + esc(e.title || e.name) + '</div></div>';
        }).join('') : '<div class="empty">مفيش حلقات</div>';
        $$('.episodes .item').forEach(function (el) {
          var i = Number(el.getAttribute('data-i'));
          el.onenter = function () { play(eps, i, { vod: true, series: true, back: function () { seriesPage(p, s, back); } }); };
        });
      }
      $$('.seasons .tab').forEach(function (t) { var n = Number(t.getAttribute('data-s')); t.onfocus2 = function () { showSeason(n); }; t.onenter = function () { var f = $('.episodes .f'); if (f) nav.focus(f); }; });
      if (seasons.length) showSeason(seasons[0]); else $('.episodes').innerHTML = '<div class="empty">المسلسل ده مفيهوش حلقات على السيرفر</div>';
      nav.first();
    }).catch(function (e) { $('#app').innerHTML = header() + '<div class="empty">مقدرتش أفتح المسلسل: ' + esc(e.message) + '</div>'; });
  }

  // ------------------------------------------------------------------ player (AVPlay on the TV, <video> in a browser)
  var hasAV = typeof window.webapis !== 'undefined' && window.webapis && window.webapis.avplay;
  var AV = hasAV ? window.webapis.avplay : null;
  var video = $('#html-player'), avEl = $('#av-player');
  var P = { list: [], i: 0, opts: {}, timer: null, saveTimer: null, osdTimer: null, zap: '', zapTimer: null, panel: false, error: null, buffering: false };

  var engine = {
    open: function (url, ua, startMs, onReady, onError, onEnd) {
      engine.stop();
      if (AV) {
        avEl.style.display = 'block';
        try {
          AV.open(url);
          AV.setDisplayRect(0, 0, 1920, 1080);
          try { AV.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX'); } catch (e) { /* older firmware */ }
          if (ua) { try { AV.setStreamingProperty('USER_AGENT', ua); } catch (e) { /* not supported */ } }
          // Start quickly (3 s), but refill more (8 s) after a stall so a shaky line doesn't stutter again at once.
          try { AV.setBufferingParam('PLAYER_BUFFER_FOR_PLAY', 'PLAYER_BUFFER_SIZE_IN_SECOND', 3); } catch (e) { /* older firmware */ }
          try { AV.setBufferingParam('PLAYER_BUFFER_FOR_RESUME', 'PLAYER_BUFFER_SIZE_IN_SECOND', 8); } catch (e) { /* older firmware */ }
          try { AV.setTimeoutForBuffering(15); } catch (e) { /* older firmware */ }
          AV.setListener({
            onbufferingstart: function () { P.buffering = true; renderOsdState(); },
            onbufferingcomplete: function () { P.buffering = false; renderOsdState(); },
            onstreamcompleted: function () { onEnd(); },
            onerror: function (e) { onError(String(e)); },
            oncurrentplaytime: function () { /* polled instead */ },
          });
          AV.prepareAsync(function () {
            AV.play();
            if (startMs > 30000) { try { AV.seekTo(startMs); } catch (e) { /* live */ } }
            onReady();
          }, function (e) { onError(String(e)); });
        } catch (e) { onError(String(e && e.name || e)); }
      } else {
        video.style.display = 'block';
        video.onerror = function () { onError('MEDIA_ERR'); };
        video.onended = onEnd;
        video.onwaiting = function () { P.buffering = true; renderOsdState(); };
        video.onplaying = function () { P.buffering = false; renderOsdState(); };
        video.src = url;
        video.oncanplay = function () { if (startMs > 30000) video.currentTime = startMs / 1000; video.oncanplay = null; onReady(); };
        var pr = video.play(); if (pr && pr.catch) pr.catch(function () {});
      }
    },
    stop: function () {
      if (AV) { try { AV.stop(); } catch (e) {} try { AV.close(); } catch (e) {} avEl.style.display = 'none'; }
      video.onerror = video.onended = video.onwaiting = video.onplaying = video.oncanplay = null;
      try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) {}
      video.style.display = 'none';
    },
    toggle: function () {
      if (AV) { var st = AV.getState(); if (st === 'PLAYING') AV.pause(); else if (st === 'PAUSED') AV.play(); }
      else if (video.paused) video.play(); else video.pause();
    },
    time: function () { try { return AV ? AV.getCurrentTime() : video.currentTime * 1000; } catch (e) { return 0; } },
    duration: function () { try { return AV ? AV.getDuration() : (isFinite(video.duration) ? video.duration * 1000 : 0); } catch (e) { return 0; } },
    seekBy: function (ms) {
      try {
        if (AV) { if (ms > 0) AV.jumpForward(ms); else AV.jumpBackward(-ms); }
        else video.currentTime = Math.max(0, video.currentTime + ms / 1000);
      } catch (e) { /* live streams can't seek */ }
    },
  };

  function play(list, i, opts) {
    P.list = list; P.opts = opts || {}; P.panel = false; P.zap = '';
    document.body.classList.add('playing');
    show('player', '<div id="osd"></div><div id="zapbox"></div><div id="panelbox"></div><div id="msg"></div>', { back: closePlayer, key: playerKey });
    startItem(i);
  }
  // ---- stability: quiet auto-reconnect, .ts <-> .m3u8 fallback, stall watchdog
  var MAX_RETRIES = 4;
  /** The other container for Xtream live links (/live/u/p/id.ts <-> .m3u8); null when there isn't one. */
  function altUrl(url) {
    if (!/\/live\/[^/]+\/[^/]+\/\d+\.(ts|m3u8)(\?|$)/.test(url)) return null;
    return /\.ts(\?|$)/.test(url) ? url.replace(/\.ts(\?|$)/, '.m3u8$1') : url.replace(/\.m3u8(\?|$)/, '.ts$1');
  }
  function startItem(i) {
    if (!P.list.length) return;
    P.i = (i + P.list.length) % P.list.length;
    P.retries = 0; P.useAlt = false;
    openCurrent(P.opts.vod ? Number(load('pos', {})[P.list[P.i].url] || 0) : 0);
  }
  /** (Re)opens the current item. Callbacks from an older attempt are ignored via the session token. */
  function openCurrent(resume) {
    var ch = P.list[P.i];
    var token = P.session = (P.session || 0) + 1;
    var alt = P.useAlt && altUrl(ch.url);
    P.error = null; P.buffering = true; P.epg = P.epg && P.epgFor === ch ? P.epg : null;
    P.stall = 0; P.lastT = -1;
    renderOsdState();
    engine.open(alt || ch.url, ch.ua, resume,
      function () { if (token !== P.session) return; P.buffering = false; renderOsdState(); },
      function (e) { if (token === P.session) failed(e); },
      function () { if (token !== P.session) return; if (P.opts.series && P.i < P.list.length - 1) startItem(P.i + 1); else if (P.opts.vod) { savePos(true); closePlayer(); } });
    clearInterval(P.watch);
    P.watch = setInterval(watchdog, 2000);
    afterOpen(ch);
  }
  /** Playback failed or stalled: retry quietly a few times, switching container once, before showing the error. */
  function failed(e) {
    if (P.retries < MAX_RETRIES) {
      P.retries++;
      if (P.retries === 2 && altUrl(P.list[P.i].url)) P.useAlt = !P.useAlt;
      var pos = P.opts.vod ? engine.time() : 0;
      P.error = null; P.buffering = true; P.reconnecting = true; renderOsdState();
      clearTimeout(P.retryTimer);
      P.retryTimer = setTimeout(function () { P.reconnecting = false; openCurrent(pos); }, 1000 * P.retries);
      return;
    }
    clearInterval(P.watch);
    P.error = e; P.buffering = false; P.reconnecting = false; renderOsdState(); showOsd();
  }
  /** Every 2 s: if the picture hasn't moved for 20 s (stuck buffering, frozen stream), reconnect. */
  function watchdog() {
    if (P.error || P.reconnecting || !P.list.length) return;
    var t = engine.time();
    if (AV) { try { if (AV.getState() === 'PAUSED') { P.stall = 0; return; } } catch (e) {} } else if (video.paused && !P.buffering) { P.stall = 0; return; }
    if (t !== P.lastT) { P.lastT = t; P.stall = 0; if (t > 0 && !P.buffering) P.retries = 0; return; }
    P.stall += 2;
    if (P.stall >= 20) { P.stall = 0; failed('STALLED'); }
  }
  function afterOpen(ch) {
    if (P.opts.live && P.opts.p && P.opts.p.type === 'xtream' && ch.id && P.epgFor !== ch) {
      P.epgFor = ch;
      X.shortEpg(P.opts.p, ch.id).then(function (list) { if (P.list[P.i] === ch) { P.epg = list; renderOsd(); } }).catch(function () {});
    }
    clearInterval(P.saveTimer);
    if (P.opts.vod) P.saveTimer = setInterval(function () { savePos(false); renderSeek(); }, 1000);
    showOsd();
  }
  function savePos(finished) {
    var ch = P.list[P.i]; if (!ch) return;
    var pos = load('pos', {}), t = engine.time(), d = engine.duration();
    if (d > 0) { pos[ch.url] = (finished || t > d - 60000) ? 0 : t; save('pos', pos); }
  }
  function closePlayer() {
    if (P.opts.vod) savePos(false);
    clearInterval(P.saveTimer); clearTimeout(P.osdTimer); clearInterval(P.watch); clearTimeout(P.retryTimer);
    P.session = (P.session || 0) + 1; P.reconnecting = false; P.epgFor = null;
    engine.stop();
    document.body.classList.remove('playing');
    var back = P.opts.back; P.opts = {};
    (back || home)();
  }

  function showOsd() {
    renderOsd();
    clearTimeout(P.osdTimer);
    if (!P.error) P.osdTimer = setTimeout(function () { var o = $('#osd'); if (o && !P.panel) o.innerHTML = ''; renderKeys(false); }, 5000);
  }
  function renderOsd() {
    var o = $('#osd'); if (!o) return;
    var ch = P.list[P.i], now = Date.now(), epg = '';
    if (P.epg && P.epg.length) {
      var cur = P.epg.filter(function (e) { return now >= e.start && now < e.stop; })[0];
      var next = P.epg.filter(function (e) { return e.start > now; })[0];
      if (cur) epg += 'الآن: ' + esc(cur.title);
      if (next) epg += (epg ? '  •  ' : '') + 'بعده ' + hhmm(next.start) + ': ' + esc(next.title);
      if (cur) epg += '<div class="bar"><i style="width:' + Math.min(100, Math.max(0, (now - cur.start) / (cur.stop - cur.start) * 100)) + '%"></i></div>';
    }
    o.innerHTML = '<div class="osd"><div class="logo"' + (ch.logo ? ' style="background-image:url(\'' + esc(ch.logo).replace(/'/g, '%27') + '\')"' : '') + '></div><div>' +
      '<div class="name">' + esc(ch.name) + '</div><div class="meta">' + (P.opts.live ? pad(P.i + 1, 3) + ' / ' + P.list.length : '') + '</div>' +
      (epg ? '<div class="epg">' + epg + '</div>' : '') + '</div></div>' + (P.opts.vod ? '<div class="seekbar" id="seek"></div>' : '');
    renderSeek(); renderOsdState(); renderKeys(true);
  }
  function renderSeek() {
    var s = $('#seek'); if (!s) return;
    var t = engine.time(), d = engine.duration();
    s.innerHTML = d > 0 ? '<span>' + clock(t) + '</span><div class="track"><i style="width:' + (t / d * 100) + '%"></i></div><span>' + clock(d) + '</span>' : '';
  }
  function renderOsdState() {
    var m = $('#msg'); if (!m) return;
    if (P.error) m.innerHTML = '<div class="center-msg">القناة مش شغالة دلوقتي<br><span style="font-size:26px;color:var(--muted)">OK: جرّب تاني' + (P.opts.live ? ' • ⬇: القناة اللي بعدها' : '') + '</span><small>' + esc(P.error) + '</small></div>';
    else if (P.reconnecting) m.innerHTML = '<div class="spinner" style="margin-top:470px"></div><div class="reconnect">بيعيد الاتصال… (' + P.retries + '/' + MAX_RETRIES + ')</div>';
    else if (P.buffering) m.innerHTML = '<div class="spinner" style="margin-top:470px"></div>';
    else m.innerHTML = '';
  }
  function renderKeys(on) {
    var o = $('#osd'); if (!o) return;
    var k = o.querySelector('.keys'); if (k) k.parentNode.removeChild(k);
    if (!on) return;
    o.insertAdjacentHTML('beforeend', '<div class="keys">' + (P.opts.live
      ? '<b>▲▼</b> / <b>CH</b> قناة  <b>OK</b> / <b>◀</b> قائمة القنوات  <b>0-9</b> رقم القناة  <b>رجوع</b> خروج'
      : '<b>◀ ▶</b> ±10 ثواني  <b>▲ ▼</b> ±دقيقة  <b>OK</b> إيقاف/تشغيل  <b>رجوع</b> خروج') + '</div>');
  }

  // receiver-style channel list inside the player
  function openPanel() {
    P.panel = true; P.panelI = P.i; renderPanel();
  }
  function renderPanel() {
    var box = $('#panelbox'); if (!box) return;
    if (!P.panel) { box.innerHTML = ''; return; }
    var from = Math.max(0, P.panelI - 8), to = Math.min(P.list.length, from + 17);
    var html = '';
    for (var i = from; i < to; i++) {
      var c = P.list[i];
      html += '<div class="item' + (i === P.panelI ? ' focus f' : '') + (i === P.i ? ' playing' : '') + '" style="' + (i === P.i ? 'color:var(--gold-light)' : '') + '">' +
        '<div class="num">' + pad(i + 1, 3) + '</div><div class="title">' + esc(c.name) + '</div></div>';
    }
    box.innerHTML = '<div class="panel"><h3 class="gold-text">' + esc(or(P.list[P.i].group, 'القنوات')) + '</h3><div class="list">' + html + '</div></div>';
  }

  function playerKey(code) {
    if (P.panel) {
      if (code === K.UP) { P.panelI = (P.panelI - 1 + P.list.length) % P.list.length; renderPanel(); return true; }
      if (code === K.DOWN) { P.panelI = (P.panelI + 1) % P.list.length; renderPanel(); return true; }
      if (code === K.ENTER) { P.panel = false; renderPanel(); startItem(P.panelI); return true; }
      if (code === K.BACK || code === K.ESC || code === K.RIGHT) { P.panel = false; renderPanel(); return true; }
      return true;
    }
    if (code >= 48 && code <= 57 && P.opts.live) {
      P.zap = (P.zap + (code - 48)).slice(0, 4);
      $('#zapbox').innerHTML = '<div class="zap gold-text">' + P.zap + '</div>';
      clearTimeout(P.zapTimer);
      P.zapTimer = setTimeout(commitZap, P.zap.length >= 4 ? 300 : 1800);
      return true;
    }
    if (P.error && code === K.ENTER) { startItem(P.i); return true; }
    if (P.opts.live) {
      if (code === K.UP || code === K.CH_UP) { P.prev = P.i; startItem(P.i - 1); return true; }
      if (code === K.DOWN || code === K.CH_DOWN) { P.prev = P.i; startItem(P.i + 1); return true; }
      if (code === K.ENTER || code === K.LEFT || code === K.CH_LIST) {
        if (code === K.ENTER && !$('#osd .osd')) { showOsd(); return true; }
        openPanel(); return true;
      }
      if (code === K.PREV_CH && P.prev != null) { var t = P.i; startItem(P.prev); P.prev = t; return true; }
      if (code === K.INFO) { showOsd(); return true; }
    } else {
      if (code === K.LEFT || code === K.RW) { engine.seekBy(-10000); showOsd(); return true; }
      if (code === K.RIGHT || code === K.FF) { engine.seekBy(10000); showOsd(); return true; }
      if (code === K.DOWN) { engine.seekBy(-60000); showOsd(); return true; }
      if (code === K.UP) { engine.seekBy(60000); showOsd(); return true; }
      if (code === K.ENTER) { engine.toggle(); showOsd(); return true; }
    }
    if (code === K.PLAY_PAUSE || code === K.PLAY || code === K.PAUSE) { engine.toggle(); showOsd(); return true; }
    if (code === K.STOP) { closePlayer(); return true; }
    return false;
  }
  function commitZap() {
    var n = Number(P.zap); P.zap = ''; $('#zapbox').innerHTML = '';
    if (n >= 1 && n <= P.list.length) { P.prev = P.i; startItem(n - 1); }
    else toast('مفيش قناة رقم ' + n);
  }

  // ------------------------------------------------------------------ keys
  function registerKeys() {
    if (!(window.tizen && tizen.tvinputdevice)) return;
    ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'ChannelUp', 'ChannelDown', 'MediaPlayPause', 'MediaPlay', 'MediaPause',
      'MediaStop', 'MediaFastForward', 'MediaRewind', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue', 'Info',
      'PreviousChannel', 'ChannelList'].forEach(function (k) { try { tizen.tvinputdevice.registerKey(k); } catch (e) { /* key not on this remote */ } });
  }

  var exitArmed = 0;
  document.addEventListener('keydown', function (e) {
    var code = e.keyCode;
    var active = document.activeElement;
    // Typing in a text box: let the TV keyboard work; Done/Cancel/Back returns to remote navigation.
    if (active && active.tagName === 'INPUT') {
      if (code === K.IME_DONE || code === K.IME_CANCEL || code === K.BACK || code === K.ESC || code === K.ENTER) {
        active.blur(); e.preventDefault();
      }
      return;
    }
    if (screen.key && screen.key(code)) { e.preventDefault(); return; }
    switch (code) {
      case K.LEFT: nav.move('left'); break;
      case K.RIGHT: nav.move('right'); break;
      case K.UP: nav.move('up'); break;
      case K.DOWN: nav.move('down'); break;
      case K.ENTER:
        var el = nav.current;
        if (el && el.tagName === 'INPUT') el.focus();
        else if (el && el.onenter) el.onenter();
        break;
      case K.BACK: case K.ESC: case K.BACKSPACE:
        if (screen.back) screen.back();
        else if (Date.now() - exitArmed < 3000) { try { tizen.application.getCurrentApplication().exit(); } catch (x) { /* browser */ } }
        else { exitArmed = Date.now(); toast('اضغط رجوع تاني عشان تخرج'); }
        break;
      default: return;
    }
    e.preventDefault();
  });

  // Pause when the TV switches to another app / source; resume when back.
  document.addEventListener('visibilitychange', function () {
    if (screen.name !== 'player') return;
    try {
      if (document.hidden) { if (AV) AV.suspend(); else video.pause(); }
      else if (AV) AV.restore(); else video.play();
    } catch (e) { /* ignore */ }
  });

  registerKeys();
  home();
  window.__almasry = { nav: nav, home: home, parseM3u: parseM3u, normalizeServer: normalizeServer }; // for tests
})();
