/* DSH PET · WebView 控制台 */
(function () {
  'use strict';

  var S = {
    boot: null, cfg: {}, persona: {}, profile: {}, catalog: [], memories: [], companionRules: [],
    reminders: [], ad: {}, adLines: [], chat: [], chatCursor: -1, chatHasMore: false,
    chatLoadingEarlier: false, perms: {}, state: {}, qqGuideTab: 'pc',
    appSel: null, gameSel: null, gameQuery: '', freeActionSel: null, freeActionQuery: '', page: 'home', openCats: {}, notifySel: null,
    featureQuery: '', featureFavs: [], featureRecent: [], featureLoaded: false,
    actQuery: '', sending: false, balance: '', balanceVision: '', balOff: false, balVOff: false, adStats: {}, asrText: '', asrOn: false, asrPending: false,
    voiceInputMode: false, voiceHoldActive: false, voiceHoldSend: false, asrFinishTimer: null,
    policy: {}, policyJson: {}, health: [], plugins: [], dshbox: null, astrbotConfig: null, astrbotApiPlugins: null, astrbotApiMarket: null, astrbotApiMarketPage: 1, astrbotMarketKeyword: '', astrbotInstallPending: null, astrbotInstallResult: null, astrbotMobileBridgeStatus: null, tasks: [], taskFocus: {},
    taskSummary: {}, events: [], notif: {}, notifGroups: [], notifAi: {}, geo: {}, well: {}, wellSteps: [],
    musicx: {}, sync: {}, tts: {}, voices: [], clips: [], moods: [], memSummary: {}, memReviewDue: false,
    memItems: [], memReview: [], memGraphPendingIds: [], memGraphPendingAt: 0, memGraphFilter: 'all', report: {}, backupPreview: null, migrations: [], dev: {}, adLog: [], adTestResult: null,
    memType: 'all', memShowArchived: 0, memQuery: '',
    intimacy: {}, traits: {}, diary: {}, timeline: {}, caring: {},
    projects: [], momentFilter: 'all', recap: {}, recapPeriod: '7d', recapAi: '', diaryPeek: false,
    contract: {}, emotion: {}, typingLine: '', replyStarted: false, iceHint: true,
    navStack: [], scrollPos: {}, books: [], bookStats: {}, bookCfg: {}, shelfTab: 'recent', shelfSort: 'recent',
    shelfQuery: '', book: {}, bookChapter: 0, readerPanel: '', readerSearch: '', readerResults: [], readerNotes: [],
    readerSel: null, pdfCache: {}, autoTimer: null, readerSaveAt: 0, readerSec: 0, readerHistory: [], readerRestore: 0,
    readerSearchTerm: '', ttsPlaying: false, autoOn: false, ttsTimerMin: 0, ttsTimer: null, bookMenuId: '', autoOpenLast: false,
    readerTimer: null, readerBars: 0, readerDrawer: 0, readerBarTimer: null, readerFade: false, readerDrag: false,
    readerPage: 0, readerPages: [], readerAnchor: 0, readerFlipping: false, readerJumpTerm: '',
    lastTab: 'home', familyMaxSaid: false, showOnboarding: 0, resumeOnboarding: 0,
    personaSeed: {}, presets: null, obStep: 1, obSel: null, seedUpgrade: 0, usage: null,
    ledger: null, ledCat: '餐饮', ledType: 'expense',
    monitor: null, monitorPkg: '', monitorPkgName: '', monitorText: '',
    skins: [], skinId: '', chatImg: '', chatFile: null, chatVideo: null, live2dOk: true,
    qqPersonas: [], qqPersonaSelected: '', chatQuote: null, userProfileDraft: null,
    chatHeaderCollapsed: false,
    streamId: '', streamText: '', streamSuppressed: false, streamRenderedCount: 0, streamDraftCut: false,
    streamBubbleNodes: [], streamPartialNode: null, streamPreviewShown: false, cancelledStreamId: ''
  };

  var _noPush = false;
  var TABS = { home: 1, chat: 1, actions: 1, companion: 1, settings: 1 };
  var PAGE_NAMES = {
    chat: '聊天', actions: '动作台词', companion: '情境陪伴', core: '智能核心', hub: '功能中心', qq: '个人 QQ 连接', qqGuide: 'QQ 连接教程',
    sg: 'S-G 数据', persona: '角色与记忆', adskip: '广告跳过', reminders: '提醒中心',
    policy: '主动记录', health: '权限体检', clip: '剪贴板', backup: '备份与迁移',
    tasks: '任务与专注', mem: '记忆图谱', memGraph: '记忆识海', relation: '我们的关系', report: '陪伴周报',
    notif: '通知摘要', geo: '位置提醒', wellness: '健康动作', musicx: '音乐陪伴',
    plugins: '插件', stickers: '表情包', usage: '今日使用', ledger: '记账',
    monitor: '应用感知', sync: '跨设备同步', readerShelf: '阅读书架', dev: '开发者模式',
    dress: '角色换装',
    settings: '设置', userProfile: '我的资料'
  };
  try { S.chatHeaderCollapsed = localStorage.getItem('chatHeaderCollapsed') === '1'; } catch (_) { }
  var seq = 0;
  var pending = {};
  var _memGraphLoadId = 0;
  var _memGraphWatchdog = 0;
  var _memGraphOpeningStarfield = null;
  var _memGraphOpeningMinTimer = 0;
  var _memGraphOpeningMaxTimer = 0;
  var _memGraphOpeningFadeTimer = 0;
  var _memGraphOpeningStatusTimers = [];
  var _memGraphOpeningReady = false;
  var _memGraphOpeningMinDone = false;
  var _memGraphOpeningVideo = null;
  var _memGraphOpeningClipEnd = 0;
  var _memGraphOpeningVideoFinished = false;
  var _memGraphOpeningVideoWatchTimer = 0;
  var _memGraphRecordFilter = 'all';
  var _memGraphSelectedRecord = '';

  function $(s) { return document.querySelector(s); }
  function $$(s) { return Array.prototype.slice.call(document.querySelectorAll(s)); }
  function destroyMemoryGraphSafely() {
    if (!window.DshMemoryGraph || typeof window.DshMemoryGraph.destroy !== 'function') return;
    try { window.DshMemoryGraph.destroy(); }
    catch (e) { try { if (window.console && console.error) console.error('识海清理失败', e); } catch (_) { } }
  }
  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function num(v, d) { v = parseInt(v, 10); return isNaN(v) ? d : v; }
  function flt(v, d) { v = parseFloat(v); return isNaN(v) ? d : v; }
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function fmt(ts) {
    if (!ts) return '';
    var d = new Date(ts);
    return (d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function toast(msg) {
    var t = $('#toast');
    if (!t) return;
    t.textContent = msg;
    t.className = 'show';
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { t.className = ''; }, 2600);
  }
  function native(method, args) {
    return new Promise(function (resolve, reject) {
      var id = String(++seq);
      pending[id] = resolve;
      try {
        if (!window.Dsh) throw new Error('原生桥未就绪');
        window.Dsh.call(id, method, JSON.stringify(args || {}));
      } catch (e) {
        delete pending[id];
        reject(e);
        return;
      }
      setTimeout(function () {
        if (pending[id]) { delete pending[id]; reject(new Error('操作超时')); }
      }, method === 'chat' ? 210000 : 90000);
    });
  }
  function call(method, args) {
    return native(method, args).then(function (r) {
      if (r && r.ok === false) throw new Error(r.error || '操作失败');
      return r || {};
    });
  }
  window.__nativeResult = function (id, res) {
    var f = pending[id];
    if (f) { delete pending[id]; f(res || {}); }
  };
  /* 表情图 file:// 读不到时，向原生要一份 base64 兜底 */
  window.__stickerFallback = function (img) {
    var name = img.getAttribute('data-name') || '';
    if (!name || img.getAttribute('data-fb')) return;
    img.setAttribute('data-fb', '1');
    call('stickerData', { name: name }).then(function (r) {
      if (r && r.data) img.src = r.data;
      else img.style.display = 'none';
    }).catch(function () { img.style.display = 'none'; });
  };
  /* 自定义皮肤在私有目录，file:// 可能被 WebView 拦截；失败时向原生要 PNG base64 */
  window.__skinFallback = function (img) {
    var id = img.getAttribute('data-skin') || '';
    if (!id || img.getAttribute('data-fb')) return;
    img.setAttribute('data-fb', '1');
    var thumb = img.getAttribute('data-thumb') === '1' ? 1 : 0;
    call('skinData', { id: id, thumb: thumb }).then(function (r) {
      if (r && r.data) img.src = r.data;
      else img.style.display = 'none';
    }).catch(function () { img.style.display = 'none'; });
  };
  function skinImgAttrs(id, thumb) {
    return ' data-skin="' + esc(id) + '" data-thumb="' + (thumb ? 1 : 0) + '" onerror="__skinFallback(this)"';
  }

  /* ---------------- 渲染分发 ---------------- */
  var _renderQueued = false;
  var _pendingRender = false;
  var _selectOpen = false;
  var _healthMore = false;
  var _lastRenderPage = '';
  var _chatPageCache = null;
  function render() {
    if (_renderQueued) return;
    // 原生下拉弹层开着时重建整页会立刻把弹层关掉，先挂起，等选完或失焦再刷
    if (_selectOpen) {
      _pendingRender = true;
      return;
    }
    _renderQueued = true;
    setTimeout(function () { _renderQueued = false; renderNow(); }, 16);
  }
  function flushPendingRender() {
    _selectOpen = false;
    clearTimeout(_selTimer);
    if (!_pendingRender) return;
    _pendingRender = false;
    render();
  }
  var _selTimer = null;
  function markSelectOpen() {
    _selectOpen = true;
    clearTimeout(_selTimer);
    // 系统没给失焦事件时自己松开，避免页面一直不刷新
    _selTimer = setTimeout(flushPendingRender, 15000);
  }
  function renderNow() {
    var main = $('#main');
    if (!main) return;
    var previousRenderedPage = _lastRenderPage;
    var pageChanged = S.page !== _lastRenderPage;
    if (S.page !== 'memGraph') {
      _memGraphLoadId++;
      clearTimeout(_memGraphWatchdog);
      stopMemoryGraphOpening();
    }
    applyMoodTheme();
    // 首次表单：没填过则强制展示；跳过过的（pending）先让用户正常用，首页保留补填入口
    var ct = S.contract || {};
    if ((ct.isSet === false && !ct.pending) || S.showOnboarding) {
      updateTop();
      _lastRenderPage = 'onboarding';
      document.body.classList.remove('mem-graph-open');
      destroyMemoryGraphSafely();
      main.innerHTML = '<section class="page onboarding-page">' + renderOnboarding() + '</section>';
      $$('#nav button').forEach(function (b) { b.classList.remove('active'); });
      return;
    }
    updateTop();
    // 静态识海画布在页面内保留；无关状态更新不销毁重建，以免清掉当前选中状态。
    if (S.page === 'memGraph' && !pageChanged) return;
    _lastRenderPage = S.page;
    var keep = main.scrollTop;
    var html = '';
    if (S.page === 'home') html = renderHome();
    else if (S.page === 'chat') html = renderChat();
    else if (S.page === 'actions') html = renderActions();
    else if (S.page === 'companion') html = renderCompanion();
    else if (S.page === 'settings') html = renderSettings();
    else if (S.page === 'qq') html = renderQQ();
    else if (S.page === 'qqGuide') html = renderQQGuide();
    else if (S.page === 'core') html = renderCore();
    else if (S.page === 'hub') html = renderHub();
    else if (S.page === 'sg') html = renderSg();
    else if (S.page === 'persona') html = renderPersona();
    else if (S.page === 'userProfile') html = renderUserProfile();
    else if (S.page === 'adskip') html = renderAd();
    else if (S.page === 'reminders') html = renderReminders();
    else if (S.page === 'policy') html = renderPolicy();
    else if (S.page === 'health') html = renderHealth();
    else if (S.page === 'clip') html = renderClip();
    else if (S.page === 'backup') html = renderBackup();
    else if (S.page === 'tasks') html = renderTasks();
    else if (S.page === 'mem') html = renderMem();
    else if (S.page === 'memGraph') html = renderMemGraph();
    else if (S.page === 'relation') html = renderRelation();
    else if (S.page === 'report') html = renderReport();
    else if (S.page === 'notif') html = renderNotif();
    else if (S.page === 'geo') html = renderGeo();
    else if (S.page === 'wellness') html = renderWellness();
    else if (S.page === 'musicx') html = renderMusicx();
    else if (S.page === 'plugins') html = renderPlugins();
    else if (S.page === 'stickers') html = renderStickers();
    else if (S.page === 'usage') html = renderUsage();
    else if (S.page === 'ledger') html = renderLedger();
    else if (S.page === 'monitor') html = renderMonitor();
    else if (S.page === 'dress') html = renderDress();
    else if (S.page === 'sync') html = renderSync();
    else if (S.page === 'readerShelf') html = renderReaderShelf();
    else if (S.page === 'reader') html = renderReader();
    else if (S.page === 'dev') html = renderDev();
    else html = renderHome();
    // 离开聊天页时暂存原 DOM。恢复时沿用已经解码的表情图片，避免切回页面闪一下。
    if (pageChanged && previousRenderedPage === 'chat') {
      var currentChatPage = main.querySelector('.page.chat-fixed');
      if (currentChatPage) _chatPageCache = currentChatPage;
    }
    destroyMemoryGraphSafely();
    if (pageChanged && S.page === 'chat' && _chatPageCache) {
      main.innerHTML = '';
      main.appendChild(_chatPageCache);
      _chatPageCache = null;
    } else {
      main.innerHTML = '<section class="page' + (pageChanged ? '' : ' still') + (S.page === 'chat' ? ' chat-fixed' : '') + (S.page === 'reader' ? ' reader-fixed' : '') + '">' + html + '</section>';
    }
    // 聊天页底部留白跟着输入条实际高度走，避免最后一条消息被输入框盖住
    document.body.classList.toggle('chat-page', S.page === 'chat');
    document.body.classList.toggle('mem-graph-open', S.page === 'memGraph');
    if (S.page === 'memGraph') {
      startMemoryGraphOpening();
      setTimeout(mountMemoryGraph, 0);
    }
    if (S.page === 'chat') fitChatTail();
    // 子页面没有自己的底部 tab：保持进入前所在的主 tab 高亮，不要跳回首页
    if (TABS[S.page]) S.lastTab = S.page;
    var navTab = TABS[S.page] ? S.page : (S.lastTab || 'home');
    $$('#nav button').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-tab') === navTab);
    });
    if (pageChanged) {
      document.body.classList.toggle('reader-mode', S.page === 'reader');
    }
    if (S.page !== 'chat' && S.page !== 'reader') {
      var target = pageChanged ? (S.scrollPos[S.page] || 0) : keep;
      var restorePage = S.page;
      main.scrollTop = target;
      setTimeout(function () {
        var m = $('#main');
        if (m && S.page === restorePage && restorePage !== 'chat' && restorePage !== 'reader') m.scrollTop = target;
      }, 0);
    } else if (S.page === 'reader') {
      main.scrollTop = 0;
      setTimeout(bindReader, 0);
    }
    if (S.page === 'chat') {
      setTimeout(function () {
        var m = $('#msgs');
        if (m) m.scrollTop = m.scrollHeight;
        var ci = $('#chatInput');
        if (ci) autoGrow(ci);
      }, 30);
    }
    if (S.page === 'home') eqStart();
    else eqStop();
  }

  /* ---------------- 首页音浪：跟随真实频谱 ---------------- */
  var eqTimer = null;
  var eqFakePhase = 0;

  function eqStart() {
    if (!document.querySelectorAll('#eqBars i').length || !(S.state.music || {}).playing) {
      eqStop();
      return;
    }
    if (eqTimer) return;
    eqTimer = setInterval(eqStep, 140);
    eqStep();
  }

  function eqStop() {
    if (eqTimer) { clearInterval(eqTimer); eqTimer = null; }
    var bars = document.querySelectorAll('#eqBars i');
    for (var i = 0; i < bars.length; i++) bars[i].style.height = '';
  }

  function eqStep() {
    var bars = document.querySelectorAll('#eqBars i');
    if (!bars.length || !(S.state.music || {}).playing) { eqStop(); return; }
    call('wave').then(function (r) {
      var lv = (r && r.levels) || [];
      if (r && r.on && lv.length) eqPaint(eqSpread(lv, bars.length));
      else eqPaint(eqFake(bars.length));
    }).catch(function () { eqPaint(eqFake(bars.length)); });
  }

  /** 把 7 段频谱插值成卡片上的若干根条 */
  function eqSpread(lv, n) {
    var out = [];
    if (n <= 1) return [num(lv[0], 0)];
    for (var i = 0; i < n; i++) {
      var p = i / (n - 1) * (lv.length - 1);
      var i0 = Math.floor(p), i1 = Math.min(lv.length - 1, i0 + 1);
      var f = p - i0;
      out.push(num(lv[i0], 0) * (1 - f) + num(lv[i1], 0) * f);
    }
    return out;
  }

  /** 没有频谱权限时按播放进度起伏，保证还在跟着歌动 */
  function eqFake(n) {
    eqFakePhase += 0.24;
    var out = [];
    for (var i = 0; i < n; i++) {
      var v = Math.sin(eqFakePhase + i * 0.55) * 0.5 + Math.sin(eqFakePhase * 1.7 + i * 0.23) * 0.5;
      out.push(38 + v * 34);
    }
    return out;
  }

  function eqPaint(list) {
    var bars = document.querySelectorAll('#eqBars i');
    for (var i = 0; i < bars.length; i++) {
      var v = Math.max(0, Math.min(100, list[i] || 0));
      bars[i].style.height = Math.round(4 + v / 100 * 20) + 'px';
    }
  }

  function balText(prefix, txt, off, key) {
    if (txt && txt.indexOf('不可用') < 0) {
      var t = txt.indexOf('余额') === 0 ? txt.substring(2).trim() : txt;
      return prefix + ' ' + t;
    }
    if (off) return prefix + ' 见控制台';
    return key ? prefix + ' --' : '未绑定 Key';
  }

  /** 文本/视觉两个模型解析出的余额查询地址；相同就只显示一条余额。 */
  function balTarget(vision) {
    var base = (vision ? S.cfg.visionBaseUrl : S.cfg.baseUrl) || 'https://api.deepseek.com';
    base = String(base).replace(/\/+$/, '');
    var path = String((vision ? S.cfg.visionBalancePath : S.cfg.balancePath) || '').trim();
    if (!path) {
      var h = base.toLowerCase();
      path = h.indexOf('moonshot') >= 0 ? '/users/me/balance'
        : h.indexOf('siliconflow') >= 0 ? '/user/info'
        : h.indexOf('openrouter') >= 0 ? '/credits' : '/user/balance';
    }
    if (path.indexOf('http') === 0) return path;
    return base + (path.charAt(0) === '/' ? path : '/' + path);
  }
  function sameBal() { return balTarget(true) === balTarget(false); }

  function updateTop() {
    var chip = $('#chipBalance');
    var same = sameBal();
    if (chip) {
      chip.textContent = same ? balText('', S.balance, S.balOff, S.cfg.apiKey).trim()
        : balText('文本', S.balance, S.balOff, S.cfg.apiKey);
      chip.className = 'chip' + (S.balance ? ' ok' : (S.cfg.apiKey ? '' : ' bad'));
    }
    var vchip = $('#chipBalanceVision');
    if (vchip) {
      vchip.style.display = same ? 'none' : '';
      var hasV = !!(S.cfg.visionApiKey || S.cfg.apiKey);
      vchip.textContent = balText('视觉', S.balanceVision, S.balVOff, hasV);
      vchip.className = 'chip' + (S.balanceVision ? ' ok' : (hasV ? '' : ' bad'));
    }
  }

  var _balPending = false;
  var _balVPending = false;
  function balUnsupported(msg) {
    return msg.indexOf('没有公开') >= 0 || msg.indexOf('不支持') >= 0;
  }
  function refreshBalance() {
    if (_balPending || !S.cfg.apiKey || S.balOff) return;
    _balPending = true;
    call('balance').then(function (r) {
      S.balance = r.text || '';
      updateTop();
    }).catch(function (e) {
      if (balUnsupported((e && e.message) || '')) { S.balOff = true; S.balance = ''; updateTop(); }
    })
      .then(function () { _balPending = false; });
  }
  function refreshVisionBalance() {
    if (_balVPending || S.balVOff || !(S.cfg.visionApiKey || S.cfg.apiKey)) return;
    _balVPending = true;
    call('balanceVision').then(function (r) {
      S.balanceVision = r.text || '';
      updateTop();
    }).catch(function (e) {
      if (balUnsupported((e && e.message) || '')) { S.balVOff = true; S.balanceVision = ''; updateTop(); }
    })
      .then(function () { _balVPending = false; });
  }
  function refreshAllBalance() {
    refreshBalance();
    // 两个模型同一个余额地址：只查一次
    if (!sameBal()) refreshVisionBalance();
  }

  function pageHead(title, sub, back) {
    return '<div class="page-head"><div class="head-back">' +
      (back ? '<button class="back-btn" data-act="back"><svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg></button>' : '') +
      '<div><h1>' + esc(title) + '</h1>' + (sub ? '<p>' + esc(sub) + '</p>' : '') + '</div></div></div>';
  }

  function switchHtml(key, on, act, sm) {
    return '<label class="switch ' + (sm ? 'sm' : '') + '"><input type="checkbox" data-key="' + key + '" data-act="' + (act || 'toggleCfg') + '" ' + (on ? 'checked' : '') + '><span class="track"></span></label>';
  }

  /* ---------------- 临时情绪展示 ---------------- */
  function moodCls(m) {
    if (m === '开心') return 'mint';
    if (m === '想你' || m === '害羞') return 'pink';
    if (m === '生气') return 'red';
    if (m === '低落') return 'violet';
    return '';
  }
  function moodLine() {
    var e = S.emotion || {};
    var m = e.mood || '平静';
    var s = num(e.moodScore, 0);
    if (Math.abs(s) <= 15) m = '平静';
    return '<div class="mood-line"><span class="tag ' + moodCls(m) + '">此刻 · ' + esc(m) + '</span></div>';
  }
  /* 极光背景跟着情绪走：开心偏紫粉、低落偏冷蓝，给潜意识一点反馈 */
  function applyMoodTheme() {
    var e = S.emotion || {};
    var m = e.mood || '平静';
    if (Math.abs(num(e.moodScore, 0)) <= 15) m = '平静';
    if (document.body.getAttribute('data-mood') !== m) document.body.setAttribute('data-mood', m);
  }

  /* ---------------- 首页 ---------------- */
  function renderHome() {
    var running = !!(S.cfg.running && S.state.running);
    var locked = !!S.cfg.locked;
    var name = S.persona.name || '蓝色大肥鱼';
    var days = S.state.days || 1;
    var ims = num((S.intimacy || {}).score, 0);
    var rems = S.reminders || [];
    var music = S.state.music || {};
    var hh = new Date().getHours();
    var hi = hh < 5 ? '夜深了' : hh < 11 ? '早上好' : hh < 13 ? '中午好' : hh < 18 ? '下午好' : '晚上好';
    var userName = String((S.profile || {}).nickname || '').trim();
    var greeting = hi + (userName ? '，' + userName : '');
    var html = '<div class="page-head"><div><h1><button class="home-greeting" data-act="profileOpen" title="编辑我的头像和名字">' + esc(greeting) +
      '<span class="greeting-edit" aria-hidden="true">✎</span></button></h1><p>你的桌宠伙伴正在本地陪着你</p></div>' +
      '<span class="tag ' + (running ? 'mint' : '') + '"><span class="dot ' + (running ? 'on' : 'off') + '"></span>' + (running ? '我来啦！' : '我走了~') + '</span></div>';

    // 首次表单跳过过：在宠物卡片上方保留「继续填写」，保存后消失
    if (S.contract && S.contract.isSet === false && S.contract.pending) {
      html += '<button class="resume-onboard" data-act="resumeOnboarding">' +
        '<span class="ro-icon">✎</span>' +
        '<span class="ro-main"><b>继续填写你们的关系</b><span>上次跳过了，现在可以补上；保存后不可更改</span></span>' +
        '<em>去填写 ›</em></button>';
    }

    html += '<div class="card hero">' +
      '<button class="hero-ocean" data-act="memGraphOpen">识海</button>' +
      '<div class="hero-top">' +
      '<div class="pet-orb skin-orb"><img src="' + esc(skinThumb()) + '"' + skinImgAttrs(S.skinId || 'original', 1) + ' alt="pet"></div>' +
      '<div class="hero-info"><h2>' + esc(name) + '</h2>' +
      '<div class="hero-sub">陪伴 <b>' + days + '</b> 天 · 亲密 <b>' + ims + '</b></div>' + moodLine() +
      '<button class="hero-skin" data-act="openPage" data-page="dress"><span>' + esc(skinName()) + '</span><em>换装 ›</em></button></div></div>';
    html += '<div class="hero-sliders">' +
      '<div class="row"><div><div class="rt">大小</div><div class="rs">' + num(S.cfg.sizeDp, 150) + ' dp</div></div>' +
      '<div class="slider-wrap"><input class="slider" id="sizeRange" type="range" min="80" max="360" step="5" value="' + num(S.cfg.sizeDp, 150) + '"></div></div>' +
      '<div class="row"><div><div class="rt">不透明度</div><div class="rs">' + Math.max(50, Math.round(num(S.cfg.opacity, 1) * 100)) + '%</div></div>' +
      '<div class="slider-wrap"><input class="slider" id="opacityRange" type="range" min="50" max="100" step="5" value="' + Math.max(50, Math.round(num(S.cfg.opacity, 1) * 100)) + '"></div></div>' +
      '<div class="row"><div><div class="rt">点击音效</div><div class="rs">点她时轻轻叫一声</div></div>' +
      switchHtml('petSound', S.cfg.petSound !== false, 'toggleCfg') + '</div></div>';
    html += '<div class="hero-actions">' +
      (running ? '<button class="btn ghost" data-act="stopPet">收起来</button>' : '<button class="btn ghost" data-act="startPet">放出来</button>') +
      '<button class="btn primary" data-act="chatTab">和她聊聊</button>' +
      '<button class="btn ghost" data-act="toggleLock">' + (locked ? '解锁' : '锁定位置') + '</button>' +
      '<button class="btn ghost" data-act="openPage" data-page="plugins">插件</button></div></div>';

    var coreMissing = [];
    (S.health || []).forEach(function (h) {
      if (!h.granted && (h.id === 'overlay' || h.id === 'notif' || h.id === 'battery')) coreMissing.push(h);
    });
    if (coreMissing.length) {
      html += '<div class="notice violet">还差 ' + coreMissing.length + ' 项设置，她才能稳定陪着你。<button class="btn sm" style="margin-left:8px" data-act="openPage" data-page="health">去检查</button></div>';
    }

    var pol = S.policy || {};
    var nf = S.notif || {};
    var fsum = S.taskSummary || {};
    var todo = 0;
    (S.tasks || []).forEach(function (t) { if (t.status !== 'done') todo++; });
    var missing = [];
    (S.health || []).forEach(function (h) { if (!h.granted) missing.push(h.title || h.id || '权限'); });
    var unread = num(nf.unread, 0);

    var bars = '';
    for (var b = 0; b < 20; b++) bars += '<i></i>';
    var eq = '<span class="eq' + (music.playing ? ' on' : '') + '" id="eqBars">' + bars + '</span>';
    var more = '<div class="quick3">' +
      '<button class="qcard music" data-act="openPage" data-page="musicx">' + eq +
      '<strong>音乐</strong><small>' + (music.playing ? esc(music.title || '正在播放') : '听歌时我会哼') + '</small></button>' +
      '<button class="qcard" data-act="openPage" data-page="persona">' +
      '<span class="qico violet"><svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.4"/><path d="M5 20c.8-4 3.6-6 7-6s6.2 2 7 6"/></svg></span>' +
      '<strong>角色与记忆</strong><small>人设 · 回忆</small></button>' +
      '<button class="qcard" data-act="openPage" data-page="tasks">' +
      '<span class="qico mint"><svg viewBox="0 0 24 24"><path d="M5 7h14M5 12h9M5 17h6"/><circle cx="18" cy="16" r="3"/></svg></span>' +
      '<strong>任务与专注</strong><small>' + (todo ? todo + ' 项待办' : '日程 · 番茄钟') + '</small></button>' +
      '</div>';

    more += '<div class="card"><div class="card-title"><h3>今日</h3><span>' + modeName(pol.mode || 'normal') +
      (pol.quiet ? ' · 静音' : '') + '</span></div>' +
      '<div class="mini">' +
      // 不再暴露「主动次数 x/8」这种配额计数器：她主动开口由亲密度在后台决定，
      // 摆一个额度条出来会让人出戏（数字生命不该有游戏化配额）
      '<button class="mi" data-act="openPage" data-page="tasks"><b>' + todo + '</b><span>待办任务</span></button>' +
      '<button class="mi" data-act="openPage" data-page="notif"><b>' + unread + '</b><span>未读通知</span></button>' +
      '<button class="mi" data-act="openPage" data-page="tasks"><b>' + num(fsum.minutes, 0) + '</b><span>专注分钟</span></button>' +
      '</div>' +
      '<div class="verdict">' +
      (pol.focus ? '<span class="tag violet">专注模式</span>' : '') +
      (pol.game ? '<span class="tag amber">游戏静默</span>' : '') +
      (pol.night ? '<span class="tag">夜间轻打扰</span>' : '') +
      (missing.length
        ? '<span class="tag pink" data-act="openPage" data-page="health">' + esc(missing[0]) + '待开启' + (missing.length > 1 ? ' +' + (missing.length - 1) : '') + '</span>'
        : '<span class="tag mint">权限齐全</span>') +
      '</div></div>';

    if (!S.cfg.apiKey) {
      html += '<div class="notice violet">还没有绑定 DeepSeek API Key。绑定后可解锁真 AI 聊天、主动陪伴、动作 AI 台词与余额查询。<button class="btn sm" style="margin-left:8px" data-act="openPage" data-page="core">去绑定</button></div>';
    }

    var pending = rems.length;
    html += '<div class="card"><div class="card-title"><h3>提醒</h3><span>' + pending + ' 条待处理</span></div>';
    if (pending === 0) html += '<div class="empty">没有新提醒。未读消息、快递、出行会自动汇总到这里。</div>';
    else {
      html += '<div class="list">';
      rems.slice(-3).reverse().forEach(function (r) {
        html += '<div class="list-item"><div class="li-ico">' + reminderIcon(r.type) + '</div><div class="li-main"><b>' + esc(r.title) + '</b><span>' + esc(r.detail) + '</span></div><div class="li-act">' + fmt(r.ts) + '</div></div>';
      });
      html += '</div><button class="btn ghost block sm" data-act="openReminders">查看全部提醒</button>';
    }
    html += '</div>';

    html += '<details class="more-wrap"><summary>更多：今日统计 · 音乐 · 任务 · 角色</summary>' + more + '</details>';

    html += '<div class="faint" style="text-align:center;padding:12px 0 4px">所有记忆、人设与聊天记录保存在 ' + esc(storageShort()) + '<br>换机时导出备份即可完整带走</div>';
    return html;
  }

  /* ---------------- 陪伴阅读 ---------------- */
  var TRAD_MAP = {'万':'萬','与':'與','专':'專','业':'業','东':'東','丝':'絲','两':'兩','严':'嚴','个':'個','临':'臨','为':'為','丽':'麗','举':'舉','义':'義','乌':'烏','乐':'樂','习':'習','乡':'鄉','书':'書','买':'買','乱':'亂','争':'爭','于':'於','亏':'虧','云':'雲','亚':'亞','产':'產','亲':'親','亿':'億','仅':'僅','从':'從','仓':'倉','仪':'儀','们':'們','价':'價','众':'眾','优':'優','会':'會','传':'傳','伤':'傷','体':'體','余':'餘','侠':'俠','侣':'侶','俭':'儉','儿':'兒','党':'黨','兰':'蘭','关':'關','兴':'興','养':'養','兽':'獸','内':'內','冈':'岡','写':'寫','军':'軍','农':'農','冲':'衝','决':'決','况':'況','冻':'凍','净':'淨','减':'減','凤':'鳳','凭':'憑','划':'劃','刘':'劉','则':'則','刚':'剛','创':'創','删':'刪','别':'別','剧':'劇','劝':'勸','办':'辦','务':'務','动':'動','劳':'勞','势':'勢','勋':'勳','医':'醫','华':'華','协':'協','单':'單','卖':'賣','卫':'衛','却':'卻','厂':'廠','历':'歷','压':'壓','厌':'厭','县':'縣','发':'發','变':'變','叶':'葉','号':'號','叹':'嘆','后':'後','吗':'嗎','听':'聽','启':'啟','员':'員','呜':'嗚','咏':'詠','响':'響','哑':'啞','唤':'喚','团':'團','园':'園','围':'圍','图':'圖','圆':'圓','圣':'聖','场':'場','坏':'壞','块':'塊','坚':'堅','坛':'壇','坟':'墳','坠':'墜','声':'聲','壮':'壯','壶':'壺','处':'處','备':'備','复':'復','够':'夠','头':'頭','夸':'誇','夹':'夾','夺':'奪','奋':'奮','奖':'獎','妆':'妝','妇':'婦','妈':'媽','娱':'娛','婴':'嬰','孙':'孫','学':'學','宁':'寧','宝':'寶','实':'實','宠':'寵','审':'審','宪':'憲','宫':'宮','宽':'寬','宾':'賓','对':'對','寻':'尋','导':'導','寿':'壽','将':'將','尔':'爾','尘':'塵','尽':'盡','层':'層','屡':'屢','岁':'歲','岗':'崗','岛':'島','币':'幣','师':'師','帅':'帥','帐':'帳','带':'帶','帮':'幫','广':'廣','庄':'莊','庆':'慶','库':'庫','应':'應','庙':'廟','开':'開','异':'異','弃':'棄','张':'張','弹':'彈','归':'歸','当':'當','录':'錄','彻':'徹','径':'徑','忆':'憶','忧':'憂','怀':'懷','态':'態','怜':'憐','总':'總','恋':'戀','恶':'惡','闷':'悶','惊':'驚','惯':'慣','愿':'願','懒':'懶','戏':'戲','战':'戰','户':'戶','扑':'撲','执':'執','扩':'擴','扫':'掃','扬':'揚','扰':'擾','抚':'撫','抛':'拋','抢':'搶','护':'護','报':'報','担':'擔','拟':'擬','拢':'攏','择':'擇','挂':'掛','挤':'擠','挥':'揮','损':'損','换':'換','据':'據','掷':'擲','摇':'搖','撑':'撐','撒':'撒','撵':'攆','播':'播','擅':'擅','攒':'攢','敌':'敵','敛':'斂','数':'數','斋':'齋','斗':'鬥','斩':'斬','断':'斷','无':'無','旧':'舊','时':'時','旷':'曠','昙':'曇','显':'顯','晋':'晉','晒':'曬','晓':'曉','晕':'暈','晖':'暉','暂':'暫','术':'術','机':'機','杀':'殺','杂':'雜','权':'權','条':'條','来':'來','杨':'楊','极':'極','构':'構','枪':'槍','柜':'櫃','标':'標','样':'樣','树':'樹','档':'檔','桥':'橋','梦':'夢','检':'檢','椭':'橢','楼':'樓','概':'概','横':'橫','欢':'歡','欧':'歐','歼':'殲','残':'殘','殴':'毆','毁':'毀','毕':'畢','气':'氣','汇':'匯','汉':'漢','汤':'湯','沟':'溝','没':'沒','沥':'瀝','沦':'淪','沪':'滬','泪':'淚','泼':'潑','泽':'澤','洁':'潔','洒':'灑','洗':'洗','浅':'淺','浆':'漿','浊':'濁','测':'測','济':'濟','浏':'瀏','浑':'渾','浓':'濃','涛':'濤','涝':'澇','润':'潤','涨':'漲','涩':'澀','淀':'澱','渊':'淵','渐':'漸','渔':'漁','渗':'滲','温':'溫','湾':'灣','湿':'濕','溃':'潰','滚':'滾','滞':'滯','满':'滿','滤':'濾','滨':'濱','滩':'灘','潇':'瀟','潜':'潛','潮':'潮','潭':'潭','澜':'瀾','灭':'滅','灯':'燈','灵':'靈','灾':'災','灿':'燦','炉':'爐','点':'點','炼':'煉','烂':'爛','烛':'燭','烟':'煙','烦':'煩','烧':'燒','热':'熱','焕':'煥','爱':'愛','爷':'爺','牍':'牘','牵':'牽','犹':'猶','狈':'狽','狞':'獰','独':'獨','狮':'獅','狱':'獄','狭':'狹','猎':'獵','猪':'豬','猫':'貓','献':'獻','猴':'猴','玛':'瑪','环':'環','现':'現','玫':'玫','玮':'瑋','矿':'礦','码':'碼','砖':'磚','础':'礎','硕':'碩','确':'確','碍':'礙','礼':'禮','祸':'禍','禄':'祿','离':'離','秃':'禿','种':'種','积':'積','称':'稱','稀':'稀','穷':'窮','窍':'竅','窗':'窗','窝':'窩','竞':'競','笔':'筆','笼':'籠','筑':'築','筛':'篩','签':'簽','简':'簡','算':'算','管':'管','箱':'箱','箭':'箭','篇':'篇','米':'米','类':'類','粮':'糧','紧':'緊','絮':'絮','红':'紅','约':'約','级':'級','纪':'紀','纯':'純','纳':'納','纵':'縱','纷':'紛','纸':'紙','纹':'紋','纺':'紡','纽':'紐','线':'線','练':'練','组':'組','细':'細','织':'織','终':'終','绍':'紹','经':'經','绑':'綁','绒':'絨','结':'結','绕':'繞','绘':'繪','给':'給','络':'絡','绝':'絕','绞':'絞','统':'統','绻':'綣','继':'繼','绩':'績','续':'續','绳':'繩','维':'維','绵':'綿','综':'綜','绿':'綠','缀':'綴','缆':'纜','缓':'緩','编':'編','缘':'緣','缚':'縛','缠':'纏','缩':'縮','缴':'繳','网':'網','罗':'羅','罚':'罰','罢':'罷','羁':'羈','翘':'翹','翻':'翻','老':'老','耻':'恥','联':'聯','聪':'聰','肃':'肅','肠':'腸','肤':'膚','肾':'腎','肿':'腫','胀':'脹','胁':'脅','胆':'膽','胜':'勝','胧':'朧','胶':'膠','脉':'脈','脆':'脆','脏':'髒','脐':'臍','脑':'腦','脓':'膿','脱':'脫','脸':'臉','腊':'臘','腐':'腐','腔':'腔','膜':'膜','腾':'騰','腿':'腿','膀':'膀','舰':'艦','舱':'艙','艱':'艱','艺':'藝','节':'節','芦':'蘆','苍':'蒼','苏':'蘇','苗':'苗','若':'若','荐':'薦','荒':'荒','荡':'蕩','荣':'榮','药':'藥','莱':'萊','莲':'蓮','获':'獲','莹':'瑩','萧':'蕭','营':'營','著':'著','蓝':'藍','藏':'藏','虎':'虎','虏':'虜','虑':'慮','虫':'蟲','虽':'雖','蚀':'蝕','蚁':'蟻','蝇':'蠅','蝉':'蟬','融':'融','补':'補','衬':'襯','袭':'襲','裁':'裁','装':'裝','裤':'褲','见':'見','观':'觀','规':'規','视':'視','览':'覽','觉':'覺','誉':'譽','讠':'訁','计':'計','订':'訂','认':'認','讨':'討','让':'讓','训':'訓','议':'議','讯':'訊','记':'記','讲':'講','许':'許','论':'論','设':'設','访':'訪','证':'證','评':'評','识':'識','诉':'訴','词':'詞','译':'譯','试':'試','诗':'詩','诚':'誠','话':'話','诞':'誕','询':'詢','该':'該','详':'詳','语':'語','误':'誤','说':'說','请':'請','诸':'諸','读':'讀','课':'課','谁':'誰','调':'調','谈':'談','谋':'謀','谎':'謊','谜':'謎','谢':'謝','谣':'謠','谦':'謙','谨':'謹','谱':'譜','谷':'谷','负':'負','贡':'貢','财':'財','责':'責','贤':'賢','败':'敗','货':'貨','质':'質','贩':'販','贪':'貪','贫':'貧','购':'購','贯':'貫','贱':'賤','贴':'貼','贵':'貴','贷':'貸','贸':'貿','费':'費','贺':'賀','贼':'賊','赁':'賃','资':'資','赋':'賦','赌':'賭','赏':'賞','赐':'賜','赔':'賠','赚':'賺','赛':'賽','赞':'讚','赠':'贈','赢':'贏','赶':'趕','趋':'趨','趟':'趟','车':'車','轨':'軌','轩':'軒','转':'轉','轮':'輪','软':'軟','轻':'輕','载':'載','较':'較','辅':'輔','辆':'輛','辈':'輩','辉':'輝','辑':'輯','输':'輸','辖':'轄','辛':'辛','辩':'辯','边':'邊','达':'達','迁':'遷','过':'過','运':'運','还':'還','这':'這','进':'進','远':'遠','违':'違','连':'連','迟':'遲','适':'適','选':'選','逊':'遜','递':'遞','通':'通','逛':'逛','逝':'逝','逗':'逗','速':'速','造':'造','逢':'逢','逼':'逼','逻':'邏','遇':'遇','遗':'遺','遥':'遙','遛':'遛','遮':'遮','遵':'遵','避':'避','邀':'邀','邓':'鄧','邮':'郵','邹':'鄒','邻':'鄰','郁':'鬱','郑':'鄭','酱':'醬','释':'釋','里':'裏','钉':'釘','针':'針','钓':'釣','钙':'鈣','钝':'鈍','钟':'鐘','钢':'鋼','钥':'鑰','钦':'欽','钩':'鉤','钱':'錢','钻':'鑽','铁':'鐵','铃':'鈴','铅':'鉛','银':'銀','铺':'鋪','链':'鏈','销':'銷','锁':'鎖','锅':'鍋','锋':'鋒','错':'錯','锡':'錫','锦':'錦','键':'鍵','镇':'鎮','镜':'鏡','长':'長','门':'門','闭':'閉','问':'問','闯':'闖','闲':'閒','间':'間','闷':'悶','闻':'聞','阀':'閥','阁':'閣','阅':'閱','队':'隊','阴':'陰','阵':'陣','阶':'階','阻':'阻','阿':'阿','附':'附','陆':'陸','陈':'陳','降':'降','限':'限','陕':'陝','院':'院','除':'除','险':'險','随':'隨','隐':'隱','难':'難','雏':'雛','雾':'霧','需':'需','静':'靜','非':'非','韩':'韓','音':'音','页':'頁','顶':'頂','项':'項','顺':'順','须':'須','顾':'顧','顿':'頓','预':'預','领':'領','颇':'頗','颈':'頸','频':'頻','颗':'顆','题':'題','额':'額','风':'風','飘':'飄','飞':'飛','食':'食','饭':'飯','饮':'飲','饱':'飽','饿':'餓','馆':'館','首':'首','香':'香','马':'馬','骑':'騎','驱':'驅','验':'驗','骂':'罵','骄':'驕','骆':'駱','骗':'騙','骨':'骨','高':'高','鱼':'魚','鲜':'鮮','鸟':'鳥','鸡':'雞','鸣':'鳴','鸭':'鴨','鸽':'鴿','鹅':'鵝','鹰':'鷹','鹿':'鹿','麦':'麥','黄':'黃','黑':'黑','默':'默'};
  function toTrad(s) {
    var out = '';
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      out += TRAD_MAP[c] || c;
    }
    return out;
  }
  /* 阅读主题：纯白 / 米黄（护眼）/ 浅灰 / 深色，另加「跟随系统」。
     旧版主题值会做一次迁移：paper、eye 都并入米黄护眼。 */
  var READER_THEMES = {
    white: { bg: '#fbfbfb', fg: '#20242c', sub: '#747d8c' },
    sepia: { bg: '#efe6d5', fg: '#3a3128', sub: '#7d7161' },
    gray: { bg: '#e9ebef', fg: '#2a2f3a', sub: '#6b7280' },
    night: { bg: '#0b0e16', fg: '#cfd8ee', sub: '#7d89a3' }
  };
  var READER_THEME_LIST = [
    ['white', '纯白'], ['sepia', '米黄'], ['gray', '浅灰'], ['night', '深色'], ['system', '跟随系统']
  ];
  /** 行距三档快捷值（滑块仍可连续微调） */
  var READER_LINES = [[1.5, '紧凑'], [1.85, '标准'], [2.15, '宽松']];
  /** 翻页动画：默认顺滑平移；3D 翻页保留为柔和完整翻页选项 */
  var READER_ANIMS = [['flip', '柔和仿真'], ['slide', '顺滑平移'], ['fade', '淡入'], ['none', '无动画']];
  function readerThemeFix(name) {
    if (name === 'paper' || name === 'eye') return 'sepia';
    if (name === 'system' || READER_THEMES[name]) return name;
    return 'night';
  }
  function readerTheme(name) {
    name = readerThemeFix(name);
    if (name === 'system') {
      try { name = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : 'white'; }
      catch (e) { name = 'night'; }
    }
    return READER_THEMES[name] || READER_THEMES.night;
  }
  function readerIsDark() {
    var t = readerThemeFix(rcfg().theme);
    if (t === 'system') {
      try { return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches); }
      catch (e) { return true; }
    }
    return t === 'night';
  }
  function fmtSec(sec) {
    sec = num(sec, 0);
    if (sec < 60) return sec + ' 秒';
    if (sec < 3600) return Math.round(sec / 60) + ' 分钟';
    return (Math.round(sec / 360) / 10) + ' 小时';
  }
  function bookFrac() {
    // 分页阅读：用当前页比例；PDF 仍然按滚动位置
    if (S.book && S.book.format !== 'PDF') {
      var n = (S.readerPages || []).length;
      if (!n) return 0;
      return n <= 1 ? 0 : Math.max(0, Math.min(1, S.readerPage / (n - 1)));
    }
    var sc = $('#readerScroll');
    if (!sc) return 0;
    var h = sc.scrollHeight - sc.clientHeight;
    return h <= 0 ? 0 : Math.max(0, Math.min(1, sc.scrollTop / h));
  }
  function bookPercent() {
    var n = Math.max(1, (S.book.chapters || []).length);
    return Math.max(0, Math.min(1, (S.bookChapter + bookFrac()) / n));
  }
  function renderReaderShelf() {
    var stats = S.bookStats || {};
    var books = (S.books || []).slice();
    if (S.shelfTab === 'hidden') books = books.filter(function (b) { return b.hidden; });
    else books = books.filter(function (b) { return !b.hidden; });
    var q = (S.shelfQuery || '').trim().toLowerCase();
    if (q) {
      books = books.filter(function (b) {
        return (b.title || '').toLowerCase().indexOf(q) >= 0 || (b.author || '').toLowerCase().indexOf(q) >= 0
          || (b.group || '').toLowerCase().indexOf(q) >= 0;
      });
    }
    if (S.shelfTab === 'finished') books = books.filter(function (b) { return b.finished; });
    if (S.shelfSort === 'title') books.sort(function (a, b) { return (a.title || '').localeCompare(b.title || ''); });
    else if (S.shelfSort === 'added') books.sort(function (a, b) { return num(b.addedAt, 0) - num(a.addedAt, 0); });
    else if (S.shelfSort === 'progress') books.sort(function (a, b) { return parseFloat(b.progress && b.progress.percent || 0) - parseFloat(a.progress && a.progress.percent || 0); });
    else books.sort(function (a, b) { return num(b.lastReadAt, 0) - num(a.lastReadAt, 0); });
    var html = pageHead('陪伴阅读', '书架、进度与笔记都保存在 S-G；阅读时桌宠会自动躲起来', true);
    html += '<div class="shelf-stats">' +
      '<div><b>' + num(stats.total, 0) + '</b><span>在架</span></div>' +
      '<div><b>' + num(stats.finished, 0) + '</b><span>读完</span></div>' +
      '<div><b>' + fmtSec(stats.totalSeconds || stats.seconds) + '</b><span>阅读</span></div>' +
      '<div><b>' + num(stats.streak, 0) + '</b><span>连续天数</span></div></div>';
    html += '<div class="shelf-actions">' +
      '<button class="btn primary sm" data-act="bookImport">导入书籍</button>' +
      '<button class="btn sm" data-act="bookUrl">网页链接</button>' +
      '<button class="btn sm" data-act="bookScan">扫描本地</button>' +
      '<button class="btn ghost sm" data-act="bookExportAll">导出书架</button></div>';
    html += '<div class="search"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg><input id="shelfSearch" placeholder="搜索书名、作者或分组…" value="' + esc(S.shelfQuery) + '"></div>';
    html += '<div class="shelf-tabs">' +
      '<button class="' + (S.shelfTab === 'recent' ? 'on' : '') + '" data-act="shelfTab" data-tab="recent">最近</button>' +
      '<button class="' + (S.shelfTab === 'all' ? 'on' : '') + '" data-act="shelfTab" data-tab="all">全部</button>' +
      '<button class="' + (S.shelfTab === 'finished' ? 'on' : '') + '" data-act="shelfTab" data-tab="finished">读完</button>' +
      '<button class="' + (S.shelfTab === 'hidden' ? 'on' : '') + '" data-act="shelfTab" data-tab="hidden">隐藏</button>' +
      '<select id="shelfSort" class="shelf-sort"><option value="recent"' + (S.shelfSort === 'recent' ? ' selected' : '') + '>最近阅读</option><option value="added"' + (S.shelfSort === 'added' ? ' selected' : '') + '>添加时间</option><option value="title"' + (S.shelfSort === 'title' ? ' selected' : '') + '>书名</option><option value="progress"' + (S.shelfSort === 'progress' ? ' selected' : '') + '>进度</option></select></div>';
    if (!books.length) {
      html += '<div class="card"><div class="empty">书架空空的。导入 TXT / EPUB / PDF / MOBI，或粘贴小说网页链接开始阅读。</div>' +
        '<button class="btn primary block sm" data-act="bookImport">导入第一本书</button>' +
        '<button class="btn ghost block sm" data-act="bookRestore" style="margin-top:8px">从书架备份恢复</button></div>';
    } else {
      html += '<div class="book-grid">';
      books.forEach(function (b) {
        var pct = Math.round(parseFloat(b.progress && b.progress.percent || 0) * 100);
        html += '<div class="book-card" data-act="bookOpen" data-id="' + esc(b.id) + '">' +
          '<div class="book-cover">' + (b.cover ? '<img src="' + esc(b.cover) + '" alt="">' : '<span>' + esc(b.format || 'BOOK') + '</span>') +
          '<i class="book-read">' + (b.finished ? '已读完' : pct + '%') + '</i></div>' +
          '<div class="book-meta"><b>' + esc(b.title || '未命名') + '</b><span>' + esc(b.author || b.format || '') + '</span>' +
          '<div class="book-bar"><i style="width:' + (b.finished ? 100 : pct) + '%"></i></div>' +
          '<small>' + (b.group ? esc(b.group) + ' · ' : '') + fmtSec(b.readingSeconds) + '</small></div>' +
          '<button class="book-more" data-act="bookMenu" data-id="' + esc(b.id) + '">⋯</button></div>';
      });
      html += '</div>';
    }
    html += '<div class="faint" style="text-align:center;padding:14px 0">支持 TXT、EPUB、PDF；MOBI/AZW3 能否读取取决于文件压缩格式。PDF 按页显示，不提取页内文字。</div>';
    return html;
  }

  function stat(v, label) { return '<div class="stat"><b>' + esc(v) + '</b><span>' + esc(label) + '</span></div>'; }
  function rcfg() {
    var c = S.bookCfg || {};
    var d = { fontSize: 18, lineHeight: 1.85, paraSpace: 12, margin: 22, align: 'justify', font: 'serif',
      theme: 'night', lightTheme: 'sepia', anim: 'slide', brightness: 1, traditional: false, autoSec: 25, ttsRate: 1 };
    for (var k in d) if (typeof c[k] === 'undefined') c[k] = d[k];
    // 旧主题值（纸张/绿色护眼）统一迁移到米黄
    c.theme = readerThemeFix(c.theme);
    c.lightTheme = readerThemeFix(c.lightTheme);
    S.bookCfg = c;
    return c;
  }
  function readerFont(name) {
    if (name === 'sans') return 'system-ui,"PingFang SC","Noto Sans SC",sans-serif';
    if (name === 'mono') return 'ui-monospace,"Noto Sans Mono",monospace';
    return '"Songti SC","Noto Serif SC",serif';
  }
  function escReg(s) { return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
  function currentChapter() {
    var cs = S.book.chapters || [];
    return cs[S.bookChapter] || { title: '正文', text: '' };
  }
  /* ---------------- 分页阅读：章节切块 → 测量切页 → 单页渲染 ---------------- */
  /** 生成一个正文块元素（用 textContent，避免把正文当 HTML 解析） */
  function readerBlockEl(type, text, cont) {
    var el = document.createElement(type === 'h2' ? 'h2' : 'p');
    el.textContent = text || '';
    if (type !== 'h2' && cont) el.className = 'cont';
    return el;
  }
  /** 章节正文块：标题 + 段落；简繁转换在这里统一处理 */
  function chapterBlocks(ci) {
    var cfg = rcfg();
    var ch = (S.book.chapters || [])[ci] || { title: '正文', text: '' };
    var text = ch.text || '';
    if (cfg.traditional) text = toTrad(text);
    var out = [{ type: 'h2', text: ch.title || '正文' }];
    var paras = text.split(/\n+/);
    for (var i = 0; i < paras.length; i++) {
      var p = paras[i].trim();
      if (p) out.push({ type: 'p', text: p });
    }
    return out;
  }
  /** 页内高亮：先拼纯文本块，再套搜索词与笔记划线 */
  function decoratePageHtml(html) {
    var term = S.readerSearchTerm ? esc(S.readerSearchTerm) : '';
    if (term) html = html.replace(new RegExp(escReg(term), 'g'), '<mark class="search">$&</mark>');
    (S.readerNotes || []).forEach(function (n) {
      if (num(n.chapter, -1) !== S.bookChapter || !n.quote) return;
      if (n.type !== 'highlight' && n.type !== 'note' && n.type !== 'search') return;
      var q = esc(n.quote);
      if (!q) return;
      var title = n.note ? ' title="' + esc(n.note) + '"' : '';
      if (html.indexOf(q) >= 0) html = html.replace(q, '<mark class="hl"' + title + '>' + q + '</mark>');
    });
    return html;
  }
  function pageHtml(page) {
    if (!page || !page.blocks || !page.blocks.length) return '';
    var html = '';
    page.blocks.forEach(function (b) {
      if (b.type === 'h2') html += '<h2 class="chapter-title">' + esc(b.text) + '</h2>';
      else html += '<p' + (b.cont ? ' class="cont"' : '') + '>' + esc(b.text) + '</p>';
    });
    return decoratePageHtml(html);
  }
  function renderReader() {
    if (!S.book || !S.book.id) {
      return pageHead('陪伴阅读', '还没有打开的书', true) + '<div class="card"><button class="btn primary block" data-act="openPage" data-page="readerShelf">去书架选一本</button></div>';
    }
    var cfg = rcfg();
    var th = readerTheme(cfg.theme);
    var cs = S.book.chapters || [];
    var ch = currentChapter();
    var isPdf = S.book.format === 'PDF';
    var dark = readerIsDark();
    var total = Math.max(1, cs.length);
    var style = '--rfs:' + num(cfg.fontSize, 18) + 'px;--rlh:' + cfg.lineHeight + ';--rps:' + num(cfg.paraSpace, 12) + 'px;--rmg:' + num(cfg.margin, 22) + 'px;--rbg:' + th.bg + ';--rfg:' + th.fg + ';--rsub:' + th.sub + ';--rf:' + readerFont(cfg.font) + ';--ralign:' + esc(cfg.align || 'justify') + ';';
    // 沉浸式：顶/底栏默认隐藏（bars-hidden），点屏幕中央呼出
    var rootCls = 'reader' + (S.readerBars ? '' : ' bars-hidden') + (S.readerDrawer ? ' drawer-open' : '');
    var stageHtml;
    if (isPdf) {
      var src = S.pdfCache[S.bookChapter] || '';
      stageHtml = '<div class="reader-scroll" id="readerScroll"><div class="pdf-wrap">' + (src
        ? '<img class="pdf-page" id="pdfImg" src="' + src + '" alt="">'
        : '<div class="pdf-loading" id="pdfLoading">正在渲染 PDF 第 ' + (S.bookChapter + 1) + ' 页…</div>') + '</div></div>';
    } else {
      // 分页模式：只有当前页与翻页动画的下一页两层，任何时刻都不会出现上下滚动
      stageHtml = '<div class="reader-sheet" id="readerSheet"><div class="reader-page" id="readerPage"></div></div>' +
        '<div class="reader-sheet" id="readerSheetNext"></div>';
    }
    var html = '<div class="' + rootCls + '" id="readerRoot" style="' + style + '">' +
      // 顶部工具栏：返回 + 书名·章节名（单行省略）+ 目录
      '<div class="reader-top" id="readerTop">' +
      '<button class="reader-ico" data-act="back">‹</button>' +
      '<div class="reader-head"><b id="readerTitle">' + esc((S.book.title || '阅读') + (ch.title ? ' · ' + ch.title : '')) + '</b></div>' +
      '<button class="reader-ico" data-act="readerDrawerOpen" id="readerTocBtn" title="目录">☰</button></div>' +
      '<div class="reader-stage" id="readerStage">' + stageHtml +
      '<div class="reader-dim" id="readerDim" style="opacity:' + Math.max(0, Math.min(0.7, 1 - num(cfg.brightness, 1))) + '"></div></div>' +
      // 底部工具栏：可拖拽进度条 + 五个按钮
      '<div class="reader-bottom" id="readerBottom">' +
      '<div class="reader-progline">' +
      '<div class="reader-track"><span class="rtrack"></span><i class="rfill" id="readerFill" style="width:' + Math.round(bookPercent() * 100) + '%"></i>' +
      '<input class="reader-range" id="readerRange" type="range" min="0" max="1000" step="1" value="' + Math.round(bookPercent() * 1000) + '" aria-label="阅读进度"></div>' +
      '<span class="reader-progtext" id="readerProgText">' + esc(readerProgressText(S.bookChapter, true)) + '</span></div>' +
      '<div class="reader-btns">' +
      '<button data-act="readerPrev"><b>‹</b>上一页</button>' +
      '<button data-act="readerSettings"><b>Aa</b>设置</button>' +
      '<button data-act="readerNight" id="readerNightBtn"><b>' + (dark ? '☀' : '☾') + '</b>' + (dark ? '日间' : '夜间') + '</button>' +
      '<button data-act="readerMore"><b>⋯</b>更多</button>' +
      '<button data-act="readerNext"><b>›</b>下一页</button></div></div>' +
      readerDrawerHtml() +
      '<div class="reader-mask" id="readerMask" data-act="readerDrawerClose"></div>' +
      '<div class="sel-bar hidden" id="selBar">' +
      '<button data-act="selHighlight">划线</button><button data-act="selNote">笔记</button>' +
      '<button data-act="selCopy">复制</button><button data-act="selDict">词典</button>' +
      '<button data-act="selTranslate">翻译</button><button data-act="selCancel">取消</button></div>' +
      (S.readerPanel ? '<div class="reader-panel-mask" data-act="readerPanelClose"></div>' + readerPanelHtml() : '') +
      '</div>';
    return html;
  }
  /** 目录抽屉：从右侧滑出的章节列表（默认在屏幕外，靠 drawer-open 类滑入） */
  function readerDrawerHtml() {
    var cs = S.book.chapters || [];
    var list = '<div class="toc-list" id="readerTocList">';
    cs.forEach(function (c, i) {
      list += '<button class="toc-item' + (i === S.bookChapter ? ' on' : '') + '" data-act="readerJump" data-index="' + i + '">' +
        '<span>' + (i + 1) + '</span><em>' + esc(c.title || ('第 ' + (i + 1) + ' 章')) + '</em></button>';
    });
    list += '</div>';
    return '<div class="reader-drawer" id="readerDrawer">' +
      '<div class="reader-drawer-head"><div><b>目录</b><small>' + esc(S.book.title || '') + ' · 共 ' + cs.length + ' 章</small></div>' +
      '<button class="reader-ico" data-act="readerDrawerClose">×</button></div>' + list + '</div>';
  }
  function readerPanelHtml() {
    if (!S.readerPanel) return '';
    var titles = { settings: '阅读设置', more: '阅读工具', search: '全书搜索', notes: '笔记与书签', history: '跳转历史' };
    var head = '<div class="reader-panel-head"><b>' + (titles[S.readerPanel] || '阅读工具') + '</b><button class="reader-ico" data-act="readerPanelClose">×</button></div>';
    if (S.readerPanel === 'settings') {
      var cfg = rcfg();
      var lines = '';
      READER_LINES.forEach(function (l) {
        lines += '<button class="rchip ' + (Math.abs(num(cfg.lineHeight, 1.85) - l[0]) < 0.03 ? 'on' : '') + '" data-act="readerLineSpace" data-lh="' + l[0] + '">' + l[1] + '</button>';
      });
      var themes = '';
      READER_THEME_LIST.forEach(function (t) {
        themes += '<button class="rtheme ' + (cfg.theme === t[0] ? 'on' : '') + '" data-act="readerTheme" data-theme="' + t[0] + '">' + t[1] + '</button>';
      });
      var anims = '';
      READER_ANIMS.forEach(function (a) {
        anims += '<button class="rchip ' + ((cfg.anim || 'slide') === a[0] ? 'on' : '') + '" data-act="readerAnimPick" data-anim="' + a[0] + '">' + a[1] + '</button>';
      });
      var aligns = '';
      [['justify', '两端对齐'], ['left', '左对齐']].forEach(function (a) {
        aligns += '<button class="rchip ' + (cfg.align === a[0] ? 'on' : '') + '" data-act="readerAlign" data-align="' + a[0] + '">' + a[1] + '</button>';
      });
      var fonts = '';
      [['serif', '宋体'], ['sans', '黑体'], ['mono', '等宽']].forEach(function (f) {
        fonts += '<button class="rchip ' + (cfg.font === f[0] ? 'on' : '') + '" data-act="readerFont" data-font="' + f[0] + '">' + f[1] + '</button>';
      });
      fonts += '<button class="rchip ' + (cfg.traditional ? 'on' : '') + '" data-act="readerTrad">' + (cfg.traditional ? '繁体' : '简体') + '</button>';
      return '<div class="reader-panel">' + head +
        '<div class="rgroup"><div class="rgroup-title">字号<em>14 – 28</em></div>' +
        '<div class="rstep"><button data-act="readerFontStep" data-step="-1">A-</button>' +
        '<span class="rstepv" id="rvFontSize">' + num(cfg.fontSize, 18) + '</span>' +
        '<button data-act="readerFontStep" data-step="1">A+</button>' +
        '<input type="range" id="readerFontRange" min="14" max="28" step="1" value="' + num(cfg.fontSize, 18) + '" data-rset="fontSize"></div></div>' +
        '<div class="rgroup"><div class="rgroup-title">行距</div>' +
        '<div class="rchips">' + lines + '</div>' +
        '<div class="rstep"><input type="range" id="readerLineRange" min="1.3" max="2.8" step="0.05" value="' + cfg.lineHeight + '" data-rset="lineHeight">' +
        '<span class="rstepv" id="rvLineHeight">' + cfg.lineHeight + '</span></div></div>' +
        '<div class="rgroup"><div class="rgroup-title">背景主题</div><div class="rchips">' + themes + '</div></div>' +
        '<div class="rgroup"><div class="rgroup-title">翻页动画<em>默认顺滑</em></div><div class="rchips">' + anims + '</div></div>' +
        '<div class="rgroup"><div class="rgroup-title">更多设置</div>' +
        '<div class="rrow"><span>段距</span><input type="range" min="0" max="32" step="1" value="' + num(cfg.paraSpace, 12) + '" data-rset="paraSpace"><b id="rvParaSpace">' + num(cfg.paraSpace, 12) + '</b></div>' +
        '<div class="rrow"><span>页边距</span><input type="range" min="8" max="44" step="1" value="' + num(cfg.margin, 22) + '" data-rset="margin"><b id="rvMargin">' + num(cfg.margin, 22) + '</b></div>' +
        '<div class="rrow"><span>亮度</span><input type="range" min="0.35" max="1" step="0.05" value="' + num(cfg.brightness, 1) + '" data-rset="brightness"><b id="rvBrightness">' + Math.round(num(cfg.brightness, 1) * 100) + '%</b></div>' +
        '<div class="rrow"><span>朗读语速</span><input type="range" min="0.5" max="2.5" step="0.1" value="' + num(cfg.ttsRate, 1) + '" data-rset="ttsRate"><b id="rvTtsRate">' + num(cfg.ttsRate, 1) + 'x</b></div>' +
        '<div class="rlabel">对齐</div><div class="rchips">' + aligns + '</div>' +
        '<div class="rlabel">字体</div><div class="rchips">' + fonts + '</div>' +
        '<div class="rlabel">自动翻页</div><div class="rchips">' +
        '<button class="rchip ' + (S.autoOn ? 'on' : '') + '" data-act="readerAuto">' + (S.autoOn ? '停止自动' : '开始自动') + '</button>' +
        '<select id="readerAutoSec" class="shelf-sort"><option value="10"' + (num(cfg.autoSec, 25) === 10 ? ' selected' : '') + '>10 秒</option><option value="20"' + (num(cfg.autoSec, 25) === 20 ? ' selected' : '') + '>20 秒</option><option value="25"' + (num(cfg.autoSec, 25) === 25 ? ' selected' : '') + '>25 秒</option><option value="30"' + (num(cfg.autoSec, 25) === 30 ? ' selected' : '') + '>30 秒</option><option value="60"' + (num(cfg.autoSec, 25) === 60 ? ' selected' : '') + '>60 秒</option></select></div></div>' +
        '</div>';
    }
    if (S.readerPanel === 'more') {
      return '<div class="reader-panel">' + head +
        '<div class="rgroup"><div class="rgroup-title">朗读</div><div class="rchips">' +
        '<button class="rchip ' + (S.ttsPlaying ? 'on' : '') + '" data-act="readerTts">' + (S.ttsPlaying ? '停止朗读' : '朗读本章') + '</button>' +
        '<button class="rchip" data-act="readerTtsTimer">定时 ' + (S.ttsTimerMin ? S.ttsTimerMin + ' 分' : '关') + '</button></div></div>' +
        '<div class="rgroup"><div class="rgroup-title">检索与笔记</div><div class="rchips">' +
        '<button class="rchip" data-act="readerSearch">全书搜索</button>' +
        '<button class="rchip" data-act="readerNotes">笔记与书签</button>' +
        '<button class="rchip" data-act="readerHistory">跳转历史</button>' +
        '<button class="rchip" data-act="readerBookmark">加书签</button>' +
        '<button class="rchip" data-act="readerExportNotes">导出笔记</button></div></div>' +
        '<div class="rgroup"><div class="rgroup-title">本书</div><div class="rchips">' +
        '<button class="rchip" data-act="bookExport" data-id="' + esc(S.book.id) + '">导出本书</button>' +
        '<button class="rchip" data-act="readerDelete">删除本书</button></div>' +
        '<div class="faint" style="margin-top:10px">朗读使用系统 TTS；退出阅读会自动保存位置。</div></div>' +
        '</div>';
    }
    if (S.readerPanel === 'search') {
      var results = '';
      (S.readerResults || []).forEach(function (r) {
        results += '<button class="search-hit" data-act="readerJump" data-index="' + r.index + '" data-term="' + esc(r.term || '') + '"><b>第 ' + (r.index + 1) + ' 章 · ' + esc(r.title) + '</b><span>' + esc(r.snippet) + '</span></button>';
      });
      return '<div class="reader-panel">' + head +
        '<div class="search" style="margin:0 0 10px"><input id="readerSearchInput" placeholder="全书搜索…" value="' + esc(S.readerSearch || '') + '"><button class="btn sm primary" data-act="readerSearchDo">搜索</button></div>' +
        (results ? '<div class="search-results">' + results + '</div>' : '<div class="empty">在当前书里搜索章节内容</div>') + '</div>';
    }
    if (S.readerPanel === 'notes') {
      var list = '';
      (S.readerNotes || []).forEach(function (n) {
        var icon = n.type === 'bookmark' ? '书签' : n.type === 'highlight' ? '划线' : '笔记';
        list += '<div class="note-item"><div><b>' + icon + ' · 第 ' + (num(n.chapter, 0) + 1) + ' 章</b><span>' + esc(n.quote || n.note || '') + '</span>' + (n.note && n.quote ? '<small>' + esc(n.note) + '</small>' : '') + '</div>' +
          '<button class="li-act danger" data-act="bookNoteDel" data-id="' + esc(n.id) + '">删除</button></div>';
      });
      return '<div class="reader-panel">' + head +
        (list || '<div class="empty">还没有笔记。选中文字可以划线、写笔记；点下方书签按钮保存当前位置。</div>') +
        '<button class="btn ghost block sm" data-act="readerBookmark" style="margin-top:10px">在当前位置加书签</button></div>';
    }
    if (S.readerPanel === 'history') {
      var list = '<div class="toc-list">';
      (S.readerHistory || []).slice().reverse().forEach(function (h) {
        list += '<button class="toc-item" data-act="readerJump" data-index="' + h.index + '"><span>' + (h.index + 1) + '</span><em>' + esc(h.title || '') + '</em></button>';
      });
      list += '</div>';
      return '<div class="reader-panel">' + head + (S.readerHistory.length ? list : '<div class="empty">还没有跳转记录</div>') + '</div>';
    }
    return '<div class="reader-panel">' + head + '<div class="empty">没有可显示的内容</div></div>';
  }
  /** 打开面板：只插入面板 DOM，不重绘阅读页，阅读位置不会丢 */
  function readerPanelOpen(panel) {
    if (S.readerPanel === panel) { closeReaderPanel(); return; }
    closeReaderPanel(true);
    S.readerPanel = panel;
    pauseReaderBars();
    var root = $('#readerRoot');
    if (!root) return;
    var wrap = document.createElement('div');
    wrap.className = 'reader-panel-mask';
    wrap.setAttribute('data-act', 'readerPanelClose');
    root.appendChild(wrap);
    root.insertAdjacentHTML('beforeend', readerPanelHtml());
    var panelEl = $('.reader-panel');
    if (panelEl) panelEl.scrollTop = 0;
  }
  /** 关闭面板：点空白、×、返回键都走这里；重绘计时恢复 */
  function closeReaderPanel(silent) {
    S.readerPanel = '';
    var p = $('.reader-panel');
    if (p) p.remove();
    var m = $('.reader-panel-mask');
    if (m) m.remove();
    if (!silent) restartReaderBars();
  }
  /** 只刷新面板内容，不动正文与页码（搜索、笔记增删后用） */
  function refreshReaderPanel() {
    var p = $('.reader-panel');
    if (!p) return;
    if (!S.readerPanel) { p.remove(); var m = $('.reader-panel-mask'); if (m) m.remove(); return; }
    p.outerHTML = readerPanelHtml();
  }
  function readerApplyStyle() {
    var cfg = rcfg();
    var th = readerTheme(cfg.theme);
    var root = $('#readerRoot');
    if (!root) return;
    root.style.setProperty('--rfs', num(cfg.fontSize, 18) + 'px');
    root.style.setProperty('--rlh', cfg.lineHeight);
    root.style.setProperty('--rps', num(cfg.paraSpace, 12) + 'px');
    root.style.setProperty('--rmg', num(cfg.margin, 22) + 'px');
    root.style.setProperty('--rbg', th.bg);
    root.style.setProperty('--rfg', th.fg);
    root.style.setProperty('--rsub', th.sub);
    root.style.setProperty('--rf', readerFont(cfg.font));
    root.style.setProperty('--ralign', cfg.align || 'justify');
    var dim = $('#readerDim');
    if (dim) dim.style.opacity = Math.max(0, Math.min(0.7, 1 - num(cfg.brightness, 1)));
  }
  /** 字号 A- / A+：范围 14–28，实时重新分页并停在原来的字上 */
  function readerFontStep(step) {
    var cfg = rcfg();
    var cur = num(cfg.fontSize, 18);
    var v = Math.max(14, Math.min(28, cur + step));
    if (v === cur) { toast(v >= 28 ? '已经是最大字号了' : '已经是最小字号了'); return; }
    cfg.fontSize = v;
    saveReaderCfg();
    readerApplyStyle();
    readerRestyle();
    syncReaderUI();
  }
  /** 夜间/日间一键互切：深色 ↔ 上次使用的浅色主题（默认米黄） */
  function readerToggleNight() {
    var cfg = rcfg();
    if (readerIsDark()) {
      var light = readerThemeFix(cfg.lightTheme || 'sepia');
      cfg.theme = (light === 'night') ? 'sepia' : light;
    } else {
      if (cfg.theme !== 'system') cfg.lightTheme = cfg.theme;
      cfg.theme = 'night';
    }
    saveReaderCfg();
    readerApplyStyle();
    syncReaderUI();
  }
  /** 不重绘地同步面板与工具栏上的高亮/文案（字号、主题、动画等实时设置用） */
  function syncReaderUI() {
    var cfg = rcfg();
    var dark = readerIsDark();
    var nb = $('#readerNightBtn');
    if (nb) {
      nb.innerHTML = '<b>' + (dark ? '☀' : '☾') + '</b>' + (dark ? '日间' : '夜间');
      nb.classList.toggle('on', dark);
    }
    $$('.rtheme').forEach(function (b) {
      b.classList.toggle('on', b.getAttribute('data-theme') === cfg.theme);
    });
    var fs = $('#rvFontSize');
    if (fs) fs.textContent = num(cfg.fontSize, 18);
    var fr = $('#readerFontRange');
    if (fr) fr.value = num(cfg.fontSize, 18);
    var ls = $('#rvLineHeight');
    if (ls) ls.textContent = flt(cfg.lineHeight, 1.85).toFixed(2);
    var lr = $('#readerLineRange');
    if (lr) lr.value = flt(cfg.lineHeight, 1.85);
    var lh = flt(cfg.lineHeight, 1.85);
    $$('.rchip[data-act=readerLineSpace]').forEach(function (b) {
      b.classList.toggle('on', Math.abs(lh - flt(b.getAttribute('data-lh'), 0)) < 0.03);
    });
    $$('.rchip[data-act=readerAnimPick]').forEach(function (b) {
      b.classList.toggle('on', (cfg.anim || 'slide') === b.getAttribute('data-anim'));
    });
    $$('.rchip[data-act=readerFont]').forEach(function (b) {
      b.classList.toggle('on', (cfg.font || 'serif') === b.getAttribute('data-font'));
    });
    $$('.rchip[data-act=readerAlign]').forEach(function (b) {
      b.classList.toggle('on', (cfg.align || 'justify') === b.getAttribute('data-align'));
    });
    var tb = $('.rchip[data-act=readerTrad]');
    if (tb) {
      tb.textContent = cfg.traditional ? '繁体' : '简体';
      tb.classList.toggle('on', !!cfg.traditional);
    }
    var ab = $('.rchip[data-act=readerAuto]');
    if (ab) {
      ab.textContent = S.autoOn ? '停止自动' : '开始自动';
      ab.classList.toggle('on', !!S.autoOn);
    }
    var ttsB = $('.rchip[data-act=readerTts]');
    if (ttsB) {
      ttsB.textContent = S.ttsPlaying ? '停止朗读' : '朗读本章';
      ttsB.classList.toggle('on', !!S.ttsPlaying);
    }
  }
  /* ---------------- 分页引擎：把章节按屏幕尺寸切成整页 ----------------
     缓存键包含章节、可用宽高与所有影响排版的字体参数；
     改字号后按「当前页第一个字」的字符偏移重新定位，不会跳页。 */
  var PageEngine = (function () {
    var cache = {}, order = [];
    function pageSize() {
      var stage = $('#readerStage');
      if (!stage || !stage.clientWidth || !stage.clientHeight) return null;
      var cfg = rcfg();
      var mg = num(cfg.margin, 22);
      return {
        w: Math.max(120, stage.clientWidth - mg * 2),
        h: Math.max(120, stage.clientHeight - 28) // 上下内边距 10 + 12，再留 6px 安全区
      };
    }
    function keyOf(ci, sz) {
      var c = rcfg();
      return [ci, sz.w, sz.h, num(c.fontSize, 18), c.lineHeight, num(c.paraSpace, 12),
        c.font || 'serif', c.align || 'justify', c.traditional ? 1 : 0].join('|');
    }
    function makeMeasure(stage, w) {
      var m = document.createElement('div');
      m.className = 'reader-page reader-measure';
      m.style.width = w + 'px';
      stage.appendChild(m);
      return m;
    }
    function paginate(ci, sz) {
      var stage = $('#readerStage');
      if (!stage) return [{ blocks: [], start: 0, end: 0 }];
      var m = makeMeasure(stage, sz.w);
      var blocks = chapterBlocks(ci);
      var pages = [], cur = [], pageStart = 0, offset = 0;
      function fits() { return m.offsetHeight <= sz.h; }
      function flush() {
        if (!cur.length) return;
        pages.push({ blocks: cur, start: pageStart, end: offset });
        pageStart = offset;
        cur = [];
        m.innerHTML = '';
      }
      function binaryFit(type, text, cont) {
        var lo = 1, hi = text.length, best = 0;
        var el = readerBlockEl(type, '', cont);
        m.appendChild(el);
        while (lo <= hi) {
          var mid = (lo + hi) >> 1;
          el.textContent = text.slice(0, mid);
          if (fits()) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        m.removeChild(el);
        return best;
      }
      for (var bi = 0; bi < blocks.length; bi++) {
        var b = blocks[bi];
        if (!b.text) continue;
        var rest = b.text, cont = false;
        while (rest.length) {
          var el = readerBlockEl(b.type, rest, cont);
          m.appendChild(el);
          if (fits()) {
            cur.push({ type: b.type, text: rest, cont: cont });
            offset += rest.length;
            rest = '';
            continue;
          }
          m.removeChild(el);
          var fit = (cur.length || m.offsetHeight > 0) ? binaryFit(b.type, rest, cont) : 0;
          if (fit > 0) {
            var head = rest.slice(0, fit);
            cur.push({ type: b.type, text: head, cont: cont });
            offset += head.length;
            rest = rest.slice(fit);
            flush();
            cont = b.type !== 'h2';
          } else if (cur.length) {
            flush();
            cont = b.type !== 'h2';
          } else {
            // 极端情况：整页连一个字都放不下，强制放 1 个字避免死循环
            var one = rest.slice(0, 1);
            cur.push({ type: b.type, text: one, cont: cont });
            offset += 1;
            rest = rest.slice(1);
            flush();
            cont = b.type !== 'h2';
          }
        }
      }
      flush();
      if (!pages.length) pages.push({ blocks: [], start: 0, end: 0 });
      stage.removeChild(m);
      return pages;
    }
    function get(ci) {
      var sz = pageSize();
      if (!sz) return [{ blocks: [], start: 0, end: 0 }];
      var key = keyOf(ci, sz);
      if (cache[key]) return cache[key];
      var pages = paginate(ci, sz);
      cache[key] = pages;
      order.push(key);
      while (order.length > 12) { delete cache[order.shift()]; }
      return pages;
    }
    function pageOfOffset(pages, off) {
      for (var i = pages.length - 1; i >= 0; i--) if (pages[i].start <= off) return i;
      return 0;
    }
    function pageOfTerm(pages, term) {
      if (!term) return 0;
      for (var i = 0; i < pages.length; i++) {
        var t = '';
        pages[i].blocks.forEach(function (b) { t += b.text; });
        if (t.indexOf(term) >= 0) return i;
      }
      return 0;
    }
    return { get: get, pageOfOffset: pageOfOffset, pageOfTerm: pageOfTerm };
  })();
  /** 重绘当前页（只换正文，不动工具栏） */
  function paintCurrentPage() {
    var page = S.readerPages && S.readerPages[S.readerPage];
    var el = $('#readerPage');
    if (el) el.innerHTML = pageHtml(page);
    updateReaderChrome();
  }
  /** 同步顶部标题、目录高亮与底部页/章进度 */
  function updateReaderChrome() {
    var t = $('#readerTitle');
    if (t) {
      var ch = currentChapter();
      t.textContent = (S.book.title || '阅读') + (ch.title ? ' · ' + ch.title : '');
    }
    $$('#readerTocList .toc-item').forEach(function (b) {
      b.classList.toggle('on', num(b.getAttribute('data-index'), -1) === S.bookChapter);
    });
    updateReaderProgress();
  }
  /** 改字号/行距/边距后：按字符锚点重新分页，停在同一段文字 */
  function readerRestyle() {
    if (S.page !== 'reader' || !S.book.id || S.book.format === 'PDF') return;
    var anchor = S.readerAnchor || 0;
    S.readerPages = PageEngine.get(S.bookChapter);
    S.readerPage = PageEngine.pageOfOffset(S.readerPages, anchor);
    S.readerAnchor = S.readerPages[S.readerPage].start;
    paintCurrentPage();
  }
  /* ---------------- 沉浸式工具栏：点击屏幕中央呼出，3 秒无操作自动渐隐 ---------------- */
  function readerBarsShow(show) {
    var root = $('#readerRoot');
    if (!root) return;
    S.readerBars = show ? 1 : 0;
    root.classList.toggle('bars-hidden', !show);
    if (show) restartReaderBars(); else pauseReaderBars();
  }
  function readerBarsToggle() {
    readerBarsShow(!S.readerBars);
  }
  /** 重新开始自动渐隐计时（面板/抽屉打开期间由 pauseReaderBars 暂停） */
  function restartReaderBars() {
    pauseReaderBars();
    if (S.page !== 'reader' || !S.readerBars) return;
    S.readerBarTimer = setTimeout(function () {
      S.readerBarTimer = null;
      if (S.readerPanel || S.readerDrawer) return;
      readerBarsShow(false);
    }, 3000);
  }
  function pauseReaderBars() {
    if (S.readerBarTimer) { clearTimeout(S.readerBarTimer); S.readerBarTimer = null; }
  }
  /* ---------------- 目录抽屉：右侧滑出，点遮罩或章节后关闭 ---------------- */
  function readerDrawerToggle(open) {
    var root = $('#readerRoot');
    if (!root) return;
    S.readerDrawer = (typeof open === 'undefined') ? (S.readerDrawer ? 0 : 1) : (open ? 1 : 0);
    root.classList.toggle('drawer-open', !!S.readerDrawer);
    if (S.readerDrawer) {
      readerBarsShow(true);
      pauseReaderBars();
      scrollTocToCurrent();
    } else {
      restartReaderBars();
    }
  }
  /** 打开抽屉时把当前章滚到列表中间，方便定位 */
  function scrollTocToCurrent() {
    var list = $('#readerTocList');
    if (!list) return;
    var on = list.querySelector('.toc-item.on');
    if (!on) return;
    list.scrollTop = Math.max(0, on.offsetTop - list.clientHeight / 2 + on.offsetHeight / 2);
  }
  function readerProgressText(idx, preview) {
    var total = Math.max(1, (S.book.chapters || []).length);
    idx = Math.max(0, Math.min(total - 1, num(idx, 0)));
    var c = (S.book.chapters || [])[idx];
    var title = (c && c.title) || ('第 ' + (idx + 1) + ' 章');
    if (!preview && S.book.format !== 'PDF' && S.readerPages && S.readerPages.length) {
      return '第 ' + (S.readerPage + 1) + ' / ' + S.readerPages.length + ' 页 · 第 ' + (idx + 1) + ' / ' + total + ' 章';
    }
    return '第 ' + (idx + 1) + ' / ' + total + ' 章 · ' + title;
  }
  /** 把 0–1000 的进度条值换算成章节序号（拖动跳转用） */
  function readerRangeChapter(v) {
    var total = Math.max(1, (S.book.chapters || []).length);
    return Math.max(0, Math.min(total - 1, Math.floor(num(v, 0) / 1000 * total)));
  }
  function updateReaderProgress() {
    var pct = bookPercent();
    var fill = $('#readerFill');
    if (fill) fill.style.width = Math.round(pct * 100) + '%';
    if (S.readerDrag) return; // 拖动中文字跟随手指，不被翻页覆盖
    var range = $('#readerRange');
    if (range) range.value = Math.round(pct * 1000);
    var txt = $('#readerProgText');
    if (txt) txt.textContent = readerProgressText(S.bookChapter);
  }
  function bindReader() {
    if (S.page !== 'reader') return;
    var stage = $('#readerStage');
    if (!stage) return;
    // 屏幕旋转 / 分屏：重新分页并停在原来的字上
    if (!bindReader._rz) {
      bindReader._rz = 1;
      window.addEventListener('resize', function () {
        if (S.page !== 'reader' || S.book.format === 'PDF') return;
        clearTimeout(bindReader._rt);
        bindReader._rt = setTimeout(function () {
          if (S.page === 'reader') readerRestyle();
        }, 200);
      });
    }
    if (S.book.format !== 'PDF') {
      S.readerFlipping = false;
      S.readerPages = PageEngine.get(S.bookChapter);
      if (S.readerJumpTerm) {
        S.readerPage = PageEngine.pageOfTerm(S.readerPages, S.readerJumpTerm);
        S.readerJumpTerm = '';
      } else if (S.readerRestore) {
        S.readerPage = Math.round(S.readerRestore * (S.readerPages.length - 1));
        S.readerRestore = 0;
      }
      S.readerPage = Math.max(0, Math.min(S.readerPages.length - 1, num(S.readerPage, 0)));
      S.readerAnchor = S.readerPages[S.readerPage].start;
      paintCurrentPage();
      if (S.readerFade) { S.readerFade = false; flashPageFade(); }
    } else {
      if (S.readerRestore) {
        var sc0 = $('#readerScroll');
        if (sc0) sc0.scrollTop = S.readerRestore * Math.max(0, sc0.scrollHeight - sc0.clientHeight);
        S.readerRestore = 0;
      }
      var sc = $('#readerScroll');
      if (sc) sc.addEventListener('scroll', function () { updateReaderProgress(); saveReaderProgress(false); });
      if (!S.pdfCache[S.bookChapter]) loadPdfPage();
    }
    updateReaderProgress();
    if (S.readerBars) restartReaderBars(); else pauseReaderBars();
    if (S.readerDrawer) scrollTocToCurrent();
    bindStageGestures();
    bindReaderRange();
    hideSelBar();
  }
  /** 点按分区、左右滑动、选区工具：全部绑在舞台上 */
  function bindStageGestures() {
    var stage = $('#readerStage');
    if (!stage) return;
    var tx = 0, ty = 0;
    stage.addEventListener('touchstart', function (e) {
      if (!e.touches || !e.touches.length) return;
      tx = e.touches[0].clientX; ty = e.touches[0].clientY;
    }, { passive: true });
    stage.addEventListener('touchend', function (e) {
      if (!e.changedTouches || !e.changedTouches.length) return;
      if (S.readerPanel || S.readerDrawer) return;
      if (window.getSelection().toString().trim()) return;
      var dx = e.changedTouches[0].clientX - tx, dy = e.changedTouches[0].clientY - ty;
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.4) {
        // Android WebView 可能在滑动结束后再派发一次 click；屏蔽它，避免一滑连翻两页。
        bindStageGestures._swipeAt = Date.now();
        e.preventDefault();
        if (S.readerBars) restartReaderBars();
        readerTurn(dx < 0 ? 1 : -1);
      }
    }, { passive: false });
    stage.addEventListener('mouseup', function (e) { showSelBar(e); });
    // 点按分区：左 1/3 上一页、右 1/3 下一页、中间 1/3 切换工具栏
    stage.addEventListener('click', function (e) {
      if (Date.now() - (bindStageGestures._swipeAt || 0) < 500) { e.preventDefault(); return; }
      if (S.readerPanel || S.readerDrawer || window.getSelection().toString().trim()) return;
      var r = stage.getBoundingClientRect();
      var x = (e.clientX - r.left) / Math.max(1, r.width);
      if (S.readerBars) restartReaderBars();
      if (x < 1 / 3) readerTurn(-1);
      else if (x > 2 / 3) readerTurn(1);
      else readerBarsToggle();
    });
  }
  /** 进度条：拖动时只实时显示目标章节，松手才真正跳转 */
  function bindReaderRange() {
    var range = $('#readerRange');
    if (!range) return;
    range.addEventListener('input', function () {
      S.readerDrag = true;
      var v = num(range.value, 0);
      var fill = $('#readerFill');
      if (fill) fill.style.width = (v / 10) + '%';
      var txt = $('#readerProgText');
      if (txt) txt.textContent = readerProgressText(readerRangeChapter(v));
      if (S.readerBars) restartReaderBars();
    });
    range.addEventListener('change', function () {
      var idx = readerRangeChapter(num(range.value, 0));
      S.readerDrag = false;
      if (idx === S.bookChapter) { updateReaderProgress(); return; }
      readerJump(idx);
    });
  }
  function loadPdfPage() {
    var p = S.bookChapter;
    var id = S.book.id;
    var wrap = document.querySelector('.pdf-wrap');
    if (wrap) wrap.innerHTML = '<div class="pdf-loading" id="pdfLoading">正在渲染 PDF 第 ' + (p + 1) + ' 页…</div>';
    call('bookPage', { id: id, page: p }).then(function (r) {
      if (S.page !== 'reader' || S.book.id !== id) return;
      if (!r || !r.img) {
        if (S.bookChapter === p) {
          var failed = document.querySelector('.pdf-wrap');
          if (failed) failed.innerHTML = '<div class="pdf-loading">PDF 第 ' + (p + 1) + ' 页没有渲染出来。' +
            '<div class="faint" style="margin:8px 0">' + esc(r && r.error ? r.error : '可以重试，或返回书架重新打开。') + '</div>' +
            '<button class="pdf-retry" data-act="readerPdfRetry">重新加载</button></div>';
        }
        return;
      }
      S.pdfCache[p] = r.img;
      var cached = Object.keys(S.pdfCache).map(function (k) { return parseInt(k, 10); });
      cached.sort(function (a, b) { return Math.abs(a - S.bookChapter) - Math.abs(b - S.bookChapter); });
      while (cached.length > 3) delete S.pdfCache[cached.pop()];
      if (S.bookChapter === p) {
        var wrap = document.querySelector('.pdf-wrap');
        if (wrap) wrap.innerHTML = '<img class="pdf-page" id="pdfImg" src="' + r.img + '" alt="">';
      }
    }).catch(function (e) {
      if (S.page !== 'reader' || S.book.id !== id || S.bookChapter !== p) return;
      var failed = document.querySelector('.pdf-wrap');
      if (failed) failed.innerHTML = '<div class="pdf-loading">PDF 第 ' + (p + 1) + ' 页加载失败。' +
        '<div class="faint" style="margin:8px 0">' + esc(e && e.message ? e.message : '请稍后重试。') + '</div>' +
        '<button class="pdf-retry" data-act="readerPdfRetry">重新加载</button></div>';
    });
  }
  /* ---------------- 翻页：轻量整页动画 ----------------
     两页只用 transform/opacity 动画；等动画结束再提交页码，减少掉帧与闪切。 */
  function readerPagesReady() {
    if (!S.readerPages || !S.readerPages.length) S.readerPages = PageEngine.get(S.bookChapter);
    return S.readerPages;
  }
  /** 淡入动画：挂一次性动画类；强制回流保证同一次阅读里能重复播放 */
  function flashPageFade() {
    var root = $('#readerRoot');
    if (!root) return;
    root.classList.remove('pag-fade');
    void root.offsetWidth;
    root.classList.add('pag-fade');
    clearTimeout(flashPageFade._t);
    flashPageFade._t = setTimeout(function () { root.classList.remove('pag-fade'); }, 420);
  }
  function readerTurn(dir) {
    if (S.page !== 'reader' || S.readerFlipping) return;
    if (S.book.format === 'PDF') {
      if ((dir > 0 && S.bookChapter < S.book.chapters.length - 1) || (dir < 0 && S.bookChapter > 0)) readerChapter(dir, dir < 0 ? 'end' : 'start');
      else toast(dir > 0 ? '已经是最后一章了' : '已经是第一章了');
      return;
    }
    var pages = readerPagesReady();
    if (!pages.length) return;
    hideSelBar();
    if (dir > 0 && S.readerPage >= pages.length - 1) { readerChapterTurn(1); return; }
    if (dir < 0 && S.readerPage <= 0) { readerChapterTurn(-1); return; }
    flipTo(S.bookChapter, pages, S.readerPage + dir, dir);
  }
  /** 章边界：目标章也先分页，目标页作为翻页动画的下一页 */
  function readerChapterTurn(dir) {
    var n = (S.book.chapters || []).length;
    var ni = S.bookChapter + dir;
    if (ni < 0 || ni >= n) { toast(dir > 0 ? '已经是最后一章了' : '已经是第一章了'); return; }
    saveReaderProgress(true);
    S.readerHistory.push({ index: S.bookChapter, title: currentChapter().title || '' });
    if (S.readerHistory.length > 60) S.readerHistory.shift();
    var pages = PageEngine.get(ni);
    flipTo(ni, pages, dir > 0 ? 0 : pages.length - 1, dir);
  }
  function flipTo(ni, pages, idx, dir) {
    var anim = rcfg().anim || 'slide';
    var cur = $('#readerSheet'), nxt = $('#readerSheetNext');
    if (!cur || !nxt) { commitPage(ni, pages, idx); return; }
    if (anim === 'none') { commitPage(ni, pages, idx); return; }
    S.readerFlipping = true;
    var html = pageHtml(pages[idx]);
    if (anim === 'flip') {
      nxt.innerHTML = '<div class="reader-page">' + html + '</div>';
      nxt.className = 'reader-sheet show' + (dir < 0 ? ' flip-top' : '');
      cur.classList.remove('flip-out', 'flip-in');
      nxt.classList.remove('flip-out', 'flip-in');
      void cur.offsetWidth;
      if (dir > 0) cur.classList.add('flip-out');
      else nxt.classList.add('flip-in');
    } else if (anim === 'slide') {
      nxt.innerHTML = '<div class="reader-page">' + html + '</div>';
      nxt.className = 'reader-sheet show flip-top ' + (dir > 0 ? 'slide-in-right' : 'slide-in-left');
      cur.classList.remove('slide-out-left', 'slide-out-right');
      cur.classList.add(dir > 0 ? 'slide-out-left' : 'slide-out-right');
    } else {
      commitPage(ni, pages, idx);
      flashPageFade();
      S.readerFlipping = false;
      return;
    }
    clearTimeout(flipTo._t);
    flipTo._t = setTimeout(function () {
      commitPage(ni, pages, idx);
      S.readerFlipping = false;
    }, anim === 'flip' ? 500 : 300);
  }
  function commitPage(ni, pages, idx) {
    var changedChapter = ni !== S.bookChapter;
    S.bookChapter = ni;
    S.readerPages = pages;
    S.readerPage = Math.max(0, Math.min(pages.length - 1, num(idx, 0)));
    S.readerAnchor = pages[S.readerPage] ? pages[S.readerPage].start : 0;
    if (changedChapter) S.readerSearchTerm = '';
    var cur = $('#readerSheet');
    if (cur) {
      cur.className = 'reader-sheet';
      var pageEl = cur.querySelector('.reader-page');
      if (pageEl) pageEl.innerHTML = pageHtml(pages[S.readerPage]);
    }
    var nxt = $('#readerSheetNext');
    if (nxt) { nxt.className = 'reader-sheet'; nxt.innerHTML = ''; }
    S.readerFlipping = false;
    updateReaderChrome();
    saveReaderProgress(false);
  }
  /** 切到上/下一章（PDF 与目录跳转用，整页重绘） */
  function readerChapter(dir, edge) {
    var n = (S.book.chapters || []).length;
    var ni = Math.max(0, Math.min(n - 1, S.bookChapter + dir));
    if (ni === S.bookChapter) { toast(dir > 0 ? '已经是最后一章了' : '已经是第一章了'); return; }
    saveReaderProgress(true);
    S.readerHistory.push({ index: S.bookChapter, title: currentChapter().title || '' });
    if (S.readerHistory.length > 60) S.readerHistory.shift();
    S.bookChapter = ni;
    S.readerRestore = edge === 'end' ? 1 : 0;
    S.readerSearchTerm = '';
    S.readerPage = 0;
    S.readerPages = [];
    S.readerAnchor = 0;
    S.readerFlipping = false;
    if ((rcfg().anim || 'slide') === 'fade') S.readerFade = true;
    render();
  }
  function readerJump(index, term) {
    var n = (S.book.chapters || []).length;
    index = Math.max(0, Math.min(n - 1, num(index, 0)));
    if (index === S.bookChapter && !term) {
      // 没发生实际跳转（例如拖动进度条又松回原章）：把进度条文字恢复成当前状态
      readerDrawerToggle(false);
      updateReaderProgress();
      return;
    }
    saveReaderProgress(true);
    S.readerHistory.push({ index: S.bookChapter, title: currentChapter().title || '' });
    S.bookChapter = index;
    S.readerRestore = 0;
    S.readerPage = 0;
    S.readerPages = [];
    S.readerAnchor = 0;
    S.readerFlipping = false;
    S.readerSearchTerm = term || '';
    S.readerJumpTerm = term || '';
    S.readerPanel = '';
    S.readerDrag = false;
    if ((rcfg().anim || 'slide') === 'fade') S.readerFade = true;
    readerDrawerToggle(false);
    render();
  }
  function saveReaderProgress(force) {
    if (!S.book.id) return;
    var now = Date.now();
    var sec = Math.round((now - (S.readerSaveAt || now)) / 1000);
    if (!force && sec < 4) return;
    if (sec < 0) sec = 0;
    S.readerSaveAt = now;
    var pct = bookPercent();
    S.book.progress = { chapter: S.bookChapter, percent: pct, scroll: bookFrac(), ts: now };
    call('bookProgress', { id: S.book.id, chapter: S.bookChapter, percent: pct, scroll: bookFrac(), seconds: Math.min(sec, 300) }).catch(function () { });
  }
  function leaveReader() {
    saveReaderProgress(true);
    if (S.autoTimer) { clearInterval(S.autoTimer); S.autoTimer = null; }
    if (S.readerTimer) { clearInterval(S.readerTimer); S.readerTimer = null; }
    pauseReaderBars();
    S.readerBars = 0;
    S.readerDrawer = 0;
    S.readerDrag = false;
    S.readerFade = false;
    S.autoOn = false;
    readerTtsStop();
    S.readerSel = null;
    S.readerPanel = '';
    S.readerPage = 0;
    S.readerPages = [];
    S.readerAnchor = 0;
    S.readerFlipping = false;
    S.readerJumpTerm = '';
    clearTimeout(flipTo._t);
    S.book = {};
    S.pdfCache = {};
    S.readerNotes = [];
    S.readerResults = [];
    S.readerHistory = [];
    document.body.classList.remove('reader-mode');
  }
  function openBook(id) {
    call('bookGet', { id: id }).then(function (b) {
      if (!b || !b.ok) throw new Error(b && b.error ? b.error : '打开失败');
      S.book = b;
      S.bookChapter = b.progress ? num(b.progress.chapter, 0) : 0;
      S.readerRestore = b.progress ? num(b.progress.scroll, 0) : 0;
      S.readerNotes = b.notes || [];
      S.readerPanel = '';
      S.readerSearch = '';
      S.readerResults = [];
      S.readerSearchTerm = '';
      S.readerJumpTerm = '';
      S.readerPage = 0;
      S.readerPages = [];
      S.readerAnchor = 0;
      S.readerFlipping = false;
      S.readerHistory = [];
      S.pdfCache = {};
      S.readerSaveAt = Date.now();
      if (S.readerTimer) clearInterval(S.readerTimer);
      S.readerTimer = setInterval(function () {
        if (S.page === 'reader') saveReaderProgress(true);
      }, 30000);
      go('reader');
      if (!S.bookCfg || !S.bookCfg.fontSize) {
        call('bookConfig').then(function (r) { S.bookCfg = r.config || {}; if (S.page === 'reader') render(); }).catch(function () { });
      }
    }).catch(function (e) { toast(e.message); });
  }
  function readerTtsStop() {
    S.ttsPlaying = false;
    call('bookTtsStop').catch(function () { });
    if (S.ttsTimer) { clearTimeout(S.ttsTimer); S.ttsTimer = null; }
    syncReaderUI();
  }
  function readerTtsStart() {
    if (S.ttsPlaying) { readerTtsStop(); syncReaderUI(); return; }
    var text = (currentChapter().text || '').slice(0, 6000);
    if (!text) { toast('当前章节没有可朗读文字'); return; }
    S.ttsPlaying = true;
    call('bookTts', { text: text, rate: num(rcfg().ttsRate, 1) }).catch(function (e) { S.ttsPlaying = false; toast(e.message); });
    if (S.ttsTimerMin) {
      S.ttsTimer = setTimeout(function () { readerTtsStop(); toast('朗读定时结束'); if (S.page === 'reader') syncReaderUI(); }, S.ttsTimerMin * 60000);
    }
    syncReaderUI();
  }
  function toggleAutoReader() {
    if (S.autoOn) {
      if (S.autoTimer) clearInterval(S.autoTimer);
      S.autoTimer = null; S.autoOn = false; syncReaderUI(); return;
    }
    S.autoOn = true;
    var sec = Math.max(5, num(rcfg().autoSec, 25));
    S.autoTimer = setInterval(function () {
      if (S.page !== 'reader') return;
      readerTurn(1);
    }, sec * 1000);
    syncReaderUI();
  }
  function doBookSearch() {
    var el = $('#readerSearchInput');
    var term = el ? el.value.trim() : '';
    S.readerSearch = term;
    S.readerResults = [];
    if (!term) { refreshReaderPanel(); return; }
    var low = term.toLowerCase();
    (S.book.chapters || []).forEach(function (c, i) {
      var t = c.text || '';
      var at = t.toLowerCase().indexOf(low);
      if (at < 0 || S.readerResults.length >= 80) return;
      var start = Math.max(0, at - 35);
      var snippet = t.slice(start, Math.min(t.length, at + term.length + 55));
      S.readerResults.push({ index: i, title: c.title || '', snippet: snippet.replace(/\n/g, ' '), term: term });
    });
    refreshReaderPanel();
  }
  function showSelBar(e) {
    var sel = window.getSelection();
    var text = sel ? sel.toString().trim() : '';
    if (!text || text.length > 1200) { hideSelBar(); return; }
    var bar = $('#selBar');
    if (!bar) return;
    S.readerSel = { text: text, chapter: S.bookChapter };
    var rect = null;
    try { rect = sel.getRangeAt(0).getBoundingClientRect(); } catch (err) { }
    bar.classList.remove('hidden');
    var top = rect ? rect.top - 54 : 90;
    var left = rect ? rect.left + rect.width / 2 : (window.innerWidth / 2);
    bar.style.top = Math.max(60, Math.min(window.innerHeight - 130, top)) + 'px';
    bar.style.left = Math.max(120, Math.min(window.innerWidth - 120, left)) + 'px';
  }
  function hideSelBar() {
    var bar = $('#selBar');
    if (bar) bar.classList.add('hidden');
  }
  function copyText(text) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text);
      } else {
        var ta = document.createElement('textarea');
        ta.value = text; document.body.appendChild(ta); ta.select();
        document.execCommand('copy'); document.body.removeChild(ta);
      }
      toast('已复制');
    } catch (e) { toast('复制失败'); }
  }
  function refreshBookNotes() {
    if (!S.book.id) return;
    call('bookNotes', { id: S.book.id }).then(function (r) {
      S.readerNotes = r.notes || [];
      if (S.page === 'reader') {
        paintCurrentPage();
        if (S.readerPanel) refreshReaderPanel();
      }
    }).catch(function () { });
  }
  function bookById(id) {
    var out = null;
    (S.books || []).forEach(function (b) { if (b.id === id) out = b; });
    return out;
  }
  function filterShelf(q) {
    q = (q || '').trim().toLowerCase();
    $$('.book-card').forEach(function (card) {
      var b = bookById(card.getAttribute('data-id'));
      var hit = !q || (b && ((b.title || '').toLowerCase().indexOf(q) >= 0
        || (b.author || '').toLowerCase().indexOf(q) >= 0
        || (b.group || '').toLowerCase().indexOf(q) >= 0));
      card.style.display = hit ? '' : 'none';
    });
  }
  function reloadShelf() {
    call('bookList').then(function (r) {
      S.books = r.books || [];
      S.bookStats = r.stats || {};
      S.bookCfg = r.config || S.bookCfg;
      if (S.page === 'readerShelf') render();
    }).catch(function () { });
  }
  function saveReaderCfg() {
    call('bookConfigSave', { config: S.bookCfg || {} }).catch(function (e) { toast(e.message); });
  }
  function openBookMenu(id) {
    var b = bookById(id);
    if (!b) return;
    S.bookMenuId = id;
    openModal('<div class="modal-head"><h3>' + esc(b.title || '书籍') + '</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<label class="field"><span>分组</span><input id="bookGroup" placeholder="例如：小说 / 学习 / 待读" value="' + esc(b.group || '') + '"></label>' +
      '<div class="btn-row"><button class="btn primary" data-act="bookSaveMeta" data-id="' + esc(id) + '">保存</button>' +
      '<button class="btn" data-act="bookCoverPick" data-id="' + esc(id) + '">换封面</button>' +
      '<button class="btn ghost" data-act="bookExport" data-id="' + esc(id) + '" data-title="' + esc(b.title || '') + '">导出</button></div>' +
      '<div class="btn-row" style="margin-top:10px"><button class="btn ghost" data-act="bookToggleHidden" data-id="' + esc(id) + '" data-on="' + (b.hidden ? '0' : '1') + '">' + (b.hidden ? '取消隐藏' : '隐藏') + '</button>' +
      '<button class="btn ghost" data-act="bookToggleFinished" data-id="' + esc(id) + '" data-on="' + (b.finished ? '0' : '1') + '">' + (b.finished ? '标记未读完' : '标记读完') + '</button></div>' +
      '<button class="btn danger block sm" data-act="bookDelete" data-id="' + esc(id) + '" style="margin-top:10px">删除这本书</button>');
  }

  function tile(page, icon, title, sub) {
    return '<button class="tile" data-act="openPage" data-page="' + page + '"><span class="ti">' + icon + '</span><strong>' + esc(title) + '</strong><small>' + esc(sub) + '</small></button>';
  }
  var FEATURE_ITEMS = [
    ['persona','角色与记忆','人设 · 资料 · 回忆','角色 人设 性格 记忆','角色与关系'],
    ['dress','角色换装','皮肤 · 预览 · 导入','换装 皮肤 外观','角色与关系'],
    ['readerShelf','阅读书架','书籍 · 阅读进度 · 笔记','读书 小说 阅读 书架','行动与探索'],
    ['plugins','插件与扩展','管理插件 · 编写 Java 插件','插件 扩展 Java DEX 编写','行动与探索'],
    ['tasks','任务与专注','待办 · 日程 · 番茄钟','任务 待办 日程 专注 番茄钟','陪伴与提醒'],
    ['reminders','提醒中心','消息 · 快递 · 出行提醒','提醒 快递 出行','陪伴与提醒'],
    ['notif','通知摘要','通知分类 · 静音 · 转待办','通知 消息 摘要','陪伴与提醒'],
    ['geo','位置提醒','到家 · 到公司 · 附近','位置 地点 到家','陪伴与提醒'],
    ['wellness','健康动作','喝水 · 拉伸 · 护眼','健康 喝水 拉伸 护眼','陪伴与提醒'],
    ['musicx','音乐陪伴','歌词 · 白噪音','音乐 歌曲 白噪音','陪伴与提醒'],
    ['adskip','广告跳过','规则 · 台词 · 统计','广告 跳过','陪伴与提醒'],
    ['policy','主动记录','主动互动 · 决策记录','主动 自由行动 记录','陪伴与提醒'],
    ['report','陪伴周报','每周回顾','周报 报告','陪伴与提醒'],
    ['mem','记忆图谱','分类 · 体检 · 归档','记忆 回忆 图谱','角色与关系'],
    ['relation','我们的关系','亲密度 · 日记 · 性格','关系 亲密 日记','角色与关系'],
    ['usage','今日使用','应用时长 · 打开次数','使用 应用 时长','数据与工具'],
    ['ledger','记账','自动识别 · 月度预算','记账 钱 预算','数据与工具'],
    ['monitor','应用感知','应用使用记录','应用 感知 使用','数据与工具'],
    ['clip','剪贴板','复制即收纳 · AI 总结','剪贴板 复制','数据与工具'],
    ['sync','跨设备同步','WebDAV · 你自己的盘','同步 WebDAV 云端','数据与工具'],
    ['dev','开发者模式','调试 · 诊断 · 提示词','开发者 调试 诊断','设置与维护']
  ];
  function featureLoad(key) {
    try { var v = JSON.parse(localStorage.getItem('dsh_feature_' + key) || '[]'); return Array.isArray(v) ? v : []; }
    catch (_) { return []; }
  }
  function featureSave(key, value) {
    try { localStorage.setItem('dsh_feature_' + key, JSON.stringify(value)); } catch (_) { }
  }
  function featureById(id) {
    if (id === 'dev' && S.cfg && S.cfg.devLocked) return null;
    for (var i = 0; i < FEATURE_ITEMS.length; i++) if (FEATURE_ITEMS[i][0] === id) return FEATURE_ITEMS[i];
    return null;
  }
  function featureIcon(id) {
    var icons = {
      actions: '<svg viewBox="0 0 24 24"><path d="M12 3l2.2 6.8L21 12l-6.8 2.2L12 21l-2.2-6.8L3 12l6.8-2.2z"/><path d="M18 3v4M16 5h4"/></svg>',
      stickers: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M8.5 10h.01M15.5 10h.01M8 14c1 2 2.3 3 4 3s3-1 4-3"/></svg>',
      plugins: '<svg viewBox="0 0 24 24"><path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"/></svg>',
      readerShelf: '<svg viewBox="0 0 24 24"><path d="M3 5h7a3 3 0 0 1 2 1v14a3 3 0 0 0-2-1H3zM21 5h-7a3 3 0 0 0-2 1v14a3 3 0 0 1 2-1h7z"/></svg>',
      companion: '<svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 0 1 16 0v4a3 3 0 0 1-3 3"/><path d="M4 12v4a3 3 0 0 0 3 3h1v-7H4zM20 12v4a3 3 0 0 1-3 3h-1v-7h4z"/></svg>',
      tasks: '<svg viewBox="0 0 24 24"><path d="M5 7h14M5 12h9M5 17h6"/><circle cx="18" cy="16" r="3"/></svg>',
      reminders: '<svg viewBox="0 0 24 24"><path d="M6 4h12v16l-6-4-6 4z"/><path d="M9 8h6"/></svg>',
      notif: '<svg viewBox="0 0 24 24"><path d="M6 9a6 6 0 0 1 12 0v5l2 3H4l2-3z"/><path d="M10 20h4"/></svg>',
      geo: '<svg viewBox="0 0 24 24"><path d="M12 21s7-6 7-11a7 7 0 1 0-14 0c0 5 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/></svg>',
      wellness: '<svg viewBox="0 0 24 24"><path d="M3 12h4l2-6 4 12 3-8 2 2h3"/></svg>',
      musicx: '<svg viewBox="0 0 24 24"><path d="M9 18V6l10-2v12"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="16" r="2"/></svg>',
      adskip: '<svg viewBox="0 0 24 24"><path d="M5 5l14 14M19 5L5 19"/></svg>',
      policy: '<svg viewBox="0 0 24 24"><path d="M12 8v5l3 2"/><circle cx="12" cy="12" r="8"/></svg>',
      report: '<svg viewBox="0 0 24 24"><path d="M6 4h12v16H6z"/><path d="M9 9h6M9 13h6M9 17h4"/></svg>',
      persona: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.4"/><path d="M5 20c.8-4 3.6-6 7-6s6.2 2 7 6"/></svg>',
      mem: '<svg viewBox="0 0 24 24"><path d="M12 3l2.4 5.6L20 11l-5.6 2.4L12 19l-2.4-5.6L4 11l5.6-2.4z"/></svg>',
      relation: '<svg viewBox="0 0 24 24"><path d="M12 20s-7-4.4-7-9a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 4.6-7 9-7 9z"/></svg>',
      dress: '<svg viewBox="0 0 24 24"><path d="M9 4l3 2 3-2 3 3-2 3v10H8V10L6 7z"/></svg>',
      usage: '<svg viewBox="0 0 24 24"><path d="M4 20V11M9 20V4M14 20v-6M19 20V7M2 20h20"/></svg>',
      ledger: '<svg viewBox="0 0 24 24"><path d="M3 7h18v12H3zM3 7l2-3h14l2 3"/><path d="M16 13h2"/></svg>',
      monitor: '<svg viewBox="0 0 24 24"><path d="M2 12s4-6 10-6 10 6 10 6-4 6-10 6S2 12 2 12z"/><circle cx="12" cy="12" r="2.6"/></svg>',
      clip: '<svg viewBox="0 0 24 24"><path d="M8 8h11v12H8zM5 16V4h11"/></svg>',
      sync: '<svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 0 1 14-5m2-3v5h-5"/><path d="M20 12a8 8 0 0 1-14 5m-2 3v-5h5"/></svg>',
      health: '<svg viewBox="0 0 24 24"><path d="M12 3l8 4v6c0 5-3.5 7.5-8 8-4.5-.5-8-3-8-8V7z"/><path d="M9 12l2 2 4-4"/></svg>',
      core: '<svg viewBox="0 0 24 24"><path d="M12 3v3M12 18v3M3 12h3M18 12h3"/><circle cx="12" cy="12" r="4"/><path d="M6.3 6.3l2.1 2.1m7.2 7.2 2.1 2.1m0-11.4-2.1 2.1m-7.2 7.2-2.1 2.1"/></svg>',
      sg: '<svg viewBox="0 0 24 24"><path d="M4 7c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 7v10c0 1.7 3.6 3 8 3s8-1.3 8-3V7"/></svg>',
      backup: '<svg viewBox="0 0 24 24"><path d="M4 7h16v13H4zM3 4h18v3H3z"/><path d="M9 12h6M12 9v6"/></svg>',
      dev: '<svg viewBox="0 0 24 24"><path d="M8 8l-5 4 5 4M16 8l5 4-5 4M13 5l-2 14"/></svg>'
    };
    return icons[id] || '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/><path d="M8 12h8M12 8v8"/></svg>';
  }
  function featureCard(item, pinned) {
    return '<div class="feature-card"><button class="tile" data-act="openPage" data-page="' + item[0] + '"><span class="ti">' + featureIcon(item[0]) + '</span><strong>' + esc(item[1]) + '</strong><small>' + esc(item[2]) + '</small></button></div>';
  }
  function featureSearchHtml(query, favoritesOnly) {
    var q = String(query || '').trim().toLowerCase();
    var list = featureItemsOrdered().filter(function (it) {
      return featureById(it[0]) && (!favoritesOnly || S.featureFavs.indexOf(it[0]) >= 0) && (!q || (it[1] + ' ' + it[2] + ' ' + it[3] + ' ' + it[4]).toLowerCase().indexOf(q) >= 0);
    });
    return list.length ? '<div class="tiles feature-tiles">' + list.map(function (it) { return featureCard(it, S.featureFavs.indexOf(it[0]) >= 0); }).join('') + '</div>' : '<div class="empty">没有找到相关功能，试试“插件”“提醒”或“阅读”。</div>';
  }
  function featureItemsOrdered() {
    var first = ['persona', 'dress', 'readerShelf'];
    return FEATURE_ITEMS.slice().sort(function (a, b) {
      var ai = first.indexOf(a[0]), bi = first.indexOf(b[0]);
      if (ai >= 0 || bi >= 0) return (ai < 0 ? 99 : ai) - (bi < 0 ? 99 : bi);
      return a[1].localeCompare(b[1]);
    });
  }
  function featureGroupsHtml() {
    var groups = ['聊天与表达', '角色与关系', '行动与探索', '陪伴与提醒', '数据与工具', '设置与维护'];
    var html = '';
    groups.forEach(function (group) {
      var items = featureItemsOrdered().filter(function (it) { return it[4] === group && featureById(it[0]); });
      if (!items.length) return;
      html += '<section class="feature-group"><div class="feature-group-head"><h3>' + esc(group) + '</h3><span>' + items.length + '</span></div>' +
        '<div class="tiles feature-tiles">' + items.map(function (it) { return featureCard(it, S.featureFavs.indexOf(it[0]) >= 0); }).join('') + '</div></section>';
    });
    return html || '<div class="empty">暂时没有可显示的功能。</div>';
  }
  function rememberFeature(page) {
    if (!featureById(page)) return;
    S.featureRecent = [page].concat((S.featureRecent || []).filter(function (id) { return id !== page; })).slice(0, 5);
    featureSave('recent', S.featureRecent);
  }
  function reminderIcon(type) {
    if (type === 'msg') return '<svg viewBox="0 0 24 24"><path d="M4 5h16v11H9l-5 4z"/></svg>';
    if (type === 'parcel') return '<svg viewBox="0 0 24 24"><path d="M4 8l8-4 8 4v9l-8 4-8-4z"/><path d="M4 8l8 4 8-4M12 12v9"/></svg>';
    if (type === 'travel') return '<svg viewBox="0 0 24 24"><path d="M3 14l18-5-4 9-4-2-3 4-1-4z"/></svg>';
    return '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/><path d="M12 8v5l3 2"/></svg>';
  }
  function storageShort() {
    var s = (S.state.storage || {}).path || '/Documents/S-G';
    return s.replace(/^.*\/storage\/emulated\/0\//, '').replace(/^.*Android\/data\/.*\//, '');
  }

  /* ---------------- 聊天 ---------------- */
  /* 破冰期（初识 + 认识不到 7 天）：给她一点「她想了解你」的暗示 */
  function iceHintOn() {
    if (!S.iceHint) return false;
    var im = S.intimacy || {};
    if (num(im.stageIndex, 0) > 0) return false;
    if (num(S.state.days, 1) >= 7) return false;
    if ((S.chat || []).length > 8) return false;
    return true;
  }
  function iceHintHtml() {
    if (!iceHintOn()) return '';
    return '<div class="ice-hint"><div class="ih-txt"><b>她还在认识你</b>' +
      '<span>说什么都行，不用先自我介绍。也可以让她先开口问一句。</span></div>' +
      '<div class="ih-acts"><button class="btn primary sm" data-act="iceAsk">让她先问</button>' +
      '<button class="ice-x" data-act="iceClose" title="收起">×</button></div></div>';
  }

  /* 输入框的表情面板：上传过的表情都在这，点一下直接发出去 */
  function stickerPanelHtml(manage) {
    var list = S.stickers || [];
    var html = '<div class="sp-head"><b>表情包</b>' + (manage ? '<button class="btn sm ghost" data-act="stickerManageClose">收起</button>' : '') + '</div>';
    if (manage) {
      html += '<div class="sticker-manage-actions"><button class="btn primary sm" data-act="stickerImport">上传一张</button><button class="btn ghost sm" data-act="stickerBatch">批量导入</button>' +
        (list.length ? '<button class="btn ghost sm" id="stickerAllBtn" data-act="stickerDescribeAll">一键识别全部 ' + list.length + ' 个</button>' : '') + '</div>';
      html += '<div class="faint sticker-manage-tip">支持 PNG、JPG、WebP。建议用情绪给表情命名；点开单张可查看大图、刷新识别、更换图片或编辑使用场景。' +
        (list.length && !(S.cfg && S.cfg.apiKey) ? '自动识别需要先绑定 API Key。' : '') + '</div>';
      if (!list.length) return html + '<div class="sp-empty">还没有表情包，点上方“上传一张”添加。</div>';
      html += '<div class="sticker-grid sticker-manage-grid">';
      list.forEach(function (s) {
        var url = stickerUrl(s.name);
        html += '<div class="sticker-cell">' +
          (url ? '<img src="' + esc(url) + '" data-act="stickerOpen" data-id="' + esc(s.id || '') + '" data-name="' + esc(s.name) + '" onerror="__stickerFallback(this)" alt="' + esc(s.name) + '">' : '<div class="sticker-none">?</div>') +
          '<b>' + esc(s.name || '') + '</b><span>' + esc(s.desc || '还没写适用场景') + '</span>' +
          '<button class="li-act danger" data-act="stickerDel" data-id="' + esc(s.id || '') + '">删除</button></div>';
      });
      return html + '</div><div class="faint sticker-manage-tip">聊天里她会根据对话情绪选择是否使用表情包，不会每句话都硬塞一张。</div>';
    }
    if (!list.length) {
      return html + '<div class="sp-empty">还没有表情包，点加号里的“表情包”添加。</div>';
    }
    html += '<div class="sp-grid">';
    list.forEach(function (s) {
      if (!s || !s.name) return;
      var url = stickerUrl(s.name);
      html += '<button class="sp-cell" data-act="sendSticker" data-name="' + esc(s.name) + '" title="' + esc(s.name) + '">' +
        (url ? '<img src="' + esc(url) + '" data-name="' + esc(s.name) + '" onerror="__stickerFallback(this)" alt="' + esc(s.name) + '">' : '<span class="sp-none">?</span>') +
        '</button>';
    });
    return html + '</div>';
  }
  function toggleStickerPanel(manage) {
    var p = $('#stickerPanel');
    if (!p) return;
    var wasOpen = p.classList.contains('show');
    hideChatKeyboard();
    closeChatAttachments();
    S.stickerManageOpen = !!manage;
    p.innerHTML = stickerPanelHtml(S.stickerManageOpen);
    p.classList.toggle('manage', S.stickerManageOpen);
    p.classList.toggle('show', !wasOpen);
    fitChatTail();
    var m = $('#msgs');
    if (m && p.classList.contains('show')) m.scrollTop = m.scrollHeight;
  }
  function closeStickerPanel() {
    var p = $('#stickerPanel');
    if (p && p.classList.contains('show')) {
      p.classList.remove('show');
      fitChatTail();
    }
  }
  function refreshStickerPanel() {
    var p = $('#stickerPanel');
    if (!p) return;
    var shown = p.classList.contains('show');
    p.innerHTML = stickerPanelHtml(!!S.stickerManageOpen);
    p.classList.toggle('manage', !!S.stickerManageOpen);
    if (shown) p.classList.add('show');
    fitChatTail();
  }

  function renderChat() {
    // 进到聊天页就算她那条主动消息已经看到了，通知栏里的那条撤掉
    call('chatSeen').catch(function () { });
    var composerText = !!String(S.asrText || '').trim() || !!S.chatFile || !!S.chatVideo || !!S.chatImg || !!S.chatQuote;
    var canStopStream = !!(S.sending && S.streamId && S.streamText);
    var sendAction = canStopStream ? 'cancelChat' : (composerText ? 'sendChat' : 'toggleChatAttachments');
    var sendTitle = canStopStream ? '停止回复' : (composerText ? '发送' : '更多');
    var sendIcon = canStopStream
      ? '<svg viewBox="0 0 24 24"><rect x="7" y="7" width="10" height="10" rx="1"/></svg>'
      : (composerText ? '<svg viewBox="0 0 24 24"><path d="M4 12l16-7-5 16-4-6z"/></svg>'
        : '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>');
    var html = S.chatHeaderCollapsed
      ? '<div class="chat-head-compact"><button data-act="toggleChatHeader" aria-label="展开聊天信息"><span>和她聊聊</span><span class="chat-head-expand">展开⌄</span></button></div>'
      : '<div class="page-head chat-head"><div><h1>和她聊聊</h1><p>' + (S.cfg.apiKey ? 'DeepSeek 驱动 · 会记得你说过的话' : '绑定 API Key 后即可开始') + '</p></div>' +
        '<span class="tag ' + (S.cfg.ttsEnabled ? 'mint' : '') + '" data-act="ttsToggle" style="margin-right:4px">' + (S.cfg.ttsEnabled ? '朗读开' : '朗读关') + '</span>' +
        '<button class="btn sm ghost" data-act="clearChat">清空</button>' +
        '<button class="chat-head-collapse" data-act="toggleChatHeader" aria-label="收起聊天信息">⌃</button></div>';
    html += '<div class="chatpage">' + iceHintHtml() + '<div class="msgs" id="msgs">' + chatMessagesHtml() + '</div>' +
      '<div class="composer">' +
      (S.chatQuote ? quotePreviewHtml(S.chatQuote, 'composer') : '') +
      (S.chatImg ? '<div class="chat-attach"><img src="' + S.chatImg + '" alt=""><button class="chat-attach-x" data-act="chatImgClear" title="移除图片">×</button></div>' : '') +
        (S.chatFile ? '<div class="chat-file-attach"><span>文档</span><b>' + esc(S.chatFile.name) + '</b><small>不设应用字节上限 · 单轮最多读取前 10 万字 · 本机保留 7 天</small><button class="chat-attach-x" data-act="chatFileClear" title="移除文件">×</button></div>' : '') +
      (S.chatVideo ? '<div class="chat-file-attach chat-video-attach"><span>视频</span><b>' + esc(S.chatVideo.name) + '</b><small>原视频交给 Gemini 分析全片与音轨 · 本机保留 7 天</small><button class="chat-attach-x" data-act="chatVideoClear" title="移除视频">×</button></div>' : '') +
      '<div class="composer-inner">' +
      '<button class="voice-mode-btn' + (S.voiceInputMode ? ' on' : '') + '" data-act="voiceInputMode" title="' + (S.voiceInputMode ? '切换文字输入' : '语音输入') + '"><svg viewBox="0 0 24 24"><path d="M4 9v6M8 5v14M12 3v18M16 7v10M20 10v4"/></svg></button>' +
      (S.voiceInputMode
        ? '<button type="button" id="voiceHoldBtn" class="voice-hold-btn' + (S.voiceHoldActive ? ' recording' : '') + '" aria-label="按住说话">' +
          (S.voiceHoldActive ? '正在听…松开发送' : '按住说话') + '</button>'
        : '<textarea id="chatInput" rows="1" placeholder="说点什么...">' + esc(S.asrText || '') + '</textarea>') +
      '<button class="mic-btn" id="stickerBtn" data-act="stickerPanel" title="表情包"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/><path d="M8.5 10h.01M15.5 10h.01M8.5 14.5c1.2 1.6 2.4 2.3 3.5 2.3s2.3-.7 3.5-2.3"/></svg></button>' +
      (!S.voiceInputMode ? '<button class="send-btn composer-action" id="sendBtn" data-act="' + sendAction + '" title="' + sendTitle + '">' + sendIcon + '</button>' : '') +
      '</div>' +
      '<div class="chat-attach-menu" id="chatAttachMenu">' +
      '<button data-act="chatPick" class="attach-option"><span><svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="8" cy="9" r="1.5"/><path d="m4 17 5-5 4 4 3-3 4 4"/></svg></span>相册</button>' +
      '<button data-act="chatCamera" class="attach-option"><span><svg viewBox="0 0 24 24"><path d="M4 7h3l2-3h6l2 3h3a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z"/><circle cx="12" cy="13" r="4"/></svg></span>拍照</button>' +
      '<button data-act="chatFile" class="attach-option"><span><svg viewBox="0 0 24 24"><path d="M6 3h8l4 4v14H6zM14 3v5h5M9 13h6M9 17h6"/></svg></span>文件</button>' +
      '<button data-act="chatPickVideo" class="attach-option"><span><svg viewBox="0 0 24 24"><rect x="3" y="5" width="13" height="14" rx="2"/><path d="m16 10 5-3v10l-5-3z"/><path d="m8 9 4 3-4 3z"/></svg></span>视频</button>' +
      '<button data-act="stickerPanel" data-manage="1" class="attach-option"><span><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M8.5 10h.01M15.5 10h.01M8 14c1 2 2.3 3 4 3s3-1 4-3"/></svg></span>表情包</button>' +
      '</div>' +
      '<div class="sticker-panel" id="stickerPanel">' + stickerPanelHtml() + '</div>' +
      '</div></div>';
    return html + '<div class="chat-tail"></div>';
  }

  /* 滚动区底部的留白 = 输入条从顶部到底部的实际高度，键盘弹起时自动变大 */
  function fitChatTail() {
    var tail = document.querySelector('.chat-tail');
    var comp = document.querySelector('.composer');
    if (!tail || !comp) return;
    var h = Math.round(window.innerHeight - comp.getBoundingClientRect().top);
    tail.style.height = Math.max(120, h + 14) + 'px';
  }

  function chatMessagesHtml() {
    var list = S.chat || [];
    if (!list.length && !S.sending) {
      var g = (S.persona.greeting || '在呢，今天怎么样？');
      return msgHtml('assistant', g, Date.now());
    }
    var html = S.chatHasMore ? '<button type="button" class="chat-earlier" data-act="chatEarlier"' +
      (S.chatLoadingEarlier ? ' disabled' : '') + '>' +
      (S.chatLoadingEarlier ? '正在读取更早消息…' : '查看更早消息') + '</button>' : '';
    list.forEach(function (m, i) {
      var row = msgHtml(m.role, m.content, m.ts, m.link, m.linkText, i, m.improvised, m.img, m.voice, m.voiceText, m.cardType, m.cardTitle, m.cardItems, m.fileName, m.fileSummary, m.fileExpired, m.docId, m.docName, m.docSummary, m.docFormat, m.docExpired, m.quote, m.videoName, m.videoExpired);
      html += row.replace('<div class="msg', '<div data-render-key="' + chatMessageRenderKey(m) + '" class="msg');
    });
    if (S.sending && !S.replyStarted) {
      html += '<div class="msg stream-thinking"><div class="ava"><img src="file:///android_asset/webp/a032.webp" alt=""></div><div class="bubble">' +
        '<div class="typing"><i></i><i></i><i></i></div>' +
        (S.typingLine ? '<div class="typing-line">' + esc(S.typingLine) + '</div>' : '') +
        '</div></div>';
    }
    if (S.sending && S.streamId && S.streamText && !S.streamSuppressed) html += streamPreviewHtml(S.streamText);
    return html;
  }
  function chatMessageRenderKey(message) {
    var fields = ['role', 'content', 'ts', 'link', 'linkText', 'improvised', 'img', 'voice', 'voiceText',
      'cardType', 'cardTitle', 'cardItems', 'fileName', 'fileSummary', 'fileExpired', 'docId', 'docName',
      'docSummary', 'docFormat', 'docExpired', 'quote', 'videoId', 'videoName', 'videoExpired'];
    var value = '';
    try { value = JSON.stringify(fields.map(function (key) { return message && message[key]; })); }
    catch (_) { value = String((message && message.content) || ''); }
    var hash = 2166136261;
    for (var i = 0; i < value.length; i++) hash = Math.imul(hash ^ value.charCodeAt(i), 16777619);
    return (hash >>> 0).toString(36) + '-' + value.length.toString(36);
  }
  // 模型可能先输出一段“拟定回复”草稿，再用一整行横线分隔正式回复。
  // 返回最后一个分隔行之后的起始下标；还没有分隔线时返回 -1。
  function draftCutIndex(text) {
    var src = String(text || '').replace(/\r/g, '');
    var lines = src.split('\n');
    var offset = 0, cut = -1;
    for (var i = 0; i < lines.length; i++) {
      var t = lines[i].trim();
      // 分隔行本身占一整行；切点越过末尾时钳到文本长度，避免多切一个不存在的换行
      if (t.length >= 3 && /^[-—–_=~·*]+$/.test(t)) cut = Math.min(offset + lines[i].length + 1, src.length);
      offset += lines[i].length + 1;
    }
    return cut;
  }
  function streamVisibleText(text) {
    var source = String(text || '').replace(/\r/g, '');
    // 草稿块不是她说的话：流式显示与定稿都只保留分隔线之后的正文。
    // 分隔线后没有内容时保留原文，避免把整条回复清空。
    var cut = draftCutIndex(source);
    if (cut >= 0) {
      var tail = source.slice(cut);
      if (tail.replace(/\s/g, '')) source = tail;
    }
    // 控制标记只供桌宠内部处理；流式显示时连未闭合的标记也不能漏到气泡里。
    return source.replace(/[ \t]+/g, ' ')
      .replace(/\[\[[\s\S]*?\]\]/g, '').replace(/\[\[[\s\S]*$/, '');
  }
  function streamParts(text) {
    var source = streamVisibleText(text), complete = [], start = 0;
    function push(end) {
      var value = source.slice(start, end).trim();
      if (value) complete.push(value);
      start = end;
    }
    for (var i = 0; i < source.length; i++) {
      var ch = source.charAt(i);
      if (ch === '\n') { push(i); start = i + 1; continue; }
      var boundary = '。！？!?'.indexOf(ch) >= 0 ||
        (ch === '.' && (i + 1 === source.length || /\s/.test(source.charAt(i + 1))));
      if (!boundary) continue;
      var end = i + 1;
      while (end < source.length && '。！？!?'.indexOf(source.charAt(end)) >= 0) end++;
      while (end < source.length && '”’」』）】)]}'.indexOf(source.charAt(end)) >= 0) end++;
      // Wait for the next character before sealing a sentence, so a closing quote
      // or the start of the next sentence cannot be stranded in a second bubble.
      if (end >= source.length) break;
      push(end);
      i = end - 1;
    }
    return { complete: complete, tail: source.slice(start).trim() };
  }
  function streamBubbleHtml(text, partial) {
    var html = msgHtml('assistant', text, 0, '', '', -1);
    html = html.replace('<div class="msg"', '<div class="msg stream-live-bubble" data-stream-bubble="1"' +
      (partial ? ' data-stream-partial="1"' : ''));
    return html;
  }
  function streamPreviewHtml(text) {
    var parts = streamParts(text), html = '<div class="stream-preview" id="chatStreamPreview">';
    parts.complete.forEach(function (line) { html += streamBubbleHtml(line, false); });
    if (parts.tail) html += streamBubbleHtml(parts.tail, true);
    return html + '</div>';
  }
  function bindStreamPreviewNodes() {
    var holder = $('#chatStreamPreview');
    S.streamBubbleNodes = holder ? Array.prototype.slice.call(holder.querySelectorAll('[data-stream-bubble]')) : [];
    S.streamPartialNode = holder ? holder.querySelector('[data-stream-partial]') : null;
    S.streamRenderedCount = S.streamBubbleNodes.length - (S.streamPartialNode ? 1 : 0);
  }
  function appendStreamBubble(text, partial) {
    var holder = $('#chatStreamPreview');
    if (!holder || !text) return null;
    var list = $('#msgs');
    var nearBottom = list && list.scrollHeight - list.scrollTop - list.clientHeight < 150;
    holder.insertAdjacentHTML('beforeend', streamBubbleHtml(text, partial));
    var node = holder.lastElementChild;
    if (node) S.streamBubbleNodes.push(node);
    if (nearBottom && list) list.scrollTop = list.scrollHeight;
    return node;
  }
  function updateStreamPreview() {
    if (S.streamSuppressed || !S.streamText || S.page !== 'chat') return;
    if (!$('#chatStreamPreview')) {
      stopTypingLines();
      S.replyStarted = true;
      refreshChat();
    }
    var holder = $('#chatStreamPreview');
    if (!holder) return;
    var parts = streamParts(S.streamText);
    while (S.streamRenderedCount < parts.complete.length) {
      var line = parts.complete[S.streamRenderedCount];
      if (S.streamPartialNode) {
        var currentText = S.streamPartialNode.querySelector('.bubble-text');
        if (currentText) currentText.textContent = line;
        S.streamPartialNode.removeAttribute('data-stream-partial');
        S.streamPartialNode.classList.remove('stream-live-bubble');
        S.streamPartialNode = null;
        S.streamRenderedCount++;
      } else {
        var completedNode = appendStreamBubble(line, false);
        if (!completedNode) return;
        completedNode.classList.remove('stream-live-bubble');
        S.streamRenderedCount++;
      }
    }
    if (parts.tail) {
      if (!S.streamPartialNode) S.streamPartialNode = appendStreamBubble(parts.tail, true);
      else {
        var partialText = S.streamPartialNode.querySelector('.bubble-text');
        if (partialText) partialText.textContent = parts.tail;
      }
    } else if (S.streamPartialNode) {
      S.streamPartialNode.remove();
      S.streamBubbleNodes = S.streamBubbleNodes.filter(function (node) { return node !== S.streamPartialNode; });
      S.streamPartialNode = null;
    }
  }
  function updateStreamStopButton() {
    var button = $('#sendBtn');
    if (!button) return;
    if (S.sending && S.streamId && S.streamText) {
      button.setAttribute('data-act', 'cancelChat');
      button.setAttribute('title', '停止回复');
      button.innerHTML = '<svg viewBox="0 0 24 24"><rect x="7" y="7" width="10" height="10" rx="1"/></svg>';
    }
  }
  function clearStreamState(removePreview) {
    var holder = $('#chatStreamPreview');
    if (removePreview && holder) holder.remove();
    S.streamId = '';
    S.streamText = '';
    S.streamSuppressed = false;
    S.streamDraftCut = false;
    S.streamRenderedCount = 0;
    S.streamBubbleNodes = [];
    S.streamPartialNode = null;
    S.streamPreviewShown = false;
    var button = $('#sendBtn');
    if (button && button.getAttribute('data-act') === 'cancelChat') {
      var input = $('#chatInput');
      var canSend = !!String(input ? input.value : S.asrText || '').trim() || !!S.chatFile || !!S.chatVideo || !!S.chatImg;
      button.setAttribute('data-act', canSend ? 'sendChat' : 'toggleChatAttachments');
      button.setAttribute('title', canSend ? '发送' : '更多');
      button.innerHTML = canSend
        ? '<svg viewBox="0 0 24 24"><path d="M4 12l16-7-5 16-4-6z"/></svg>'
        : '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>';
    }
  }
  function commitStreamReply(reply, meta) {
    if (!S.streamId || S.streamSuppressed || !S.streamText || (meta && (meta.link || meta.voiceReply))) return false;
    var streamed = splitReplyBubbles(streamVisibleText(S.streamText));
    var finalLines = splitReplyBubbles(reply || '');
    if (!finalLines.length || streamed.length !== finalLines.length) return false;
    for (var i = 0; i < finalLines.length; i++) if (streamed[i] !== finalLines[i]) return false;
    var holder = $('#chatStreamPreview');
    var list = $('#msgs');
    if (!holder || !list || S.streamBubbleNodes.length !== finalLines.length) return false;
    stopTypingLines();
    var firstIndex = S.chat.length;
    finalLines.forEach(function (line) { S.chat.push({ role: 'assistant', content: line, ts: Date.now() }); });
    var fragment = document.createDocumentFragment();
    S.streamBubbleNodes.forEach(function (node, index) {
      node.setAttribute('data-idx', String(firstIndex + index));
      node.removeAttribute('data-stream-bubble');
      node.removeAttribute('data-stream-partial');
      node.classList.remove('stream-live-bubble');
      fragment.appendChild(node);
    });
    list.insertBefore(fragment, holder);
    holder.remove();
    var thinking = list.querySelector('.stream-thinking');
    if (thinking) thinking.remove();
    S.sending = false;
    S.replyStarted = false;
    clearStreamState(false);
    if (list.scrollHeight - list.scrollTop - list.clientHeight < 180) list.scrollTop = list.scrollHeight;
    return true;
  }
  function cancelActiveChat() {
    if (!S.streamId) return;
    var requestId = S.streamId;
    S.cancelledStreamId = requestId;
    call('cancelChat', { streamId: requestId }).catch(function () { });
    clearStreamState(true);
    S.sending = false;
    S.replyStarted = false;
    stopTypingLines();
    refreshChat();
    toast('好，先停在这里。');
  }
  function preserveInterruptedStream(requestId) {
    if (S.streamId !== requestId || !S.streamText || S.streamSuppressed) return false;
    var partial = streamVisibleText(S.streamText).trim();
    var lines = splitReplyBubbles(partial);
    if (!lines.length) return false;
    clearStreamState(true);
    stopTypingLines();
    S.sending = false;
    S.replyStarted = false;
    lines.forEach(function (line) { S.chat.push({ role: 'assistant', content: line, ts: Date.now() }); });
    S.chat.push({ role: 'assistant', content: '刚才连接断了一下，后面的内容没收到。', ts: Date.now() });
    if (S.page === 'chat') refreshChat();
    call('chatInterrupted', { text: partial }).catch(function () { });
    toast('连接中断，已保留收到的内容。');
    return true;
  }
  /* 旧版语音回复曾把多段文字存进同一条记录；重载时恢复成独立气泡。 */
  function expandStoredChatBubbles(messages) {
    var out = [];
    (messages || []).forEach(function (m) {
      var content = String((m && m.content) || '');
      if (m && m.cardType) { out.push(m); return; }
      if (!m || m.role !== 'assistant') {
        if (m) out.push(m);
        return;
      }
      // 使用和实时回复相同的切分规则；兼容旧版只存成一条的多段回复。
      var lines = splitReplyBubbles(content);
      if (lines.length < 2) { out.push(m); return; }
      lines.forEach(function (line, index) {
        var last = index === lines.length - 1;
        var copy = {};
        Object.keys(m).forEach(function (key) { copy[key] = m[key]; });
        copy.content = line;
        copy.link = last ? (m.link || '') : '';
        copy.linkText = last ? (m.linkText || '') : '';
        copy.voice = last && !!m.voice;
        copy.voiceText = last && m.voice ? (m.voiceText || content) : '';
        if (!last) copy.img = '';
        out.push(copy);
      });
    });
    return out;
  }
  /* 表情包标记：[[表情:名字]]，只显示图，不显示标记 */
  function stickerTag(text) {
    var m = /\[\[\s*表情\s*[:：]\s*([^\]\n]{1,16})\s*\]\]/.exec(text || '');
    if (!m) return null;
    return { name: m[1].trim(), text: (text || '').replace(m[0], '').trim() };
  }
  function stickerByName(name) {
    var list = S.stickers || [];
    for (var i = 0; i < list.length; i++) {
      if (list[i] && list[i].name === name) return list[i];
    }
    return null;
  }
  function stickerUrl(name) {
    var st = stickerByName(name);
    var base = (S.state && S.state.storage && S.state.storage.path) || '';
    if (!st || !st.file || !base) return '';
    return 'file://' + base + '/stickers/' + st.file;
  }
  /* 聊天图片：当次会话是 data URL，历史里是 S-G 内的相对路径，统一成可访问的地址 */
  function chatImgSrc(img) {
    if (!img) return '';
    if (/^(data:|file:|https?:|content:)/i.test(img)) return img;
    var base = (S.state && S.state.storage && S.state.storage.path) || '';
    return base ? 'file://' + base + '/' + img : '';
  }
  function profileName() {
    return String((S.profile || {}).nickname || '').trim();
  }
  function userAvatarHtml() {
    var data = String((S.profile || {}).avatarData || '');
    return data.indexOf('data:image/') === 0
      ? '<img src="' + esc(data) + '" alt="">'
      : esc(profileName().slice(0, 1) || '我');
  }
  function quoteAuthorName(role) {
    return role === 'user' ? (profileName() || '我') : ((S.persona || {}).name || '桌宠');
  }
  function quotePreviewHtml(quote, mode) {
    if (!quote || !String(quote.content || '').trim()) return '';
    var text = String(quote.content).trim();
    if (text.length > 220) text = text.slice(0, 220) + '…';
    var css = mode === 'composer' ? 'chat-quote-preview' : 'msg-quote';
    var remove = mode === 'composer'
      ? '<button class="quote-remove" data-act="chatQuoteClear" aria-label="取消引用">×</button>' : '';
    var author = String(quote.name || '').trim() || quoteAuthorName(quote.role);
    return '<div class="' + css + '"><div class="quote-preview-text"><b>' + esc(author) + '：</b>' + esc(text) + '</div>' + remove + '</div>';
  }
  function msgHtml(role, text, ts, link, linkText, idx, improvised, img, voice, voiceText, cardType, cardTitle, cardItems, fileName, fileSummary, fileExpired, docId, docName, docSummary, docFormat, docExpired, quote, videoName, videoExpired) {
    var me = role === 'user';
    var at = ' data-idx="' + (typeof idx === 'number' ? idx : -1) + '"';
    var improv = !!improvised;
    var st = stickerTag(text);
    var shown = st ? st.text : (text || '');
    var stUrl = st ? stickerUrl(st.name) : '';
    var shot = chatImgSrc(img);
    var card = '';
    if (cardType === 'session_insights') {
      var items = Array.isArray(cardItems) ? cardItems : [];
      card = '<details class="insight-card"><summary><b>' + esc(cardTitle || '共同心得') + '</b><small>' + items.length + ' 条 · 点开聊聊</small></summary>' +
        (items.length ? '<ol>' + items.map(function (x) { return '<li>' + esc(String(x || '')) + '</li>'; }).join('') + '</ol>' : '') + '</details>';
    }
    var fileCard = me && fileName ? '<div class="chat-file-card"><b>▤ ' + esc(fileName) + '</b><small>' + esc(fileSummary || (fileExpired ? '原文件已清理，仅保留摘要' : '文件内容已用于本轮分析；原文件保留 7 天')) + '</small></div>' : '';
    var videoCard = me && videoName ? '<div class="chat-file-card chat-video-card"><b>▶ ' + esc(videoName) + '</b><small>' + (videoExpired ? '本机视频已清理；聊天记录仍保留' : '原视频已提交 Gemini 分析；本机原件保留 7 天') + '</small></div>' : '';
    var docCard = !me && cardType === 'long_text_document' ? '<button type="button" class="longdoc-card' + (docExpired ? ' expired' : '') + '" data-act="longDocOpen" data-id="' + esc(docId || '') + '"><b>▤ ' + esc(docName || '长文档') + ' <small>' + esc(String(docFormat || 'md').toUpperCase()) + '</small></b><span>' + esc(docSummary || 'AI 已整理完成') + '</span><small>' + (docExpired ? '正文已过期，仅保留摘要' : '点开查看、复制或下载 · 保留 7 天') + '</small></button>' : '';
    var bubble = '<div class="bubble' + (improv ? ' improv' : '') + (card ? ' insight-bubble' : '') + '"' +
      (improv ? ' data-act="improvReply" title="点一下回她"' : '') + '>' +
      (improv ? '<span class="improv-tag">即兴</span>' : '') +
      quotePreviewHtml(quote, 'message') +
      (shot ? '<img class="chat-shot" src="' + esc(shot) + '" onerror="this.classList.add(&quot;chat-shot-error&quot;);this.removeAttribute(&quot;src&quot;)" alt="图片加载失败">' : '') +
      fileCard +
      videoCard +
      docCard +
      (card || '<span class="bubble-text">' + esc(shown) + '</span>') +
      (stUrl ? '<img class="sticker-img" src="' + esc(stUrl) + '" data-name="' + esc(st.name) + '" onerror="__stickerFallback(this)" alt="' + esc(st.name) + '">' : '') +
      (!me && voice && typeof idx === 'number' && idx >= 0 ? '<button type="button" class="chat-voice" data-act="chatVoice" data-idx="' + idx + '" aria-label="播放语音消息"><span class="chat-voice-play">▶</span><span>语音消息</span><span class="chat-voice-hint">点击播放</span></button>' : '') +
      (link ? '<button class="msg-link" data-act="openUrl" data-url="' + esc(link) + '">' + esc(linkText || '打开链接') + '</button>' : '') +
      '</div>';
    if (me) {
      var userName = profileName() || '我';
      return '<div class="msg me"' + at + '><div class="ava" title="' + esc(userName) + '">' + userAvatarHtml() + '</div><div class="msg-col">' +
        '<div class="msg-author">' + esc(userName) + '</div>' +
        (ts ? '<div class="msg-time">' + fmt(ts) + '</div>' : '') +
        bubble + '</div></div>';
    }
    var petName = String((S.persona || {}).name || '桌宠');
    return '<div class="msg"' + at + '><div class="ava" title="' + esc(petName) + '"><img src="file:///android_asset/webp/a032.webp" alt=""></div>' +
      '<div class="msg-col msg-col-her"><div class="msg-author">' + esc(petName) + '</div>' + bubble + '</div></div>';
  }
  function startAsr() {
    S.asrOn = true;
    S.asrText = '';
    S.asrPending = false;
    call('asrStart').catch(function (e) {
      S.asrOn = false;
      render();
      toast(e.message);
    });
    render();
    toast('请说话…');
  }
  function beginVoiceHold() {
    if (!S.voiceInputMode || S.voiceHoldActive || S.sending) return;
    if (!(S.perms && S.perms.record)) {
      toast('请先允许麦克风权限，再按住说话');
      call('requestPerm', { kind: 'record' }).catch(function (e) { toast(e.message); });
      return;
    }
    S.voiceHoldActive = true;
    S.voiceHoldSend = true;
    if (S.asrFinishTimer) clearTimeout(S.asrFinishTimer);
    S.asrFinishTimer = null;
    S.asrText = '';
    var b = $('#voiceHoldBtn');
    if (b) { b.classList.add('recording'); b.textContent = '正在听…松开发送'; }
    call('asrStart').then(function () { S.asrOn = true; }).catch(function (e) {
      S.voiceHoldActive = false;
      S.voiceHoldSend = false;
      S.asrOn = false;
      var btn = $('#voiceHoldBtn');
      if (btn) { btn.classList.remove('recording'); btn.textContent = '按住说话'; }
      toast(e.message || '语音输入启动失败');
    });
  }
  function endVoiceHold(cancelled) {
    if (!S.voiceHoldActive) return;
    S.voiceHoldActive = false;
    var b = $('#voiceHoldBtn');
    if (b) { b.classList.remove('recording'); b.textContent = cancelled ? '按住说话' : '识别中…'; }
    if (cancelled) {
      if (S.asrFinishTimer) clearTimeout(S.asrFinishTimer);
      S.asrFinishTimer = null;
      S.voiceHoldSend = false;
      S.asrText = '';
      S.asrOn = false;
      call('asrStop').catch(function () { });
      if (b) b.textContent = '按住说话';
      return;
    }
    call('asrFinish').catch(function () { });
    // Android recognizers often need several seconds after stopListening() to
    // return their final result. Give the recognizer a full 10 seconds to settle.
    S.asrFinishTimer = setTimeout(function () {
      S.asrFinishTimer = null;
      if (!S.voiceHoldSend) return;
      S.voiceHoldSend = false;
      S.asrOn = false;
      call('asrStop').catch(function () { });
      if (String(S.asrText || '').trim()) sendChat();
      else {
        if (S.page === 'chat') render();
        toast('系统语音服务没有返回识别文字。请检查麦克风权限和手机的语音识别服务，再试一次');
      }
    }, 10000);
  }
  function refreshChat() {
    var m = $('#msgs');
    if (!m) return;
    var nearBottom = m.scrollHeight - m.scrollTop - m.clientHeight < 120;
    var oldTop = m.scrollTop;
    var html = chatMessagesHtml();
    // 重用相同内容的气泡节点，尤其保留已解码的本地表情图，避免刷新历史时闪烁。
    var incoming = document.createElement('div');
    incoming.innerHTML = html;
    var reusable = Object.create(null);
    Array.prototype.forEach.call(m.children, function (node) {
      var key = node.getAttribute('data-render-key');
      if (!key) return;
      if (!reusable[key]) reusable[key] = [];
      reusable[key].push(node);
    });
    var fragment = document.createDocumentFragment();
    while (incoming.firstChild) {
      var next = incoming.firstChild;
      incoming.removeChild(next);
      var key = next.getAttribute && next.getAttribute('data-render-key');
      var matches = key && reusable[key];
      if (matches && matches.length) {
        var kept = matches.shift();
        var idx = next.getAttribute('data-idx');
        if (idx != null) kept.setAttribute('data-idx', idx);
        var incomingIndexed = next.querySelectorAll('[data-idx]');
        var keptIndexed = kept.querySelectorAll('[data-idx]');
        for (var j = 0; j < Math.min(incomingIndexed.length, keptIndexed.length); j++) {
          keptIndexed[j].setAttribute('data-idx', incomingIndexed[j].getAttribute('data-idx'));
        }
        fragment.appendChild(kept);
      } else {
        fragment.appendChild(next);
      }
    }
    m.innerHTML = '';
    m.appendChild(fragment);
    bindStreamPreviewNodes();
    if (nearBottom) m.scrollTop = m.scrollHeight;
    else m.scrollTop = oldTop;
  }
  function loadEarlierChat() {
    if (!S.chatHasMore || S.chatLoadingEarlier) return;
    var m = $('#msgs');
    var oldHeight = m ? m.scrollHeight : 0;
    var oldTop = m ? m.scrollTop : 0;
    S.chatLoadingEarlier = true;
    refreshChat();
    call('history', { cursor: S.chatCursor }).then(function (r) {
      var older = expandStoredChatBubbles(r.messages || []);
      S.chat = older.concat(S.chat || []);
      S.chatCursor = num(r.cursor, 0);
      S.chatHasMore = !!r.hasMore;
      S.chatLoadingEarlier = false;
      refreshChat();
      requestAnimationFrame(function () {
        var list = $('#msgs');
        if (list) list.scrollTop = oldTop + (list.scrollHeight - oldHeight);
      });
    }).catch(function (e) {
      S.chatLoadingEarlier = false;
      refreshChat();
      toast(e.message || '读取更早消息失败');
    });
  }
  function autoGrow(el) {
    var bar = el.closest ? el.closest('.composer-inner') : null;
    if (!bar) {
      el.style.height = 'auto';
      el.style.height = Math.min(96, el.scrollHeight) + 'px';
      return;
    }

    // Always measure in the compact layout first. Measuring after expanding the
    // textarea changes its width, which can make the class flip on every keypress.
    bar.classList.remove('has-long-input');
    el.style.height = 'auto';
    var compactHeight = el.scrollHeight;
    var style = window.getComputedStyle(el);
    var lineHeight = parseFloat(style.lineHeight) || 20;
    var verticalPadding = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0);
    var hasWrappedLine = compactHeight > lineHeight + verticalPadding + 2;
    bar.classList.toggle('has-long-input', hasWrappedLine);

    // Recompute only after the final layout is selected, so it never renders an
    // intermediate one-line/two-line jump.
    el.style.height = 'auto';
    el.style.height = Math.min(hasWrappedLine ? 120 : 96, el.scrollHeight) + 'px';
    var action = document.getElementById('sendBtn');
    if (action) {
      if (S.sending && S.streamId && S.streamText) {
        action.setAttribute('data-act', 'cancelChat');
        action.setAttribute('title', '停止回复');
        action.innerHTML = '<svg viewBox="0 0 24 24"><rect x="7" y="7" width="10" height="10" rx="1"/></svg>';
        closeChatAttachments();
        return;
      }
      var canSend = !!String(el.value || '').trim() || !!S.chatFile || !!S.chatVideo || !!S.chatImg;
      action.setAttribute('data-act', canSend ? 'sendChat' : 'toggleChatAttachments');
      action.setAttribute('title', canSend ? '发送' : '更多');
      action.innerHTML = canSend
        ? '<svg viewBox="0 0 24 24"><path d="M4 12l16-7-5 16-4-6z"/></svg>'
        : '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>';
      if (canSend) closeChatAttachments();
    }
  }
  function closeChatAttachments() {
    var menu = document.getElementById('chatAttachMenu');
    if (menu) menu.classList.remove('show');
  }
  function hideChatKeyboard() {
    var input = document.getElementById('chatInput');
    if (input && document.activeElement === input) input.blur();
  }
  function toggleChatAttachments() {
    var menu = document.getElementById('chatAttachMenu');
    if (!menu) return;
    var wasOpen = menu.classList.contains('show');
    hideChatKeyboard();
    closeStickerPanel();
    menu.classList.toggle('show', !wasOpen);
    fitChatTail();
  }
  function sendChat() {
    if (S.sending) return;
    var ta = $('#chatInput');
    var text = ta ? ta.value.trim() : String(S.asrText || '').trim();
    var img = S.chatImg || '';
    var file = S.chatFile;
    var video = S.chatVideo;
    var quote = S.chatQuote ? { role: S.chatQuote.role, name: S.chatQuote.name || quoteAuthorName(S.chatQuote.role), content: S.chatQuote.content, ts: S.chatQuote.ts || 0 } : null;
    if (!text && !img && !file && !video && !quote) return;
    if (!text && quote) text = '请回应我引用的这句话。';
    if (!text && file) text = '请帮我总结并分析这个文件。';
    if (!text && video) text = '请完整观看这段视频，概括画面变化、声音和对白，并告诉我你注意到的重点。';
    var sentAt = Date.now();
    S.asrText = '';
    if (ta) { ta.value = ''; autoGrow(ta); }
    S.chatImg = '';
    S.chatFile = null;
    S.chatVideo = null;
    S.chatQuote = null;
    // refreshChat() only updates the message list, so remove the composer chips
    // explicitly after their data has moved into the outgoing message bubble.
    $$('.composer .chat-attach, .composer .chat-file-attach').forEach(function (node) { node.remove(); });
    $$('.composer .chat-quote-preview').forEach(function (node) { node.remove(); });
    var sendButton = $('#sendBtn');
    if (sendButton) {
      sendButton.setAttribute('data-act', 'toggleChatAttachments');
      sendButton.setAttribute('title', '更多');
      sendButton.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>';
    }
    closeChatAttachments();
    closeStickerPanel();
    fitChatTail();
    postChat({ role: 'user', content: text || (video ? '（发送了一个视频）' : (file ? '（发送了一个文件）' : '（发了一张图片）')), ts: Date.now(), img: img, quote: quote,
      fileId: file ? file.id : '', fileName: file ? file.name : '', fileSummary: file ? file.summary : '',
      videoId: video ? video.id : '', videoName: video ? video.name : '' },
      text, img, sentAt, file, quote, video);
  }
  /* 用户点表情包：本地立刻显示成图片；发给模型的也是标记，
     原生会把它换成这张表情的场景描述，免得她只看到名字瞎猜画面 */
  function sendSticker(name) {
    if (S.sending || !name) return;
    closeStickerPanel();
    var tag = '[[表情:' + name + ']]';
    postChat({ role: 'user', content: tag, ts: Date.now() }, tag, '', Date.now());
  }
  function friendlyChatError(error) {
    var raw = String(error || '').trim();
    var lower = raw.toLowerCase();
    if (lower.indexOf('503') >= 0 || lower.indexOf('service_unavailable') >= 0 || lower.indexOf('service is too busy') >= 0 || lower.indexOf('overloaded') >= 0)
      return '聊天服务暂时繁忙或正在维护，这条消息没有得到回复。稍后重试，或到「设置 → 智能核心」切换可用的模型服务。';
    if (lower.indexOf('429') >= 0 || lower.indexOf('rate limit') >= 0 || lower.indexOf('too many requests') >= 0)
      return '聊天服务现在有些忙，或请求次数暂时达到上限。等一会儿再试，也可以检查服务商额度。';
    if (lower.indexOf('401') >= 0 || lower.indexOf('invalid_api_key') >= 0 || lower.indexOf('unauthorized') >= 0)
      return '聊天服务身份校验没有通过。请到「设置 → 智能核心」检查 API Key 是否填对或已过期。';
    if (lower.indexOf('402') >= 0 || lower.indexOf('insufficient_quota') >= 0 || lower.indexOf('billing') >= 0)
      return '聊天服务的可用额度可能不足了。请检查服务商账户余额或套餐额度，补充后再试。';
    if (lower.indexOf('timeout') >= 0 || lower.indexOf('timed out') >= 0 || lower.indexOf('超时') >= 0)
      return '聊天服务响应超时了，这条消息没有处理完。确认网络正常后稍等片刻再试。';
    if (lower.indexOf('unknownhost') >= 0 || lower.indexOf('unable to resolve') >= 0 || lower.indexOf('failed to connect') >= 0 || lower.indexOf('network is unreachable') >= 0)
      return '我现在连不上聊天服务。请检查手机网络、服务地址和网络限制，再重新发送。';
    if (!raw || raw.length > 170 || /^https?\s+\d{3}/i.test(raw) || /[{}\[\]]/.test(raw) || lower.indexOf('request_id') >= 0 || lower.indexOf('exception') >= 0)
      return '我这次没能顺利处理这条消息，可能是聊天服务暂不可用或配置需要检查。稍后重试；若持续发生，请检查「设置 → 智能核心」中的网络、API Key 和模型。';
    return raw;
  }
  function postChat(bubble, ask, img, sentAt, file, quote, video) {
    var requestId = 'chat-' + Date.now() + '-' + (++seq);
    S.sending = true;
    S.replyStarted = false;
    S.streamId = requestId;
    S.streamText = '';
    S.streamSuppressed = false;
    S.streamDraftCut = false;
    S.streamRenderedCount = 0;
    S.streamBubbleNodes = [];
    S.streamPartialNode = null;
    S.streamPreviewShown = false;
    startTypingLines();
    S.chat.push(bubble);
    refreshChat();
    call('chat', { text: ask, image: img, fileText: file ? file.text : '', fileName: file ? file.name : '', fileId: file ? file.id : '',
      videoId: video ? video.id : '', streamId: requestId, quote: quote || null }).then(function (r) {
      if (S.cancelledStreamId === requestId) { S.cancelledStreamId = ''; return; }
      var reply = r.text || '';
      // 本地快答（电量 / 型号 / 内存…）不给它「瞬发」：先假装想 1 秒，
      // 再像正常聊天那样一个字一个字打出来
      if (r.local) {
        var wait = Math.max(0, 1000 - (Date.now() - sentAt));
        setTimeout(function () {
          if (S.cancelledStreamId === requestId) return;
          clearStreamState(true);
          stopTypingLines();
          showReplyBubbles(reply, r);
        }, wait);
        return;
      }
      if (commitStreamReply(reply, r)) return;
      clearStreamState(true);
      stopTypingLines();
      showReplyBubbles(reply, r);
    }).catch(function (e) {
      if (S.cancelledStreamId === requestId) {
        S.cancelledStreamId = '';
        return;
      }
      if (preserveInterruptedStream(requestId)) return;
      if (S.streamId === requestId) clearStreamState(true);
      stopTypingLines();
      S.sending = false;
      S.replyStarted = false;
      var friendlyError = friendlyChatError(e && e.message);
      S.chat.push({ role: 'assistant', content: friendlyError, ts: Date.now() });
      refreshChat();
      toast(friendlyError);
    });
  }
  /* 逐字把一段话打出来；整句控制在 1 秒多点，太快会看不清，太慢又像卡住 */
  /* 每个完整句子单独成为一条消息；逗号连接的句内表达保持完整。 */
  function splitReplyBubbles(text) {
    var source = String(text || '').replace(/\r/g, '').replace(/[ \t]+/g, ' ').trim();
    if (!source) return [];
    var out = [], start = 0;
    function push(end) {
      var value = source.slice(start, end).trim();
      if (value) out.push(value);
      start = end;
    }
    for (var i = 0; i < source.length; i++) {
      var ch = source.charAt(i);
      if (ch === '\n') { push(i); start = i + 1; continue; }
      var boundary = '。！？!?'.indexOf(ch) >= 0 ||
        (ch === '.' && (i + 1 === source.length || /\s/.test(source.charAt(i + 1))));
      if (!boundary) continue;
      var end = i + 1;
      while (end < source.length && '。！？!?'.indexOf(source.charAt(end)) >= 0) end++;
      while (end < source.length && '”’」』）】)]}'.indexOf(source.charAt(end)) >= 0) end++;
      push(end);
      i = end - 1;
    }
    if (start < source.length) {
      var tail = source.slice(start).trim();
      if (/^\[\[(?:表情|找表情|动作)[:：][\s\S]*\]\]$/.test(tail) && out.length) out[out.length - 1] += tail;
      else if (tail) out.push(tail);
    }
    return out;
  }
  function showReplyBubbles(reply, meta) {
    var doc = /^\[\[LONGDOC_CARD:([a-f0-9]{32})\]\]$/i.exec(String(reply || '').trim());
    if (doc) {
      S.replyStarted = true;
      stopTypingLines();
      call('longDocInfo', { id: doc[1] }).then(function (r) {
        S.chat.push({ role: 'assistant', content: '', cardType: 'long_text_document', docId: doc[1],
          docName: r.title || '长文档', docFormat: r.format || 'md', docSummary: r.summary || '',
          docExpired: !!r.expired, ts: Date.now() });
        if (S.page === 'chat') refreshChat();
      }).catch(function () {
        S.chat.push({ role: 'assistant', content: '长文已完成，但卡片信息暂时读取失败。', ts: Date.now() });
        if (S.page === 'chat') refreshChat();
      }).finally(function () { S.sending = false; S.replyStarted = false; });
      return;
    }
    var lines = splitReplyBubbles(reply || '');
    if (!lines.length) { S.sending = false; S.replyStarted = false; stopTypingLines(); return; }
    S.replyStarted = true;
    stopTypingLines();
    var index = 0;
    function next() {
      var last = index === lines.length - 1;
      S.chat.push({ role: 'assistant', content: lines[index], ts: Date.now(),
        link: last ? ((meta && meta.link) || '') : '',
        linkText: last ? ((meta && meta.linkText) || '') : '',
        voice: last && !!(meta && meta.voiceReply),
        voiceText: last && meta && meta.voiceReply ? reply : '' });
      if (S.page === 'chat') refreshChat();
      index++;
      if (index < lines.length) setTimeout(next, Math.max(420, Math.min(950, lines[index - 1].length * 24)));
      else { S.sending = false; S.replyStarted = false; }
    }
    next();
  }
  function typeOut(msg, text, done) {
    var len = text.length;
    var i = 0;
    var step = Math.max(1, Math.ceil(len / 45));
    var timer = setInterval(function () {
      i = Math.min(len, i + step);
      msg.content = text.slice(0, i);
      if (S.page === 'chat') refreshChat();
      if (i >= len) {
        clearInterval(timer);
        if (done) done();
      }
    }, 24);
    window.__typeTimer = timer;
  }
  /* 思考时间变长时换句话，别让她一直干等三个点 */
  var TYPING_LINES = [[2600, '她正在想怎么回你…'], [6800, '她好像被噎住了…'], [13000, '还在斟酌怎么开口…']];
  function startTypingLines() {
    stopTypingLines();
    S.typingLine = '';
    TYPING_LINES.forEach(function (st) {
      window.__typingTimers.push(setTimeout(function () {
        if (!S.sending) return;
        S.typingLine = st[1];
        refreshChat();
      }, st[0]));
    });
  }
  function stopTypingLines() {
    (window.__typingTimers || []).forEach(function (t) { clearTimeout(t); });
    window.__typingTimers = [];
    S.typingLine = '';
  }

  /* ---------------- 动作 ---------------- */
  function renderActions() {
    var html = pageHead('动作台词', '每个动作都可以配一句专属台词；留空则交给 AI 即兴发挥');
    html += '<div class="card tight"><div class="row" style="border-top:0;padding:0"><div><div class="rt">AI 即兴台词</div><div class="rs">没有自定义台词的动作，让 AI 现场说一句（有冷却，省 token）</div></div>' +
      '<label class="switch"><input type="checkbox" id="aiActionGlobal" ' + (S.cfg.aiActionLines ? 'checked' : '') + '><span class="track"></span></label></div>' +
      '<div class="row"><div><div class="rt">AI 场景动作</div><div class="rs">她会按聊天情绪偶尔播放合适动作，不会每条都做</div></div>' +
      switchHtml('aiSceneActions', S.cfg.aiSceneActions !== false, 'toggleCfg') + '</div></div>';
    html += '<div class="search"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg><input id="actSearch" placeholder="搜索动作名称或编号…" value="' + esc(S.actQuery) + '"></div>';
    html += '<div id="actList">';
    // 动作库来自 assets/pet-config.json，正常会有几十个动作；真为空说明资源没读出来，
    // 至少给一句人话，别让页面白成一片
    if (!S.catalog.length) {
      html += '<div class="card"><div class="empty">动作库还没加载出来，可能是资源读取失败。重启一次应用通常会恢复。</div></div>';
    }
    S.catalog.forEach(function (cat, ci) {
      // Start every action group collapsed; the first group used to auto-open
      // on each fresh visit, which made the small-actions section feel stuck open.
      var open = S.openCats[cat.id] === true;
      html += '<div class="cat ' + (open ? 'open' : '') + '" data-cat="' + esc(cat.id) + '"><div class="cat-head" data-act="toggleCat" data-cat="' + esc(cat.id) + '">' +
        '<div><b>' + esc(cat.id) + '</b><span class="cnt">' + cat.actions.length + '</span></div>' +
        '<svg class="arr" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg></div><div class="cat-body">';
      cat.actions.forEach(function (a) {
        html += '<div class="act" data-slug="' + esc(a.slug) + '" data-name="' + esc(a.name) + '">' +
          '<div class="act-top"><div><div class="act-name">' + esc(a.name) + '</div><div class="act-code">' + esc(a.slug) + '</div></div>' +
          '<button class="mini-play" data-act="preview" data-slug="' + esc(a.slug) + '"><svg viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z"/></svg></button></div>' +
          '<div class="act-line"><input class="act-input" data-slug="' + esc(a.slug) + '" placeholder="这个动作时想说的话…" value="' + esc(a.line) + '"></div>' +
          '<div class="act-foot"><span class="lbl"><span class="dot ' + (a.ai ? 'on' : 'off') + '"></span>AI 即兴</span>' +
          '<label class="switch sm"><input type="checkbox" class="act-ai" data-slug="' + esc(a.slug) + '" ' + (a.ai ? 'checked' : '') + '><span class="track"></span></label></div></div>';
      });
      html += '</div></div>';
    });
    html += '</div>';
    return html;
  }

  function filterActions(q) {
    q = (q || '').trim().toLowerCase();
    $$('#actList .act').forEach(function (row) {
      var n = (row.getAttribute('data-name') || '').toLowerCase();
      var s = (row.getAttribute('data-slug') || '').toLowerCase();
      row.style.display = !q || n.indexOf(q) >= 0 || s.indexOf(q) >= 0 ? '' : 'none';
    });
    $$('#actList .cat').forEach(function (cat) {
      var any = Array.prototype.some.call(cat.querySelectorAll('.act'), function (r) { return r.style.display !== 'none'; });
      cat.style.display = any ? '' : 'none';
      if (q && any) cat.classList.add('open');
    });
  }

  function saveActionLine(slug, inputEl) {
    var row = document.querySelector('.act[data-slug="' + slug + '"]');
    if (!row) return;
    var ai = row.querySelector('.act-ai');
    var line = inputEl.value;
    var aiOn = !!(ai && ai.checked);
    for (var i = 0; i < S.catalog.length; i++) {
      var acts = S.catalog[i].actions || [];
      for (var j = 0; j < acts.length; j++) {
        if (acts[j].slug === slug) {
          acts[j].line = line;
          acts[j].ai = aiOn;
        }
      }
    }
    call('actionSave', { slug: slug, line: line, ai: aiOn }).catch(function (e) {
      toast(e.message);
    });
  }

  /* ---------------- 陪伴 ---------------- */
  function ttsChip(rate, pitch, label) {
    var on = Math.abs(flt(S.cfg.ttsRate, 1) - rate) < 0.051 && Math.abs(flt(S.cfg.ttsPitch, 1) - pitch) < 0.051;
    return '<button type="button" class="rchip' + (on ? ' on' : '') + '" data-act="ttsStyle" data-rate="' + rate +
      '" data-pitch="' + pitch + '" data-label="' + label + '">' + label + '</button>';
  }

  function companionFold(key, title, hint, body) {
    var open = false;
    try { open = localStorage.getItem('companionFold:' + key) === '1'; } catch (_) { }
    return '<details class="more-wrap companion-fold" data-companion-fold="' + key + '"' + (open ? ' open' : '') + '><summary><span>' + title + '</span><span class="companion-fold-hint">' + hint + '</span></summary><div class="companion-fold-body">' + body + '</div></details>';
  }

  function renderCompanion() {
    var m = S.state.music || {};
    var lyricHint = m.playing
      ? '歌词：' + (m.lyricStatus || '未取到') + (m.lyric ? ' · 当前「' + m.lyric + '」' : '')
      : '歌词：待播放';
    var html = pageHead('情境陪伴', '让她知道你正在听什么、做什么，再主动开口');
    var foldAt = html.length;
    html += '<div class="card"><div class="card-title"><h3>音乐感知</h3><span>' + (m.playing ? '正在播放' : '待机') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">' + esc(m.title || '未检测到音乐') + '</div><div class="rs">' + esc(m.artist || '支持网易云、QQ 音乐、汽水、Spotify 等') + '</div><div class="rs">' + esc(lyricHint) + '</div></div>' +
      switchHtml('musicAware', S.cfg.musicAware, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">深夜慢歌共情</div><div class="rs">深夜 + 安静的歌 + 你情绪不高时，她会问一句</div></div>' + switchHtml('musicCare', S.cfg.musicCare !== false, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">深夜电量关心</div><div class="rs">深夜电量低于 15% 还在玩手机时，她会急</div></div>' + switchHtml('batteryCare', S.cfg.batteryCare !== false, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">头顶歌词气泡</div><div class="rs">从播放器通知栏或曲库读取，不消耗 token，也不占主动次数</div></div>' + switchHtml('lyricBubble', S.cfg.lyricBubble, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">AI 认识这首歌</div><div class="rs">按歌名 + 歌手查一次资料并缓存，能接着跟你聊这首歌；消耗少量 token</div></div>' + switchHtml('musicAi', S.cfg.musicAi !== false, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">哼歌动作</div><div class="rs">每隔一段时间做一次「悠闲哼歌」</div></div>' + switchHtml('musicHum', S.cfg.musicHum !== false, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">歌词换句间隔</div><div class="rs">每 ' + num(S.cfg.lyricGapSec, 5) + ' 秒最多飘一句歌词（5–30）</div></div><div style="width:150px"><input class="slider" type="range" min="5" max="30" step="1" value="' + num(S.cfg.lyricGapSec, 5) + '" data-set="lyricGapSec" data-apply="timer"></div></div>' +
      '<div class="row"><div><div class="rt">哼歌间隔</div><div class="rs">每 ' + num(S.cfg.musicHumSec, 55) + ' 秒做一次哼歌动作</div></div><div style="width:150px"><input class="slider" type="range" min="5" max="180" step="5" value="' + num(S.cfg.musicHumSec, 55) + '" data-set="musicHumSec" data-apply="timer"></div></div></div>';
    html = html.slice(0, foldAt) + companionFold('music', '音乐陪伴', (m.playing ? '正在播放 · ' + esc(m.title || '音乐') : '音乐识别、歌词与哼歌'), html.slice(foldAt));

    foldAt = html.length;
    var ttsEng = S.cfg.ttsEngine || 'edge';
    var ttsExtra = '';
    if (ttsEng === 'edge') {
      var elist = (S.tts && S.tts.edgeVoices) || [];
      if (!elist.length) elist = [{ id: 'zh-CN-XiaoxiaoNeural', name: '晓晓 · 自然温柔' }];
      var ecur = S.cfg.edgeVoice || 'zh-CN-XiaoxiaoNeural';
      ttsExtra = '<label class="field"><span>在线音色</span><select id="edgeVoice">' +
        elist.map(function (v) {
          return '<option value="' + esc(v.id) + '"' + (ecur === v.id ? ' selected' : '') + '>' + esc(v.name) + '</option>';
        }).join('') + '</select></label>' +
        '<div class="faint" style="margin:-4px 0 8px">Edge 免费接口的中文音色共 14 个，已全部列出；想再多可以换「自定义在线语音」。</div>';
    } else if (ttsEng === 'online') {
      ttsExtra = '<label class="field"><span>接口地址</span><input id="ttsOnlineUrl" placeholder="https://api.openai.com/v1" value="' + esc(S.cfg.ttsOnlineUrl || '') + '"></label>' +
        '<label class="field"><span>API Key</span><input id="ttsOnlineKey" type="password" placeholder="' + ((S.tts && S.tts.onlineKey) ? '已填写，留空不改' : 'sk-...') + '" value=""></label>' +
        '<label class="field"><span>模型</span><input id="ttsOnlineModel" placeholder="tts-1" value="' + esc(S.cfg.ttsOnlineModel || 'tts-1') + '"></label>' +
        '<label class="field"><span>音色</span><input id="ttsOnlineVoice" placeholder="alloy / nova / anna" value="' + esc(S.cfg.ttsOnlineVoice || 'alloy') + '"></label>' +
        '<div class="faint" style="margin:-4px 0 8px">走 OpenAI 兼容的 /audio/speech。OpenAI 可用 alloy、nova、shimmer、onyx；硅基流动可填 FunAudioLLM/CosyVoice2-0.5B:anna 这类带冒号的音色。失败会自动退回系统朗读。</div>';
    } else {
      var engList = (S.tts && S.tts.sysEngines) || [];
      ttsExtra = '<label class="field"><span>系统引擎</span><select id="ttsSysEngine"><option value="">系统默认</option>' +
        engList.map(function (e) {
          return '<option value="' + esc(e.pkg) + '"' + ((S.cfg.ttsSysEngine || '') === e.pkg ? ' selected' : '') + '>' + esc(e.name) + (e.default ? ' · 默认' : '') + '</option>';
        }).join('') + '</select></label>' +
        '<label class="field"><span>系统发音人</span><select id="ttsVoice"><option value="">引擎默认</option>' +
        (S.voices || []).slice().sort(function (a, b) { return (b.quality || 0) - (a.quality || 0); }).map(function (v) {
          var id = v.id || v.name;
          var sel = S.cfg.ttsVoice || '';
          var on = sel === id || (sel.indexOf('|') < 0 && sel === v.name);
          return '<option value="' + esc(id) + '"' + (on ? ' selected' : '') + '>' + esc(v.name) + (v.locale ? ' · ' + esc(v.locale) : '') + ((v.quality || 0) >= 400 ? ' · 高清' : (v.network ? ' · 在线' : '')) + '</option>';
        }).join('') +
        '</select></label>' +
        '<div class="faint" style="margin:-4px 0 8px">装了多个语音引擎（讯飞、Google、厂商自带）时，换「系统引擎」能看到各自的音色。</div>';
    }
    html += '<div class="card"><div class="card-title"><h3>朗读与语音</h3><span>' + (S.cfg.ttsEnabled ? '朗读开' : '朗读关') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">回复朗读</div><div class="rs">默认用在线语音，没网时自动退回系统朗读</div></div>' + switchHtml('ttsEnabled', S.cfg.ttsEnabled, 'ttsToggle') + '</div>' +
      '<label class="field"><span>朗读引擎</span><select id="ttsEngine">' +
      '<option value="edge"' + (ttsEng === 'edge' ? ' selected' : '') + '>Edge 在线语音 · 自然（需联网）</option>' +
      '<option value="online"' + (ttsEng === 'online' ? ' selected' : '') + '>自定义在线语音 · 音色最多</option>' +
      '<option value="system"' + (ttsEng === 'system' ? ' selected' : '') + '>系统 TTS · 离线</option>' +
      '</select></label>' + ttsExtra +
      (S.tts && S.tts.voiceError ? '<div class="faint" style="margin:-4px 0 8px">' + esc(S.tts.voiceError) + '</div>' : '') +
      '<div class="row"><div><div class="rt">语速</div><div class="rs">' + flt(S.cfg.ttsRate, 1).toFixed(1) + 'x</div></div><div style="width:150px"><input class="slider" type="range" min="0.5" max="2" step="0.1" value="' + flt(S.cfg.ttsRate, 1) + '" data-tts="ttsRate"></div></div>' +
      '<div class="row"><div><div class="rt">音调</div><div class="rs">' + flt(S.cfg.ttsPitch, 1).toFixed(1) + 'x</div></div><div style="width:150px"><input class="slider" type="range" min="0.5" max="2" step="0.1" value="' + flt(S.cfg.ttsPitch, 1) + '" data-tts="ttsPitch"></div></div>' +
      '<div class="field"><span>女声音色</span><div class="rchips" id="ttsStyleChips">' +
      ttsChip(1, 1, '自然') + ttsChip(0.88, 0.95, '温柔') + ttsChip(1.05, 1.12, '元气') +
      ttsChip(0.98, 0.88, '御姐') + ttsChip(0.95, 1.18, '软萌') + '</div></div>' +
      '<div class="row"><div><div class="rt">自然语气</div><div class="rs">自动分句停顿，减少机械感</div></div>' + switchHtml('ttsNatural', S.cfg.ttsNatural !== false, 'toggleCfg') + '</div>' +
      '<button class="btn ghost block sm" data-act="ttsTest">试听一句</button></div>';
    html = html.slice(0, foldAt) + companionFold('voice', '语音与朗读', (S.cfg.ttsEnabled ? '朗读已开启' : '朗读已关闭') + ' · 音色与语速', html.slice(foldAt));

    foldAt = html.length;
    html += '<div class="card"><div class="card-title"><h3>主动陪伴</h3><span>' + (S.cfg.proactive ? '开启' : '关闭') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">主动开口</div><div class="rs">结合时间、应用、音乐和记忆，不定期说一句</div></div>' + switchHtml('proactive', S.cfg.proactive, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">自由行动</div><div class="rs">需无障碍、可用 AI，且聊天类主动行为未关闭。屏幕亮着、已解锁且连续 10 分钟无触控后，她会先告诉你，再进入限时探索；不依赖普通主动开口开关</div></div>' + switchHtml('freeAction', S.cfg.freeAction === true, 'toggleCfg') + '</div>' +
      '<div class="notice violet" style="margin-top:8px">应用列表勾选后会限制到勾选项；全部不勾选时允许所有通过安全筛选的应用。她可在页面能可靠识别时滑动、返回或点普通导航文字；不会发消息、点赞、评论、发布、购买、支付、删除、登录或改设置。总静音、夜间、专注，或你正在聊天/听语音时不会启动。屏幕文字和必要截图会发送给你配置的 AI 服务分析，学习只保存简短心得，不保存整屏内容。行动期间会临时保持屏幕点亮，会增加耗电和 AI 用量；每轮观察间隔可调，默认 60 秒。屏幕熄灭、锁屏、你开始操作/切换应用、关闭自由行动，或双击桌宠/说“停下来”都会结束。</div>' +
      '<div class="row"><div><div class="rt">允许她探索的应用</div><div class="rs">' + (((S.cfg.freeActionApps || []).length) ? ('限制为已选 ' + S.cfg.freeActionApps.length + ' 个应用') : '未勾选时允许所有安全应用') + '</div></div><button class="btn sm ghost" data-act="openFreeActionApps">选择</button></div>' +
      '<div class="row"><div><div class="rt">观察间隔</div><div class="rs">每 ' + Math.max(30, Math.min(120, num(S.cfg.freeActionIntervalSec, 60))) + ' 秒判断一次；默认 60 秒，间隔越短越费电、AI 用量越高</div></div><div style="width:150px"><input class="slider" type="range" min="30" max="120" step="15" value="' + Math.max(30, Math.min(120, num(S.cfg.freeActionIntervalSec, 60))) + '" data-set="freeActionIntervalSec" data-apply="timer"></div></div>' +
      '<div class="row"><div><div class="rt">间隔</div><div class="rs">每 ' + num(S.cfg.proactiveMin, 20) + ' 分钟（3–120）</div></div><div style="width:150px"><input class="slider" type="range" min="3" max="120" step="1" value="' + num(S.cfg.proactiveMin, 20) + '" data-set="proactiveMin" data-apply="timer"></div></div>' +
      '<div class="row"><div><div class="rt">气泡时长</div><div class="rs">每秒停留 ' + num(S.cfg.bubbleSec, 6) + ' 秒</div></div><div style="width:150px"><input class="slider" type="range" min="3" max="15" step="1" value="' + num(S.cfg.bubbleSec, 6) + '" data-set="bubbleSec" data-apply="timer"></div></div>' +
      '<div class="row"><div><div class="rt">主动说话冷却</div><div class="rs">每 ' + num(S.cfg.speakCoolSec, 120) + ' 秒最多主动说一句</div></div><div style="width:150px"><input class="slider" type="range" min="30" max="600" step="30" value="' + num(S.cfg.speakCoolSec, 120) + '" data-set="speakCoolSec" data-apply="timer"></div></div>' +
      '<button class="btn ghost block sm" data-act="proactiveNow">现在说一句</button></div>';
    html = html.slice(0, foldAt) + companionFold('proactive', '主动陪伴', (S.cfg.proactive ? '已开启' : '已关闭') + ' · 自由行动与频率', html.slice(foldAt));

    foldAt = html.length;
    var pomo = S.pomo || {};
    var pomoOn = !!pomo.running;
    var phase = pomo.phase === 'work' ? '专注中' : pomo.phase === 'rest' ? '休息中' : '未开始';
    var left = '';
    if (pomoOn && pomo.endAt) {
      var sec = Math.max(0, Math.round((pomo.endAt - Date.now()) / 1000));
      left = ' · 剩余 ' + Math.floor(sec / 60) + ':' + pad(sec % 60);
    }
    html += '<div class="card"><div class="card-title"><h3>专注番茄钟</h3><span>' + (pomoOn ? phase + left : '未开始') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">工作时长</div><div class="rs">结束后我会提醒你站起来</div></div>' +
      '<div style="width:92px"><input id="pomoWork" type="number" min="1" max="180" value="' + num(pomo.work, 25) + '" style="width:100%;padding:8px 10px;border-radius:10px;border:1px solid var(--line);background:rgba(0,0,0,.24);text-align:center"></div></div>' +
      '<div class="row"><div><div class="rt">休息时长</div><div class="rs">分钟</div></div>' +
      '<div style="width:92px"><input id="pomoRest" type="number" min="1" max="60" value="' + num(pomo.rest, 5) + '" style="width:100%;padding:8px 10px;border-radius:10px;border:1px solid var(--line);background:rgba(0,0,0,.24);text-align:center"></div></div>' +
      (pomoOn
        ? '<button class="btn danger block sm" data-act="pomoStop">停止番茄钟</button>'
        : '<button class="btn primary block sm" data-act="pomoStart">开始专注</button>') + '</div>';
    html = html.slice(0, foldAt) + companionFold('focus', '专注与番茄钟', pomoOn ? phase + left : '番茄钟与专注时长', html.slice(foldAt));

    foldAt = html.length;
    html += '<div class="card"><div class="card-title"><h3>屏幕情境</h3><span>' + (S.perms.a11y ? '已授权' : '未授权') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">读取当前页面文字</div><div class="rs">只读取文字用于理解情境，不上传截图</div></div>' + switchHtml('screenContext', S.cfg.screenContext, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">识别频率</div><div class="rs">每 ' + Math.max(3, num(S.cfg.screenScanSec, 3)) + ' 秒读取一次；锁屏或灭屏时不识别</div></div><div style="width:150px"><input class="slider" type="range" min="3" max="30" step="1" value="' + Math.max(3, num(S.cfg.screenScanSec, 3)) + '" data-set="screenScanSec" data-apply="timer"></div></div>' +
      '<div class="row"><div><div class="rt">当前应用</div><div class="rs" id="companionCurrentPkg">' + esc(S.state.pkg || '未知') + '</div></div>' +
      (S.perms.a11y ? '<span class="tag mint">已授权</span>' : '<button class="btn sm ghost" data-act="openPerm" data-kind="a11y">授权</button>') + '</div>' +
      '<div id="companionScreenText"' + (S.state.screenText ? '' : ' hidden') + ' class="notice mint">' + esc((S.state.screenText || '').slice(0, 110)) + '</div></div>';

    var games = S.cfg.games || [];
    html += '<div class="card"><div class="card-title"><h3>游戏自动收起</h3><span>' + games.length + ' 个应用</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">进入游戏时隐藏</div><div class="rs">打开选中的应用自动收起，退出后重新出现</div></div>' + switchHtml('gameEnabled', S.cfg.gameEnabled, 'toggleCfg') + '</div>' +
      '<button class="btn block sm" data-act="openGames">选择游戏应用</button></div>';
    html = html.slice(0, foldAt) + companionFold('screen', '屏幕与应用', '屏幕识别、当前应用与游戏收起', html.slice(foldAt));

    foldAt = html.length;
    html += '<div class="card"><div class="card-title"><h3>夜间作息</h3><span>' + (S.cfg.sleepEnabled ? '开启' : '关闭') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">到点自动休息</div><div class="rs">睡眠时段内收起，早上自动出现</div></div>' + switchHtml('sleepEnabled', S.cfg.sleepEnabled, 'toggleCfg') + '</div>' +
      '<div class="field-row"><label class="field"><span>入睡</span><input type="time" id="sleepStart" value="' + esc(S.cfg.sleepStart || '23:30') + '"></label>' +
      '<label class="field"><span>起床</span><input type="time" id="sleepEnd" value="' + esc(S.cfg.sleepEnd || '07:00') + '"></label></div>' +
      '<button class="btn ghost block sm" data-act="saveSleep">保存作息</button></div>';

    html += '<div class="card"><div class="card-title"><h3>提醒来源</h3><span>' + (S.perms.listener ? '监听中' : '未授权') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">实时关心总开关</div></div>' + switchHtml('remindersEnabled', S.cfg.remindersEnabled, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">微信 / QQ 消息</div><div class="rs">推送、订阅号、公众号不理会</div></div>' + switchHtml('msgEnabled', S.cfg.msgEnabled, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">外卖 / 快递</div><div class="rs">不催你去取，只好奇你买了什么</div></div>' + switchHtml('parcelEnabled', S.cfg.parcelEnabled, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">叫车 / 出行</div><div class="rs">问你去哪儿玩，可以给建议</div></div>' + switchHtml('travelEnabled', S.cfg.travelEnabled, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">实时播报</div><div class="rs">外卖、快递、微信/QQ 即时回应</div></div>' + switchHtml('realtimeEnabled', S.cfg.realtimeEnabled, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">实时朗读</div><div class="rs">实时提醒同时用语音念出</div></div>' + switchHtml('realtimeSpeak', S.cfg.realtimeSpeak, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">接收应用</div><div class="rs">' + ((S.cfg.notifyApps || []).length ? '已选 ' + (S.cfg.notifyApps || []).length + ' 个' : '全部应用') + '</div></div><button class="btn sm ghost" data-act="notifyApps">选择</button></div>' +
      '<div class="row"><div><div class="rt">天气提醒</div><div class="rs">只提醒带伞、防晒、别出门，不念预报</div></div>' + switchHtml('weatherEnabled', S.cfg.weatherEnabled !== false, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">日历日程</div><div class="rs">每天 0 点同步，一天最多提醒 5 次</div></div>' + switchHtml('calEnabled', S.cfg.calEnabled !== false, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">立即检查天气</div><div class="rs">用当前位置查询并播报一次</div></div><button class="btn sm ghost" data-act="weatherNow">检查</button></div>' +
      '<div class="row"><div><div class="rt">通知读取权限</div><div class="rs">解析未读消息、外卖与快递</div></div>' +
      (S.perms.listener ? '<span class="tag mint">已授权</span>' : '<button class="btn sm ghost" data-act="openPerm" data-kind="listener">授权</button>') + '</div>' +
      '<div class="notice" style="margin-top:10px">提醒中心只汇总「日历日程」和「我手动加的提醒」；来自外卖、快递、消息的通知只会变成桌宠的一句关心，不会留下来催你。</div></div>';
    html = html.slice(0, foldAt) + companionFold('routine', '作息与提醒', '夜间休息、通知来源与提醒', html.slice(foldAt));
    return html;
  }

  /* ---------------- 设置 ---------------- */
  function flushTts() {
    var json = {};
    $$('[data-tts]').forEach(function (el) {
      if (el._tt) { clearTimeout(el._tt); el._tt = null; }
      var k = el.getAttribute('data-tts');
      if (!k) return;
      var v = parseFloat(el.value);
      S.cfg[k] = v;
      json[k] = v;
    });
    return call('ttsSave', { json: json });
  }

  function renderSettings() {
    var html = pageHead('设置', '智能核心、功能中心、本地数据与权限');
    html += '<div class="tiles">' +
      tile('core', '<svg viewBox="0 0 24 24"><path d="M12 3v3M12 18v3M3 12h3M18 12h3"/><circle cx="12" cy="12" r="4"/></svg>', '智能核心', 'API Key · 模型 · 余额') +
      tile('hub', '<svg viewBox="0 0 24 24"><path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"/></svg>', '功能中心', '全部陪伴功能') +
      tile('sg', '<svg viewBox="0 0 24 24"><path d="M4 7c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z"/><path d="M4 7v10c0 1.7 3.6 3 8 3s8-1.3 8-3V7"/></svg>', 'S-G 数据', '存储 · 导出 · 迁移') +
      tile('health', '<svg viewBox="0 0 24 24"><path d="M12 3l8 4v6c0 5-3.5 7.5-8 8-4.5-.5-8-3-8-8V7z"/><path d="M9 12l2 2 4-4"/></svg>', '权限体检', '逐项检查 · 一键修复') +
      '</div>';
    html += '<div class="card"><div class="card-title"><h3>个人 QQ</h3><span>' + (S.cfg.qqLinkEnabled ? '连接已开启' : '未连接') + '</span></div>' +
      '<div class="rs">通过运行在电脑或另一部手机上的 NapCat 连接个人 QQ，可让桌宠加入指定私聊或群聊。</div>' +
      '<button class="btn ghost block sm" data-act="openPage" data-page="qq" style="margin-top:10px">设置个人 QQ 连接</button></div>';
    html += '<div class="card"><div class="card-title"><h3>启动</h3><span>基础</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">开机自动出现</div><div class="rs">重启手机后自动恢复桌宠</div></div>' + switchHtml('autoStart', S.cfg.autoStart, 'toggleCfg') + '</div>' +
      '<div class="faint" style="margin-top:8px">大小与不透明度已移到首页卡片，直接滑动调节。</div></div>';
    html += '<div class="card"><div class="card-title"><h3>后台与通知</h3><span>Android 常驻</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">后台保活</div><div class="rs">每 15 分钟检查一次，服务被系统回收时尝试拉回</div></div>' +
      switchHtml('keepAlive', S.cfg.keepAlive, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">电池优化</div><div class="rs">' + (S.perms.battery ? '已允许后台运行' : '未设置，系统可能更早回收桌宠') + '</div></div>' +
      (S.perms.battery ? '<button class="btn sm ghost" data-act="openPerm" data-kind="batteryOff">关闭</button>' : '<button class="btn sm ghost" data-act="openPerm" data-kind="battery">去设置</button>') + '</div>' +
      '<div class="notice violet">Android 要求前台服务必须显示一条常驻通知，无法真正隐藏；通知栏里可以直接藏起来、锁定、收起来。保活也不是无限后台，系统强行停止应用后不会自动复活。</div></div>';
    html += '<div class="card"><div class="card-title"><h3>关于</h3></div>' +
      '<div class="faint" style="line-height:1.8">DSH 桌宠<br>基于原版 106 个动画与物理引擎重建，新增 AI 伙伴、人设卡、记忆、歌词、广告跳过与提醒。<br>聊天由 DeepSeek API 提供；所有持久化数据存放在 S-G。</div></div>';
    return html;
  }

  function renderQQ() {
    var st = S.qqStatus || {};
    var connected = !!st.connected;
    var state = st.state || (S.cfg.qqLinkEnabled ? '连接状态尚未读取' : '未连接');
    var error = st.error ? '<div class="notice rose" style="margin-top:10px">' + esc(st.error) + '</div>' : '';
    var groupReplyError = st.groupReplyError ? '<div class="notice rose" style="margin-top:10px">' + esc(st.groupReplyError) + '</div>' : '';
    var html = pageHead('个人 QQ 连接', 'NapCat 所在设备负责连接 QQ；可限定私聊联系人和群聊范围', true);
    html += '<button class="btn ghost block" data-act="openPage" data-page="qqGuide" style="margin-bottom:14px">第一次设置？看大白话连接教程 ›</button>';
    html += '<div class="card"><div class="card-title"><h3>连接状态</h3><span class="tag ' + (connected ? 'mint' : 'muted') + '">' + (connected ? '已连接' : (S.cfg.qqLinkEnabled ? '连接中' : '未连接')) + '</span></div>' +
      '<div class="rt">' + esc(state) + '</div>' +
      (st.selfId ? '<div class="rs">当前登录：' + esc(st.selfName || st.selfId) + '（QQ ' + esc(st.selfId) + '）</div>' : '') + error + groupReplyError +
      (connected ? '<div class="rs">' + (Number(st.groupCount || 0) > 0
        ? '群聊允许名单：' + Math.min(20, Number(st.groupCount || 0)) + ' 个；' + (st.groupMode === 'participate' ? '自由参与，不限频，忙时按顺序接续处理' : '默认仅在 @ 桌宠时回复') + '；这里只显示设置，不代表已收到群消息'
        : '尚未填写群号，当前只响应私聊；填写群号并保存设置即可启用群聊') + '</div>' : '') +
      '<div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="qqStatus">刷新状态</button><button class="btn ghost" data-act="qqDisconnect">断开连接</button></div></div>';
    html += '<div class="card"><div class="card-title"><h3>连接信息</h3><span>NapCat / OneBot V11</span></div>' +
      '<label class="field"><span>NapCat WebSocket 地址</span><input id="qqWsUrl" autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="ws://NapCat所在设备IP:3001/" value="' + esc(S.cfg.qqOnebotUrl || '') + '"></label>' +
      '<label class="field"><span>OneBot Token</span><input id="qqWsToken" type="password" autocomplete="new-password" placeholder="' + (S.cfg.qqOnebotToken ? '已保存；留空保持原值' : 'NapCat 未设置 Token 时可留空；设置过就必须一致') + '"></label>' +
      '<label class="field"><span>允许对话的私聊 QQ 号（可留空）</span><input id="qqAllowedId" inputmode="numeric" autocomplete="off" placeholder="只响应这个 QQ 号发来的私聊" value="' + esc(S.cfg.qqAllowedUserId || '') + '"></label>' +
      '<label class="field"><span>允许加入的群号</span><textarea id="qqGroupIds" rows="3" placeholder="可填多个，用逗号分开；例如 123456789，留空则不接群聊">' + esc(S.cfg.qqGroupIds || '') + '</textarea></label>' +
      '<label class="field"><span>群聊回应方式</span><select id="qqGroupMode">' +
      '<option value="mention" ' + ((S.cfg.qqGroupMode || 'mention') === 'mention' ? 'selected' : '') + '>被 @ 时回应（推荐）</option>' +
      '<option value="participate" ' + (S.cfg.qqGroupMode === 'participate' ? 'selected' : '') + '>自由参与（不限频，消息按顺序回应）</option>' +
      '</select></label>' +
      '<label class="field"><span>群聊使用 AstrBot 插件</span><select id="qqGroupAstrBot">' +
      '<option value="0" ' + (!S.cfg.qqGroupAstrBot ? 'selected' : '') + '>关闭：使用手机当前聊天模型</option>' +
      '<option value="1" ' + (S.cfg.qqGroupAstrBot ? 'selected' : '') + '>开启：使用远端 AstrBot 智能体</option>' +
      '</select></label>' +
      '<label class="field"><span>私聊使用 AstrBot 插件</span><select id="qqPrivateAstrBot">' +
      '<option value="0" ' + (!S.cfg.qqPrivateAstrBot ? 'selected' : '') + '>关闭：私聊全部用手机当前聊天模型</option>' +
      '<option value="1" ' + (S.cfg.qqPrivateAstrBot ? 'selected' : '') + '>开启：私聊里 / 开头的指令交给 AstrBot</option>' +
      '</select></label>' +
      '<label class="field"><span>本次 QQ 连接使用的群聊人设</span><select id="qqGroupPersona">' +
      '<option value="" ' + (!S.qqPersonaSelected ? 'selected' : '') + '>跟随桌宠默认人设</option>' +
      (S.qqPersonas || []).map(function (p) { return '<option value="' + esc(p.id) + '" ' + (p.id === S.qqPersonaSelected ? 'selected' : '') + '>' + esc(p.name) + '</option>'; }).join('') +
      '</select></label>' +
      '<div class="btn-row"><button class="btn ghost" data-act="qqPersonaNew">新建人设</button><button class="btn ghost" data-act="qqPersonaImport">导入人设卡</button></div>' +
      ((S.qqPersonas || []).length ? '<div class="list" style="margin-top:8px">' + (S.qqPersonas || []).map(function (p) {
        return '<div class="list-item"><div class="li-ico">人</div><div class="li-main"><b>' + esc(p.name) + '</b><span>' + esc(String(p.prompt || '').slice(0, 72)) + '</span></div>' +
          '<button class="li-act" data-act="qqPersonaEdit" data-id="' + esc(p.id) + '">编辑</button><button class="li-act danger" data-act="qqPersonaDelete" data-id="' + esc(p.id) + '">删除</button></div>';
      }).join('') + '</div>' : '<div class="faint" style="margin-top:8px">可新建人设，或导入 Character Card JSON、PNG（含角色卡数据）、TXT/MD。每次连接时选择一张。</div>') +
      '<div class="btn-row"><button class="btn ghost" data-act="qqSave">保存设置</button><button class="btn primary" data-act="qqConnect">保存并连接</button></div>' +
      '<div class="faint" style="margin-top:8px">QQ 私聊与群聊都只由桌宠应答，本应用不支持让 AstrBot 原生接管同一个 QQ 机器人。若 AstrBot 里也开着 OneBot/NapCat 平台并连同一个号，同一条消息会被两个客户端各回一次，出现双重人格；请到 AstrBot 里停用该 OneBot 平台，只保留本应用这一条连接。</div>' +
      (function () {
        var at = S.qqStatus && Number(S.qqStatus.externalResponderAt || 0);
        if (!at) return '';
        return '<div class="notice" style="margin-top:8px;color:#ffd6dc">检测到同一 QQ 机器人正被另一个客户端应答（如 AstrBot 的 OneBot 平台，最近：' + new Date(at).toLocaleString() + '）。同一条消息会被回两次，出现双重人格：请到 AstrBot 里停用连接这个号的 OneBot 平台，只保留本应用连接。</div>';
      })() +
      '<div class="notice violet" style="margin-top:12px">NapCat 在另一台电脑或手机上时：在那边开启 OneBot V11「WebSocket 服务端」（正向 WS），DSH 填它的局域网 IP，例如 <b>ws://192.168.1.25:3001/</b>；不要填 0.0.0.0 或 DSH 手机自己的 127.0.0.1。若 NapCat 就跑在本机的「AstrBot 运行管理器」里：地址填 <b>ws://127.0.0.1:6199/ws</b>，NapCat 未设 Token 时这里可留空。两台设备方案必须能互相访问；建议只在可信 Wi‑Fi 使用，不要把端口暴露到公网。</div>' +
      '<div class="faint" style="margin-top:10px">私聊只接收指定 QQ 号；群聊只接收上面列出的群。私聊与群聊都由桌宠应答，AstrBot 只作为桌面「功能中心 → 插件」里的智能体使用，不接管 QQ 原生连接。群聊上下文按群隔离、只在桌宠后台单独运行，不读取桌宠私聊记忆；每个群结束时的摘要会额外打包进群记忆，桌宠私聊可按话题读到，但群聊永远读不到私聊。给群聊开启 AstrBot 后，群消息会发送到你配置的远端服务，由 AstrBot 智能体调用已安装且支持对话工具的插件；仅监听原生 QQ 群事件的插件不会因此触发。群聊不会接入 DSH 手机工具，不应通过群消息操作手机。请只在信任的群开启；群内容会发送给远端 AstrBot。需要先在「功能中心 → 插件」配置并启用 AstrBot 智能体。断开后停止后台 QQ 连接。</div></div>';
    return html;
  }

  function renderQQGuide() {
    var active = S.qqGuideTab || 'pc';
    function link(label, url) {
      return '<div class="faint" style="margin-top:8px;overflow-wrap:anywhere"><code>' + esc(url) + '</code></div>' +
        '<div class="btn-row guide-link-actions" style="margin-top:6px"><button class="btn ghost guide-open" data-act="openUrl" data-url="' + esc(url) + '">打开' + label + '</button><button class="btn ghost guide-copy" data-act="copyText" data-text="' + esc(url) + '">复制链接</button></div>';
    }
    var html = pageHead('QQ 连接教程', '先选你的设备组合，再照着步骤操作', true);
    html += '<div class="notice violet">NapCat 负责让 QQ 在线，桌宠负责处理消息；AstrBot 只作为桌宠背后的智能体，不直接接管 QQ。NapCat 是第三方开源工具，不是腾讯官方软件。</div>' +
      '<div class="btn-row" style="flex-wrap:wrap;margin:10px 0 14px">' +
      '<button class="btn ' + (active === 'pc' ? 'primary' : 'ghost') + '" data-act="qqGuideTab" data-tab="pc">电脑—手机</button>' +
      '<button class="btn ' + (active === 'phone' ? 'primary' : 'ghost') + '" data-act="qqGuideTab" data-tab="phone">手机—手机</button>' +
      '<button class="btn ' + (active === 'astrbot' ? 'primary' : 'ghost') + '" data-act="qqGuideTab" data-tab="astrbot">AstrBot 后台智能体</button></div>';
    if (active === 'pc') {
      html += '<div class="card"><div class="card-title"><h3>第一步：电脑准备 NapCat</h3><span>下载地址可复制</span></div>' +
        '<div class="rs">在电脑上下载 Windows 版并启动 NapCat，按提示登录你自己的 QQ。下载页面选 Windows 版本，不要选 Source code（源码压缩包）。电脑和 NapCat 要一直开着。</div>' +
        link('NapCat 下载页', 'https://github.com/NapNeko/NapCatQQ/releases') +
        link('直接下载 NapCat Shell ZIP（电脑已装 QQ）', 'https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip') +
        link('直接下载 NapCat Windows 一键包（不需要先装 QQ）', 'https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.Windows.OneKey.zip') +
        link('NapCat Windows / Shell 安装说明', 'https://napneko.github.io/guide/boot/Shell') +
        '<div class="notice" style="margin-top:10px">NapCat 若提示打开 WebUI，就在电脑浏览器打开控制台显示的地址；常见是 <b>http://127.0.0.1:6099/webui/</b>，端口以实际显示为准。WebUI 密码只登录管理页，不是 OneBot Token。</div></div>' +
        '<div class="card"><div class="card-title"><h3>第二步：让手机能找到 NapCat</h3></div>' +
        '<div class="rs">NapCat WebUI →「网络配置」→「新建」→ 选「WebSocket 服务端」（也叫正向 WebSocket）。它表示电脑开一个入口，手机主动连过来。</div>' +
        '<div class="rs" style="margin-top:9px">监听地址填 <b>0.0.0.0</b>，端口用 <b>3001</b>，路径填 <b>/</b>，设置并保存一串 Token，再勾选启用。不要把 WebUI 密码当 Token。</div>' +
        '<div class="rs" style="margin-top:9px">同一 Wi‑Fi 时，在 Windows「设置 → 网络和 Internet → Wi‑Fi → 当前网络」找电脑 IPv4，例如 <b>192.168.1.25</b>。手机地址就是 <b>ws://192.168.1.25:3001/</b>。不要填 127.0.0.1，那是手机自己。</div>' +
        link('NapCat WebUI 配置说明', 'https://napneko.github.io/config/basic') +
        '<div class="notice rose" style="margin-top:10px">不要把 3001 端口映射到公网。只在可信网络里连接。</div></div>' +
        '<div class="card"><div class="card-title"><h3>第三步：在 DSH 填地址</h3></div>' +
        '<div class="rs">回到 DSH「设置 → 个人 QQ 连接」，填电脑地址和刚设置的同一串 Token。私聊白名单填发消息人的 QQ 号；要进群就填群号。点「保存并连接」，状态显示已连接后试发一条消息。</div>' +
        '<button class="btn primary block" data-act="openPage" data-page="qq" style="margin-top:10px">打开 DSH 连接设置</button></div>' +
        '<div class="card"><div class="card-title"><h3>两边不在同一个 Wi‑Fi</h3><span>推荐私有网络</span></div>' +
        '<div class="rs">电脑和手机都安装 Tailscale 并登录同一个私人网络。电脑在线后，查看电脑的 Tailscale IP（常见以 100. 开头）；DSH 地址改成 <b>ws://电脑的TailscaleIP:3001/</b>。Windows 防火墙只允许可信的 Tailscale/专用网络访问。</div>' +
        link('Tailscale 安卓安装说明', 'https://tailscale.com/docs/install/android') + link('Tailscale 其他设备下载', 'https://tailscale.com/download') +
        '<div class="notice rose" style="margin-top:10px">不要直接向公网开放 NapCat 端口。连不上时检查 NapCat 是否在线、IP/端口/路径/Token 是否一致，以及防火墙是否拦截。</div></div>';
    } else if (active === 'phone') {
      var installCmd = 'curl -fL -o napcat.termux.sh https://raw.githubusercontent.com/NapNeko/NapCat-Installer/main/script/install.termux.sh && bash napcat.termux.sh';
      var startCmd = 'proot-distro login napcat -- bash -c \'xvfb-run -a /root/Napcat/opt/QQ/qq --no-sandbox\'';
      var backgroundCmd = 'screen -dmS napcat proot-distro login napcat -- bash -c "xvfb-run -a /root/Napcat/opt/QQ/qq --no-sandbox"';
      html += '<div class="notice violet">手机 A 安装并运行 DSH；手机 B 专门运行 QQ 和 NapCat。先让两台手机连同一个普通 Wi‑Fi，不要使用访客 Wi‑Fi 或手机热点；部分路由器会隔离设备，导致互相找不到。手机 B 建议为 64 位 ARM 设备，并预留约 2 GB 空间；Termux、Linux 容器和 NapCat 会持续占用电量。</div>' +
        '<div class="card"><div class="card-title"><h3>第一步：手机 B 安装 Termux 和 NapCat</h3></div>' +
        '<div class="rs">从同一个来源安装 Termux（推荐 F-Droid；不要把 F-Droid 和 GitHub 版混装）。首次启动 Termux 后，复制命令回车。安装会下载 Debian 和 NapCat，约需 1.5 GB 以上空间；保持网络稳定，不要清理 Termux 数据或中途卸载。屏幕显示“NapCat 安装完成”后再继续。</div>' +
        link('Termux（F-Droid）', 'https://f-droid.org/packages/com.termux/') +
        link('Termux 官方 GitHub 版本', 'https://github.com/termux/termux-app/releases') +
        link('NapCat Termux 安装脚本下载', 'https://raw.githubusercontent.com/NapNeko/NapCat-Installer/main/script/install.termux.sh') +
        '<pre class="guide-tree">' + esc(installCmd) + '</pre><div class="btn-row"><button class="btn ghost" data-act="copyText" data-text="' + esc(installCmd) + '">复制安装命令</button><button class="btn ghost" data-act="openUrl" data-url="https://napneko.github.io/guide/boot/Shell">打开 NapCat 官方 Termux 说明</button></div>' +
        '<div class="notice" style="margin-top:10px">如果安装中断，重新打开 Termux，在原目录执行 <code>bash napcat.termux.sh --resume</code> 继续；若报空间不足，先在手机 B 清理至少 2 GB 空间。</div></div>' +
        '<div class="card"><div class="card-title"><h3>第二步：启动 NapCat，并确认 QQ 已登录</h3></div>' +
        '<div class="rs">在手机 B 的 Termux 运行下面命令，首次启动时保持 Termux 打开。按 NapCat 的登录提示完成 QQ 登录；看到 NapCat 在线后，使用手机 B 浏览器打开启动日志给出的 WebUI 地址，常见为 <b>http://127.0.0.1:6099/webui/</b>。如果端口不是 6099，以日志显示为准。</div><pre class="guide-tree">' + esc(startCmd) + '</pre>' +
        '<button class="btn ghost" data-act="copyText" data-text="' + esc(startCmd) + '">复制启动命令</button>' +
        '<div class="rs" style="margin-top:10px">WebUI 密码只用于登录管理页；登录 QQ 后进入「网络配置」→「新建」→「WebSocket 服务端」（正向 WS）。设置监听地址 <b>0.0.0.0</b>、端口 <b>3001</b>、Token，并勾选保存时启用。这个服务使用根路径 <b>/</b>；请记下 Token，后面要原样填入 DSH。WebUI 的 6099 和 OneBot 的 3001 是两个不同端口。</div>' +
        '<div class="notice" style="margin-top:10px">若 WebUI 首次要求设置管理密码，请按提示完成。不要把 WebUI 管理密码填到 DSH 的 OneBot Token 栏。</div></div>' +
        '<div class="card"><div class="card-title"><h3>第三步：查手机 B 的 IP，再从手机 A 连接</h3></div>' +
        '<div class="rs">手机 B 打开「设置 → WLAN/Wi‑Fi → 当前已连接网络」，查看 IPv4 地址，例如 <b>192.168.1.25</b>。回到手机 A 的 DSH「设置 → 个人 QQ 连接」：地址填 <b>ws://192.168.1.25:3001/</b>（把示例 IP 换成手机 B 的实际 IP），OneBot Token 填刚才设置的同一串。不要填 <b>0.0.0.0</b>、<b>127.0.0.1</b> 或 WebUI 的 6099 端口。</div>' +
        '<div class="rs" style="margin-top:9px">私聊测试时，允许私聊 QQ 号填写发消息人的 QQ 号；要接群聊则填群号并保存。连接状态显示「已连接」后，先用白名单 QQ 给机器人发一条私聊消息；群聊默认需要 @ 桌宠才回复。</div>' +
        '<button class="btn primary block" data-act="openPage" data-page="qq" style="margin-top:10px">打开 DSH 连接设置</button>' +
        '<div class="notice rose" style="margin-top:10px">如果两台手机不在同一个 Wi‑Fi，可在两边安装并登录 Tailscale，把地址里的局域网 IP 换成手机 B 的 Tailscale IP（常见为 100.x）。不要把 3001 端口映射或开放到公网。</div>' +
        link('Tailscale 安卓安装说明', 'https://tailscale.com/docs/install/android') + '</div>' +
        '<div class="card"><div class="card-title"><h3>第四步：锁屏后保持 NapCat 在线</h3></div>' +
        '<div class="rs">先完成一次前台启动、QQ 登录和 WebSocket 配置。若手机 B 锁屏后断线，可在 Termux 执行下面命令改为 screen 后台运行，然后在 Android 电池设置中把 Termux 设为「不受限制/允许后台活动」；部分手机还需允许自启动并锁定最近任务。</div>' +
        '<pre class="guide-tree">' + esc(backgroundCmd) + '</pre><button class="btn ghost" data-act="copyText" data-text="' + esc(backgroundCmd) + '">复制后台启动命令</button>' +
        '<div class="rs" style="margin-top:10px">回到后台会话看日志：<code>screen -r napcat</code>；从日志界面退出但让 NapCat 继续运行：按 <b>Ctrl+A</b>，再按 <b>D</b>。若提示会话已存在，先运行 <code>screen -r napcat</code> 检查旧进程，不要重复启动多个 NapCat。</div></div>' +
        '<div class="card"><div class="card-title"><h3>连不上时按这个顺序检查</h3></div>' +
        '<div class="rs"><b>超时/一直重试：</b>确认手机 B 上 NapCat 仍运行、两机连的是同一普通 Wi‑Fi、手机 B IP 没变化；关闭访客网络隔离，暂时断开会拦截局域网的 VPN。</div>' +
        '<div class="rs" style="margin-top:8px"><b>连接被拒绝：</b>手机 B 的 NapCat 没启动，或 WebSocket 服务端未启用；确认端口是 3001，别误用 WebUI 的 6099。</div>' +
        '<div class="rs" style="margin-top:8px"><b>401/403：</b>DSH 与 NapCat 的 OneBot Token 不一致。注意这不是 WebUI 登录密码。</div>' +
        '<div class="rs" style="margin-top:8px"><b>404/400 或未升级成功：</b>检查选的是「WebSocket 服务端/正向 WS」，地址路径以 <code>/</code> 结尾；不要选 HTTP，也不要选 WebSocket 客户端/反向 WS。</div>' +
        '<div class="rs" style="margin-top:8px"><b>已连接但没有回复：</b>先核对允许私聊 QQ 号；群聊核对群号，默认还需要 @ 桌宠。确认 NapCat 的 QQ 登录状态在线。</div>' +
        '<div class="rs" style="margin-top:8px">也可在手机 A 浏览器访问 <code>http://手机B的IP:3001/</code> 检查端口是否可达；该端口是 WebSocket 服务，浏览器出现协议提示或错误页不一定代表失败，若直接显示「无法连接/超时」才优先查网络或监听设置。</div></div>';
    } else {
      html += '<div class="notice">本应用只使用自己这条 NapCat 连接处理 QQ：私聊和群聊都由桌宠应答，群聊在后台单开一路、不接私聊。AstrBot 不再直接接管 QQ，只作为桌宠背后的智能体按需调用；请不要再把 NapCat 连到 AstrBot 的 OneBot 平台，否则同一个 QQ 号会被两个客户端各回一次，出现双重人格。</div>' +
        '<div class="card"><div class="card-title"><h3>本机运行管理器（同一部手机）</h3><span>AstrBot + NapCat</span></div>' +
        '<div class="rs">如果 AstrBot 和 NapCat 都在本机的「AstrBot 运行管理器」里：插件页的 AstrBot 地址填 <b>http://127.0.0.1:6185</b>，并在 AstrBot WebUI「设置 → OpenAPI」创建 Key、勾选 <b>chat</b> 和 <b>plugin</b>；QQ 连接地址填 <b>ws://127.0.0.1:6199/ws</b>，NapCat 未设 Token 时可留空。</div>' +
        '<div class="rs" style="margin-top:9px"><b>关键一步：</b>管理器默认会把 NapCat 同时接到 AstrBot 的 OneBot v11 平台。这样同一条 QQ 消息会被 AstrBot 和桌宠各回一次，插件指令也可能执行两次。请到 AstrBot WebUI 的「机器人 / 消息平台」停用或删除 OneBot v11 平台，只保留 NapCat 的 WebSocket 服务端（6199）给 DSH 连接。</div>' +
        '<div class="rs" style="margin-top:9px">插件下载、启用和状态都在「功能中心 → 插件」；插件指令必须以 <b>/</b> 开头，例如 <b>/help</b>。</div></div>' +
        '<div class="card"><div class="card-title"><h3>正确的工作方式</h3></div>' +
        '<div class="rs"><b>QQ 这一侧：</b>NapCat 只开一个 WebSocket 服务端给 DSH 手机连（见「电脑—手机」或「手机—手机」）。不要同时给 AstrBot 建反向 WebSocket 客户端。</div>' +
        '<div class="rs" style="margin-top:10px"><b>AstrBot 这一侧：</b>只到「功能中心 → 插件」填写 AstrBot 地址和 API Key 并启用智能体，不需要在 AstrBot 里创建 OneBot v11 平台。DSH 私聊会通过官方 <b>POST /api/v1/chat</b> 进入 AstrBot 内建 Agent 并支持插件工具；群聊在「个人 QQ 连接」里单独开启「群聊使用 AstrBot 插件」，会话按群隔离。</div>' +
        '<div class="rs" style="margin-top:10px"><b>以前连过的：</b>到 AstrBot WebUI「机器人」里停用或删除 OneBot v11 平台，并在 NapCat WebUI「网络配置」里删除指向 AstrBot 的 WebSocket 客户端，只保留给 DSH 的那个 WebSocket 服务端。若页面提示检测到另一个客户端在应答，按这一步处理后重连即可。</div>' +
        link('AstrBot 官方下载安装说明', 'https://docs.astrbot.app/deploy/astrbot/launcher.html') +
        link('AstrBot OpenAPI 官方说明', 'https://docs.astrbot.app/dev/openapi.html') +
        '<button class="btn ghost block" data-act="openPage" data-page="qq" style="margin-top:10px">返回 DSH QQ 连接设置</button></div>';
    }
    return html;
  }

  function renderCore() {
    var html = pageHead('智能核心', 'DeepSeek 驱动聊天、主动陪伴与台词', true);
    html += '<div class="card"><div class="card-title"><h3>API</h3><span>仅本地保存</span></div>' +
      '<label class="field"><span>API Key</span><input id="apiKey" type="password" placeholder="sk-..." value="' + esc(S.cfg.apiKey || '') + '"></label>' +
      '<label class="field"><span>接口地址</span><input id="baseUrl" value="' + esc(S.cfg.baseUrl || 'https://api.deepseek.com') + '"></label>' +
      '<label class="field"><span>模型</span><input id="model" value="' + esc(S.cfg.model || 'deepseek-chat') + '"></label>' +
      '<label class="field"><span>余额接口路径</span><input id="balancePath" placeholder="留空自动判断，如 /user/balance" value="' + esc(S.cfg.balancePath || '') + '"></label>' +
      '<div class="btn-row"><button class="btn primary" data-act="saveApi">保存并连接</button><button class="btn" data-act="balance">查询余额</button></div>' +
      '<div class="btn-row" style="margin-top:8px"><button class="btn ghost sm" data-act="modelExport">导出模型配置</button><button class="btn ghost sm" data-act="modelImport">导入模型配置</button></div>' +
      (S.balance ? '<div class="notice mint" style="margin-top:12px">' + esc(S.balance) + '</div>' : '') +
      '<div class="faint" style="margin-top:10px">Key 仅保存在本地 S-G，不会上传到任何第三方。余额默认按供应商判断（DeepSeek / Moonshot / 硅基流动 / OpenRouter）；智谱、通义、豆包没有公开余额接口，只能去控制台看，知道路径也可以手动填。</div></div>';
    html += '<div class="card"><div class="card-title"><h3>视觉模型</h3><span>看图专用</span></div>' +
      '<label class="field"><span>视觉接口地址</span><input id="visionBaseUrl" placeholder="留空沿用上面的接口地址" value="' + esc(S.cfg.visionBaseUrl || '') + '"></label>' +
      '<label class="field"><span>视觉 API Key</span><input id="visionApiKey" type="password" placeholder="留空沿用上面的 Key" value="' + esc(S.cfg.visionApiKey || '') + '"></label>' +
      '<label class="field"><span>视觉模型</span><input id="visionModel" placeholder="留空默认 deepseek-flash" value="' + esc(S.cfg.visionModel || '') + '"></label>' +
      '<label class="field"><span>视觉余额路径</span><input id="visionBalancePath" placeholder="留空自动判断" value="' + esc(S.cfg.visionBalancePath || '') + '"></label>' +
      '<div class="btn-row"><button class="btn primary" data-act="saveVision">保存视觉模型</button><button class="btn" data-act="balVision">查询视觉余额</button></div>' +
      (S.balanceVision && !sameBal() ? '<div class="notice mint" style="margin-top:12px">视觉：' + esc(S.balanceVision) + '</div>' : '') +
      '<div class="faint" style="margin-top:10px">只用于看屏幕截图、识别表情包；留空时 DeepSeek 走 deepseek-flash，别家沿用主模型。普通聊天不会走这里，费用也分开算。</div></div>';
    html += '<div class="card"><div class="card-title"><h3>完整视频理解</h3><span>' + (S.cfg.geminiVideoApiKey ? '已配置' : '需配置') + '</span></div>' +
      '<label class="field"><span>Gemini API Key</span><input id="geminiVideoApiKey" type="password" placeholder="' + (S.cfg.geminiVideoApiKey ? '已保存；留空保持原值' : '从 Google AI Studio 获取') + '" value=""></label>' +
      '<label class="field"><span>视频理解模型</span><input id="geminiVideoModel" autocapitalize="off" spellcheck="false" value="' + esc(S.cfg.geminiVideoModel || 'gemini-3.8-flash') + '"></label>' +
      '<div class="btn-row"><button class="btn primary" data-act="saveGeminiVideo">保存视频设置</button><button class="btn ghost" data-act="clearGeminiVideoKey">清除 Key</button></div>' +
      '<div class="faint" style="margin-top:10px">发送后会把原始整段视频交给 Gemini，覆盖全片时间线和音轨；应用不抽取关键帧或用截图替代。Gemini 会按自身方式采样画面，快速闪过的细节可能遗漏。视频会上传到 Google 临时处理，完成后应用会请求删除云端文件；使用前请确认你接受将视频发送给该服务。模型服务自身的格式、时长、大小和额度限制仍适用。</div></div>';
    var comfySelected = S.cfg.imageGenProvider === 'comfyui';
    var imageGenConfigured = comfySelected
      ? !!(S.cfg.comfyUiBaseUrl && S.cfg.comfyUiWorkflow)
      : !!(S.cfg.imageGenApiKey && S.cfg.imageGenBaseUrl && S.cfg.imageGenModel);
    html += '<div class="card"><div class="card-title"><h3>图像生成</h3><span>' + (imageGenConfigured ? '已配置' : '未配置') + '</span></div>' +
      '<label class="field"><span>绘图方式</span><select id="imageGenProvider">' +
      '<option value="openai" ' + (!comfySelected ? 'selected' : '') + '>OpenAI 兼容图像接口</option>' +
      '<option value="comfyui" ' + (comfySelected ? 'selected' : '') + '>ComfyUI（使用电脑上的模型）</option></select></label>' +
      '<div class="rs" style="margin:8px 0">云端图像接口</div>' +
      '<label class="field"><span>图像服务地址</span><input id="imageGenBaseUrl" placeholder="例如 https://api.openai.com/v1" value="' + esc(S.cfg.imageGenBaseUrl || '') + '"></label>' +
      '<label class="field"><span>图像服务 API Key</span><input id="imageGenApiKey" type="password" placeholder="' + (S.cfg.imageGenApiKey ? '已保存；留空保持原值' : '图像服务提供的 Key') + '" value=""></label>' +
      '<label class="field"><span>图像模型</span><input id="imageGenModel" placeholder="按服务商提供的模型名填写" value="' + esc(S.cfg.imageGenModel || '') + '"></label>' +
      '<div class="rs" style="margin:16px 0 8px">ComfyUI 局域网绘图</div>' +
      '<label class="field"><span>电脑上的 ComfyUI 地址</span><input id="comfyUiBaseUrl" autocapitalize="off" spellcheck="false" placeholder="例如 http://192.168.1.20:8188" value="' + esc(S.cfg.comfyUiBaseUrl || '') + '"></label>' +
      '<label class="field"><span>正向提示词节点 ID（可留空自动识别）</span><input id="comfyUiPromptNodeIds" autocapitalize="off" placeholder="多个 ID 用逗号分隔，例如 6" value="' + esc(S.cfg.comfyUiPromptNodeIds || '') + '"></label>' +
      '<label class="field"><span>ComfyUI API 工作流 JSON</span><textarea id="comfyUiWorkflow" rows="8" autocapitalize="off" spellcheck="false" placeholder="粘贴 ComfyUI 导出的 API 格式工作流 JSON">' + esc(S.cfg.comfyUiWorkflow || '') + '</textarea></label>' +
      '<button class="btn ghost" data-act="comfyUiWorkflowImport">从文件导入 API 工作流 JSON</button>' +
      '<button class="btn primary" data-act="saveImageGen">保存图像生成设置</button>' +
      '<div class="faint" style="margin-top:10px">ComfyUI 需在电脑上运行，手机和电脑连同一局域网；默认端口 8188。ComfyUI 默认只接受本机连接，需开启局域网监听（例如 --listen 0.0.0.0）。请导出“API 格式”工作流，不是普通画布工作流；建议包含 SaveImage 输出节点。想保留工作流里的画风词，可在正向提示词中写 {{prompt}}，生成时会替换成你的描述。请勿把未加保护的 ComfyUI 服务暴露到公网。</div>' +
      '<div class="faint" style="margin-top:8px">未配置或生成失败时，桌宠会说明原因，不会用模板图冒充成图。</div></div>';
    html += '<div class="card"><div class="card-title"><h3>语音</h3><span>' + (S.cfg.ttsEnabled ? '朗读开' : '朗读关') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">回复朗读</div><div class="rs">使用系统 TTS，可在聊天页右上角切换</div></div>' +
      '<button class="btn sm ghost" data-act="openPage" data-page="companion">语音设置</button></div></div>';
    return html;
  }

  function renderHub() {
    if (!S.featureLoaded) {
      S.featureFavs = featureLoad('favorites');
      S.featureRecent = featureLoad('recent');
      S.featureLoaded = true;
    }
    var q = String(S.featureQuery || '').trim().toLowerCase();
    var html = pageHead('功能中心', '常用功能集中在这里', true);
    html += '<div class="card feature-center-search"><label class="feature-search"><span>⌕</span><input id="featureHubSearch" placeholder="搜功能或关键词，例如：插件、提醒" value="' + esc(S.featureQuery) + '"></label></div>';
    html += '<div id="featureHubResults">';
    if (q) {
      html += featureSearchHtml(q, false);
    } else html += featureGroupsHtml();
    html += '</div>';
    return html;
  }

  function renderSg() {
    var p = S.perms || {};
    var path = (S.state.storage || {}).path || p.path || '';
    var html = pageHead('S-G 数据', '仅本应用可读 · 导出备份 · 换机迁移', true);
    html += '<div class="card"><div class="card-title"><h3>存储位置</h3><span>应用私有</span></div>' +
      '<div class="mono" style="margin-bottom:10px">' + esc(path) + '</div>' +
      '<div class="notice mint">数据在应用私有目录：相册、文件管理器、媒体扫描和其他应用都看不到，也无法读取或删除图片、视频、模型和文本。卸载应用会一并删除，换机前请先导出备份。</div>' +
      '<div class="btn-row" style="margin-top:12px"><button class="btn" data-act="export">导出备份</button>' +
      '<button class="btn" data-act="importZip">导入备份</button></div></div>';
    html += '<div class="card"><div class="card-title"><h3>备份与迁移</h3><span>ZIP / 加密</span></div>' +
      '<div class="faint" style="line-height:1.7">以后换手机：先在旧机导出 ZIP，再在新机导入，人设、聊天、记忆、提醒、台词、书籍、插件和设置都会一起带过去。旧版 Documents/S-G 不再读取，可以从文件管理器里手动删掉。</div>' +
      '<button class="btn primary block sm" data-act="openPage" data-page="backup" style="margin-top:12px">打开备份与迁移</button></div>';
    return html;
  }

  /* ---------------- 首次表单（一次性，不可更改） ---------------- */
  /* ---------------- 首次表单 / 人格种子 ---------------- */
  function obInit() {
    var c = S.contract || {};
    var label = c.relationLabel || c.relation || '朋友';
    S.obSel = {
      userName: c.userName || '',
      herName: c.herName || '蓝色大肥鱼',
      group: c.relationGroup || 'friend',
      relationId: c.relationId || 'friend',
      relationLabel: label,
      customRelation: c.customRelation || '',
      archetypeId: c.archetypeId || (S.seedUpgrade ? 'natural' : 'gentle'),
      toneId: c.toneId || 'soft',
      toneName: c.tone || '软萌',
      seed: c.seed || Math.floor(Math.random() * 999999)
    };
    S.obStep = 1;
  }

  function obRelations() {
    return (S.presets && S.presets.relations) || [];
  }

  function obSyncRel() {
    if (!S.obSel) obInit();
    var all = obRelations();
    var found = null;
    all.forEach(function (r) { if (r.id === S.obSel.relationId) found = r; });
    if (!found && S.obSel.relationLabel) {
      all.forEach(function (r) { if (!found && r.label === S.obSel.relationLabel) found = r; });
    }
    if (found) {
      S.obSel.relationId = found.id;
      S.obSel.group = found.group;
      S.obSel.relationLabel = found.label;
    } else if (S.obSel.relationLabel && S.obSel.relationId !== 'custom') {
      S.obSel.relationId = 'custom';
      S.obSel.group = 'custom';
      S.obSel.customRelation = S.obSel.relationLabel;
    }
  }

  function obLoadPresets() {
    if (S.presetsLoading) return;
    S.presetsLoading = 1;
    call('personaPresets').then(function (r) {
      S.presets = r.presets || {};
      S.presetsLoading = 0;
      if (!S.obSel) obInit();
      obSyncRel();
      render();
    }).catch(function () {
      S.presetsLoading = 0;
      toast('人格目录读取失败');
    });
  }

  function obReadInputs() {
    if (!S.obSel) obInit();
    var un = $('#obUserName');
    var hn = $('#obHerName');
    var cu = $('#obCustom');
    if (un) S.obSel.userName = un.value || '';
    if (hn) S.obSel.herName = hn.value || '蓝色大肥鱼';
    if (cu) S.obSel.customRelation = cu.value || '';
  }

  function renderOnboarding() {
    if (!S.presets) {
      obLoadPresets();
      return '<div class="onboarding"><div class="onboarding-card"><h2>正在准备人格目录…</h2><p class="faint">30 种关系 × 12 种性格 × 8 种语调</p></div></div>';
    }
    if (!S.obSel) obInit();
    obSyncRel();
    var sel = S.obSel;
    var resuming = !!S.resumeOnboarding;
    var step = num(S.obStep, 1);
    var groupLabel = sel.group;
    (S.presets.groups || []).forEach(function (g) { if (g.id === sel.group) groupLabel = g.label; });
    var head = S.seedUpgrade ? '这次可以重新选一次' : (resuming ? '把上次没填的补上' : '认识一下吧');
    var sub = S.seedUpgrade
      ? '保存后永久锁定；记忆、亲密度和聊天记录都会保留。'
      : (resuming ? '上次跳过了。现在补上还来得及；保存后同样不可更改。' : '这些只填一次，之后她会自己长成只属于你的样子。');
    var html = '<div class="onboarding">' +
      '<div class="onboarding-warn">⚠️ 填写后不可更改，三思后写</div>' +
      '<div class="onboarding-card"><h2>' + head + '</h2><p class="faint">' + sub + '</p>' +
      '<div class="ob-steps">' + [1, 2, 3].map(function (n) {
        return '<i class="' + (step === n ? 'on' : '') + '"></i>';
      }).join('') + '</div>';

    if (step === 1) {
      html += '<label class="field"><span>你希望她怎么叫你</span><input id="obUserName" placeholder="可以留空，让她在聊天里自己发现" value="' + esc(sel.userName) + '"></label>' +
        '<label class="field"><span>给她起个名字</span><input id="obHerName" value="' + esc(sel.herName) + '" placeholder="蓝色大肥鱼"></label>' +
        '<div class="ob-group"><div class="ob-label">你们的关系 · 大类</div><div class="ob-chips">';
      (S.presets.groups || []).forEach(function (g) {
        html += '<button class="ob-chip' + (sel.group === g.id ? ' on' : '') + '" data-act="obGroup" data-group="' + esc(g.id) + '">' + esc(g.label) + '</button>';
      });
      html += '<button class="ob-chip' + (sel.group === 'custom' ? ' on' : '') + '" data-act="obGroup" data-group="custom">自定义</button></div>';
      if (sel.group === 'custom') {
        html += '<div class="ob-sub-label">自定义关系 · 小类</div>' +
          '<input id="obCustom" maxlength="12" placeholder="例如：妹妹、姐姐、搭档…" value="' + esc(sel.customRelation || '') + '">';
      } else {
        html += '<div class="ob-sub-label">具体关系 · ' + esc(groupLabel) + '（小类）</div>' +
          '<div class="ob-chips">';
        obRelations().forEach(function (r) {
          if (r.group !== sel.group) return;
          html += '<button class="ob-subchip' + (sel.relationId === r.id ? ' on' : '') + '" data-act="obPickRel" data-id="' + esc(r.id) + '">' + esc(r.label) + '</button>';
        });
        html += '</div>';
      }
      html += '</div>' +
        '<button class="btn onboarding-skip" data-act="onboardingSkip">跳过</button>' +
        (resuming ? '<button class="btn ghost block" data-act="closeOnboarding" style="margin-top:10px">返回首页</button>' : '') +
        '<button class="btn primary block" data-act="obNext" style="margin-top:14px">下一步：选性格</button>';
    } else if (step === 2) {
      html += '<div class="ob-label" style="margin-bottom:8px">她的性格原型</div><div class="ob-arch-grid">';
      if (S.seedUpgrade) {
        html += '<button class="ob-arch' + (sel.archetypeId === 'natural' ? ' on' : '') + '" data-act="obPickArch" data-id="natural"><b>自然演化</b><span>沿用现状</span><small>不重置性格，继续按相处慢慢长</small></button>';
      }
      (S.presets.archetypes || []).forEach(function (a) {
        html += '<button class="ob-arch' + (sel.archetypeId === a.id ? ' on' : '') + '" data-act="obPickArch" data-id="' + esc(a.id) + '"><b>' + esc(a.name) + '</b><span>' + esc((a.keywords || []).join(' · ')) + '</span><small>' + esc(a.prompt || '') + '</small></button>';
      });
      html += '</div><button class="btn ghost block sm" data-act="obRandom" style="margin-top:10px">换一个性格</button>' +
        '<div class="btn-row" style="margin-top:14px"><button class="btn ghost" data-act="obBack">上一步</button><button class="btn primary" data-act="obNext">下一步：选语调</button></div>';
    } else {
      html += '<div class="ob-label" style="margin-bottom:8px">她的语调</div><div class="ob-chips">';
      (S.presets.tones || []).forEach(function (t) {
        html += '<button class="ob-chip' + (sel.toneId === t.id ? ' on' : '') + '" data-act="obPickTone" data-id="' + esc(t.id) + '" data-name="' + esc(t.name) + '">' + esc(t.name) + '</button>';
      });
      var rl = sel.relationId === 'custom' ? (sel.customRelation || '自定义') : sel.relationLabel;
      var an = '自然演化';
      (S.presets.archetypes || []).forEach(function (a) { if (a.id === sel.archetypeId) an = a.name; });
      var tn = sel.toneName || '软萌';
      (S.presets.tones || []).forEach(function (t) { if (t.id === sel.toneId) tn = t.name; });
      html += '</div><div class="notice violet" style="margin-top:12px">预览：<b>' + esc(rl) + '</b> · <b>' + esc(an) + '</b> · <b>' + esc(tn) + '</b><br><span class="faint">关系决定称呼与边界，性格决定关心方式，语调只改表达节奏。</span></div>' +
        '<div class="btn-row" style="margin-top:14px"><button class="btn ghost" data-act="obBack">上一步</button><button class="btn primary" data-act="onboardingSave">保存，之后不可更改</button></div>' +
        (S.seedUpgrade ? '<button class="btn ghost block sm" data-act="obUseCurrent" style="margin-top:10px">沿用现状，不改人格</button>' : '');
    }
    html += '</div></div>';
    return html;
  }

  function permRow(name, on, kind) {
    return '<div class="perm"><div class="pi"><b>' + esc(name) + '</b></div><span class="pv ' + (on ? 'on' : 'off') + '">' + (on ? '已开启' : '未开启') + '</span>' +
      (on ? '' : '<button class="btn sm ghost" data-act="openPerm" data-kind="' + kind + '">去开启</button>') + '</div>';
  }

  /* ---------------- 人设与记忆 ---------------- */
  function renderPersona() {
    var p = S.persona, u = S.profile;
    var html = pageHead('角色与记忆', '只有名字可以改，其他由相处自然长出来', true);
    html += '<div class="card"><div class="persona-top"><div class="persona-model"><img src="file:///android_asset/webp/a032.webp" alt=""></div>' +
      '<div><h2>' + esc(p.name || '蓝色大肥鱼') + '</h2><p>' + esc((p.style || '').slice(0, 70)) + '</p>' +
      (p.category ? '<span class="tag violet" style="margin-top:7px">' + esc(p.category) + '</span>' : '') + '</div></div>' +
      '<div class="faint" style="margin-top:9px">人设不再手写。说话风格、开场白、边界和小习惯会在每天 0:00 根据你们的聊天与记忆自动更新，你只需要给她一个名字。</div>' +
      (p.autoAt ? '<div class="faint" style="margin-top:4px">上次自动更新：' + esc(fmt(p.autoAt)) + '</div>' : '') + '</div>';

    var ps = S.personaSeed || {};
    html += '<div class="card"><div class="card-title"><h3>角色框架</h3><span>首次填写后锁定</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">关系</div><div class="rs">决定称呼、亲近速度与边界</div></div><span class="tag violet">' + esc(ps.relationLabel || '朋友') + '</span></div>' +
      '<div class="row"><div><div class="rt">性格原型</div><div class="rs">决定她怎么表达关心和情绪</div></div><span class="tag mint">' + esc(ps.archetypeName || '自然演化') + '</span></div>' +
      '<div class="row"><div><div class="rt">语调</div><div class="rs">只影响表达节奏与用词</div></div><span class="tag">' + esc(ps.toneName || '软萌') + '</span></div>' +
      '<div class="faint">人格只影响说话与相处方式，不会更换立绘，也不是多角色。</div></div>';

    html += '<div class="card"><div class="card-title"><h3>名字</h3><span>唯一可修改项</span></div>' +
      field('personaName', '名字', p.name) +
      '<button class="btn primary block" data-act="savePersona">保存名字</button></div>';

    html += '<div class="card"><div class="card-title"><h3>她现在的样子</h3><span>每天 0:00 自动更新</span></div>' +
      '<div class="rlabel">说话风格</div><div class="faint" style="line-height:1.8">' + esc(p.style || '还在观察你…') + '</div>' +
      '<div class="rlabel">最近的小习惯</div><div class="faint" style="line-height:1.8">' + esc(p.habits || '还在观察你…') + '</div>' +
      '<div class="rlabel">初次问候 / 开场白</div><div class="faint" style="line-height:1.8">' + esc(p.greeting || '还在观察你…') + '</div>' +
      '<div class="rlabel">边界与禁忌</div><div class="faint" style="line-height:1.8">' + esc(p.boundaries || '还在观察你…') + '</div>' +
      '<div class="faint" style="margin-top:10px">这些文字只读；她会根据聊天、记忆和性格变化自己重写，不需要你操心。</div></div>';

    html += '<div class="card"><div class="card-title"><h3>我的资料</h3><span>让她更懂你</span></div>' +
      '<button class="profile-edit-link" data-act="profileOpen"><span><b>头像与显示名字</b><small>用于首页问候和聊天展示；不规定她如何称呼你</small></span><em>编辑 ›</em></button>' +
      '<div class="field-row">' + field('uAge', '年龄', u.age) + field('uJob', '职业', u.job) + '</div>' +
      '<div class="field-row">' + field('uCity', '城市', u.city) + field('uLikes', '喜欢', u.likes) + '</div>' +
      field('uDislikes', '不喜欢', u.dislikes) +
      area('uNotes', '补充说明', u.notes, 64) +
      field('uImportant', '重要日子', u.important) +
      '<button class="btn primary block" data-act="saveProfile">保存我的资料</button></div>';

    html += '<div class="card"><div class="card-title"><h3>相处习惯</h3><span>重复确认后由你决定</span></div>' +
      '<div class="faint" style="line-height:1.7">她只会从你明确纠正或提出、并在不同消息里重复出现的相处方式生成候选。你启用后才会用于一对一聊天；单次推断不会改写她的人设，群聊和手机操作边界不受影响。</div>';
    var rules = S.companionRules || [];
    var visibleRules = rules.filter(function (r) { return r.status === 'pending' || r.status === 'active'; });
    if (!visibleRules.length) html += '<div class="empty">还没有待确认的相处习惯。她会先观察，不会把一次聊天当成长期规则。</div>';
    visibleRules.forEach(function (r) {
      var evidence = Array.isArray(r.evidence) ? r.evidence : [];
      var evidenceHtml = evidence.slice(-3).map(function (e) {
        return '<div class="faint" style="margin-top:5px">“' + esc(e.quote || '') + '” · ' + esc(fmt(e.ts || 0)) + '</div>';
      }).join('');
      html += '<div class="list-item" style="align-items:flex-start"><div class="li-ico"><svg viewBox="0 0 24 24"><path d="M12 3l2.4 5.6L20 11l-5.6 2.4L12 19l-2.4-5.6L4 11l5.6-2.4z"/></svg></div>' +
        '<div class="li-main"><b style="white-space:normal">' + esc(r.text || '') + '</b>' +
        '<span>' + (r.status === 'active' ? '已启用' : '待你确认') + ' · ' + (r.evidenceCount || evidence.length || 0) + ' 次独立表达</span>' + evidenceHtml +
        '<div class="btn-row" style="margin-top:9px">' +
        (r.status === 'pending'
          ? '<button class="btn sm primary" data-act="companionRuleApprove" data-id="' + esc(r.id) + '">启用习惯</button><button class="btn sm ghost" data-act="companionRuleDismiss" data-id="' + esc(r.id) + '">忽略</button>'
          : '<button class="btn sm ghost" data-act="companionRuleDelete" data-id="' + esc(r.id) + '">移除</button>') +
        '</div></div></div>';
    });
    html += '</div>';

    html += '<div class="card"><div class="card-title"><h3>长期记忆</h3><span>' + S.memories.length + ' 条</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">自动记住</div><div class="rs">每 4 条对话自动提炼稳定事实</div></div>' + switchHtml('memoryAuto', S.cfg.memoryAuto, 'toggleCfg') + '</div>' +
      '<div class="search" style="margin-top:10px"><input id="memInput" placeholder="手动写一条关于你的记忆…"><button class="btn sm primary" data-act="memoryAdd">添加</button></div>';
    if (!S.memories.length) html += '<div class="empty">还没有记忆。多聊几句，或手动写一条。</div>';
    else {
      html += '<div class="list">';
      S.memories.slice().reverse().forEach(function (m) {
        html += '<div class="list-item"><div class="li-ico"><svg viewBox="0 0 24 24"><path d="M12 3l2.4 5.6L20 11l-5.6 2.4L12 19l-2.4-5.6L4 11l5.6-2.4z"/></svg></div>' +
          '<div class="li-main"><b style="white-space:normal">' + esc(m.text) + '</b><span>' + (m.source === 'auto' ? 'AI 提炼' : '手动') + ' · ' + fmt(m.ts) + '</span></div>' +
          '<button class="li-act danger" data-act="memoryDel" data-id="' + esc(m.id) + '">删除</button></div>';
      });
      html += '</div>';
    }
    html += '<button class="btn ghost block sm" data-act="memoryExtract" style="margin-top:10px">现在整理一次记忆</button>' +
      '<button class="btn ghost block sm" data-act="memoryClearAll" style="margin-top:8px">清空全部记忆</button>' +
      '<div class="faint" style="margin-top:6px">清空只删除记忆与整理日志；人设、聊天记录和亲密度会保留。</div></div>';
    return html;
  }

  function field(id, label, value) {
    return '<label class="field"><span>' + esc(label) + '</span><input id="' + id + '" value="' + esc(value || '') + '"></label>';
  }
  function area(id, label, value, h) {
    return '<label class="field"><span>' + esc(label) + '</span><textarea id="' + id + '"' + (h ? ' style="min-height:' + h + 'px"' : '') + '>' + esc(value || '') + '</textarea></label>';
  }

  function renderUserProfile() {
    var draft = S.userProfileDraft || {
      nickname: profileName(),
      avatarData: String((S.profile || {}).avatarData || '')
    };
    S.userProfileDraft = draft;
    var avatar = draft.avatarData
      ? '<img src="' + esc(draft.avatarData) + '" alt="我的头像">'
      : '<span>' + esc(String(draft.nickname || '我').trim().slice(0, 1) || '我') + '</span>';
    var html = pageHead('我的资料', '这是你的资料名字，不是她必须使用的称呼', true);
    html += '<div class="card user-profile-card"><div class="user-profile-avatar-wrap">' +
      '<button class="user-profile-avatar" data-act="profileAvatarPick" aria-label="更换头像">' + avatar + '<i>更换</i></button>' +
      '<div class="user-profile-avatar-note"><b>个人头像</b><span>显示在聊天里</span></div></div>' +
      '<label class="field"><span>你的名字</span><input id="userDisplayName" maxlength="40" value="' + esc(draft.nickname || '') + '" placeholder="输入你希望显示的名字"></label>' +
      '<div class="notice violet">首页会按时间显示“早上好 / 中午好 / 下午好 / 晚上好，名字”。她能看到你的资料名字，但会按你们的关系和自己的性格决定怎样称呼你；这不会改变她自己的名字。</div>' +
      '<div class="btn-row"><button class="btn ghost" data-act="profileAvatarRemove">移除头像</button><button class="btn primary" data-act="profileSaveCard">保存资料</button></div></div>' +
      '<div class="faint" style="text-align:center;padding:4px 8px 18px">头像和显示名字只保存在这台设备的用户资料中。</div>';
    return html;
  }

  /* ---------------- 广告跳过 ---------------- */
  function renderAd() {
    var ad = S.ad || {};
    var lines = S.adLines || [];
    var html = pageHead('广告跳过', '基于无障碍的跳过引擎，兼容常见规则 JSON', true);
    html += '<div class="card"><div class="row" style="border-top:0;padding:0"><div><div class="rt">启用跳过</div><div class="rs">识别明确的广告跳过按钮，或匹配已导入规则</div></div>' +
      '<label class="switch"><input type="checkbox" id="adEnabled" ' + (S.cfg.adEnabled ? 'checked' : '') + '><span class="track"></span></label></div>' +
      '<div class="divider"></div><div class="row" style="border-top:0;padding:0"><div><div class="rt">成功后台词</div><div class="rs">随机说一条，并播放庆祝动作</div></div>' + switchHtml('adShowLine', S.cfg.adShowLine, 'toggleCfg') + '</div>' +
      '<div class="notice violet" style="margin-top:12px">跳过成功 ' + num((S.state.stats || {}).skips, 0) + ' 次。无障碍权限是必须的；规则仅在本机解析。</div></div>';

    html += '<div class="card"><div class="card-title"><h3>规则库</h3><span>' + num(ad.count, 0) + ' 条</span></div>';
    if (ad.sample && ad.sample.length) {
      html += '<div class="list">';
      ad.sample.forEach(function (s) {
        html += '<div class="list-item"><div class="li-ico"><svg viewBox="0 0 24 24"><path d="M5 5h14v14H5z"/><path d="M9 9h6M9 13h4"/></svg></div><div class="li-main"><b>' + esc(s) + '</b></div></div>';
      });
      html += '</div>';
    } else {
      html += '<div class="empty">暂无自定义规则。内置规则只会匹配明确的“跳过广告 / Skip Ad”控件或广告页面里的跳过按钮，不会误点普通页面的“跳过”。</div>';
    }
    html += '<div class="btn-row"><button class="btn primary" data-act="importRules">导入规则文件</button><button class="btn" data-act="pasteRules">粘贴 JSON</button><button class="btn ghost" data-act="clearRules">清空</button></div>' +
      '<div class="faint" style="margin-top:10px">支持李跳跳风格的 package / text / desc / id 规则，解析器会自动兼容嵌套结构。无法识别的字段会被忽略。</div></div>';

    html += '<div class="card"><div class="card-title"><h3>成功台词</h3><span>随机一条</span></div>';
    for (var i = 0; i < 5; i++) {
      html += '<label class="field"><span>台词 ' + (i + 1) + '</span><input id="adLine' + i + '" value="' + esc(lines[i] || '') + '"></label>';
    }
    html += '<button class="btn primary block" data-act="saveAdLines">保存台词</button></div>';
    return html;
  }

  /* ---------------- 提醒 ---------------- */
  function renderReminders() {
    var html = pageHead('提醒中心', '只汇总日历日程和我手动加的提醒', true);
    html += '<div class="card"><div class="card-title"><h3>新增提醒</h3><span>手动</span></div>' +
      '<label class="field"><span>标题</span><input id="remTitle" placeholder="例如：取快递"></label>' +
      '<label class="field"><span>详情</span><input id="remDetail" placeholder="例如：菜鸟驿站 3 号柜"></label>' +
      '<label class="field"><span>到点时间（可选）</span><input id="remAt" type="datetime-local"></label>' +
      '<label class="field"><span>类型</span><input id="remType" type="hidden" value="manual">' +
      '<div class="rchips" id="remTypeChips">' +
      '<button type="button" class="rchip on" data-act="remType" data-value="manual">普通</button>' +
      '<button type="button" class="rchip" data-act="remType" data-value="plan">日程</button>' +
      '<button type="button" class="rchip" data-act="remType" data-value="travel">出行</button></div></label>' +
      '<button class="btn primary block" data-act="addReminder">添加</button>' +
      '<div class="faint" style="margin-top:8px">填了到点时间，到点会提醒一次；不填就只记录。来自微信、外卖、快递的通知不会写进这里。</div></div>';

    html += '<div class="card"><div class="card-title"><h3>待处理</h3><span>' + S.reminders.length + ' 条</span></div>';
    if (!S.reminders.length) html += '<div class="empty">暂时没有提醒。</div>';
    else {
      html += '<div class="list">';
      S.reminders.slice().reverse().forEach(function (r) {
        html += '<div class="list-item"><div class="li-ico">' + reminderIcon(r.type) + '</div><div class="li-main"><b>' + esc(r.title) + '</b><span>' + esc(r.detail || '') + '</span><span>' + (r.at ? '到点：' + fmt(r.at) + (r.fired ? ' · 已提醒' : '') : fmt(r.ts)) + '</span></div>' +
          '<button class="li-act danger" data-act="delReminder" data-id="' + esc(r.id) + '">完成</button></div>';
      });
      html += '</div>';
    }
    html += '</div>';

    html += '<div class="card"><div class="card-title"><h3>来源</h3><span>' + (S.perms.listener ? '已授权' : '未授权') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">读取通知</div><div class="rs">解析未读消息、外卖与快递</div></div>' +
      (S.perms.listener ? '<span class="tag mint">已授权</span>' : '<button class="btn sm ghost" data-act="openPerm" data-kind="listener">授权</button>') + '</div>' +
      '<div class="row"><div><div class="rt">接收应用</div><div class="rs">' + ((S.cfg.notifyApps || []).length ? '已选 ' + (S.cfg.notifyApps || []).length + ' 个' : '全部应用') + '</div></div><button class="btn sm ghost" data-act="notifyApps">选择</button></div>' +
      '<div class="row"><div><div class="rt">日历出行</div><div class="rs">从系统日历提取航班、高铁、酒店与行程</div></div><button class="btn sm ghost" data-act="calendarSync">同步</button></div>' +
      '<div class="row"><div><div class="rt">未读消息计数</div><div class="rs">' + num(S.state.unread, 0) + ' 条</div></div><span class="tag">实时</span></div></div>';
    return html;
  }

  /* ---------------- 新增：主动策略 ---------------- */
  function modeName(m) {
    return m === 'game' ? '游戏中' : m === 'focus' ? '专注中' : m === 'night' ? '夜间' : '日常';
  }
  function typeLabel(t) {
    var map = { chat: '闲聊', greet: '问候', reminder: '提醒', health: '健康', music: '音乐', ad: '广告',
      focus: '专注', geo: '位置', plugin: '插件', plan: '日程', parcel: '快递', travel: '出行', weather: '天气' };
    return map[t] || (t || '其他');
  }
  function moodLabel(m) {
    var map = { happy: '开心', calm: '平静', tired: '累', annoyed: '烦躁', focused: '专注' };
    return map[m] || '未记录';
  }

  function renderPolicy() {
    var p = S.policy || {};
    var pv = p.preview || {};
    var cats = [['polCatChat', '闲聊'], ['polCatReminder', '提醒'], ['polCatHealth', '健康'], ['polCatMusic', '音乐'],
      ['polCatAd', '广告'], ['polCatFocus', '专注'], ['polCatGeo', '位置'], ['polCatPlugin', '插件']];
    var html = pageHead('主动记录', '她不会一直打扰你，这里决定什么时候可以开口', true);
    var it = S.intimacy || {};
    var quietUntil = num(p.quietUntil, 0);
    html += '<div class="stats">' + stat(num(p.total, 0), '今日主动') +
      stat(num(p.actionHour, 0) + ' / ' + num(p.actionCap, 4), '动作碎碎念') +
      stat(esc(it.stage || '初识'), '关系阶段') + '</div>';

    html += '<div class="card"><div class="card-title"><h3>她此刻会开口吗</h3><span>' + (pv.ok ? '可以' : '先安静') + '</span></div>' +
      '<div class="notice ' + (pv.ok ? 'mint' : 'violet') + '">' + esc(pv.reason || '正在判断…') + '</div>' +
      '<div class="faint" style="margin-top:8px">' + (pv.ok ? '现在适合说一句。' : '下一次大约 ' + num(pv.nextMin, 20) + ' 分钟后再看。') + '</div>' +
      '<div class="faint">价值 ' + num(pv.value, 0) + ' / 100 · 连续未回应 ' + num(pv.unanswered, 0) + ' 条</div></div>';

    html += '<div class="card"><div class="card-title"><h3>主动节奏</h3><span>' + ((S.cfg.polQuiet || quietUntil > Date.now()) ? '静默中' : moodLabel(p.mood)) + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">一键静默</div><div class="rs">只保留日程、快递、位置等重要提醒</div></div>' + switchHtml('polQuiet', S.cfg.polQuiet, 'toggleCfg') + '</div>' +
      // 主动次数不再由用户设定：亲密度越高，她越想找你、也越敢开口
      '<div class="row"><div><div class="rt">动作碎碎念</div><div class="rs">现在唯一还有配额的主动类型，随亲密度提高</div></div><span class="tag">' + num(p.actionCap, 4) + ' 次/时</span></div>' +
      '<div class="row"><div><div class="rt">其他主动</div><div class="rs">音乐、闲聊、健康、天气、位置按各自节奏，不再吃预算</div></div><span class="tag mint">不拦截</span></div>' +
      '<div class="row"><div><div class="rt">屏幕闲聊</div><div class="rs">跟主动检查间隔一致；和音乐挤在一起时自动错开</div></div><span class="tag">自动 ' + num(p.chatGapMin, 20) + ' 分钟</span></div>' +
      '<div class="row"><div><div class="rt">打开应用问候</div><div class="rs">回到应用时本地问候，4 小时间隔、每天最多 2 次</div></div>' + switchHtml('greetEnabled', S.cfg.greetEnabled !== false, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">夜间重要提醒只显示气泡</div><div class="rs">夜间不闲聊；日程/提醒只出气泡，不朗读</div></div>' + switchHtml('polNightBubbleOnly', S.cfg.polNightBubbleOnly, 'toggleCfg') + '</div>' +
      '<div class="field-row">' + field('polNightStart', '夜间开始', S.cfg.polNightStart || '23:00') + field('polNightEnd', '夜间结束', S.cfg.polNightEnd || '07:00') + '</div>' +
      '<div class="row"><div><div class="rt">专注时只保留重要提醒</div><div class="rs">番茄钟工作阶段自动降噪</div></div>' + switchHtml('polFocusImportant', S.cfg.polFocusImportant, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">参考心情</div><div class="rs">记录到你烦躁/疲惫时，减少主动开口</div></div>' + switchHtml('polMoodAffect', S.cfg.polMoodAffect, 'toggleCfg') + '</div>' +
      '<div class="btn-row" style="margin-top:10px"><button class="btn sm ghost" data-act="policyPause" data-min="60">静默 1 小时</button><button class="btn sm ghost" data-act="policyPause" data-morning="1">到明天早上</button></div>' +
      (quietUntil > Date.now() ? '<div class="faint" style="margin-top:6px">定时静默到 ' + fmt(quietUntil) + '</div>' : '') +
      '<button class="btn ghost block sm" data-act="openPage" data-page="relation">在「我们的关系」里看亲密度</button></div>';

    var lv = num(S.cfg.proactiveLevel, 1);
    var lvName = lv <= 0 ? '安静' : lv >= 2 ? '黏人' : '适中';
    var lvChips = '';
    [['0', '安静'], ['1', '适中'], ['2', '黏人']].forEach(function (x) {
      lvChips += '<button class="rchip ' + (lv === num(x[0], 1) ? 'on' : '') + '" data-act="setProactiveLevel" data-level="' + x[0] + '">' + x[1] + '</button>';
    });
    html += '<div class="card"><div class="card-title"><h3>主动程度</h3><span>' + lvName + '</span></div>' +
      '<div class="rchips">' + lvChips + '</div>' +
      '<div class="faint" style="margin-top:8px">只调整“值不值得开口”的门槛；夜间、忙碌、静默、未回应降频仍然生效。</div></div>';

    html += '<div class="card"><div class="card-title"><h3>分类开关</h3><span>关掉的类型不会主动开口</span></div>';
    cats.forEach(function (c) {
      html += '<div class="row"><div><div class="rt">' + c[1] + '</div></div>' + switchHtml(c[0], S.cfg[c[0]], 'toggleCfg') + '</div>';
    });
    html += '</div>';

    var srcs = p.sources || [];
    html += '<div class="card"><div class="card-title"><h3>她参考了哪些信息</h3><span>本地判断</span></div>';
    srcs.forEach(function (s) {
      html += '<div class="row"><div><div class="rt">' + esc(s.name || '') + '</div><div class="rs">' + esc(s.note || '') + '</div></div>' +
        '<span class="tag ' + (s.on ? 'mint' : '') + '">' + esc(s.status || '') + '</span></div>';
    });
    html += '<div class="faint" style="margin-top:8px">屏幕文字只有开启并授权后使用；主动日志只存决策理由和短文案，不存屏幕原文。</div></div>';

    var log = p.log || [];
    html += '<div class="card"><div class="card-title"><h3>决策日志</h3><span>' + log.length + ' 条</span></div>';
    if (!log.length) html += '<div class="empty">还没有记录。她每次想开口都会先经过这里。</div>';
    else {
      html += '<div class="list">';
      log.slice(0, 40).forEach(function (l) {
        html += '<div class="list-item"><div class="li-ico"><svg viewBox="0 0 24 24"><path d="M12 8v5l3 2"/><circle cx="12" cy="12" r="8"/></svg></div>' +
          '<div class="li-main"><b style="white-space:normal">' + esc(l.text || l.why || '') + '</b><span>' + typeLabel(l.type) + ' · ' + esc(l.why || '') + (num(l.tokens, 0) > 0 ? ' · ' + num(l.tokens, 0) + ' tokens' : '') + ' · ' + fmt(l.ts) + '</span></div>' +
          '<span class="tag ' + (l.ok ? 'mint' : '') + '">' + (l.ok ? '已开口' : '已拦截') + '</span></div>';
      });
      html += '</div>';
    }
    html += '<button class="btn ghost block sm" data-act="policyClear">清空日志</button></div>';
    return html;
  }

  /* ---------------- 新增：权限健康 ---------------- */
  function renderHealth() {
    var list = S.health || [];
    var coreIds = { overlay: 1, notif: 1, battery: 1 };
    var core = [], more = [];
    list.forEach(function (h) { (coreIds[h.id] ? core : more).push(h); });
    var okCore = 0, okMore = 0;
    core.forEach(function (h) { if (h.granted) okCore++; });
    more.forEach(function (h) { if (h.granted) okMore++; });
    function row(h) {
      return '<div class="perm"><div class="pi"><b>' + esc(h.title) + '</b><span>' + esc(h.affects || '') + '</span>' +
        (h.granted ? '' : '<span style="color:#ffcf7a">' + esc(h.fix || '') + '</span>') + '</div>' +
        '<span class="pv ' + (h.granted ? 'on' : 'off') + '">' + (h.granted ? '已开启' : '未开启') + '</span>' +
        (h.granted
          ? '<button class="btn sm ghost" data-act="openPerm" data-kind="' + esc(h.id === 'battery' ? 'batteryOff' : (h.id === 'calendar' ? 'app' : h.id)) + '">关闭</button>'
          : '<button class="btn sm ghost" data-act="openPerm" data-kind="' + esc(h.id === 'calendar' ? 'app' : h.id) + '">去开启</button>') + '</div>';
    }
    var html = pageHead('使用前检查', (core.length - okCore > 0 ? '只差 ' + (core.length - okCore) + ' 项就能正常用，其他都可以以后再说' : '必做项都好了，其他都是可选'), true);
    html += '<div class="stats">' + stat(okCore + ' / ' + core.length, '必做已开') + stat(okMore + ' / ' + more.length, '可选已开') + stat((S.state.running ? '是' : '否'), '桌宠运行') + '</div>';
    html += '<div class="card"><div class="card-title"><h3>必做</h3><span>' + (okCore === core.length ? '都好了' : '还差 ' + (core.length - okCore) + ' 项') + '</span></div>';
    if (!list.length) html += '<div class="empty">正在检查…</div>';
    else core.forEach(function (h) { html += row(h); });
    html += '<button class="btn ghost block sm" data-act="healthRefresh" style="margin-top:10px">重新检查</button></div>';
    html += '<details class="more-wrap"' + (_healthMore ? ' open' : '') + '><summary>更多权限（可选 ' + more.length + ' 项）</summary><div class="card">';
    more.forEach(function (h) { html += row(h); });
    html += '<div class="notice violet">无障碍和通知读取都是可选的。开启后，屏幕文字或通知内容可能被发送给你配置的 AI 服务来理解情境；不需要就保持关闭。</div></div></details>';
    html += '<div class="notice violet">权限只能由你在系统设置里改，应用不会背着你改。若某项开启后仍显示未开启，请到系统设置确认。</div>';
    return html;
  }

  /* ---------------- 新增：剪贴板 ---------------- */
  function renderClip() {
    var list = S.clips || [];
    var html = pageHead('剪贴板', '复制、分享进来的内容都会收在这里，不上传', true);
    html += '<div class="card"><div class="card-title"><h3>收纳</h3><span>' + list.length + ' 条</span></div>' +
      '<div class="search"><input id="clipInput" placeholder="手动记一条…"><button class="btn sm primary" data-act="clipAdd">保存</button></div>' +
      '<div class="btn-row"><button class="btn primary" data-act="clipCapture">读取当前剪贴板</button><button class="btn ghost" data-act="clipClear">清空</button></div>' +
      '<div class="faint" style="margin-top:10px">在任意应用里分享文字或图片给桌宠，也会自动收进来。可用 AI 一键总结或改写。</div></div>';
    html += '<div class="card"><div class="card-title"><h3>记录</h3><span>' + (S.cfg.clipMask ? '已脱敏' : '原文') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">手机号 / 邮箱脱敏</div><div class="rs">保存时自动打码</div></div>' + switchHtml('clipMask', S.cfg.clipMask, 'toggleCfg') + '</div>';
    if (!list.length) html += '<div class="empty">还没有内容。复制一段文字后再来。</div>';
    else {
      html += '<div class="list">';
      list.slice().reverse().forEach(function (c) {
        var text = c.text || c.path || '';
        html += '<div class="list-item"><div class="li-ico"><svg viewBox="0 0 24 24"><path d="M8 8h11v12H8z"/><path d="M5 16V4h11"/></svg></div>' +
          '<div class="li-main"><b style="white-space:normal">' + esc(text.slice(0, 90)) + '</b><span>' + esc(c.source || 'manual') + ' · ' + fmt(c.ts) + (c.masked ? ' · 已脱敏' : '') + '</span></div>' +
          '<button class="li-act" data-act="clipAct" data-id="' + esc(c.id) + '" data-text="' + esc(text) + '">总结</button>' +
          '<button class="li-act danger" data-act="clipDel" data-id="' + esc(c.id) + '">删除</button></div>';
      });
      html += '</div>';
    }
    html += '</div>';
    return html;
  }

  /* ---------------- 新增：备份与迁移 ---------------- */
  function renderBackup() {
    var pv = S.backupPreview;
    var html = pageHead('备份与迁移', '整包带走人设、聊天、记忆、任务和设置', true);
    html += '<div class="card"><div class="card-title"><h3>导出</h3><span>ZIP</span></div>' +
      '<label class="field"><span>密码（建议设置，可留空）</span><input id="backupPass" type="password" placeholder="设置后导入需要同一密码"></label>' +
      '<div class="notice violet">加密使用 PBKDF2 + AES-GCM，密码只在本机计算。忘记密码将无法恢复。</div>' +
      '<div class="notice">导出文件包含 API Key 与全部聊天记录，请不要转发给他人或上传到公开网盘。</div>' +
      '<button class="btn primary block" data-act="backupExport">导出备份</button></div>';
    html += '<div class="card"><div class="card-title"><h3>导入</h3><span>先预览再决定</span></div>' +
      '<label class="field"><span>密码（加密备份必填）</span><input id="restorePass" type="password" placeholder="没有密码可留空"></label>' +
      '<div class="btn-row"><button class="btn" data-act="backupPreview">预览备份</button><button class="btn ghost" data-act="backupApply" data-mode="merge">只补充缺少的内容</button></div>' +
      '<button class="btn danger block sm" data-act="backupApply" data-mode="replace" style="margin-top:10px">用备份替换现在</button>' +
      '<div class="faint" style="margin-top:8px">「补充」只加入现在没有的内容，不动现有的；「替换」会清空现在的内容，完全按备份恢复。</div>';
    if (pv) {
      html += '<div class="notice mint" style="margin-top:12px">' + (pv.encrypted ? '加密备份' : '普通备份') + ' · ' +
        num(pv.entries, 0) + ' 个文件 · ' + Math.round(num(pv.bytes, 0) / 1024) + ' KB<br>' +
        '人设：' + esc(pv.persona || '—') + ' · 记忆 ' + num(pv.memories, 0) + ' 条 · 聊天 ' + num(pv.chats, 0) + ' 行 · 提醒 ' + num(pv.reminders, 0) + ' 条 · 任务 ' + num(pv.tasks, 0) + ' 条</div>';
    } else if (S.backupPreview === null) {
      html += '<div class="faint" style="margin-top:10px">导入前会检查文件完整性，并列出里面有什么。</div>';
    }
    html += '</div>';
    html += '<div class="card"><div class="card-title"><h3>迁移日志</h3><span>' + (S.migrations || []).length + ' 条</span></div>';
    if (!(S.migrations || []).length) html += '<div class="empty">还没有导入记录。</div>';
    else {
      html += '<div class="list">';
      (S.migrations || []).slice(0, 20).forEach(function (m) {
        html += '<div class="list-item"><div class="li-main"><b>' + esc(m.mode || '导入') + '</b><span>' + esc(m.file || '') + ' · ' + fmt(m.ts) + '</span></div></div>';
      });
      html += '</div>';
    }
    html += '<div class="faint" style="margin-top:10px">本地目录：' + esc(storageShort()) + '</div></div>';

    html += '<div class="card"><div class="card-title"><h3>删除我的全部数据</h3><span>不可恢复</span></div>' +
      '<div class="faint" style="line-height:1.7">会删除本机保存的人设、聊天记录、记忆、提醒、任务、书籍、插件、皮肤和 API Key 设置，桌宠会停止。卸载应用也会达到同样效果。</div>' +
      '<div class="notice">删除前建议先导出一份备份；删除后无法找回。</div>' +
      '<button class="btn danger block" data-act="deleteAllData" style="margin-top:10px">删除我的全部数据</button></div>';
    return html;
  }

  /* ---------------- 新增：任务与专注 ---------------- */
  function renderTasks() {
    var tasks = S.tasks || [];
    var f = S.taskFocus || {};
    var sum = S.taskSummary || {};
    var todo = tasks.filter(function (t) { return t.status !== 'done'; });
    var html = pageHead('任务与专注', '把待办和番茄钟连起来，做完会记住', true);
    html += '<div class="stats">' + stat(todo.length, '待办') + stat(num(sum.minutes, 0), '近 7 天分钟') + stat(num(sum.sessions, 0), '专注段数') + '</div>';
    var running = !!f.running;
    html += '<div class="card"><div class="card-title"><h3>专注</h3><span>' + (running ? (f.phase === 'work' ? '专注中' : '休息中') : '未开始') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">当前任务</div><div class="rs">' + esc(f.title || '自由专注') + '</div></div><span class="tag">' + num(f.minutes, 0) + ' 分钟</span></div>' +
      (running
        ? '<button class="btn danger block sm" data-act="focusStop">结束专注</button>'
        : '<label class="field"><span>分钟</span><input id="focusMin" type="number" min="1" max="180" value="25"></label><button class="btn primary block sm" data-act="focusStart">开始专注</button>') +
      '</div>';
    html += '<div class="card"><div class="card-title"><h3>新增待办</h3><span>本地</span></div>' +
      '<label class="field"><span>标题</span><input id="taskTitle" placeholder="例如：写完周报"></label>' +
      '<label class="field"><span>详情</span><input id="taskDetail" placeholder="可选"></label>' +
      '<div class="field-row"><label class="field"><span>截止时间</span><input id="taskDue" type="datetime-local"></label>' +
      '<label class="field"><span>优先级</span><select id="taskPriority"><option value="2">高</option><option value="1" selected>中</option><option value="0">低</option></select></label></div>' +
      '<button class="btn primary block" data-act="taskAdd">添加</button></div>';
    html += '<div class="card"><div class="card-title"><h3>待办</h3><span>' + todo.length + ' 条</span></div>';
    if (!todo.length) html += '<div class="empty">没有待办。想专注时也可以直接开始。</div>';
    else {
      html += '<div class="list">';
      todo.forEach(function (t) {
        html += '<div class="list-item"><div class="li-ico"><svg viewBox="0 0 24 24"><path d="M5 5h14v14H5z"/><path d="M9 12l2 2 4-5"/></svg></div>' +
          '<div class="li-main"><b>' + esc(t.title) + '</b><span>' + esc(t.detail || '') + (t.due ? ' · ' + fmt(t.due) : '') + ' · ' + (t.priority === 2 ? '高优先' : t.priority === 0 ? '低优先' : '普通') + '</span></div>' +
          '<button class="li-act" data-act="taskFocus" data-id="' + esc(t.id) + '" data-title="' + esc(t.title) + '">专注</button>' +
          '<button class="li-act" data-act="taskDone" data-id="' + esc(t.id) + '">完成</button>' +
          '<button class="li-act danger" data-act="taskDel" data-id="' + esc(t.id) + '">删除</button></div>';
      });
      html += '</div>';
    }
    html += '</div>';
    var ev = S.events || [];
    html += '<div class="card"><div class="card-title"><h3>近期日程</h3><span>' + ev.length + ' 项</span></div>';
    if (!ev.length) html += '<div class="empty">未读取到日程。授权日历后会显示会议与出行。</div>';
    else {
      html += '<div class="list">';
      ev.slice(0, 8).forEach(function (e) {
        html += '<div class="list-item"><div class="li-ico"><svg viewBox="0 0 24 24"><path d="M4 6h16v14H4z"/><path d="M8 4v4M16 4v4M4 10h16"/></svg></div>' +
          '<div class="li-main"><b>' + esc(e.title) + '</b><span>' + esc(e.place || '') + ' · ' + fmt(e.start) + '</span></div></div>';
      });
      html += '</div>';
    }
    html += '</div>';
    return html;
  }

  /* ---------------- 记忆图谱 ---------------- */
  function renderMem() {
    var sm = S.memSummary || {};
    var it = S.intimacy || {};
    var html = pageHead('记忆图谱', '她记住的关于你的一切，都可以在这里查看、修改和清理', true);
    html += '<div class="card"><div class="card-title"><h3>我们的关系</h3><span>' + esc(it.stage || '初识') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">亲密度 ' + num(it.score, 0) + ' / ∞</div>' +
      '<div class="rs">她一天会主动找你 ' + num(it.dayCap, 8) + ' 次，越亲近越多</div></div>' +
      '<button class="btn sm" data-act="openPage" data-page="relation">查看</button></div>' +
      '<div class="faint">日记 · 共同经历 · 她因你变成的样子 · 她惦记的事</div></div>';
    html += '<div class="stats">' + stat(num(sm.total, 0), '有效记忆') + stat(num(sm.archived, 0), '已归档') + stat(S.memReviewDue ? '待体检' : '正常', '记忆体检') + '</div>';
    html += '<div class="card mem-graph-launch-card"><div><b>打开识海</b><span>看看她记住的生活、感情与真实关联</span></div>' +
      '<button class="btn primary" data-act="memGraphOpen">进入识海</button></div>';
    if (S.memReviewDue) {
      html += '<div class="notice violet">有一批很久没用到的记忆。体检一下，避免她记着过时的信息。<button class="btn sm" style="margin-left:8px" data-act="memCheckup">开始体检</button></div>';
    }
    html += '<div class="card"><div class="card-title"><h3>添加</h3><span>会参与之后的对话</span></div>' +
      '<div class="search"><input id="memText" maxlength="120" placeholder="例如：我每周三晚上健身"></div>' +
      '<div class="field-row"><label class="field"><span>类型</span><select id="memTypeSel">' +
      '<option value="">自动判断</option><option value="fact">事实</option><option value="preference">偏好</option>' +
      '<option value="person">人物</option><option value="project">项目</option><option value="event">事件</option>' +
      '<option value="plan">计划</option><option value="concern">惦记</option></select></label></div>' +
      '<button class="btn primary block" data-act="memAddNew">记住这条</button></div>';

    var types = [['all', '全部'], ['fact', '事实'], ['preference', '偏好'], ['person', '人物'],
      ['project', '项目'], ['event', '事件'], ['plan', '计划'], ['concern', '惦记']];
    html += '<div class="card"><div class="card-title"><h3>筛选</h3><span>' + (S.memShowArchived ? '已归档' : '有效记忆') + '</span></div><div class="rchips">';
    types.forEach(function (t) {
      html += '<button class="rchip ' + (S.memType === t[0] ? 'on' : '') + '" data-act="memFilter" data-type="' + t[0] + '">' + t[1] + '</button>';
    });
    html += '<button class="rchip ' + (S.memShowArchived ? 'on' : '') + '" data-act="memArchivedToggle">' + (S.memShowArchived ? '返回有效记忆' : '查看已归档') + '</button>' +
      '</div></div>';

    var items = S.memItems || [];
    if (S.memType !== 'all') {
      items = items.filter(function (m) { return (m.type || 'fact') === S.memType; });
    }
    html += '<div class="card"><div class="card-title"><h3>' + (S.memShowArchived ? '已归档' : '记忆条目') + '</h3><span>' + items.length + ' 条</span></div>';
    if (!items.length) html += '<div class="empty">' + (S.memShowArchived ? '没有已归档的记忆。' : '还没有记忆。') + '</div>';
    else {
      items.forEach(function (m) {
        var type = m.type || 'fact';
        var status = m.status || (m.archived ? 'archived' : 'active');
        var conf = Math.round(flt(m.confidence, 0.7) * 100);
        var quote = m.sourceQuote || '';
        html += '<div class="mem-item">' +
          '<div class="mem-top"><b class="mem-text">' + esc(m.text) + '</b>' +
          (m.pinned ? '<span class="tag violet">固定</span>' : '') +
          (m.heavy ? '<span class="tag pink">重要</span>' : '') +
          (status === 'uncertain' ? '<span class="tag amber">不确定</span>' : '') +
          (status === 'dormant' ? '<span class="tag">搁置</span>' : '') +
          '</div>' +
          '<div class="mem-meta">' + esc(memTypeName(type)) + ' · ' + esc(memStatusName(status)) + ' · 置信 ' + conf + '% · ' + fmt(m.ts) + ' · ' + esc(memOriginName(m.origin, m.source)) + '</div>' +
          (quote && quote !== m.text ? '<div class="mem-quote">原话：' + esc(quote) + '</div>' : '') +
          '<div class="btn-row" style="margin-top:10px">' +
          '<button class="btn sm ghost" data-act="memEditOpen" data-id="' + esc(m.id) + '">编辑</button>' +
          (S.memShowArchived
            ? '<button class="btn sm" data-act="memUnarchive" data-id="' + esc(m.id) + '">恢复</button>'
            : '<button class="btn sm ghost" data-act="memPin" data-id="' + esc(m.id) + '" data-pinned="' + (m.pinned ? '1' : '0') + '">' + (m.pinned ? '取消固定' : '固定') + '</button>' +
              '<button class="btn sm ghost" data-act="memArchive" data-id="' + esc(m.id) + '">归档</button>') +
          '<button class="btn sm danger" data-act="memDel" data-id="' + esc(m.id) + '" data-pinned="' + (m.pinned ? '1' : '0') + '" data-heavy="' + (m.heavy ? '1' : '0') + '">删除</button>' +
          '</div></div>';
      });
    }
    html += '</div>';
    return html;
  }

  function renderMemGraph() {
    var filters = [['all', '全部'], ['fact', '事实'], ['preference', '偏好'], ['person', '人物'],
      ['project', '项目'], ['event', '事件'], ['plan', '计划'], ['concern', '惦记'],
      ['commitment', '承诺'], ['observation', '印象'], ['emotion', '心情'], ['daily', '日常'],
      ['health', '健康'], ['place', '地点'], ['learning', '学习'], ['interest', '兴趣'],
      ['goal', '目标'], ['experience', '经历']];
    var recordFilters = [['all', '全部'], ['memory', '记忆'], ['experience', '经历'], ['important', '重要']];
    var html = '<div class="mem-graph-screen is-render-paused"><div class="mem-graph-header">' +
      '<button class="mem-graph-back" data-act="back" aria-label="返回">‹</button>' +
      '<div class="mem-graph-heading"><small>DSH / LIVING MEMORY FIELD</small><b>记忆识海</b><span>她记下的你，会在这里慢慢亮起来</span></div>' +
      '<button class="mem-graph-refresh" data-act="memGraphRefresh" aria-label="重新连接识海">↻</button></div>' +
      '<div class="mem-graph-live"><span><i></i>陪伴信号 · 本地私有</span><b id="memGraphCount">正在建立连接…</b></div>' +
      '<div class="mem-graph-filters">' + filters.map(function (f) {
        return '<button class="mem-graph-filter' + (f[0] === S.memGraphFilter ? ' on' : '') + '" data-act="memGraphFilter" data-type="' + f[0] + '">' + f[1] + '</button>';
      }).join('') + '</div>' +
      '<div class="mem-graph-stage"><div class="mem-graph-nebula"></div><div class="mem-graph-grid"></div>' +
      '<div class="mem-graph-left-hud"><small>DSH / MEMORY CONSTELLATION</small><b>她记下的每一件事，<br>都会成为一颗星。</b>' +
      '<div class="mem-graph-stage-stats"><div><b id="memGraphStageMem">—</b><span>有效记忆</span></div><div><b id="memGraphStageLink">—</b><span>真实关联</span></div><div><b id="memGraphStageMoment">—</b><span>共同经历</span></div></div></div>' +
      '<div id="memGraphCanvas" class="mem-graph-canvas"></div>' +
      '<div class="mem-graph-presence" aria-hidden="true"><img src="file:///android_asset/ui/images/memory-maid-orb.webp" alt="闭眼捧着光球休息的桌宠"><i class="mem-graph-orb-glow"></i></div>' +
      '<aside class="mem-graph-record-panel" aria-label="记忆星录"><div class="mem-graph-record-head"><b>记忆星录</b><span>本地记录</span></div><div class="mem-graph-record-tabs">' +
      recordFilters.map(function (f) { return '<button class="' + (f[0] === _memGraphRecordFilter ? 'on' : '') + '" data-act="memGraphRecordFilter" data-type="' + f[0] + '">' + f[1] + '</button>'; }).join('') +
      '</div><div id="memGraphRecordList" class="mem-graph-record-list"><span class="mem-graph-record-empty">正在读取本地记录…</span></div></aside>' +
      '<div class="mem-graph-stage-controls"><button data-act="memGraphZoomOut" aria-label="缩小">−</button><button data-act="memGraphRotate" aria-pressed="false">自动旋转</button><button data-act="memGraphZoomIn" aria-label="放大">+</button><button data-act="memGraphReset">全景</button><button data-act="memGraphRecords">记忆星录</button><button data-act="memGraphExpand" aria-expanded="false">放大视图</button></div>' +
      '<div class="mem-graph-recall-pulse"><i></i><span>她刚刚想起了一段记忆</span></div>' +
      '<div id="memGraphStatus" class="mem-graph-status">正在读取本地记忆…</div>' +
      '<div class="mem-graph-stage-foot"><span>MEMORY NODES</span><i></i><span>VERIFIED RELATIONS</span><i></i><span>3D SPACE</span></div></div>' +
      '<div class="mem-graph-key" aria-label="记忆类型图例"></div>' +
      '<div class="mem-graph-detail" id="memGraphDetail"><div class="mem-graph-detail-head"><i></i><b id="memGraphDetailTitle">点一颗星，看看她记住了什么</b><span>MEMORY TRACE</span></div>' +
      '<span id="memGraphDetailText">每颗发光气泡都对应一条本地记忆，颜色代表类别；点击节点可以查看原始记录。</span>' +
      '<small id="memGraphDetailMeta">只有实际共同标签或原文关键词匹配的记录才会连线；点亮星点可查看来源。</small></div>' +
      '<div class="mem-graph-footnote"><span>PRIVATE · EVIDENCE LINKED</span><span>只读本地有效记忆与已观察经历 · 不上传</span></div>' +
      '<div id="memGraphOpening" class="mem-graph-opening" role="status" aria-live="polite" aria-label="正在汇聚识海记忆">' +
      '<video id="memGraphOpeningVideo" class="mem-graph-opening-video" muted playsinline preload="auto" aria-hidden="true" poster="file:///android_asset/ui/images/oss-cosmic-nebula.webp"><source src="file:///android_asset/ui/videos/dsh-video-skin-boot.mp4" type="video/mp4"></video>' +
      '<canvas id="memGraphOpeningCanvas" class="mem-graph-opening-canvas" aria-hidden="true"></canvas>' +
      '<div class="mem-graph-opening-nebula" aria-hidden="true"></div><div class="mem-graph-opening-grid" aria-hidden="true"></div>' +
      '<div class="mem-graph-opening-top"><i></i><span>DSH / LIVING MEMORY FIELD</span><b>记忆正在归航</b></div>' +
      '<div class="mem-graph-opening-companion">' +
      '<img id="memGraphOpeningMaid" class="mem-graph-opening-maid" src="file:///android_asset/webp/a032.webp" alt="桌宠陪伴形象"></div>' +
      '<div class="mem-graph-opening-copy"><small>MEMORY SYNCHRONIZING</small><b>每一段相处，<br>都在这里重新亮起</b><span id="memGraphOpeningStatus">正在读取手机里保存的记忆…</span>' +
      '<i class="mem-graph-opening-progress"><b id="memGraphOpeningVideoProgress"></b><u></u></i></div>' +
      '<div class="mem-graph-opening-bottom"><span>LOCAL MEMORY · PRIVATE</span><i></i><span>陪伴信号已建立</span></div></div></div>';
    return html;
  }

  function clearMemoryGraphOpeningTimers() {
    clearTimeout(_memGraphOpeningMinTimer);
    clearTimeout(_memGraphOpeningMaxTimer);
    clearTimeout(_memGraphOpeningFadeTimer);
    _memGraphOpeningMinTimer = _memGraphOpeningMaxTimer = _memGraphOpeningFadeTimer = 0;
    clearInterval(_memGraphOpeningVideoWatchTimer);
    _memGraphOpeningVideoWatchTimer = 0;
    _memGraphOpeningStatusTimers.forEach(clearTimeout);
    _memGraphOpeningStatusTimers = [];
  }

  function releaseMemoryGraphOpeningVideo(video) {
    if (!video) return;
    clearInterval(_memGraphOpeningVideoWatchTimer);
    _memGraphOpeningVideoWatchTimer = 0;
    try { video.pause(); } catch (ignored) { }
    video.onplaying = video.onerror = video.ontimeupdate = null;
    video.onloadedmetadata = video.onended = null;
    try { video.removeAttribute('src'); } catch (ignored) { }
    try {
      var sources = video.querySelectorAll('source');
      for (var i = 0; i < sources.length; i++) sources[i].removeAttribute('src');
      video.load();
    } catch (ignored) { }
  }

  function stopMemoryGraphOpening() {
    clearMemoryGraphOpeningTimers();
    if (_memGraphOpeningVideo) {
      releaseMemoryGraphOpeningVideo(_memGraphOpeningVideo);
      _memGraphOpeningVideo = null;
    }
    if (_memGraphOpeningStarfield) {
      try { _memGraphOpeningStarfield.destroy(); } catch (ignored) { }
      _memGraphOpeningStarfield = null;
    }
    var opening = $('#memGraphOpening');
    if (opening && opening.parentNode) opening.parentNode.removeChild(opening);
    _memGraphOpeningReady = false;
    _memGraphOpeningMinDone = false;
    _memGraphOpeningClipEnd = 0;
    _memGraphOpeningVideoFinished = false;
  }

  function finishMemoryGraphOpening(force) {
    var opening = $('#memGraphOpening');
    if (!opening || !opening.parentNode) return;
    if (opening.classList.contains('is-leaving')) return;
    if (!force && (!_memGraphOpeningReady || !_memGraphOpeningMinDone)) return;
    clearTimeout(_memGraphOpeningMinTimer);
    clearTimeout(_memGraphOpeningMaxTimer);
    _memGraphOpeningMinTimer = _memGraphOpeningMaxTimer = 0;
    clearInterval(_memGraphOpeningVideoWatchTimer);
    _memGraphOpeningVideoWatchTimer = 0;
    if (_memGraphOpeningVideo) _memGraphOpeningVideo.pause();
    opening.classList.add('is-leaving');
    _memGraphOpeningFadeTimer = setTimeout(function () {
      if (_memGraphOpeningStarfield) {
        try { _memGraphOpeningStarfield.destroy(); } catch (ignored) { }
        _memGraphOpeningStarfield = null;
      }
      if (_memGraphOpeningVideo) {
        releaseMemoryGraphOpeningVideo(_memGraphOpeningVideo);
        _memGraphOpeningVideo = null;
      }
      if (opening.parentNode) opening.parentNode.removeChild(opening);
      _memGraphOpeningFadeTimer = 0;
    }, 1300);
  }

  function startMemoryGraphOpening() {
    var opening = $('#memGraphOpening');
    if (!opening) return;
    clearMemoryGraphOpeningTimers();
    if (_memGraphOpeningVideo) {
      releaseMemoryGraphOpeningVideo(_memGraphOpeningVideo);
      _memGraphOpeningVideo = null;
    }
    if (_memGraphOpeningStarfield) {
      try { _memGraphOpeningStarfield.destroy(); } catch (ignored) { }
      _memGraphOpeningStarfield = null;
    }
    _memGraphOpeningReady = false;
    _memGraphOpeningMinDone = false;
    _memGraphOpeningClipEnd = 0;
    _memGraphOpeningVideoFinished = false;
    opening.classList.remove('is-leaving');
    opening.classList.remove('has-video');
    var status = $('#memGraphOpeningStatus');
    if (status) status.textContent = '正在读取手机里保存的记忆…';
    var progressFill = $('#memGraphOpeningVideoProgress');
    if (progressFill) progressFill.style.width = '0%';
    var video = $('#memGraphOpeningVideo');
    if (video) {
      _memGraphOpeningVideo = video;
      video.muted = true;
      video.loop = false;
      video.onplaying = function () {
        if (_memGraphOpeningVideo === video && video.isConnected) opening.classList.add('has-video');
      };
      video.onloadedmetadata = function () {
        if (_memGraphOpeningVideo !== video || !isFinite(video.duration) || video.duration <= 0) return;
        // 片尾至少提前 1.7 秒裁停，为口型收尾留出缓冲；比旧版缩短 0.5 秒。
        _memGraphOpeningClipEnd = Math.max(0.1, video.duration - 1.7);
        clearInterval(_memGraphOpeningVideoWatchTimer);
        _memGraphOpeningVideoWatchTimer = setInterval(function () {
          if (_memGraphOpeningVideo !== video || _memGraphOpeningVideoFinished || !_memGraphOpeningClipEnd) return;
          if (video.currentTime >= _memGraphOpeningClipEnd) completeMemoryGraphOpeningVideo(video);
        }, 40);
      };
      video.onerror = function () {
        if (_memGraphOpeningVideo === video) opening.classList.remove('has-video');
      };
      video.ontimeupdate = function () {
        if (_memGraphOpeningVideo !== video || !isFinite(video.duration) || video.duration <= 0) return;
        if (!_memGraphOpeningClipEnd) _memGraphOpeningClipEnd = Math.max(0.1, video.duration - 1.7);
        var fill = $('#memGraphOpeningVideoProgress');
        if (fill) fill.style.width = Math.max(0, Math.min(100, video.currentTime / _memGraphOpeningClipEnd * 100)) + '%';
        if (!_memGraphOpeningVideoFinished && video.currentTime >= _memGraphOpeningClipEnd) completeMemoryGraphOpeningVideo(video);
      };
      video.onended = function () {
        if (_memGraphOpeningVideo !== video || _memGraphOpeningVideoFinished) return;
        completeMemoryGraphOpeningVideo(video);
      };
      try { video.currentTime = 0; } catch (ignored) { }
      try {
        var playResult = video.play();
        if (playResult && typeof playResult.catch === 'function') playResult.catch(function () { opening.classList.remove('has-video'); });
      } catch (ignored) { opening.classList.remove('has-video'); }
    }
    var canvas = $('#memGraphOpeningCanvas');
    if (canvas && window.DshOssStarfield && typeof window.DshOssStarfield.create === 'function') {
      try {
        var reduced = false;
        try { reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (ignored) { }
        _memGraphOpeningStarfield = window.DshOssStarfield.create(canvas, {
          mode: 'converge', count: reduced ? 150 : 240, duration: 5900
        });
      } catch (ignored) { }
    }
    _memGraphOpeningStatusTimers.push(setTimeout(function () {
      if (!_memGraphOpeningReady && status && status.isConnected) status.textContent = '正在把记忆星点编织成脉络…';
    }, 3200));
    _memGraphOpeningStatusTimers.push(setTimeout(function () {
      if (!_memGraphOpeningReady && status && status.isConnected) status.textContent = '正在点亮她与你共同走过的片段…';
    }, 5700));
    _memGraphOpeningMinTimer = setTimeout(function () {
      _memGraphOpeningMinDone = true;
      finishMemoryGraphOpening(false);
    }, 6700);
    _memGraphOpeningMaxTimer = setTimeout(function () {
      if (!_memGraphOpeningReady) {
        _memGraphOpeningReady = true;
        if (status && status.isConnected) status.textContent = '识海仍在后台连接，先为你展开本地记忆…';
      }
      _memGraphOpeningMinDone = true;
      finishMemoryGraphOpening(true);
    }, 16000);
  }

  function completeMemoryGraphOpeningVideo(video) {
    if (!video || _memGraphOpeningVideo !== video || _memGraphOpeningVideoFinished) return;
    _memGraphOpeningVideoFinished = true;
    clearInterval(_memGraphOpeningVideoWatchTimer);
    _memGraphOpeningVideoWatchTimer = 0;
    try {
      video.pause();
      if (_memGraphOpeningClipEnd > 0 && video.currentTime > _memGraphOpeningClipEnd) video.currentTime = _memGraphOpeningClipEnd;
    } catch (ignored) { }
    finishMemoryGraphOpening(false);
  }

  function setMemoryGraphOpeningStatus(message) {
    var status = $('#memGraphOpeningStatus');
    if (status && message) status.textContent = message;
  }

  function markMemoryGraphOpeningReady(message) {
    _memGraphOpeningReady = true;
    setMemoryGraphOpeningStatus(message || '识海数据已就绪，正在为你点亮星海…');
    finishMemoryGraphOpening(false);
  }

  function mountMemoryGraph() {
    if (S.page !== 'memGraph') return;
    var requestId = ++_memGraphLoadId;
    clearTimeout(_memGraphWatchdog);
    var canvas = $('#memGraphCanvas');
    var status = $('#memGraphStatus');
    if (!canvas || !status) return;
    if (window.DshMemoryGraph) window.DshMemoryGraph.pause();
    status.textContent = '正在连接本地记忆核心…';
    setMemoryGraphOpeningStatus('正在读取手机里保存的记忆…');
    status.classList.remove('hidden');
    _memGraphWatchdog = setTimeout(function () {
      if (requestId !== _memGraphLoadId || S.page !== 'memGraph' || !document.documentElement.contains(status)) return;
      status.innerHTML = '<div class="mem-graph-empty"><div class="mem-graph-card"><b>记忆读取比预期更久</b><span>可以点右上角重新连接，或返回后再试。</span></div></div>';
      status.classList.remove('hidden');
      markMemoryGraphOpeningReady('记忆连接稍慢，仍可在后台继续整理…');
    }, 12000);
    call('memGraphData').then(function (r) {
      if (requestId !== _memGraphLoadId || S.page !== 'memGraph' || !document.documentElement.contains(canvas)) return;
      clearTimeout(_memGraphWatchdog);
      var data = (r && r.graph) || r || {};
      setMemoryGraphOpeningStatus('记忆已读取，正在搭建她的星海…');
      var ok = window.DshMemoryGraph && window.DshMemoryGraph.mount(canvas, data, {
        onSelect: function (node) { showMemoryGraphDetail(node); }
      });
      if (ok) {
        window.DshMemoryGraph.filter(S.memGraphFilter || 'all');
        var renderMode = window.DshMemoryGraph.renderMode ? window.DshMemoryGraph.renderMode() : '3d';
        markMemoryGraphOpeningReady(renderMode === '2d'
          ? '本地记忆已点亮，只有真实关联才会连线…'
          : renderMode === 'unavailable'
            ? '当前 WebView 无法绘制星海，记忆仍在本机保存…'
            : '识海数据已就绪，正在为你点亮星海…');
        status.classList.add('hidden');
        var count = $('#memGraphCount');
        var graphStats = window.DshMemoryGraph.stats ? window.DshMemoryGraph.stats() : {};
        var shownMemories = graphStats.memories || 0;
        var totalMemories = graphStats.totalMemories || shownMemories;
        var memoryCountLabel = graphStats.truncated && totalMemories > shownMemories
          ? '展示 ' + shownMemories + '/' + totalMemories + ' 记忆'
          : shownMemories + ' 记忆';
        if (count) count.textContent = memoryCountLabel + ' · ' + (graphStats.experiences || 0) + ' 经历 · ' +
          (graphStats.sharedLinks || 0) + ' 真实关联';
        var statMem = $('#memGraphStageMem');
        var statLink = $('#memGraphStageLink');
        var statMoment = $('#memGraphStageMoment');
        if (statMem) statMem.textContent = String(graphStats.memories || 0);
        if (statLink) statLink.textContent = String(graphStats.sharedLinks || 0);
        if (statMoment) statMoment.textContent = String(graphStats.experiences || 0);
        renderMemoryGraphRecords();
        var detailText = $('#memGraphDetailText');
        var detailMeta = $('#memGraphDetailMeta');
        if (detailText) {
          if (graphStats.truncated && totalMemories > shownMemories) {
            detailText.textContent = '识海当前展示优先度较高的 ' + shownMemories + ' / ' + totalMemories + ' 条记忆。未显示的记忆仍保存在本地记忆库中，不会被删除。';
          } else if (graphStats.memories > 0 && graphStats.experiences === 0) {
        detailText.textContent = '她已经记下 ' + graphStats.memories + ' 件关于你的事。每种类别有固定颜色，关系线只在本地记录确实匹配时出现。';
          } else if (graphStats.experiences > 0) {
            detailText.textContent = '她记下了 ' + graphStats.memories + ' 件关于你的事，也留下 ' + graphStats.experiences + ' 段实际共同经历。彩色节点区分记忆类别，真实关联会在它们之间亮起。';
          } else {
            detailText.textContent = '这里还没有可展示的记忆。等她在聊天中记住你的偏好，或记录下实际共同经历，识海才会开始生长。';
          }
        }
        if (detailMeta) detailMeta.textContent = graphStats.truncated && totalMemories > shownMemories
          ? '识海单次最多显示 8,000 条记忆；其余可在记忆列表中查看。连线仍只依据本地记录里的真实标签或关键词。'
          : (graphStats.sharedLinks || 0) > 0
            ? '连线只来自本地记录中的共同标签或原文关键词；没有真实匹配的记忆不会被强行连在一起。'
            : '目前还没有符合条件的共同标签或关键词；记忆节点会先各自点亮，出现真实关联后才连接。';
        if (S.memGraphPendingAt && Date.now() - S.memGraphPendingAt < 60000) {
          window.DshMemoryGraph.highlight(S.memGraphPendingIds || []);
        }
      } else {
        status.textContent = '记忆网络暂时无法显示，返回后仍可使用本地记忆列表。';
        markMemoryGraphOpeningReady('图形连接遇到波动，仍可稍后重新连接…');
      }
    }).catch(function (e) {
      if (requestId !== _memGraphLoadId || S.page !== 'memGraph' || !document.documentElement.contains(status)) return;
      clearTimeout(_memGraphWatchdog);
      status.textContent = (e && e.message) || '读取记忆失败，请返回后重试。';
      status.classList.remove('hidden');
      markMemoryGraphOpeningReady('记忆暂时无法读取，连接恢复后可重新打开…');
    });
  }

  function showMemoryGraphDetail(node) {
    if (!node) return;
    var detail = $('#memGraphDetail');
    if (detail) detail.classList.add('has-selection');
    var title = $('#memGraphDetailTitle');
    var text = $('#memGraphDetailText');
    var meta = $('#memGraphDetailMeta');
    if (title) title.textContent = node.kind === 'experience'
      ? '共同经历 · ' + (node.label || '一次观察') : (node.label || '一条记忆');
    if (text) text.textContent = node.text || '';
    if (meta) {
      if (node.kind === 'experience') {
        meta.textContent = '经过实际观察的共同经历 · ' + (node.updatedAt ? fmt(node.updatedAt) : '本地记录') + ' · 未上传';
        return;
      }
      var tags = Array.isArray(node.tags) ? node.tags.join('、') : '';
      var originNames = {
        user_explicit: '你明确告诉她', user_implicit: '从对话整理', manual: '手动记录',
        legacy_manual: '旧记录', legacy_auto: '从对话整理', legacy_merge: '合并整理',
        assistant_inference: 'AI 推测', diary_observation: '日记观察', import: '导入记录'
      };
      var origin = originNames[node.origin] || '本地记忆';
      var confidence = Number(node.confidence);
      meta.textContent = (node.typeLabel || '记忆') + ' · ' + origin +
        (isFinite(confidence) ? ' · 记录置信度 ' + Math.round(confidence * 100) + '%' : '') +
        (tags ? ' · 标签：' + tags : '') +
        (node.lastRecalledAt ? ' · 最近想起 ' + fmt(node.lastRecalledAt) : '');
    }
  }

  function renderMemoryGraphRecords() {
    var host = $('#memGraphRecordList');
    if (!host) return;
    var records = window.DshMemoryGraph && window.DshMemoryGraph.records ? window.DshMemoryGraph.records() : [];
    var selected = _memGraphRecordFilter || 'all';
    if (selected === 'memory') records = records.filter(function (r) { return r.kind !== 'experience'; });
    else if (selected === 'experience') records = records.filter(function (r) { return r.kind === 'experience'; });
    else if (selected === 'important') records = records.filter(function (r) {
      var importance = Number(r.importance || r.importanceScore || 0);
      return r.pinned === true || r.important === true || importance >= 0.72;
    });
    records.sort(function (a, b) { return Number(b.createdAt || b.updatedAt || 0) - Number(a.createdAt || a.updatedAt || 0); });
    host.textContent = '';
    if (!records.length) {
      var empty = document.createElement('span');
      empty.className = 'mem-graph-record-empty';
      empty.textContent = selected === 'important' ? '还没有标为重要的记忆' : '这里暂时还没有记录';
      host.appendChild(empty);
      return;
    }
    records.slice(0, 30).forEach(function (record) {
      var row = document.createElement('button');
      row.type = 'button';
      row.className = 'mem-graph-record' + (String(record.id) === _memGraphSelectedRecord ? ' on' : '');
      row.setAttribute('data-act', 'memGraphSelect');
      row.setAttribute('data-id', String(record.id || ''));
      var title = document.createElement('b');
      title.textContent = String(record.label || record.summary || record.text || record.content || '一段记忆').slice(0, 96);
      var meta = document.createElement('span');
      var kind = record.kind === 'experience' ? '共同经历' : (record.typeLabel || memTypeName(record.type));
      var date = record.createdAt || record.updatedAt;
      meta.textContent = kind + (date ? ' · ' + fmt(date) : ' · 本地记录');
      row.appendChild(title);
      row.appendChild(meta);
      host.appendChild(row);
    });
  }

  function memTypeName(k) {
    var map = { fact: '事实', preference: '偏好', person: '人物', project: '项目',
      event: '事件', plan: '计划', concern: '惦记', commitment: '承诺', observation: '印象' };
    return map[k] || '记忆';
  }

  function memStatusName(k) {
    var map = { active: '有效', uncertain: '不确定', dormant: '搁置', superseded: '已被取代', archived: '已归档', done: '已完成' };
    return map[k] || '有效';
  }

  function memOriginName(origin, source) {
    var map = { manual: '手动', legacy_manual: '旧手动', user_explicit: '明确记住',
      user_implicit: 'AI 提炼', assistant_inference: '推测', legacy_auto: '旧提炼',
      legacy_merge: '合并', diary_observation: '日记印象', import: '导入' };
    if (origin && map[origin]) return map[origin];
    if (source === 'auto') return 'AI 提炼';
    if (source === 'merge') return '合并';
    if (source === 'import') return '导入';
    if (source === 'explicit') return '明确记住';
    return '手动';
  }

  function memCatName(k) {
    // 同时兼容后端写入的分类键（person/project/preference/event）和旧键
    var map = { work: '工作', life: '生活', prefer: '偏好', people: '人际', health: '健康', other: '其他',
      person: '人物', project: '项目', preference: '偏好', event: '事件' };
    return map[k] || k || '未分类';
  }

  /** 统一刷新记忆页：统计、体检、当前筛选的条目。 */
  function reloadMemList() {
    return Promise.all([
      call('memSummary'),
      call('memList', {
        type: S.memType || 'all',
        status: S.memShowArchived ? 'archived' : '',
        archived: !!S.memShowArchived
      })
    ]).then(function (a) {
      var sum = a[0] || {};
      S.memSummary = sum.summary || {};
      S.memReviewDue = !!sum.due;
      S.memReview = sum.items || [];
      S.memItems = (a[1] && a[1].memories) || [];
      return S.memItems;
    });
  }

  /* ---------------- 我们的关系：亲密度 / 性格演化 / 日记 / 共同经历 ---------------- */
  function renderRelation() {
    var it = S.intimacy || {};
    var tr = S.traits || {};
    var dy = S.diary || {};
    var tl = S.timeline || {};
    var ca = S.caring || {};
    var score = num(it.score, 0);
    var last = !!it.lastStage;
    var family = num(it.stageIndex, 0) >= 5;
    var sealed = !!it.familySealed;
    var html = pageHead('我们的关系', '亲密度决定她有多黏你、一天会主动找你几次', true);

    html += '<div class="card"><div class="card-title"><h3>亲密度</h3><span>' + esc(it.stage || '初识') + '</span></div>' +
      '<div class="rel-big"><b>' + score + '</b><em>/ ∞</em></div>' + moodLine() +
      ((S.contract || {}).isSet ? '<div class="faint" style="margin-top:8px">约定：<b>' + esc((S.contract || {}).relation || '朋友') + '</b> · 实际：<b>' + esc(it.stage || '初识') + '</b></div>' : '') +
      '<div class="faint" style="margin-top:8px">' +
      (last ? '已经是家人了，没有上限。' : '距离「' + esc(nextStageName(it, num(it.nextAt, 0))) + '」还差 ' + Math.max(0, num(it.nextAt, 0) - score) + ' 点') + '</div>' +
      '<div class="stats" style="margin:14px 0 0">' +
      (family && !sealed
        ? '<button class="stat stat-btn" data-act="familyCap"><b>' + num(it.dayCap, 60) + '</b><span>今天最多说</span></button>'
        : stat(num(it.dayCap, 8), '今天最多说')) +
      stat('+' + num(it.dayGain, 0), '今天涨了') + stat(num(it.days, 1), '认识天数') + '</div>' +
      (family && !sealed ? '<div class="faint" style="margin-top:10px">点「今天最多说」可以自定义她一天最多主动几次（1–1440）。</div>' : '') +
      (it.lastWhy ? '<div class="notice mint" style="margin-top:12px">最近一次变化：' + esc(it.lastWhy) + '</div>' : '') +
      '<div class="faint" style="margin-top:8px">每天能涨的上限会随阶段收紧：' + num(it.dayCapGain, 9) + ' 分/天。所以越亲近越难再靠近，也更珍贵。</div>' +
      '<div class="faint" style="margin-top:6px">几天没来不会扣分，只是亲密度涨得慢一点。她不会怪你。</div>' +
      '</div>';

    // 六个阶段
    var stages = it.stages || [];
    if (stages.length) {
      html += '<div class="card"><div class="card-title"><h3>六个阶段</h3><span>现在：' + esc(it.stage || '') + '</span></div><div class="list">';
      stages.forEach(function (s) {
        var capText = s.family
          ? (sealed ? '固定 60 次/天' : '主动次数可自定义（当前 ' + num(it.dayCap, 60) + ' 次/天）')
          : ('每天最多主动 ' + num(s.dayCap, 0) + ' 次');
        html += '<div class="list-item' + (s.reached ? ' rel-on' : '') + '"><div class="li-main"><b>' + esc(s.name || '') +
          '</b><span>' + num(s.at, 0) + ' 分起 · ' + capText + '</span></div>' +
          (s.reached ? '<span class="tag mint">已到达</span>' : '') + '</div>';
      });
      html += '</div></div>';
    }

    // 性格演化：以行为描述为主，数值只作为可选细节
    var dims = tr.dims || [];
    var desc = tr.descriptors || [];
    var trend = tr.trend || [];
    html += '<div class="card"><div class="card-title"><h3>她因你变成的样子</h3><span>' + (tr.evolved ? '已稳定' : '观察中 ' + num(tr.days, 0) + ' 天') + '</span></div>';
    if (desc.length) {
      html += '<div class="notice violet">' + desc.map(function (s) { return esc(s); }).join('<br>') + '</div>';
    } else {
      html += '<div class="empty">还在慢慢熟悉你。相处几天后，这里会出现她的表达习惯，而不是一堆数字。</div>';
    }
    if (trend.length) {
      html += '<div class="faint" style="margin-top:8px">最近 7 天：' + trend.map(function (t) {
        return esc(t.label) + ' ' + (num(t.delta, 0) > 0 ? '+' : '') + num(t.delta, 0);
      }).join(' · ') + '</div>';
    }
    if (tr.lastChangeWhy) html += '<div class="faint" style="margin-top:6px">最近一次变化：' + esc(tr.lastChangeWhy) + '</div>';
    if (tr.note) html += '<div class="notice violet" style="margin-top:10px">' + esc(tr.note) + '</div>';
    var topics = tr.topics || [];
    if (topics.length) {
      html += '<div class="faint" style="margin-top:10px">你们的专属话题：' + topics.map(function (t) { return esc(t); }).join(' · ') + '</div>';
    }
    if (tr.address) html += '<div class="faint" style="margin-top:6px">她叫你：' + esc(tr.address) + '</div>';
    if (tr.evolved) {
      html += '<details style="margin-top:10px"><summary class="faint">查看数值细节</summary><div class="dim-list">';
      dims.forEach(function (d) {
        html += '<div class="dim-row"><span>' + esc(d.label) + '</span><i><b style="width:' + num(d.value, 50) + '%"></b></i><em>' + num(d.value, 50) + '</em></div>';
      });
      html += '</div></details>';
    }
    var sp = num(tr.speed, 1);
    var spNames = ['慢', '标准', '快'];
    html += '<div class="row trait-speed-row" style="margin-top:8px"><div class="trait-speed-copy"><div class="rt">变化速度</div><div class="rs">只影响缓慢演化的幅度，不会突破上限</div></div><div class="rchips">';
    [0, 1, 2].forEach(function (n) {
      html += '<button class="rchip ' + (sp === n ? 'on' : '') + '" data-act="setTraitSpeed" data-speed="' + n + '">' + spNames[n] + '</button>';
    });
    html += '</div></div>' +
      '<button class="btn ghost block sm" data-act="traitsReset" style="margin-top:10px">重置到人格种子</button>' +
      '<div class="faint" style="margin-top:6px">会回到首次填写时的性格、风格和开场白；记忆、亲密度、聊天记录保留，旧数据会归档。</div>' +
      '<div class="row"><div><div class="rt">性格随你演化</div><div class="rs">关掉后她的基线不再变化，但当下情绪仍会影响这一句</div></div>' + switchHtml('traitEvolve', S.cfg.traitEvolve !== false, 'toggleCfg') + '</div></div>';

    // 共同经历：只展示重要时刻/里程碑，回顾快照不混进来
    var tls = (tl.items || []).filter(function (e) { return e.type !== 'recap'; });
    var mf = S.momentFilter || 'all';
    var moments = tls.filter(function (e) {
      if (mf === 'mark') return String(e.source || '').indexOf('user_mark') === 0;
      if (mf === 'milestone') return e.type === 'milestone';
      if (mf === 'done') return e.source === 'plan_confirmed';
      return true;
    }).slice(0, 12);
    html += '<div class="card"><div class="card-title"><h3>共同经历</h3><span>' + num(tl.total, tls.length) + ' 件</span></div>' +
      '<div class="rchips" style="margin:0 0 10px">' +
      [['all', '全部'], ['mark', '你标记'], ['milestone', '里程碑'], ['done', '完成']].map(function (p) {
        return '<button class="rchip ' + (mf === p[0] ? 'on' : '') + '" data-act="momentFilter" data-filter="' + p[0] + '">' + p[1] + '</button>';
      }).join('') + '</div>';
    if (!moments.length) {
      html += '<div class="empty">还没有标记过。长按聊天里的一句话，或在这里记一件重要的事。</div>';
    } else {
      html += '<div class="list">';
      moments.forEach(function (e) {
        html += '<div class="list-item moment-item"><div class="li-main"><b>' + esc(e.text || '') +
          '</b><span>' + esc(dayOf(e)) + ' · ' + esc(momentSourceName(e)) +
          (e.note ? ' · ' + esc(e.note) : '') + '</span></div>' +
          '<button class="li-act" data-act="momentMenu" data-key="' + esc(e.key || '') + '">⋯</button></div>';
      });
      html += '</div>';
    }
    html += '<button class="btn ghost block sm" data-act="momentAdd" style="margin-top:10px">记一件重要的事</button></div>';

    // 相处回顾：本地模板为主，AI 只在用户点击时调用
    var rp = S.recap || {};
    var rpText = S.recapAi || rp.text || '';
    html += '<div class="card"><div class="card-title"><h3>相处回顾</h3><span>' + (rp.hasData ? '只看片段' : '记录还不多') + '</span></div>' +
      '<div class="rchips" style="margin:0 0 10px">' +
      [['7d', '最近 7 天'], ['30d', '最近 30 天'], ['all', '全部']].map(function (p) {
        return '<button class="rchip ' + (S.recapPeriod === p[0] ? 'on' : '') + '" data-act="recapPeriod" data-period="' + p[0] + '">' + p[1] + '</button>';
      }).join('') + '</div>';
    if (rpText) {
      html += '<div class="notice mint recap-text">' + (S.recapAi ? '<b>她想了想，说：</b><br>' : '') + esc(rpText) + '</div>';
    } else {
      html += '<div class="empty">这段时间还没有足够的片段，等她慢慢记。</div>';
    }
    var rf = rp.facts || [];
    if (rf.length) {
      html += '<details class="recap-facts"><summary class="faint">依据（只来自聊天、日记和记忆）</summary><div class="faint">' +
        rf.map(function (s) { return '· ' + esc(s); }).join('<br>') + '</div></details>';
    }
    html += '<div class="btn-row" style="margin-top:10px">' +
      '<button class="btn ghost sm" data-act="recapRefresh">刷新</button>' +
      (S.cfg && S.cfg.apiKey ? '<button class="btn primary sm" data-act="recapSay">让她说说</button>' : '') +
      '</div>' +
      '<div class="row" style="margin-top:8px"><div><div class="rt">自动生成回顾</div><div class="rs">只在关系页静默保存，不弹窗、不朗读</div></div>' +
      switchHtml('recapAuto', S.cfg.recapAuto !== false, 'toggleCfg') + '</div></div>';

    // 最近在忙：复用现有项目/计划记忆，不新建项目实体
    var prs = S.projects || [];
    var plans = (ca.items || []).filter(function (k) { return k.kind === 'plan'; });
    if (prs.length || plans.length) {
      html += '<div class="card"><div class="card-title"><h3>最近在忙</h3><span>来自记忆</span></div><div class="list">';
      prs.forEach(function (p) {
        html += '<div class="list-item"><div class="li-main"><b>' + esc(p.text || '') +
          '</b><span>她记得 · ' + esc(p.day || '') + (num(p.confidence, 1) < 0.7 ? ' · 可能不准' : '') + '</span></div></div>';
      });
      plans.forEach(function (p) {
        html += '<div class="list-item"><div class="li-main"><b>' + esc(p.text || '') +
          '</b><span>你说过 · ' + fmt(p.ts) + '</span></div></div>';
      });
      html += '</div><button class="btn ghost block sm" data-act="openPage" data-page="mem" style="margin-top:10px">去记忆里管理</button></div>';
    }

    // 自动判定完成：先让用户确认，确认后才进入共同经历
    var pd = ca.pendingDone || [];
    if (pd.length) {
      html += '<div class="card"><div class="card-title"><h3>可能完成的事</h3><span>' + pd.length + ' 件待确认</span></div><div class="list">';
      pd.forEach(function (p) {
        html += '<div class="list-item moment-item"><div class="li-main"><b>' + esc(p.text || '') +
          '</b><span>她猜你可能完成了</span></div><div class="moment-acts">' +
          '<button class="li-act" data-act="doneConfirm" data-id="' + esc(p.id || '') + '" data-done="1">完成了</button>' +
          '<button class="li-act" data-act="doneConfirm" data-id="' + esc(p.id || '') + '" data-done="0">还没有</button></div></div>';
      });
      html += '</div></div>';
    }

    // 她的日记
    var diaries = (dy.items || []).slice();
    html += '<div class="card"><div class="card-title"><h3>她的日记</h3><span>' + diaries.length + ' 篇</span></div>';
    html += '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">允许她自发写日记</div><div class="rs">有想记下的真实经历时再写；她可以不写，篇幅由她决定</div></div>' + switchHtml('diaryEnabled', S.cfg.diaryEnabled !== false, 'toggleCfg') + '</div>';
    if (!diaries.length) {
      html += '<div class="empty">她还没有想写下来的日记。</div>';
    } else {
      diaries.forEach(function (d, i) {
        html += '<div class="diary"><b>' + esc(d.date || '') + (d.stage ? ' · ' + esc(d.stage) : '') + '</b><p>' + esc(d.text || '') + '</p>' +
          ((i === 0 && S.diaryPeek) ? '<p class="diary-peek">（她好像发现你在看…）</p>' : '') + '</div>';
      });
    }
    html += '<button class="btn ghost block sm" data-act="diaryWrite" data-writing="0">请她现在写一篇</button></div>';

    // 她惦记的事
    var cg = ca.items || [];
    html += '<div class="card"><div class="card-title"><h3>她惦记的事</h3><span>' + num(ca.total, 0) + ' 件未结</span></div>';
    if (!cg.length) html += '<div class="empty">暂时没有。你跟她说「明天要……」时，她会替你记着。</div>';
    else {
      html += '<div class="list">';
      cg.forEach(function (c) {
        html += '<div class="list-item"><div class="li-main"><b style="white-space:normal">' + esc(c.text || '') + '</b><span>' +
          esc(c.kind === 'promise' ? '她答应过你' : '你提过的安排') + ' · ' + fmt(c.ts) + '</span></div>' +
          '<button class="li-act danger" data-act="caringDel" data-id="' + esc(c.id) + '">忘记</button></div>';
      });
      html += '</div>';
    }
    html += '</div>';
    return html;
  }
  function nextStageName(it, next) {
    var stages = it.stages || [];
    for (var k = 0; k < stages.length; k++) if (num(stages[k].at, 0) === next) return stages[k].name;
    return '下一阶段';
  }

  /* ---------------- 共同经历 / 相处回顾 ---------------- */
  function todayIso() {
    var d = new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }
  function dayOf(e) {
    if (e && e.day) return String(e.day).slice(5);
    return fmt(e && e.ts);
  }
  function momentSourceName(e) {
    var src = String((e && e.source) || '');
    if (src === 'first_chat') return '第一次聊天';
    if (src === 'user_mark_chat') return '聊天标记';
    if (src === 'user_mark_manual') return '你标记';
    if (src === 'plan_confirmed') return '确认完成';
    if (e && e.type === 'milestone') return '里程碑';
    return '记录';
  }
  function findMoment(key) {
    var items = (S.timeline || {}).items || [];
    for (var k = 0; k < items.length; k++) if (items[k].key === key) return items[k];
    return null;
  }
  function loadRecap(period) {
    S.recapPeriod = period || S.recapPeriod || '7d';
    S.recapAi = '';
    return call('recapGet', { period: S.recapPeriod }).then(function (r) {
      S.recap = r.recap || {};
    });
  }
  function openMomentForm(key) {
    var e = key ? findMoment(key) : null;
    var edit = !!e;
    openModal('<div class="modal-head"><h3>' + (edit ? '编辑重要时刻' : '记一件重要的事') + '</h3>' +
      '<button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<label class="field"><span>日期</span><input id="momentDay" type="date" value="' + esc(e && e.day ? e.day : todayIso()) + '"></label>' +
      '<label class="field"><span>这件事</span><textarea id="momentText" maxlength="120" placeholder="例如：今天终于把方案交了">' + esc(e ? e.text || '' : '') + '</textarea></label>' +
      '<label class="field"><span>备注（可选）</span><input id="momentNote" maxlength="160" value="' + esc(e ? e.note || '' : '') + '"></label>' +
      '<button class="btn primary block" data-act="momentSave" data-key="' + esc(key || '') + '" style="margin-top:8px">保存</button>' +
      '<div class="faint" style="margin-top:8px">只有你手动记的事才能编辑；系统里程碑可以删除，但不能改。</div>');
  }
  function openMomentMenu(key) {
    var e = findMoment(key);
    if (!e) return;
    openModal('<div class="modal-head"><h3>这条共同经历</h3>' +
      '<button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<div class="notice mint">' + esc(e.text || '') + '</div>' +
      '<div class="faint" style="margin-top:8px">' + esc(dayOf(e)) + ' · ' + esc(momentSourceName(e)) + '</div>' +
      '<div class="btn-row" style="margin-top:14px">' +
      (e.editable ? '<button class="btn ghost" data-act="momentEdit" data-key="' + esc(key) + '">编辑</button>' : '') +
      '<button class="btn danger" data-act="momentDel" data-key="' + esc(key) + '">删除</button>' +
      '</div>');
  }
  function markChatMoment(idx) {
    var m = (S.chat || [])[idx];
    if (!m || !m.content) return;
    closeMsgMenu();
    call('momentAdd', { text: m.content, refTs: m.ts || 0, refRole: m.role || 'user' }).then(function (r) {
      if (r.timeline) S.timeline = r.timeline;
      render();
      toast('已记进共同经历');
    }).catch(function (e) { toast(e.message); });
  }

  /** 家人阶段的自定义主动次数：1–1440，拉满并保存会触发彩蛋 */
  function openFamilyCap() {
    var it = S.intimacy || {};
    if (num(it.stageIndex, 0) < 5) { toast('到「家人」阶段才能调整'); return; }
    if (it.familySealed) { toast('已经固定为 60 次/天了'); return; }
    var cur = Math.max(1, Math.min(1440, num(it.dayCap, 60)));
    S.familyMaxSaid = cur >= 1440;
    openModal('<div class="modal-head"><h3>每天最多主动说话</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<div class="faint" style="line-height:1.7">家人阶段可以自己决定她一天最多主动开口几次。上限 1440 次，也就是一分钟一条。</div>' +
      '<div class="rstep" style="margin-top:18px"><input class="slider" id="familyCapRange" type="range" min="1" max="1440" step="1" value="' + cur + '">' +
      '<b class="family-cap-val" id="familyCapVal">' + cur + '</b></div>' +
      '<div class="faint" id="familyCapHint" style="margin-top:10px">' + (cur >= 1440 ? '再保存一次，会有特别的事发生。' : '拖到 1440 会有特别的事发生…') + '</div>' +
      '<button class="btn primary block" data-act="familyCapSave" style="margin-top:16px">保存</button>');
  }

  /* ---------------- 新增：每周报告 ---------------- */
  function renderReport() {
    var r = S.report || {};
    var html = pageHead('陪伴周报', '只记录陪伴，不做评分', true);
    html += '<div class="card"><div class="card-title"><h3>这一周</h3><span>' + (r.savedAt ? fmt(r.savedAt) : '未生成') + '</span></div>' +
      '<div class="notice mint" style="line-height:1.9">' + esc(r.text || '还没有本周报告。点下面的按钮生成。') + '</div>' +
      '<button class="btn primary block" data-act="reportGen">生成本周报告</button></div>';
    html += '<div class="stats">' + stat(num(r.focusMin, 0), '专注分钟') + stat(num(r.chats, 0), '聊天句数') + stat(num(r.ads, 0), '跳过广告') + '</div>';
    html += '<div class="card"><div class="card-title"><h3>小动作</h3><span>累积次数</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">拉伸</div><div class="rs">久坐提醒</div></div><span class="tag">' + num(r.stretch, 0) + '</span></div>' +
      '<div class="row"><div><div class="rt">喝水</div><div class="rs">按时补水</div></div><span class="tag">' + num(r.water, 0) + '</span></div>' +
      '<div class="row"><div><div class="rt">护眼</div><div class="rs">远眺放松</div></div><span class="tag">' + num(r.eye, 0) + '</span></div></div>';
    var music = r.music || [];
    var topics = r.topics || [];
    html += '<div class="card"><div class="card-title"><h3>音乐与话题</h3><span>本周</span></div>';
    if (!music.length && !topics.length) html += '<div class="empty">还没有足够数据。</div>';
    music.forEach(function (m) {
      html += '<div class="list-item"><div class="li-main"><b>' + esc(m.title) + '</b><span>播放 ' + num(m.count, 0) + ' 次</span></div></div>';
    });
    topics.forEach(function (t) {
      html += '<div class="list-item"><div class="li-main"><b>' + esc(t.name) + '</b><span>出现 ' + num(t.count, 0) + ' 次</span></div></div>';
    });
    html += '</div>';
    html += '<div class="card"><div class="card-title"><h3>心情</h3><span>' + esc(r.moodTop || '未标记') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">记录心情</div><div class="rs">轻量记录，不强制</div></div>' +
      '<button class="btn sm ghost" data-act="moodPick">记录</button></div></div>';
    return html;
  }

  /* ---------------- 新增：通知摘要 ---------------- */
  function renderNotif() {
    var n = S.notif || {};
    var groups = S.notifGroups || n.groups || [];
    var items = (n.items || []).filter(function (o) { return !o.read; });
    var html = pageHead('通知摘要', '把刷屏的通知收成一束，再决定要不要处理', true);
    html += '<div class="stats">' + stat(num(n.unread, 0), '未读') + stat(groups.length, '应用') + stat(num((S.cfg.notifMuted || []).length, 0), '已静音') + '</div>';
    var ai = S.notifAi || {};
    html += '<div class="card"><div class="card-title"><h3>摘要</h3><span>' + (S.cfg.notifSummary ? '已开启' : '关闭') + '</span></div>' +
      '<div class="notice mint" style="margin-bottom:10px">实时筛选在通知到达时立即于本机判断类型：营销推送和已静音应用不弹桌宠提醒；私聊、@你的群消息，以及识别到的天气、快递、外卖、出行和账单动态再按各自开关处理。</div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">收集通知</div><div class="rs">标题与正文只存在本机</div></div>' + switchHtml('notifSummary', S.cfg.notifSummary, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">摘要 AI 补充识别</div><div class="rs">为通知摘要补充类型和要点；实时提醒筛选不依赖网络，不产生这项 API 调用</div></div>' + switchHtml('notifAi', S.cfg.notifAi !== false, 'toggleCfg') + '</div>' +
      '<button class="btn ghost block sm" data-act="notifRefresh">刷新</button>' +
      '<button class="btn ghost block sm" data-act="notifClear">全部清空</button></div>';
    html += '<div class="card"><div class="card-title"><h3>按应用</h3><span>' + groups.length + ' 组</span></div>';
    if (!groups.length) html += '<div class="empty">暂无通知。授权通知读取后会自动收集。</div>';
    groups.forEach(function (g) {
      var muted = (S.cfg.notifMuted || []).indexOf(g.pkg) >= 0;
      html += '<div class="list-item"><div class="li-ico"><svg viewBox="0 0 24 24"><path d="M6 9a6 6 0 0 1 12 0v5l2 3H4l2-3z"/></svg></div>' +
        '<div class="li-main"><b>' + esc(g.app || g.pkg) + '</b><span>' + esc(g.latest || '') + ' · ' + fmt(g.ts) + '</span></div>' +
        '<span class="tag">' + num(g.count, 0) + '</span>' +
        '<button class="li-act" data-act="notifAppRead" data-pkg="' + esc(g.pkg) + '">已读</button>' +
        '<button class="li-act" data-act="notifMute" data-pkg="' + esc(g.pkg) + '" data-on="' + (muted ? '0' : '1') + '">' + (muted ? '取消静音' : '静音') + '</button></div>';
    });
    html += '</div>';
    var recent = items.slice(-20).reverse();
    var aiN = recent.filter(function (o) { return ai[o.key]; }).length;
    html += '<div class="card"><div class="card-title"><h3>最近条目</h3><span>' + items.length + ' 条' + (aiN ? ' · ' + aiN + ' 已识别' : '') + '</span></div>';
    if (!items.length) html += '<div class="empty">暂无条目。</div>';
    recent.forEach(function (o) {
      var tag = ai[o.key] || {};
      var kind = o.kind === '广告营销' ? o.kind : (tag.t || '');
      var head = (kind ? '[' + kind + '] ' : '') + (o.title || '');
      var line = tag.s || (o.text || '').slice(0, 60);
      html += '<div class="list-item"><div class="li-main"><b>' + esc(head) + '</b><span>' + esc(line) + ' · ' + fmt(o.ts) + '</span></div>' +
        '<button class="li-act" data-act="notifSnooze" data-key="' + esc(o.key) + '">+10分</button>' +
        '<button class="li-act" data-act="notifToTask" data-key="' + esc(o.key) + '">转待办</button></div>';
    });
    html += '</div>';
    return html;
  }

  /* ---------------- 新增：位置提醒 ---------------- */
  function renderGeo() {
    var g = S.geo || {};
    var html = pageHead('位置提醒', '到家、到公司时，她会主动迎你', true);
    var places = Array.isArray(g.places) ? g.places : [];
    html += '<div class="card"><div class="card-title"><h3>定位</h3><span>' + (g.granted ? '已授权' : '未授权') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">启用位置提醒</div><div class="rs">GPS + 网络定位，仅本机使用</div></div>' + switchHtml('geoEnabled', S.cfg.geoEnabled, 'toggleCfg') + '</div>' +
      permRow('前台定位', g.granted, 'location') +
      permRow('后台定位', g.background, 'backgroundLocation') +
      '<div class="row"><div><div class="rt">到家提醒</div></div>' + switchHtml('geoRemindHome', S.cfg.geoRemindHome, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">到公司提醒</div></div>' + switchHtml('geoRemindWork', S.cfg.geoRemindWork, 'toggleCfg') + '</div></div>';
    html += '<div class="card"><div class="card-title"><h3>地点</h3><span>半径约 250 米</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">家</div><div class="rs">' + esc(g.home ? (g.home.lat + ', ' + g.home.lng) : '未设置') + '</div></div><button class="btn sm" data-act="geoPoint" data-kind="home">设为家</button></div>' +
      '<div class="row"><div><div class="rt">公司</div><div class="rs">' + esc(g.work ? (g.work.lat + ', ' + g.work.lng) : '未设置') + '</div></div><button class="btn sm" data-act="geoPoint" data-kind="work">设为公司</button></div>' +
      '<div class="section-title" style="margin-top:14px">自定义地点</div>' +
      '<div class="row" style="gap:8px"><input id="geoPlaceName" maxlength="30" placeholder="例如：健身房、奶茶店" style="flex:1;min-width:0;padding:10px;border-radius:10px;border:1px solid var(--line);background:rgba(0,0,0,.24);color:var(--text)"><button class="btn sm" data-act="geoAdd">添加并设为当前位置</button></div>';
    places.forEach(function (p) {
      if (!p || !p.id) return;
      html += '<div class="row"><div><div class="rt">' + esc(p.name || '未命名地点') + '</div><div class="rs">' + esc((p.lat || 0) + ', ' + (p.lng || 0)) + '</div></div><button class="btn sm danger" data-act="geoDelete" data-id="' + esc(p.id) + '">删除</button></div>';
    });
    html +=
      '<div class="row"><div><div class="rt">最近定位</div><div class="rs">' + (g.last ? esc((g.last.lat || 0).toFixed(5) + ', ' + (g.last.lng || 0).toFixed(5)) + ' · 精度 ' + num(g.last.acc, 0) + ' 米 · ' + fmt(g.last.ts) : '暂无') + '</div></div><span class="tag">' + esc(g.place || '未知') + '</span></div>' +
      '<div class="faint" style="margin-top:10px">设定地点时会读取当前位置。到达已保存地点后，她会用地点名称表达关心并记住这次到访；地点信息只保存在本机。</div></div>';
    return html;
  }

  /* ---------------- 新增：健康动作 ---------------- */
  function renderWellness() {
    var w = S.well || {};
    var st = w.stats || {};
    var html = pageHead('健康动作', '久坐、喝水、护眼，用动作而不是弹窗提醒你', true);
    html += '<div class="stats">' + stat(num(st.stretch, 0), '拉伸') + stat(num(st.water, 0), '喝水') + stat(num(st.eye, 0), '护眼') + '</div>';
    html += '<div class="card"><div class="card-title"><h3>提醒间隔</h3><span>分钟</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">启用健康提醒</div><div class="rs">按间隔由她带着做动作</div></div>' + switchHtml('wellEnabled', S.cfg.wellEnabled, 'toggleCfg') + '</div>' +
      field('wellSitMin', '久坐提醒（分钟）', num(S.cfg.wellSitMin, 50)) +
      field('wellWaterMin', '喝水提醒（分钟）', num(S.cfg.wellWaterMin, 90)) +
      field('wellEyeMin', '护眼提醒（分钟）', num(S.cfg.wellEyeMin, 45)) +
      '<div class="row"><div><div class="rt">夜间不提醒</div><div class="rs">跟随夜间时段</div></div>' + switchHtml('wellQuietNight', S.cfg.wellQuietNight, 'toggleCfg') + '</div>' +
      '<button class="btn primary block sm" data-act="wellSave">保存间隔</button></div>';
    html += '<div class="card"><div class="card-title"><h3>拉伸三步</h3><span>约 21 秒</span></div>';
    (S.wellSteps || []).forEach(function (s, i) {
      html += '<div class="list-item"><div class="li-ico"><span class="tag">' + (i + 1) + '</span></div>' +
        '<div class="li-main"><b>' + esc(s.text) + '</b><span>' + esc(s.action) + ' · ' + num(s.sec, 7) + ' 秒</span></div>' +
        '<button class="li-act" data-act="wellPlay" data-action="' + esc(s.action) + '">播放</button></div>';
    });
    html += '</div>';
    html += '<div class="card"><div class="card-title"><h3>现在来一次</h3><span>手动</span></div><div class="btn-row">' +
      '<button class="btn" data-act="wellDo" data-type="stretch">拉伸</button>' +
      '<button class="btn" data-act="wellDo" data-type="water">喝水</button>' +
      '<button class="btn" data-act="wellDo" data-type="eye">护眼</button></div></div>';
    return html;
  }

  /* ---------------- 新增：音乐增强 ---------------- */
  function renderMusicx() {
    var m = S.musicx || {};
    var recOk = !!(S.perms && S.perms.record);
    (S.health || []).forEach(function (h) { if (h.id === 'record' && h.granted) recOk = true; });
    // 状态文案可能很长（比如「没取到歌词：请在音乐 App 里打开…」），只让短文案进右侧标签，
    // 否则 nowrap 的标签会把左边的标题挤成一行一个字
    var lyricSt = m.lyricStatus || (m.lyricSource === 'notify' ? '通知栏歌词' : (num(m.lines, 0) + ' 行歌词'));
    var lyricTag = lyricSt && lyricSt.length <= 8 ? '<span class="tag">' + esc(lyricSt) + '</span>' : '';
    // 以后台实时诊断为准：S.perms 可能还没刷新，诊断值是原生刚查到的
    var listenerOk = (m.diag && typeof m.diag.listener === 'boolean') ? !!m.diag.listener : !!(S.perms && S.perms.listener);
    var html = pageHead('音乐陪伴', '歌词、白噪音和手势切歌', true);
    html += '<div class="card"><div class="card-title"><h3>正在播放</h3><span>' + (m.playing ? '播放中' : '待机') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">' + esc(m.title || '未检测到音乐') + '</div><div class="rs">' + esc((m.artist || '') + (m.app && m.playing ? (m.artist ? ' · ' : '') + '来自 ' + m.app : '')) + '</div></div>' + lyricTag + '</div>' +
      '<div class="faint" style="margin:-4px 0 10px">先取播放器推到通知栏的那句（需在音乐应用里开启「通知栏歌词」），没有才按歌名去网易云 / LRCLIB 匹配。</div>' +
      (!listenerOk ? '<div class="notice violet">还没给「通知使用权」，读不到播放器，也拿不到歌词。<div style="margin-top:8px"><button class="btn sm" data-act="openPerm" data-kind="listener">去授权</button></div></div>' : '') +
      (m.lyric ? '<div class="notice mint">' + esc(m.lyric) + '</div>' : '') +
      (!m.lyric && m.playing ? '<div class="notice violet">还没取到歌词。多半是音乐 App 里没开「通知栏歌词」（部分机型叫「桌面歌词」），打开后下一句就会飘出来。</div>' : '') +
      '<div class="faint" style="margin:-4px 0 10px">诊断：来源 ' + esc(m.lyricSource || '无') +
      ' · 媒体会话 ' + ((m.diag && m.diag.hasSession) ? '有' : '无') +
      ' · 通知读取 ' + ((m.diag && m.diag.listener) ? '已授权' : '未授权') +
      (((m.diag && m.diag.notifText) ? ' · 通知正文「' + m.diag.notifText + '」' : ' · 通知里没有正文')) +
      (lyricSt && lyricSt.length > 8 ? ' · 状态 ' + esc(lyricSt) : '') + '</div>' +
      (m.songInfo ? '<div class="notice violet" style="white-space:pre-wrap">她对这首歌的了解：\n' + esc(m.songInfo) + '</div>' : '') +
      '<div class="btn-row"><button class="btn" data-act="musicPrev">上一首</button><button class="btn" data-act="musicNext">下一首</button><button class="btn ghost" data-act="musicTr">翻译歌词</button></div>' +
      '<div class="row"><div><div class="rt">双击宠物切歌</div><div class="rs">播放音乐时生效</div></div>' + switchHtml('musicGesture', S.cfg.musicGesture, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">首页音浪跟随频谱</div><div class="rs">' + (recOk ? '已授权，音浪按真实频谱跳动' : '需要录音权限才能读实时频谱，未授权时按节奏起伏') + '</div></div>' +
      (recOk ? '<span class="tag mint">已开启</span>' : '<button class="btn sm ghost" data-act="openPerm" data-kind="record">去授权</button>') + '</div></div>';
    html += '<div class="card"><div class="card-title"><h3>白噪音</h3><span>' + (m.noise ? esc(m.noiseKind || '播放中') : '关闭') + '</span></div>' +
      '<div class="btn-row"><button class="btn" data-act="noise" data-kind="off">关闭</button><button class="btn" data-act="noise" data-kind="white">白噪音</button><button class="btn" data-act="noise" data-kind="pink">粉噪音</button><button class="btn" data-act="noise" data-kind="brown">棕噪音</button></div>' +
      '<div class="faint" style="margin-top:10px">白噪音在本机合成，不消耗流量；会和音乐同时播放，请按需开启。</div></div>';
    return html;
  }

  /* ---------------- 新增：插件 ---------------- */
  function astrbotMarketEntries(market) {
    if (!market) return [];
    var rows = market.plugins || market.items || market.data || [];
    if (!Array.isArray(rows) && rows && typeof rows === 'object') rows = rows.plugins || rows.items || [];
    return Array.isArray(rows) ? rows : [];
  }

  function astrbotMarketRepository(plugin) {
    if (!plugin) return '';
    var meta = plugin.metadata || plugin.meta || {};
    var repo = String(plugin.repository || plugin.repo || plugin.github_url || meta.repository || meta.repo || '');
    return /^https:\/\/github\.com\/[A-Za-z0-9_-]+\/[A-Za-z0-9_.-]+(?:\.git)?(?:\/tree\/[A-Za-z0-9_-]+)?$/.test(repo) ? repo : '';
  }

  function astrbotMarketIdentity(plugin) {
    if (!plugin) return '';
    var explicit = String(plugin.market_plugin_id || '');
    if (/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(explicit)) return explicit;
    var meta = plugin.metadata || plugin.meta || {};
    var author = plugin.author || meta.author;
    var name = plugin.name || meta.name;
    if (!author || !name) return '';
    var id = String(author) + '/' + String(name);
    if (plugin.plugin_id && String(plugin.plugin_id) !== id) return '';
    return /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(id) ? id : '';
  }

  function astrbotMarketRegistry(plugin, market) {
    var source = plugin && plugin.source;
    if (source && typeof source === 'object') source = source.url || source.registry_url || '';
    var marketSource = market && market.source;
    if (marketSource && typeof marketSource === 'object') marketSource = marketSource.url || marketSource.registry_url || '';
    var pMeta = plugin && (plugin.metadata || plugin.meta) || {};
    var candidates = [plugin && plugin.registry_url, plugin && plugin.market_source_url, plugin && plugin.market_url, plugin && plugin.source_url, plugin && plugin.registry, source,
      pMeta.registry_url, pMeta.market_source_url, market && market.registry_url, market && market.market_source_url, market && market.market_url, market && market.source_url, marketSource];
    for (var i = 0; i < candidates.length; i++) {
      var value = String(candidates[i] || '');
      if (/^https:\/\/[^\s]+$/.test(value)) return value;
    }
    return '';
  }

  function renderPlugins() {
    var list = S.plugins || [];
    var on = 0;
    list.forEach(function (p) { if (p.enabled) on++; });
    var html = pageHead('插件', '代码插件 · 配置插件 · 快捷指令', true);
    var db = S.dshbox || {};
    var astrMarket = S.astrbotApiMarket || {};
    var astrMarketRows = astrbotMarketEntries(astrMarket);
    var dbWebPackages = db.webProfilePackages || [];
    var dbHeadlessPackages = db.headlessProfilePackages || [];
    function dshBoxPackageList(packages, emptyText) {
      if (!packages.length) return '<div class="faint" style="margin:8px 0">' + esc(emptyText) + '</div>';
      return '<div style="display:flex;flex-wrap:wrap;gap:6px;margin:8px 0">' +
        packages.slice(0, 24).map(function (p) { return '<span class="tag" title="' + esc(p.description || '') + '">' + esc(p.name || '') + '</span>'; }).join('') +
        (packages.length > 24 ? '<span class="tag">另有 ' + (packages.length - 24) + ' 个</span>' : '') + '</div>';
    }
    html += '<div class="card"><div class="card-title"><h3>DSHBox 插件引擎</h3><span class="tag ' + (db.ready ? 'mint' : '') + '">' +
      (db.ready ? '已连接' : (db.installed ? '未就绪' : '未安装')) + '</span></div>' +
      '<div class="faint" style="line-height:1.7">这里接入 DSHBox 独立插件系统的状态与委派分析。桌宠插件仍由本机审核、安装和运行；DSHBox 插件仍由 DSHBox 自己的 profile 管理，不会被误报成已经装入桌宠。</div>' +
      '<div class="notice ' + (db.ready ? 'violet' : '') + '" style="margin-top:10px">' + esc(db.message || '正在检查 DSHBox…') +
      (db.dshVersion ? '<br>DSH 引擎 ' + esc(db.dshVersion) : '') + '</div>' +
      '<div class="card-title" style="margin-top:12px"><h3>Headless 任务插件</h3><span>' + dbHeadlessPackages.length + ' 个</span></div>' +
      dshBoxPackageList(dbHeadlessPackages, '当前任务通过 headless profile 执行；这里显示该 profile 的插件。若要让任务调用某插件，需要先把它安装到 DSHBox 的 headless profile。') +
      '<div class="card-title" style="margin-top:12px"><h3>Web 页面插件</h3><span>' + dbWebPackages.length + ' 个</span></div>' +
      dshBoxPackageList(dbWebPackages, '没有从 DSHBox Web profile 读到已安装包；Web 插件不会自动出现在 headless 任务环境。') +
      '<div class="btn-row" style="margin-top:10px"><button class="btn ghost" data-act="pluginDshBoxRefresh">重新检测</button>' +
      '<button class="btn primary" data-act="pluginDshBoxAnalyzeOpen" ' + (db.ready ? '' : 'disabled') + '>交给 DSHBox 分析插件问题</button></div>' +
      '<div class="faint" style="margin-top:8px">也可在聊天中发送「用 DSHBox 分析插件：……」。桌宠与 DSHBox 共存，但插件 profile 和格式彼此独立；这一步只委派分析，不替你安装、启用或声称真机测试通过。</div></div>';
    var ac = S.astrbotConfig || {};
    html += '<div class="card"><div class="card-title"><h3>远端 AstrBot 智能体与插件市场</h3><span class="tag ' + (ac.configured ? 'mint' : '') + '">' + (ac.configured ? '已配置地址' : '待连接') + '</span></div>' +
      '<div class="faint" style="line-height:1.7">AstrBot 负责 Agent 与 Python 插件；桌宠负责本机权限、屏幕感知和操作。远端手机工具只在一轮聊天期间按需连接，操作前会在桌宠浮窗逐次征求确认，不执行远端代码。</div>' +
      '<div class="notice" style="margin-top:10px">远端地址：' + esc(ac.baseUrl || '尚未设置') + '<br>OpenAPI Key：' + (ac.configured ? '仅保存在本机应用私有设置中' : '尚未配置') + '</div>' +
      '<div class="btn-row" style="margin-top:10px"><button class="btn" data-act="astrbotConfigOpen">连接 AstrBot 服务</button><button class="btn ghost" data-act="astrbotRefresh">检查连接</button></div>' +
      '<button class="btn ghost block sm" data-act="astrbotGuide" style="margin-top:8px">第一次连接？看完整上手教程</button>' +
      '<div class="btn-row" style="margin-top:8px"><button class="btn ' + (ac.agentEnabled ? 'ghost' : '') + '" data-act="astrbotAgentToggle">' + (ac.agentEnabled ? '关闭远端智能体' : '让桌宠和 QQ 私聊共用远端智能体') + '</button><button class="btn ghost" data-act="astrbotPlugins">读取插件状态</button></div>' +
      '<div class="faint" style="margin-top:8px">桌宠文本和通过本应用 OneBot 连接的 QQ 私聊共用稳定的 AstrBot WebChat 用户与会话；本机记忆或人设变化后，会在下一轮把更新后的限长摘要附加到同一会话，不会另开会话。直接接入 AstrBot 的原生 QQ 会话与这个 WebChat 会话分开，本应用也不支持由 AstrBot 原生接管 QQ。QQ 群消息默认留在手机本机、按群隔离；只有为群聊开启「群聊使用 AstrBot 插件」时才会发送到远端。群摘要使用手机本机聊天模型，若本机模型未配置或不可用，暂存原文会清除但无法生成摘要。桌宠聊天里的图片、文件和屏幕观察走手机现有流程；经 NapCat 收到的 QQ 私聊图片会交给手机视觉模型并保存在本机聊天，不上传给 AstrBot。QQ 回复目前只发文字，表情图片只会显示在 DSH 本机聊天。</div>' +
      '<div class="card-title" style="margin-top:14px"><h3>手机本地工具</h3><span class="tag ' + (ac.mobileToolsEnabled ? 'mint' : '') + '">' + (ac.mobileToolsEnabled ? '已允许请求' : '默认关闭') + '</span></div>' +
      '<div class="faint" style="line-height:1.7">需要在 AstrBot 安装配套的「DSH 手机本地工具桥」。支持读取当前屏幕文字、打开应用、精确点击可见文字、上下滑动和返回；每一步都需要你点浮窗确认。读取到的屏幕文字会发给 AstrBot。QQ 群聊不会触发这些工具。</div>' +
      '<div class="notice" style="margin-top:8px">桥接插件：' + (S.astrbotMobileBridgeStatus && S.astrbotMobileBridgeStatus.ok && S.astrbotMobileBridgeStatus.available ? '已加载 v' + esc(S.astrbotMobileBridgeStatus.plugin_version || '?') : (S.astrbotMobileBridgeStatus && S.astrbotMobileBridgeStatus.error ? esc(S.astrbotMobileBridgeStatus.error) : '尚未检查')) + '</div>' +
      (ac.agentEnabled ? '<div class="notice" style="margin-top:8px' + (ac.remoteFatal ? ';color:#ffd6dc' : '') + '">' + (ac.remoteFatal ? '远端会话通道异常：远端 AstrBot 没有可用对话模型，聊天会一直失败，直到在 AstrBot WebUI 配好模型，或在本页关闭“远端 AstrBot 智能体”。' : '注意：启用后桌宠文本与 QQ 私聊都由远端 AstrBot 回答，远端 WebUI 必须已添加并启用对话模型，否则聊天会报错。') + '</div>' : '') +
      '<div class="btn-row" style="margin-top:8px"><button class="btn ghost" data-act="astrbotMobilePluginExport">导出配套 AstrBot 插件</button><button class="btn ghost" data-act="astrbotMobileBridgeCheck">检查桥接</button></div>' +
      '<button class="btn ' + (ac.mobileToolsEnabled ? 'ghost' : 'primary') + ' block sm" data-act="astrbotMobileToolsToggle" style="margin-top:8px">' + (ac.mobileToolsEnabled ? '关闭手机工具' : '启用手机工具') + '</button>' +
      (S.astrbotApiPlugins && S.astrbotApiPlugins.ok ? '<div class="card-title" style="margin-top:12px"><h3>AstrBot 已安装插件</h3><span>' + ((S.astrbotApiPlugins.plugins || []).length) + ' 个</span></div>' +
        '<div class="faint" style="line-height:1.7">生效范围：指令类插件必须以 / 开头发送（如 /help），只要已连接 AstrBot 就会在桌宠聊天和 QQ 私聊中直接转发，无需开启“远端智能体”；普通聊天需开启远端智能体；QQ 群聊还需在对应会话开启“群聊使用 AstrBot 插件”。</div>' +
        ((S.astrbotApiPlugins.plugins || []).length ? (S.astrbotApiPlugins.plugins || []).slice(0, 30).map(function (p) {
          if (typeof p === 'string') return '<div class="notice" style="margin-top:7px">' + esc(p) + '</div>';
          var id = p.plugin_id || p.market_plugin_id || p.id || p.name || p.root_dir_name || 'AstrBot 插件';
          var enabled = !(p.activated === false || p.is_active === false || p.enabled === false);
          var state = p.is_failed || p.error ? '加载异常' : (enabled ? ((p.activated === true || p.is_active === true || p.enabled === true) ? '已启用' : '运行状态未提供') : '已停用');
          var moduleName = String(p.name || p.root_dir_name || '');
          var toggle = moduleName ? '<div class="btn-row" style="margin-top:6px"><button class="btn sm ghost" data-act="astrbotPluginToggle" data-plugin-name="' + esc(moduleName) + '" data-enabled="' + (enabled ? '0' : '1') + '">' + (enabled ? '停用' : '启用') + '</button></div>' : '';
          return '<div class="notice" style="margin-top:7px"><b>' + esc(p.display_name || p.name || id) + '</b> · v' + esc(p.version || '?') + '<br><span class="faint">' + esc(id) + ' · ' + state + (p.error ? ' · ' + esc(p.error) : '') + '</span>' + toggle + '</div>';
        }).join('') : '<div class="faint">接口已连通，当前没有返回已安装插件。</div>') :
        (S.astrbotApiPlugins && !S.astrbotApiPlugins.ok ? '<div class="notice" style="margin-top:10px;color:#ffd6dc">AstrBot API：' + esc(S.astrbotApiPlugins.error || '读取失败') + '</div>' : '')) +
      '<div class="card-title" style="margin-top:14px"><h3>AstrBot 插件市场</h3><span>' + (astrMarket.ok ? (astrMarket.total != null ? esc(astrMarket.total) + ' 个' : astrMarketRows.length + ' 个') : '需连接服务') + '</span></div>' +
      '<div class="faint">从已连接的远端 AstrBot 市场读取；API Key 需要 plugin scope。确认安装后由远端 AstrBot 下载、安装并加载插件，手机仅控制安装与查看结果。</div>' +
      '<div class="row" style="gap:8px;margin-top:8px"><input id="astrbotMarketKeyword" value="' + esc(S.astrbotMarketKeyword || '') + '" placeholder="搜索插件名称或简介" style="flex:1;min-width:0;padding:12px;border-radius:12px;border:1px solid var(--line);background:rgba(0,0,0,.25);color:var(--ink)"><button class="btn" data-act="astrbotApiMarket">搜索</button></div>' +
      (astrMarket.ok ? (astrMarketRows.length ? '<div style="display:flex;flex-direction:column;gap:8px;margin-top:8px">' + astrMarketRows.slice(0, 40).map(function (p, i) {
        if (typeof p === 'string') return '<div class="notice">' + esc(p) + '</div>';
        p = p || {};
        var pMeta = p.metadata || p.meta || {};
        var title = p.display_name || p.title || p.name || pMeta.display_name || pMeta.name || p.plugin_id || '未命名插件';
        var version = p.version || p.latest_version || p.tag || pMeta.version || '未标注';
        var platform = p.support_platforms || p.platforms || p.platform || pMeta.support_platforms || [];
        if (typeof platform === 'string') platform = [platform];
        var deps = p.dependencies || p.python_dependencies || p.pip_dependencies || p.requirements || pMeta.dependencies || pMeta.requirements || [];
        if (!Array.isArray(deps)) deps = typeof deps === 'string' ? [deps] : Object.keys(deps || {});
        var versionRule = p.astrbot_version || p.minimum_astrbot_version || pMeta.astrbot_version || pMeta.minimum_astrbot_version || '';
        var category = p.category || p.category_name || pMeta.category || '';
        var repo = astrbotMarketRepository(p);
        var marketId = astrbotMarketIdentity(p);
        var registry = astrbotMarketRegistry(p, astrMarket);
        var installButton = repo && marketId ? '<button class="btn sm" data-act="astrbotInstallMarketOpen" data-market-index="' + i + '">交给 AstrBot 安装</button>' : '<span class="tag">该记录缺少可安装的仓库地址，仅浏览</span>';
        return '<div class="notice"><b>' + esc(title) + '</b> · v' + esc(version) + '<br><span class="faint">' + esc(p.author || pMeta.author || '') + (marketId ? ' · ' + esc(marketId) : '') + '</span>' +
          '<br><span class="faint">平台：' + esc(Array.isArray(platform) ? (platform.join(', ') || '未声明') : platform) + ' · 依赖：' + esc(deps.length ? deps.join(', ') : '未声明') + (versionRule ? ' · AstrBot：' + esc(versionRule) : '') + (category ? ' · 分类：' + esc(category) : '') + '</span>' +
          (p.desc || p.description || pMeta.desc || pMeta.description ? '<br><span class="faint">' + esc(p.desc || p.description || pMeta.desc || pMeta.description) + '</span>' : '') +
          (repo ? '<br><span class="faint" style="word-break:break-all">仓库：' + esc(repo) + '</span>' : '') +
          (registry ? '<br><span class="faint" style="word-break:break-all">市场来源：' + esc(registry) + '</span>'
            : (p.registry_inferred || astrMarket.default_registry ? '<br><span class="faint">市场来源：AstrBot 默认插件市场</span>' : '')) +
          '<div class="btn-row" style="margin-top:8px">' + installButton + '</div></div>';
      }).join('') + '</div>' : '<div class="notice" style="margin-top:8px">AstrBot 市场没有返回插件记录。</div>') +
        '<div class="btn-row" style="margin-top:8px"><button class="btn ghost" data-act="astrbotApiMarketPage" data-page-delta="-1" ' + ((S.astrbotApiMarketPage || 1) <= 1 ? 'disabled' : '') + '>上一页</button><span class="faint" style="align-self:center">第 ' + (S.astrbotApiMarketPage || 1) + ' 页</span><button class="btn ghost" data-act="astrbotApiMarketPage" data-page-delta="1" ' + ((astrMarket.total != null && (S.astrbotApiMarketPage || 1) * 40 >= num(astrMarket.total, 0)) || astrMarketRows.length < 40 ? 'disabled' : '') + '>下一页</button></div>' :
        (astrMarket.error ? '<div class="notice" style="margin-top:8px;color:#ffd6dc">AstrBot 市场：' + esc(astrMarket.error) + '</div>' : '<div class="faint" style="margin-top:8px">点击搜索后读取已连接的远端 AstrBot 市场。需要先配置服务地址和 API Key。</div>')) +
      (S.astrbotInstallResult ? '<div class="notice ' + (S.astrbotInstallResult.verification === 'loaded' ? 'mint' : '') + '" style="margin-top:8px">最近一次安装：' + (S.astrbotInstallResult.verification === 'loaded' ? 'AstrBot 明确报告已加载' : (S.astrbotInstallResult.verification === 'disabled' ? '已找到插件，但 AstrBot 报告其已停用' : (S.astrbotInstallResult.verification === 'installed' ? '已在安装列表中找到，运行状态未提供' : (S.astrbotInstallResult.verification === 'failed' ? 'AstrBot 报告加载失败' : '安装请求已返回，尚未在已安装列表确认')))) +
        (S.astrbotInstallResult.activation === 'enabled' ? '，已自动启用' : (S.astrbotInstallResult.activation === 'already' ? '，插件本就处于启用状态' : '')) +
        (S.astrbotInstallResult.message ? '<br>' + esc(S.astrbotInstallResult.message) : '') + (S.astrbotInstallResult.verification === 'failed' && S.astrbotInstallResult.failedMessage ? '<br>' + esc(S.astrbotInstallResult.failedMessage) : '') +
        (S.astrbotInstallResult.activation === 'failed' ? '<br><span style="color:#ffd6dc">自动启用失败：' + esc(S.astrbotInstallResult.activationError || '未知原因') + '，可在下方已安装列表点“启用”重试。</span>' : '') +
        (S.astrbotInstallResult.verification !== 'loaded' && S.astrbotInstallResult.verification !== 'failed' && S.astrbotInstallResult.repository ? '<div class="btn-row" style="margin-top:8px"><button class="btn sm ghost" data-act="astrbotInstallVerify">重新核验安装状态</button></div>' : '') + '</div>' : '') +
      '</div>';
    html += '<div class="card"><div class="card-title"><h3>已安装</h3><span>' + list.length + ' 个 · ' + on + ' 开启</span></div>';
    if (!list.length) html += '<div class="empty">当前没有已安装的代码插件。可以从本机导入插件包，或在聊天里让她生成待确认的代码插件草稿。</div>';
    else {
      list.forEach(function (p) {
        html += '<div class="plug-item"><div class="plug-head">' +
          '<div class="li-ico"><svg viewBox="0 0 24 24"><path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"/></svg></div>' +
          '<div class="plug-name"><b>' + esc(p.name || p.id) + '</b><span>v' + esc(p.version || '1.0.0') +
          (p.author ? ' · ' + esc(p.author) : '') + '</span></div>' +
          '<label class="switch sm"><input type="checkbox" data-act="pluginToggle" data-id="' + esc(p.id) + '" ' + (p.enabled ? 'checked' : '') + '><span class="track"></span></label></div>' +
          '<p class="plug-desc">' + esc(p.description || '这个插件没有写介绍，但动作与关键词已经可以使用。') + '</p>' +
          (p.audit ? '<div class="notice ' + (p.auditState === 'blocked' ? '' : 'violet') + '" style="margin:10px 0;line-height:1.65' + (p.auditState === 'blocked' ? ';border-color:rgba(255,105,120,.42);background:rgba(255,80,100,.08);color:#ffd6dc' : '') + '">' +
            '<b>导入检测 · ' + (p.auditState === 'blocked' ? '发现阻断项' : (p.audit.aiState === 'running' ? 'AI 检查中' : (p.audit.aiState === 'complete' ? '已完成' : '本机检查'))) + '</b><br>' +
            esc(p.audit.aiSummary || p.auditSummary || p.audit.summary || '正在检查插件…') +
            (p.audit.dryRunSummary ? '<br>' + esc(p.audit.dryRunSummary) : '') +
            (p.audit.repairSummary ? '<br><br>' + esc(p.audit.repairSummary) : '') +
            (p.audit.issues && p.audit.issues.length ? '<br><span class="faint">' + esc(p.audit.issues.slice(0, 4).join('；')) + '</span>' : '') +
            (p.audit.aiIssues && p.audit.aiIssues.length ? '<br><span class="faint">AI 建议：' + esc(p.audit.aiIssues.slice(0, 4).join('；')) + '</span>' : '') +
            (p.audit.repairCandidate ? '<br><b>兼容副本：</b>' + esc(p.audit.repairCandidate) + '（默认关闭）' : '') +
            '</div>' : '') +
          (p.native && p.runtimeError ? '<p class="plug-desc" style="color:#ff8a8a">启动失败：' + esc(p.runtimeError) + '</p>' : '') +
          '<div class="plug-foot">' +
          (p.ai ? '<span class="tag pink">她写的</span>' : (p.builtin ? '<span class="tag violet">预设</span>' : '<span class="tag">本机</span>')) +
          (p.native ? '<span class="tag pink">原生 · 无沙箱</span>' :
            (p.hasCode ? '<span class="tag violet">代码沙箱</span><span class="tag">' + num(p.codeSize, 0) + ' 字</span>' : '<span class="tag">配置</span>')) +
          '<span class="tag">' + num(p.quickCount, 0) + ' 快捷</span>' +
          '<span class="tag ' + (p.enabled ? 'mint' : '') + '">' + (p.enabled ? '已开启' : '已停用') + '</span>' +
          '<button class="li-act danger" data-act="pluginDel" data-id="' + esc(p.id) + '">删除</button></div></div>';
      });
    }
    html += '</div>';
    html += '<div class="card"><div class="card-title"><h3>安装</h3><span>本机文件</span></div>' +
      '<div class="faint" style="line-height:1.7">选择手机里下载好的 .json 或 .zip 插件包。导入后先保持关闭并自动检查，报告会显示在插件卡片中。<br>' +
      '• 配置插件：检查规则、快捷指令和动作结构；AI 修复后会验证副本是否仍有可用入口；<br>' +
      '• JavaScript 插件：会在屏蔽联网、截图、点击、滑动和记忆写入的沙箱中试跑入口；AI 可生成独立副本；<br>' +
      '• 原生插件：只做清单和 DEX 静态检查，不自动执行；若包内带源码，AI 可以尝试转成受限 JS 副本。原生插件与 App 同进程、权限相同，启用前必须确认。</div>' +
      '<div class="faint" style="margin-top:8px">AI 检查会把必要的清单字段和最多 18,000 字符源码发送给当前配置的聊天模型；不会上传 DEX 二进制。没有源码时不能可靠还原原生插件。副本的隔离试跑不等于真机联网或操作验证。未配置模型时只做本机检查。</div>' +
      '<button class="btn primary block" data-act="pluginImport" style="margin-top:12px">选择本机插件</button></div>';
    html += '<div class="notice violet">想让她现场写一个代码插件？在聊天里说「帮我写个…插件」，她会先生成草稿，你回复「装上」才会实际尝试安装。广告跳过不是这类代码插件，请到“功能中心 → 广告跳过”启用内置规则或导入规则。</div>';
    return html;
  }

  function openDshBoxAnalyze() {
    openModal('<div class="modal-head"><h3>让 DSHBox 分析插件</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<div class="faint" style="line-height:1.7;margin-bottom:10px">贴入插件用途、清单、关键源码或报错，最多 12,000 字。DSHBox 会结合它自己的插件清单给出兼容建议；不会自动改动任何插件。</div>' +
      '<label class="field"><span>插件问题与材料</span><textarea id="dshBoxTask" maxlength="12000" placeholder="例如：这是我导入的桌宠插件清单/报错……请判断原因和适配方案"></textarea></label>' +
      '<div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="closeModal">取消</button><button class="btn primary" data-act="pluginDshBoxAnalyze">开始分析</button></div>');
  }

  /* ---------------- 表情包 ---------------- */
  function stickerById(id) {
    var list = S.stickers || [];
    for (var i = 0; i < list.length; i++) if ((list[i].id || '') === (id || '')) return list[i];
    return null;
  }
  function stickerOpen(id) {
    var s = stickerById(id);
    if (!s) return;
    S.viewSticker = id;
    var url = stickerUrl(s.name);
    openModal('<div class="modal-head"><h3>' + esc(s.name || '表情') + '</h3><div class="modal-head-actions">' +
      '<button class="sticker-refresh-top" data-act="stickerRefresh" data-id="' + esc(s.id || '') + '" title="重新识别这张表情的适用场景" aria-label="重新识别这张表情的适用场景"><svg viewBox="0 0 24 24"><path d="M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4"/></svg></button>' +
      '<button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div></div>' +
      (url ? '<img class="sticker-big" src="' + esc(url) + '" data-name="' + esc(s.name) + '" onerror="__stickerFallback(this)" alt="">' : '<div class="empty">图片丢了，点「更换」重新选一张</div>') +
      '<p class="faint" style="margin:12px 0 2px;line-height:1.65">' + esc(s.desc || '还没写适用场景') + '</p>' +
      '<div class="btn-row" style="margin-top:12px">' +
      '<button class="btn ghost" data-act="stickerReplace" data-id="' + esc(s.id || '') + '">更换</button>' +
      '<button class="btn ghost" data-act="stickerEdit" data-id="' + esc(s.id || '') + '">修改</button>' +
      '</div>' +
      '<div class="faint" style="margin-top:8px;font-size:10.5px;line-height:1.6">右上角刷新＝只重新识别这张图的适用场景；更换＝换一张图，名字不变；修改＝自己写适合什么场景。</div>');
  }
  function stickerEditDesc(id) {
    var s = stickerById(id);
    if (!s) return;
    openModal('<div class="modal-head"><h3>修改使用场景</h3><button class="modal-close" data-act="closeModal">' +
      '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<label class="field"><span>适合什么场景（她挑表情时会参考这句）</span>' +
      '<input id="stickerDesc" placeholder="例如：他说累的时候发，表示心疼" value="' + esc(s.desc || '') + '"></label>' +
      '<div class="btn-row" style="margin-top:14px"><button class="btn ghost" data-act="closeModal">取消</button>' +
      '<button class="btn primary" id="stickerDescSave">保存</button></div>');
    $('#stickerDescSave').onclick = function () {
      var d = val('#stickerDesc');
      call('stickerSetDesc', { id: id, desc: d }).then(function (r) {
        S.stickers = r.stickers || [];
        stickerOpen(id);
        if (S.page === 'stickers') render();
        toast('已保存');
      }).catch(function (e) { toast(e.message); });
    };
  }

  function renderStickers() {
    var list = S.stickers || [];
    var html = pageHead('表情包', '点开单个可以看大图、重新识别或换图', true);
    html += '<div class="card"><div class="card-title"><h3>已上传</h3><span>' + list.length + ' 个</span></div>';
    if (!list.length) {
      html += '<div class="empty">还没有表情包。上传一张带情绪的图，给她起个名字，聊天时她会自己判断什么时候用。</div>';
    } else {
      html += '<div class="sticker-grid">';
      list.forEach(function (s) {
        var url = stickerUrl(s.name);
        html += '<div class="sticker-cell">' +
          (url ? '<img src="' + esc(url) + '" data-act="stickerOpen" data-id="' + esc(s.id || '') + '" data-name="' + esc(s.name) + '" onerror="__stickerFallback(this)" alt="' + esc(s.name) + '">' : '<div class="sticker-none">?</div>') +
          '<b>' + esc(s.name || '') + '</b>' +
          '<span>' + esc(s.desc || '还没写适用场景') + '</span>' +
          '<button class="li-act danger" data-act="stickerDel" data-id="' + esc(s.id || '') + '">删除</button>' +
          '</div>';
      });
      html += '</div>';
    }
    html += '</div>';
    html += '<div class="card"><div class="card-title"><h3>导入</h3><span>静态图片</span></div>' +
      '<div class="faint" style="line-height:1.7">支持相册里的 PNG、JPG 或 WebP。名字写清楚情绪（例如“开心”“无语”“贴贴”）；场景可以留空，让 AI 看图识别。同名上传会覆盖旧图。</div>' +
      '<div class="btn-row" style="margin-top:12px">' +
      '<button class="btn primary" data-act="stickerImport">上传一张</button>' +
      '<button class="btn ghost" data-act="stickerBatch">批量导入</button>' +
      '</div>' +
      (list.length ? '<button class="btn ghost block" id="stickerAllBtn" data-act="stickerDescribeAll" style="margin-top:8px">一键批量识别（重新识别全部 ' + list.length + ' 个）</button>' : '') +
      (list.length && !(S.cfg && S.cfg.apiKey) ? '<div class="faint" style="margin-top:6px;font-size:10.5px">识别需要先绑定 API Key。</div>' : '') +
      '</div>';
    html += '<div class="notice violet">聊天里她通过 [[表情:名字]] 使用表情包，标记不会显示也不会朗读；情绪不明显时她不会硬用。</div>';
    return html;
  }

  /* ---------------- 今日使用 ---------------- */
  function fmtMin(m) {
    m = num(m, 0);
    if (m < 60) return m + ' 分钟';
    var h = Math.floor(m / 60), r = m % 60;
    return h + ' 小时' + (r ? ' ' + r + ' 分' : '');
  }
  function loadUsage() {
    return call('usage').then(function (r) {
      S.usage = r.usage || {};
      if (S.page === 'usage') render();
    }).catch(function (e) {
      toast(e.message);
    });
  }
  function renderUsage() {
    var html = pageHead('今日使用', '今天打开了哪些应用、用了多久 · 只存本机', true);
    var u = S.usage;
    if (!u) return html + '<div class="card"><div class="empty">正在读取今天的使用情况…</div></div>';
    if (!u.granted) {
      return html + '<div class="card"><div class="card-title"><h3>需要「使用情况访问」权限</h3><span>可选</span></div>' +
        '<div class="faint" style="line-height:1.7">Android 需要这个权限才能读取每个应用的前台时长。数据只留在本机 S-G，不上传、不消耗 token。</div>' +
        '<button class="btn primary block" data-act="openPerm" data-kind="usage" style="margin-top:12px">去开启权限</button></div>';
    }
    var t = u.today || {};
    var apps = t.apps || [];
    var cats = {};
    apps.forEach(function (a) {
      var k = a.cat || '其他';
      cats[k] = num(cats[k], 0) + num(a.min, 0);
    });
    var catList = Object.keys(cats).sort(function (a, b) { return cats[b] - cats[a]; });
    html += '<div class="card"><div class="card-title"><h3>今天</h3><span>' + esc(t.date || '') + '</span></div>' +
      '<div class="use-big"><b>' + fmtMin(t.totalMin) + '</b><em>累计前台</em></div>' +
      '<div class="btn-row"><span class="tag violet">解锁 ' + num(t.unlocks, 0) + ' 次</span>' +
      '<span class="tag mint">最长 ' + esc(t.top || '—') + ' ' + fmtMin(t.topMin) + '</span></div>' +
      (catList.length ? '<div class="faint" style="margin-top:10px;line-height:1.8">主要在：' +
        catList.slice(0, 4).map(function (k) { return esc(k) + ' ' + fmtMin(cats[k]); }).join(' · ') + '</div>' : '') +
      '<button class="btn ghost block sm" data-act="usageRefresh" style="margin-top:12px">刷新今天</button></div>';
    if (!apps.length) {
      html += '<div class="card"><div class="empty">今天还没有记录到应用使用。用一会儿再回来刷新。</div></div>';
    } else {
      html += '<div class="card"><div class="card-title"><h3>应用明细</h3><span>' + apps.length + ' 个</span></div>';
      var max = Math.max(1, num(apps[0].min, 1));
      apps.forEach(function (a) {
        var pct = Math.max(2, Math.round(num(a.min, 0) / max * 100));
        html += '<div class="use-row"><div class="use-top"><b>' + esc(a.name || a.pkg) + '</b><span>' + fmtMin(a.min) + '</span></div>' +
          '<i><b style="width:' + pct + '%"></b></i>' +
          '<div class="use-sub">' + esc(a.cat || '其他') + (num(a.launches, 0) ? ' · 打开 ' + num(a.launches, 0) + ' 次' : '') + '</div></div>';
      });
      html += '</div>';
    }
    var h = u.history || [];
    if (h.length) {
      var hmax = 1;
      h.forEach(function (d) { hmax = Math.max(hmax, num(d.totalMin, 0)); });
      html += '<div class="card"><div class="card-title"><h3>最近 7 天</h3><span>前台时长</span></div><div class="use-bars">';
      h.forEach(function (d) {
        var hp = Math.max(3, Math.round(num(d.totalMin, 0) / hmax * 72));
        html += '<div class="use-bar" title="' + esc(d.date || '') + '"><b style="height:' + hp + 'px"></b>' +
          '<span>' + num(d.totalMin, 0) + '</span><em>' + esc((d.date || '').slice(5)) + '</em></div>';
      });
      html += '</div></div>';
    }
    return html;
  }

  /* ---------------- 记账 ---------------- */
  var LED_CATS = ['餐饮', '购物', '出行', '生活', '娱乐', '医疗', '转账', '其他'];
  function money(v) {
    v = flt(v, 0);
    return Math.abs(v - Math.round(v)) < 0.005 ? String(Math.round(v)) : v.toFixed(2);
  }
  function loadLedger() {
    return call('ledger').then(function (r) {
      S.ledger = r.ledger || {};
      if (S.page === 'ledger') render();
    }).catch(function (e) {
      toast(e.message);
    });
  }
  function renderLedger() {
    var html = pageHead('记账', '自动识别支付通知 · 手动补一笔 · 月度预算', true);
    var l = S.ledger;
    if (!l) return html + '<div class="card"><div class="empty">正在读取账本…</div></div>';
    var budget = flt(l.budget, 0);
    var spent = flt(l.monthSpent, 0);
    var pct = budget > 0 ? Math.min(100, Math.round(spent / budget * 100)) : 0;
    html += '<div class="card"><div class="card-title"><h3>本月</h3><span>' + esc(l.month || '') + '</span></div>' +
      '<div class="use-big"><b>' + money(spent) + '</b><em>元支出</em></div>' +
      '<div class="btn-row"><span class="tag violet">今日 ' + money(l.todaySpent) + ' 元</span>' +
      (budget > 0 ? '<span class="tag ' + (l.over ? '' : 'mint') + '">预算 ' + money(budget) + ' 元' + (l.over ? ' · 已超支' : '') + '</span>'
        : '<span class="tag">未设预算</span>') +
      '</div>' +
      (budget > 0 ? '<div class="rel-bar' + (l.over ? ' over' : '') + '" style="margin-top:12px"><i style="width:' + pct + '%"></i></div>' +
        '<div class="faint" style="margin-top:6px">已用 ' + pct + '%</div>' : '') +
      '</div>';
    html += '<div class="card"><div class="card-title"><h3>记一笔</h3><span>手动补记</span></div>' +
      '<div class="btn-row" style="margin-bottom:10px">' +
      '<button class="btn sm' + (S.ledType === 'expense' ? ' primary' : '') + '" data-act="ledType" data-type="expense">支出</button>' +
      '<button class="btn sm' + (S.ledType === 'income' ? ' primary' : '') + '" data-act="ledType" data-type="income">收入</button></div>' +
      '<label class="field"><span>金额（元）</span><input id="ledAmt" inputmode="decimal" placeholder="0.00"></label>' +
      '<label class="field"><span>备注</span><input id="ledNote" placeholder="例如：午饭"></label>' +
      '<div class="led-cats">' + LED_CATS.map(function (c) {
        return '<button class="led-cat' + (c === S.ledCat ? ' on' : '') + '" data-act="ledCat" data-cat="' + esc(c) + '">' + esc(c) + '</button>';
      }).join('') + '</div>' +
      '<button class="btn primary block" data-act="ledgerAdd" style="margin-top:10px">记下来</button></div>';
    html += '<div class="card"><div class="card-title"><h3>预算与自动记账</h3><span>只认支付通知</span></div>' +
      '<label class="field"><span>月度预算（元，0 = 不提醒）</span><input id="ledBudget" inputmode="decimal" value="' + (budget > 0 ? money(budget) : '') + '" placeholder="例如 3000"></label>' +
      '<button class="btn block sm" data-act="ledgerBudget">保存预算</button>' +
      '<div class="row" style="margin-top:10px"><div><div class="rt">自动记账</div><div class="rs">支付/银行类通知带明确金额时自动记一笔</div></div>' +
      '<label class="switch"><input type="checkbox" data-act="ledgerAuto" ' + (l.auto ? 'checked' : '') + '><span class="track"></span></label></div></div>';
    var items = l.items || [];
    html += '<div class="card"><div class="card-title"><h3>最近记录</h3><span>' + items.length + ' 笔</span></div>';
    if (!items.length) {
      html += '<div class="empty">还没有记录。自动记账识别到支付通知后会出现在这里，也可以手动补一笔。</div>';
    } else {
      items.forEach(function (it) {
        var inc = it.type === 'income';
        html += '<div class="led-row"><div class="led-main"><b>' + esc(it.note || it.cat || '一笔') + '</b>' +
          '<span>' + fmt(it.ts) + ' · ' + esc(it.cat || '其他') + ' · ' + (it.source === 'auto' ? '自动' : '手动') + '</span></div>' +
          '<span class="led-amt' + (inc ? ' in' : '') + '">' + (inc ? '+' : '-') + money(it.amount) + '</span>' +
          '<button class="li-act" data-act="ledgerDel" data-id="' + esc(it.id || '') + '">×</button></div>';
      });
    }
    html += '</div>';
    html += '<div class="notice violet">自动记账只处理微信支付、支付宝、银行等带「¥ / 元」金额的支付通知，聊天内容不参与；所有记录只存本机 S-G。</div>';
    return html;
  }

  /* ---------------- 应用感知 ---------------- */
  function loadMonitor() {
    return call('monitor').then(function (r) {
      S.monitor = r.monitor || {};
      S.monitorPkg = r.pkg || '';
      S.monitorPkgName = r.pkgName || r.pkg || '';
      S.monitorText = r.text || '';
      if (S.page === 'monitor') render();
    }).catch(function (e) {
      toast(e.message);
    });
  }
  function renderMonitor() {
    var html = pageHead('应用感知', '她记得你在这个应用里做过什么 · 只存本机', true);
    var m = S.monitor;
    if (!m) return html + '<div class="card"><div class="empty">正在回想…</div></div>';
    html += '<div class="card"><div class="card-title"><h3>她看得到</h3><span>' +
      (num(m.appCount, 0) ? '已认识 ' + num(m.appCount, 0) + ' 个应用' : '') + '</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">应用内感知</div>' +
      '<div class="rs">翻页、改设置、看书、发消息、记账都会成为她聊天时的记忆</div></div>' +
      '<label class="switch"><input type="checkbox" data-act="monitorToggle" ' + (m.enabled ? 'checked' : '') + '><span class="track"></span></label></div>' +
      '<div class="faint" style="margin-top:10px;line-height:1.7">现在前台：' + esc(S.monitorPkgName || '—') + '</div>' +
      (S.monitorText ? '<div class="mono" style="margin-top:6px;max-height:90px;overflow:auto">' + esc(S.monitorText) + '</div>' : '') +
      '<button class="btn ghost block sm" data-act="monitorRefresh" style="margin-top:10px">刷新当前状态</button></div>';
    var remark = m.remark || '';
    var picks = (m.picks || []).slice().sort(function (a, b) { return num(b.count, 0) - num(a.count, 0); });
    html += '<div class="card"><div class="card-title"><h3>她眼里的你</h3><span>最近 12 小时</span></div>' +
      (remark ? '<div class="notice violet">' + esc(remark) + '</div>' : '<div class="empty">她还没抓到什么值得吐槽的。</div>') +
      (picks.length ? '<div class="btn-row" style="margin-top:10px">' + picks.slice(0, 6).map(function (p) {
        return '<span class="tag">' + esc(p.cat || '其他') + ' ' + num(p.count, 0) + '</span>';
      }).join('') + '</div>' : '') + '</div>';
    var items = m.items || [];
    html += '<div class="card"><div class="card-title"><h3>最近动作</h3><span>' + items.length + ' 条</span></div>';
    if (!items.length) {
      html += '<div class="empty">还没有记录。她去翻翻功能、改改设置或者看会儿书，这里就会慢慢有内容。</div>';
    } else {
      items.forEach(function (it) {
        html += '<div class="mon-row"><span class="tag">' + esc(it.cat || '其他') + '</span>' +
          '<div class="mon-main"><b>' + esc(it.action || '') + '</b>' +
          (it.detail ? '<span>' + esc(it.detail) + '</span>' : '') + '</div>' +
          '<em>' + fmt(it.ts) + '</em></div>';
      });
    }
    html += '</div>';
    html += '<div class="notice violet">这些记录只保存在本机 S-G，可以随时清空，也可以关闭应用内感知；关闭后她不会再看你在这个应用里的动作。</div>' +
      '<button class="btn block ghost sm" data-act="monitorClear" style="margin-bottom:20px">清空她的观察记录</button>';
    return html;
  }

  /* ---------------- 角色换装 ---------------- */
  function loadSkins() {
    return call('skins').then(function (r) {
      S.skins = r.skins || [];
      S.skinId = r.skinId || S.skinId || 'original';
      if (r.live2dSupported != null) S.live2dOk = !!r.live2dSupported;
      if (S.page === 'dress' || S.page === 'home') render();
    }).catch(function (e) {
      if (S.page === 'dress') toast(e.message);
    });
  }
  function skinById(id) {
    var list = S.skins || [];
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }
  function skinThumb() {
    var d = skinById(S.skinId);
    if (d && d.thumb) return d.thumb;
    return 'file:///android_asset/webp/a032.webp';
  }
  function skinName() {
    var d = skinById(S.skinId);
    if (d) return d.name;
    return '原版动态';
  }
  function renderDress() {
    var cur = skinById(S.skinId);
    var curImg = cur ? cur.image : 'file:///android_asset/webp/a032.webp';
    var curName = cur ? cur.name : '原版动态';
    var html = pageHead('角色换装', '原版逐帧动画 · 静态立绘 · 序列帧动图 · Live2D', true);
    html += '<div class="card skin-hero"><div class="skin-stage">' +
      '<img src="' + esc(curImg) + '"' + skinImgAttrs(cur ? cur.id : (S.skinId || 'original'), 0) +
      ' alt="' + esc(curName) + '"></div>' +
      '<div class="skin-hero-info"><h3>' + esc(curName) + '</h3><p>已换上 · ' +
      (cur && cur.original ? '原版 106 个逐帧动作'
        : (cur && cur.live2d ? 'Live2D 模型 · 实时表情与动作'
          : (cur && cur.anim ? '序列帧动图 · ' + num(cur.frames, 0) + ' 帧 / ' + num(cur.fps, 12) + ' fps'
            : '静态立绘 · 程序化动作'))) +
      '</p></div></div>';
    html += '<div class="card"><div class="card-title"><h3>选择皮肤</h3><span>' +
      (S.skins || []).length + ' 套</span></div>';
    if (!(S.skins || []).length) {
      html += '<div class="empty">正在读取皮肤…</div>';
    } else {
      html += '<div class="skin-grid">';
      S.skins.forEach(function (s) {
        html += '<div class="skin-cell' + (s.id === S.skinId ? ' on' : '') + '">' +
          '<button class="skin-pick" data-act="skinPick" data-id="' + esc(s.id) + '">' +
          '<span class="skin-thumb"><img src="' + esc(s.thumb) + '"' + skinImgAttrs(s.id, 1) +
          ' alt="' + esc(s.name) + '"></span>' +
          '<b>' + esc(s.name) + '</b><em>' +
          (s.original ? '原版动态'
            : (s.live2d ? 'Live2D 模型'
              : (s.anim ? '动图 ' + num(s.frames, 0) + ' 帧' : '静态立绘'))) +
          '</em></button>' +
          (s.custom ? '<button class="skin-del" data-act="skinDel" data-id="' + esc(s.id) + '" title="删除">×</button>' : '') +
          '</div>';
      });
      html += '</div>';
    }
    html += '</div>';
    html += '<div class="card"><div class="card-title"><h3>导入自己的皮肤</h3>' +
      '<button class="btn ghost sm" data-act="skinGuide">导入说明</button></div>' +
      '<div class="btn-row">' +
      '<button class="btn primary" data-act="skinImport">单张导入</button>' +
      '<button class="btn" data-act="skinImportMulti">批量选图</button>' +
      '</div>' +
      '<button class="btn block" data-act="skinImportZip">导入多套皮肤（zip）</button>' +
      '<button class="btn block" data-act="skinImportAnim">导入序列帧动图（zip）</button>' +
      (S.live2dOk
        ? '<button class="btn block" data-act="skinImportLive2d">导入 Live2D 模型（zip）</button>'
        : '<div class="notice">当前是 32 位安装包，Live2D 需要 64 位，暂时只能导入图片皮肤。</div>') +
      '<button class="btn ghost block sm" data-act="skinExportZip">导出我的皮肤包</button></div>';
    html += '<div class="notice violet">导入的皮肤只存在本机 S-G 目录，卸载应用会一起清空；素材请只用于个人使用，别二次分发有版权的图。</div>';
    return html;
  }

  /** 换装页的「导入说明」：三种皮肤包长什么样、放什么文件、失败了怎么排查。 */
  function openSkinGuide() {
    openModal('<div class="modal-head"><h3>皮肤导入说明</h3>' +
      '<button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<div class="notice violet">共四种用法：单张立绘、多套打包、序列帧动图、Live2D 模型。都只保存在手机本地的 S-G 目录。</div>' +

      '<h4 class="guide-h">① 单张立绘（最省事）</h4>' +
      '<div class="guide-body">' +
      '<b>格式</b>：PNG（推荐，可带透明底）或 JPG<br>' +
      '<b>尺寸</b>：竖图，建议 900×1200，最小不能低于 512×512<br>' +
      '<b>背景</b>：纯白或透明。纯白会自动抠掉；渐变、照片、花纹背景抠不干净<br>' +
      '<b>内容</b>：角色完整，头顶和脚底别裁掉，四周留一点空白<br>' +
      '<b>名字</b>：文件名就是皮肤名，例如 <span class="mono">白裙小猫.png</span> → 显示「白裙小猫」<br>' +
      '<b>处理</b>：导入后按最长边 1024 压缩保存</div>' +

      '<h4 class="guide-h">② 多套一起导入（zip）</h4>' +
      '<div class="guide-body">把多张立绘放进一个 zip，<b>每张图 = 一套皮肤</b>，文件名就是皮肤名。' +
      '可以带子文件夹，只认最里层的文件名；一次最多 60 张，单张不超过 24MB。</div>' +
      '<pre class="guide-tree">我的皮肤.zip\n  ├─ 女仆.png\n  ├─ 白裙.png\n  └─ 冬日/冬装.png</pre>' +

      '<h4 class="guide-h">③ 序列帧动图（zip）</h4>' +
      '<div class="guide-body">zip 里放<b>同一个角色的一串帧图</b>，一张一帧，按文件名自然顺序播放（2.png 排在 10.png 前面）。<br>' +
      '<b>硬性要求</b>：每帧画布尺寸必须一样、角色位置一致，否则播放时会抖。<br>' +
      '帧数 2–64 张；白底或透明底都行，白底会自动抠掉；帧图会等比缩放到 360 宽以内。<br>' +
      '默认 12 帧/秒。想自定义名字和帧率，在 zip 根目录加一个 <span class="mono">config.json</span>。</div>' +
      '<pre class="guide-tree">小猫.zip\n  ├─ config.json\n  ├─ 01.png\n  ├─ 02.png\n  └─ …\n\nconfig.json 内容：\n{"name":"小猫","fps":10}</pre>' +
      '<div class="guide-body"><b>name</b>：皮肤名，可省略（省略时用 zip 文件名）；<b>fps</b>：每秒播放几帧，1–30，默认 12。</div>' +

      '<h4 class="guide-h">④ Live2D 模型（zip）</h4>' +
      '<div class="guide-body">zip 里必须有 <span class="mono">*.model3.json</span>，和它同目录的 moc3、贴图（png）、动作文件会一起导入。<br>' +
      '<b>结构</b>：model3.json 放在根目录或子文件夹都行，导入时以它所在的那一层为模型根，子目录结构保持原样。<br>' +
      '<b>版本</b>：只支持 Cubism 3 / 4 / 5 的 <span class="mono">.moc3</span> 模型，旧版 .moc / .model 不行。<br>' +
      '<b>限制</b>：解压后 180MB 以内，单个文件 40MB 以内；只支持 64 位安装包。<br>' +
      '<b>预览</b>：导入时会先在手机上渲染一帧当缩略图，渲染不出来会直接报错，不会留下坏皮肤。' +
      '</div>' +
      '<pre class="guide-tree">Hiyori.zip\n  └─ Hiyori/\n       ├─ Hiyori.model3.json\n       ├─ Hiyori.moc3\n       ├─ Hiyori.2048/texture_00.png\n       ├─ motions/*.motion3.json\n       └─ physics3.json</pre>' +
      '<div class="guide-body">Live2D 模型有官方许可约束，请只用作者允许个人使用的模型，别商用或二次分发。</div>' +

      '<h4 class="guide-h">⑤ 素材去哪找</h4>' +
      '<div class="guide-body">Shimeji-ee 桌宠素材包、PNGTuber 帧图、itch.io 上的免费像素宠物，' +
      '下载后把图片按上面 ③ 重新打成一个 zip 就能直接用；静态立绘按 ① / ②。<br>' +
      'Live2D 模型可从 Cubism 官方示例、Booth 上的免费模型里找，按 ④ 打成 zip 导入。<br>' +
      '请只用作者允许个人使用的素材，别拿商业版权图二次分发。</div>' +

      '<h4 class="guide-h">⑥ 常见问题</h4>' +
      '<div class="guide-body">' +
      '• 「帧太多」：拆成两套，或删掉重复帧<br>' +
      '• 「图片都读不出来」：确认 zip 里是 png/jpg，不是 rar/7z，也没加密<br>' +
      '• Live2D「没有 .model3.json」：zip 里要保留模型原始目录结构，不要把 moc3 单独拖出来<br>' +
      '• Live2D「模型渲染失败」：多半缺贴图或 moc3 版本不兼容，先用官方示例模型试一次<br>' +
      '• 白色衣服被抠空？只抠和画面边缘连着的白色；边缘没连上的白色衣服会保留<br>' +
      '• 删皮肤：在「选择皮肤」里点缩略图右上角的 ×，帧文件会一起清掉<br>' +
      '• 导入后桌面没变：先在「选择皮肤」里点一下新皮肤，切回桌面即可</div>');
  }

  /* ---------------- 跨设备同步 ---------------- */
  function renderSync() {
    var s = S.sync || {};
    var html = pageHead('跨设备同步', '用你自己的 WebDAV 在手机之间同步 S-G', true);
    html += '<div class="card"><div class="card-title"><h3>WebDAV</h3><span>' + (s.lastSync ? '上次 ' + esc(s.lastSync) : '未同步') + '</span></div>' +
      '<label class="field"><span>地址</span><input id="davUrl" placeholder="https://dav.example.com/remote.php/dav/files/me" value="' + esc(s.url || '') + '"></label>' +
      '<label class="field"><span>用户名</span><input id="davUser" value="' + esc(s.user || '') + '"></label>' +
      '<label class="field"><span>密码 / 应用密码</span><input id="davPass" type="password" placeholder="' + (s.hasPass ? '已保存，留空则不修改' : '') + '"></label>' +
      '<label class="field"><span>备份加密密码（可选）</span><input id="syncPass" type="password" placeholder="' + (s.hasEnc ? '已保存，留空则不修改' : '') + '"></label>' +
      '<div class="row"><div><div class="rt">自动同步</div><div class="rs">每天一次，失败会跳过</div></div>' + switchHtml('syncAuto', S.cfg.syncAuto, 'toggleCfg') + '</div>' +
      '<button class="btn primary block sm" data-act="syncSave">保存配置</button>' +
      '<div class="btn-row" style="margin-top:10px"><button class="btn" data-act="syncTest">测试连接</button><button class="btn" data-act="syncUpload">上传</button><button class="btn ghost" data-act="syncDownload">下载</button></div></div>';
    html += '<div class="notice violet">下载会用云端备份覆盖本地，操作前建议先在「备份与迁移」里导出一份。</div>';
    return html;
  }

  /* ---------------- 新增：开发者模式 ---------------- */
  function renderDev() {
    var d = S.dev || {};
    var a = S.ad || {};
    var html = pageHead('开发者模式', '给排障用的开关和日志', true);
    html += '<div class="card"><div class="card-title"><h3>环境</h3><span>v' + esc(d.version || '2.1.0') + '</span></div>' +
      '<div class="mono">' + esc(d.cfgPath || '') + '</div>' +
      '<div class="btn-row"><button class="btn" data-act="devRefresh">刷新</button><button class="btn ghost" data-act="diagExport">导出诊断</button></div></div>';
    html += '<div class="card"><div class="card-title"><h3>广告调试</h3><span>' + num(a.total, 0) + ' 次尝试</span></div>' +
      '<div class="row" style="border-top:0;padding-top:0"><div><div class="rt">测试模式</div><div class="rs">只记录命中和节点，不实际点击</div></div>' + switchHtml('adTest', S.cfg.adTest, 'toggleCfg') + '</div>' +
      '<div class="row"><div><div class="rt">命中率</div><div class="rs">成功 ' + num(a.ok, 0) + ' / 失败 ' + num(a.fail, 0) + '</div></div><span class="tag mint">' + num(a.hitRate, 0) + '%</span></div>' +
      '<button class="btn block sm" data-act="adTestRun">在当前应用测试规则</button>' +
      '<button class="btn ghost block sm" data-act="adLog">查看调试日志</button></div>';
    if (S.adTestResult) html += '<div class="card"><div class="card-title"><h3>测试结果</h3><span>当前应用</span></div><div class="mono">' + esc(JSON.stringify(S.adTestResult)) + '</div></div>';
    if ((S.adLog || []).length) {
      html += '<div class="card"><div class="card-title"><h3>最近日志</h3><span>' + S.adLog.length + ' 条</span></div>';
      S.adLog.forEach(function (l) {
        html += '<div class="list-item"><div class="li-main"><b>' + esc(l.pkg || '') + '</b><span>' + (l.ok ? '成功' : '失败') + ' · ' + num(l.ms, 0) + 'ms · ' + esc(l.label || '') + ' ' + esc(l.why || '') + '</span></div></div>';
      });
      html += '</div>';
    }
    html += '<div class="card"><div class="card-title"><h3>系统提示词</h3><span>只读</span></div><div class="mono" style="max-height:260px;overflow:auto;white-space:pre-wrap">' + esc(d.prompt || '') + '</div></div>';
    return html;
  }

  /* ---------------- 事件与动作 ---------------- */
  function go(page) {
    if (page === 'dev' && S.cfg && S.cfg.devLocked) {
      toast('调试入口已关闭');
      return;
    }
    if (page === 'readerLast') {
      S.autoOpenLast = true;
      page = 'readerShelf';
    }
    // Android WebView 个别版本在原生 select 弹层关闭时不发 focusout/change，
    // 不能让它的 15 秒兜底锁住页面导航、顶部返回和系统返回。
    if (page !== S.page && _selectOpen) {
      _selectOpen = false;
      _pendingRender = false;
      clearTimeout(_selTimer);
    }
    if (S.page === 'memGraph' && page !== 'memGraph') {
      destroyMemoryGraphSafely();
      call('memoryGraphMode', { on: false }).catch(function () { });
    }
    rememberFeature(page);
    if (page !== S.page) {
      var mm = $('#main');
      if (mm) S.scrollPos[S.page] = mm.scrollTop;
      if (S.page === 'reader') leaveReader();
      if (page === 'reader') call('readingMode', { on: true }).catch(function () { });
      else if (S.page === 'reader') call('readingMode', { on: false }).catch(function () { });
      // 沉浸式：进入阅读页隐藏系统状态栏/导航栏，离开时恢复
      if (page === 'reader') call('immersive', { on: true }).catch(function () { });
      else if (S.page === 'reader') call('immersive', { on: false }).catch(function () { });
    }
    if (page !== S.page && !_noPush) {
      S.navStack.push(S.page);
      if (S.navStack.length > 12) S.navStack.shift();
    }
    _noPush = false;
    S.page = page;
    if (PAGE_NAMES[page]) call('activity', { name: PAGE_NAMES[page] }).catch(function () { });
    if (page === 'chat') {
      render();
      S.chatCursor = -1;
      S.chatHasMore = false;
      call('history', { cursor: -1 }).then(function (r) {
        S.chat = expandStoredChatBubbles(r.messages || []);
        S.chatCursor = num(r.cursor, 0);
        S.chatHasMore = !!r.hasMore;
        S.chatLoadingEarlier = false;
        S.persona.greeting = r.greeting || S.persona.greeting;
        refreshChat();
      }).catch(function () { });
    } else if (page === 'reminders') {
      render();
      call('reminders').then(function (r) {
        S.reminders = r.reminders || [];
        render();
      }).catch(function () { });
    } else if (page === 'companion') {
      render();
      call('tts').then(function (r) {
        S.tts = r.tts || {};
        S.voices = r.voices || [];
        if (S.page === 'companion') render();
      }).catch(function () { });
    } else if (page === 'usage') {
      render();
      loadUsage();
    } else if (page === 'ledger') {
      render();
      loadLedger();
    } else if (page === 'monitor') {
      render();
      loadMonitor();
    } else if (page === 'dress') {
      render();
      loadSkins();
    } else if (page === 'policy') {
      render();
      call('policy').then(function (r) { S.policy = r.policy || {}; S.policyJson = r.json || {}; S.intimacy = (r.policy || {}).intimacy || S.intimacy; render(); }).catch(function () { });
    } else if (page === 'relation') {
      S.diaryPeek = false;
      render();
      call('relations').then(function (r) {
        S.intimacy = r.intimacy || {}; S.traits = r.traits || {};
        S.diary = r.diary || {}; S.timeline = r.timeline || {}; S.caring = r.caring || {};
        S.projects = r.projects || [];
        if (r.emotion) S.emotion = r.emotion;
        return call('diaryPeek').then(function (p) {
          S.diaryPeek = !!(p && p.peeked);
          return loadRecap();
        });
      }).then(function () {
        if (S.page === 'relation') render();
      }).catch(function () { });
    } else if (page === 'health') {
      render();
      call('health').then(function (r) { S.health = r.health || []; render(); }).catch(function () { });
    } else if (page === 'clip') {
      render();
      call('clips').then(function (r) { S.clips = r.clips || []; render(); }).catch(function () { });
    } else if (page === 'backup') {
      render();
      call('migrations').then(function (r) { S.migrations = r.migrations || []; render(); }).catch(function () { });
    } else if (page === 'tasks') {
      render();
      call('tasks').then(function (r) {
        S.tasks = r.tasks || [];
        S.taskFocus = r.focus || {};
        S.taskSummary = r.summary || {};
        S.events = r.events || [];
        render();
      }).catch(function () { });
    } else if (page === 'memGraph') {
      render();
      call('memoryGraphMode', { on: true }).catch(function () { });
    } else if (page === 'mem') {
      render();
      reloadMemList().then(function () {
        render();
        // 顺带刷新亲密度，让顶部「我们的关系」显示最新阶段
        call('relations').then(function (r) {
          S.intimacy = r.intimacy || {}; S.traits = r.traits || {}; S.diary = r.diary || {};
          S.timeline = r.timeline || {}; S.caring = r.caring || {};
          if (S.page === 'mem') render();
        }).catch(function () { });
      }).catch(function () { });
    } else if (page === 'notif') {
      render();
      call('notifList').then(function (r) {
        S.notif = { unread: r.unread, items: r.list || [], muted: r.muted || [] };
        S.notifGroups = r.group || [];
        S.notifAi = r.ai || {};
        render();
        // 打开页面顺手让模型认一下新通知；已有缓存的会先显示，分析完再推一次
        if (S.cfg.notifAi !== false) {
          call('notifAi').then(function (x) {
            S.notifAi = x.ai || S.notifAi;
            if (S.page === 'notif') render();
          }).catch(function () { });
        }
      }).catch(function () { });
    } else if (page === 'geo') {
      render();
      call('geoState').then(function (r) { S.geo = r.geo || {}; render(); }).catch(function () { });
    } else if (page === 'wellness') {
      render();
      call('wellness').then(function (r) { S.well = r.well || {}; S.wellSteps = r.steps || []; render(); }).catch(function () { });
    } else if (page === 'musicx') {
      render();
      call('musicx').then(function (r) { S.musicx = r.musicx || {}; render(); }).catch(function () { });
      refreshPerms();
    } else if (page === 'qq') {
      render();
      call('qqLinkStatus').then(function (r) {
        S.qqStatus = r || {};
        if (S.page === 'qq') render();
      }).catch(function (e) {
        S.qqStatus = { state: '读取连接状态失败', error: e.message };
        if (S.page === 'qq') render();
      });
    } else if (page === 'plugins') {
      render();
      call('plugins').then(function (r) { S.plugins = r.plugins || []; render(); }).catch(function () { });
      call('dshboxStatus').then(function (r) { S.dshbox = r || {}; render(); }).catch(function () { S.dshbox = { installed: false, ready: false, message: '无法读取 DSHBox 状态。' }; render(); });
      call('astrbotConfig').then(function (r) { S.astrbotConfig = r || {}; render(); }).catch(function () { });
    } else if (page === 'sync') {
      render();
      call('syncState').then(function (r) { S.sync = r.sync || {}; render(); }).catch(function () { });
    } else if (page === 'readerShelf') {
      render();
      call('bookList').then(function (r) {
        S.books = r.books || [];
        S.bookStats = r.stats || {};
        S.bookCfg = r.config || S.bookCfg || {};
        render();
        if (S.autoOpenLast) {
          S.autoOpenLast = false;
          var last = null;
          (S.books || []).forEach(function (b) {
            if (b.hidden) return;
            if (!last || num(b.lastReadAt, 0) > num(last.lastReadAt, 0)) last = b;
          });
          if (last) openBook(last.id);
        }
      }).catch(function () { });
    } else if (page === 'dev') {
      render();
      call('devState').then(function (r) { S.dev = r; S.ad = r.ad || S.ad; render(); }).catch(function () { });
    } else if (page === 'persona') {
      render();
      loadMemories().then(function () { if (S.page === 'persona') render(); }).catch(function () { });
    } else if (page === 'home') {
      render();
      call('emotion').then(function (r) {
        if (r.emotion) { S.emotion = r.emotion; if (S.page === 'home') render(); }
      }).catch(function () { });
    } else {
      render();
    }
  }
  window.__goto = go;
  window.__openBookFromHost = openBook;
  window.__back = function () { return navBack(); };
  /** 阅读页的音量键翻页入口（原生 MainAct 调用） */
  window.__readerKey = function (dir) {
    if (S.page === 'reader') readerTurn(dir < 0 ? -1 : 1);
  };

  /** 面板/目录打开时，返回键先关它们，而不是离开阅读页 */
  function readerOverlayClose() {
    if (S.page !== 'reader') return false;
    if (S.readerPanel) { closeReaderPanel(); return true; }
    if (S.readerDrawer) { readerDrawerToggle(false); return true; }
    return false;
  }
  function navBack() {
    if (collapseMemoryGraphView()) return true;
    if (closeMsgMenu()) return true;
    var md = $('#modal');
    if (md && !md.classList.contains('hidden')) { closeModal(); return true; }
    if (readerOverlayClose()) return true;
    var prev = S.navStack.pop();
    if (prev) { _noPush = true; go(prev); return true; }
    if (S.page !== 'home') { _noPush = true; go('home'); return true; }
    return false;
  }
  function collapseMemoryGraphView() {
    var stage = $('.mem-graph-stage.is-expanded');
    if (!stage) return false;
    stage.classList.remove('is-expanded');
    var screen = stage.closest('.mem-graph-screen');
    if (screen) screen.classList.remove('is-expanded');
    if (window.DshMemoryGraph && window.DshMemoryGraph.setExpanded) window.DshMemoryGraph.setExpanded(false);
    var button = stage.querySelector('[data-act="memGraphExpand"]');
    if (button) { button.textContent = '放大视图'; button.setAttribute('aria-expanded', 'false'); }
    return true;
  }
  function goTab(page) {
    S.navStack = [];
    _noPush = true;
    go(page);
  }

  function openPage(page) { go(page); }

  function openUserProfile() {
    S.userProfileDraft = {
      nickname: profileName(),
      avatarData: String((S.profile || {}).avatarData || '')
    };
    openPage('userProfile');
  }

  function openQQPersonaEditor(item) {
    item = item || {};
    openModal('<div class="modal-head"><h3>' + (item.id ? '编辑群聊人设' : '新建群聊人设') + '</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<label class="field"><span>人设名称</span><input id="qqPersonaName" maxlength="60" value="' + esc(item.name || '') + '" placeholder="例如：温柔学姐"></label>' +
      '<label class="field"><span>角色设定</span><textarea id="qqPersonaPrompt" maxlength="8000" style="min-height:220px" placeholder="写下角色身份、性格、背景和说话方式；群聊隐私与安全边界仍由桌宠保护。">' + esc(item.prompt || '') + '</textarea></label>' +
      '<input id="qqPersonaId" type="hidden" value="' + esc(item.id || '') + '">' +
      '<button class="btn primary block" data-act="qqPersonaSave">保存人设</button>');
  }

  function reloadQQPersonas() {
    return call('qqPersonas').then(function (r) {
      S.qqPersonas = r.items || [];
      S.qqPersonaSelected = r.selectedId || '';
      return r;
    });
  }

  function readQQForm() {
    return {
      url: ($('#qqWsUrl') || {}).value || '',
      token: ($('#qqWsToken') || {}).value || '',
      allowedUserId: ($('#qqAllowedId') || {}).value || '',
      groupIds: ($('#qqGroupIds') || {}).value || '',
      groupMode: ($('#qqGroupMode') || {}).value || 'mention',
      groupAstrBot: (($('#qqGroupAstrBot') || {}).value || '0') === '1',
      privateAstrBot: (($('#qqPrivateAstrBot') || {}).value || '0') === '1',
      personaId: ($('#qqGroupPersona') || {}).value || ''
    };
  }

  function saveQQForm(connectAfter) {
    var form = readQQForm();
    call('qqLinkSave', form).then(function (saved) {
      S.cfg.qqOnebotUrl = form.url.trim();
      S.cfg.qqAllowedUserId = form.allowedUserId.trim();
      S.cfg.qqGroupIds = form.groupIds.trim();
      S.cfg.qqGroupMode = form.groupMode;
      S.cfg.qqGroupAstrBot = form.groupAstrBot;
      S.cfg.qqPrivateAstrBot = form.privateAstrBot;
      S.cfg.qqGroupPersonaId = form.personaId;
      S.qqPersonaSelected = form.personaId;
      if (form.token.trim()) S.cfg.qqOnebotToken = 'saved';
      S.qqStatus = saved.status || S.qqStatus || {};
      if (!connectAfter) {
        toast('QQ 连接设置已保存');
        render();
        return;
      }
      return call('qqLinkConnect').then(function (r) {
        S.cfg.qqLinkEnabled = true;
        S.qqStatus = r.status || S.qqStatus || {};
        toast('已开始连接 NapCat');
        render();
        setTimeout(function () {
          call('qqLinkStatus').then(function (x) {
            S.qqStatus = x || {};
            if (S.page === 'qq') render();
          }).catch(function () { });
        }, 1200);
      });
    }).catch(function (e) { toast(e.message || 'QQ 连接设置失败'); });
  }

  function toggleCfgKey(key, val) {
    var patch = {};
    patch[key] = val;
    S.cfg[key] = val;
    // 点击音效只需重读配置，不重建窗口，避免换开关时桌宠跳位置
    var apply = (key === 'petSound') ? 'timer' : 'general';
    return call('saveCfg', { json: patch, apply: apply }).catch(function (e) { toast(e.message); });
  }

  function submitOnboarding(keepCurrent) {
    obReadInputs();
    var sel = S.obSel || {};
    if (sel.relationId === 'custom' && !(sel.customRelation || '').trim()) {
      S.obStep = 1;
      render();
      toast('先写一个 2–12 字的关系称呼');
      return;
    }
    if (keepCurrent) sel.archetypeId = 'natural';
    var relationLabel = sel.relationId === 'custom' ? (sel.customRelation || '').trim() : (sel.relationLabel || '朋友');
    var toneName = sel.toneName || '软萌';
    var payload = {
      userName: sel.userName || '',
      herName: sel.herName || '蓝色大肥鱼',
      tone: toneName,
      relation: relationLabel,
      relationGroup: sel.relationId === 'custom' ? 'custom' : (sel.group || 'friend'),
      relationId: sel.relationId || 'friend',
      relationLabel: relationLabel,
      customRelation: sel.relationId === 'custom' ? relationLabel : (sel.customRelation || ''),
      archetypeId: keepCurrent ? 'natural' : (sel.archetypeId || 'gentle'),
      toneId: sel.toneId || 'soft',
      seed: sel.seed || Math.floor(Math.random() * 999999)
    };
    call('onboardingSave', payload).then(function (r) {
      S.contract = r.contract || { isSet: true, pending: false };
      S.personaSeed = r.seed || S.personaSeed;
      S.showOnboarding = 0;
      S.seedUpgrade = 0;
      S.resumeOnboarding = 0;
      S.obSel = null;
      S.obStep = 1;
      return boot(true);
    }).then(function () {
      toast('已经记下了，之后就不能再改');
    }).catch(function (e) { toast(e.message); });
  }

  function handleAct(act, el, ev) {
    switch (act) {
      case 'onboardingSkip': {
        var wasUpgrade = !!S.seedUpgrade;
        call('onboardingSkip').then(function (r) {
          S.contract = r.contract || { isSet: false, pending: true };
          S.showOnboarding = 0;
          S.resumeOnboarding = 0;
          return boot(true);
        }).then(function () {
          toast(wasUpgrade ? '已跳过这次重选' : '已跳过，之后可以在首页补上');
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'resumeOnboarding':
        S.showOnboarding = 1;
        S.resumeOnboarding = 1;
        render();
        break;
      case 'closeOnboarding':
        S.showOnboarding = 0;
        S.resumeOnboarding = 0;
        render();
        break;
      case 'onboardingSave':
        submitOnboarding(false);
        break;
      case 'obUseCurrent':
        submitOnboarding(true);
        break;
      case 'obNext':
        obReadInputs();
        if (S.obStep >= 3) { submitOnboarding(false); break; }
        if (S.obStep === 1 && S.obSel && S.obSel.relationId === 'custom' && !(S.obSel.customRelation || '').trim()) {
          toast('先写一个 2–12 字的关系称呼');
          break;
        }
        S.obStep = Math.min(3, num(S.obStep, 1) + 1);
        render();
        break;
      case 'obBack':
        obReadInputs();
        S.obStep = Math.max(1, num(S.obStep, 1) - 1);
        render();
        break;
      case 'obGroup':
        obReadInputs();
        if (!S.obSel) obInit();
        S.obSel.group = el.getAttribute('data-group') || 'friend';
        if (S.obSel.group === 'custom') {
          S.obSel.relationId = 'custom';
          S.obSel.relationLabel = S.obSel.customRelation || '';
        } else {
          var allRel = obRelations();
          var keep = false;
          allRel.forEach(function (r) {
            if (r.id === S.obSel.relationId && r.group === S.obSel.group) keep = true;
          });
          if (!keep) {
            allRel.forEach(function (r) {
              if (!keep && r.group === S.obSel.group) {
                S.obSel.relationId = r.id;
                S.obSel.relationLabel = r.label;
                keep = true;
              }
            });
          }
        }
        render();
        break;
      case 'obPickRel':
        obReadInputs();
        if (!S.obSel) obInit();
        var rid = el.getAttribute('data-id') || '';
        obRelations().forEach(function (r) {
          if (r.id === rid) {
            S.obSel.relationId = r.id;
            S.obSel.group = r.group;
            S.obSel.relationLabel = r.label;
          }
        });
        render();
        break;
      case 'obPickArch':
        if (!S.obSel) obInit();
        S.obSel.archetypeId = el.getAttribute('data-id') || 'gentle';
        render();
        break;
      case 'obPickTone':
        if (!S.obSel) obInit();
        S.obSel.toneId = el.getAttribute('data-id') || 'soft';
        S.obSel.toneName = el.getAttribute('data-name') || '软萌';
        render();
        break;
      case 'obRandom':
        if (!S.obSel) obInit();
        var arches = (S.presets && S.presets.archetypes) || [];
        var tones = (S.presets && S.presets.tones) || [];
        if (arches.length) S.obSel.archetypeId = arches[Math.floor(Math.random() * arches.length)].id;
        if (tones.length) {
          var t = tones[Math.floor(Math.random() * tones.length)];
          S.obSel.toneId = t.id;
          S.obSel.toneName = t.name;
        }
        S.obStep = 2;
        render();
        break;
      case 'home': goTab('home'); break;
      case 'chatTab': goTab('chat'); break;
      case 'back': if (!readerOverlayClose()) navBack(); break;
      case 'openPage': openPage(el.getAttribute('data-page')); break;
      case 'qqGuideTab':
        S.qqGuideTab = el.getAttribute('data-tab') || 'pc';
        if (['pc', 'phone', 'astrbot'].indexOf(S.qqGuideTab) < 0) S.qqGuideTab = 'pc';
        render();
        break;
      case 'memGraphOpen': go('memGraph'); break;
      case 'memGraphRefresh': mountMemoryGraph(); break;
      case 'memGraphFilter':
        S.memGraphFilter = el.getAttribute('data-type') || 'all';
        if (window.DshMemoryGraph) window.DshMemoryGraph.filter(S.memGraphFilter);
        $$('.mem-graph-filter').forEach(function (b) { b.classList.toggle('on', b === el); });
        break;
      case 'memGraphRecordFilter':
        _memGraphRecordFilter = el.getAttribute('data-type') || 'all';
        $$('.mem-graph-record-tabs button').forEach(function (b) { b.classList.toggle('on', b === el); });
        renderMemoryGraphRecords();
        break;
      case 'memGraphSelect':
        _memGraphSelectedRecord = el.getAttribute('data-id') || '';
        if (window.DshMemoryGraph) window.DshMemoryGraph.select(_memGraphSelectedRecord);
        $$('.mem-graph-record').forEach(function (b) { b.classList.toggle('on', b === el); });
        if (window.matchMedia && window.matchMedia('(max-width: 759px)').matches) {
          var recordsScreen = $('.mem-graph-screen');
          if (recordsScreen) recordsScreen.classList.remove('show-records');
          var recordsToggle = $('[data-act="memGraphRecords"]');
          if (recordsToggle) { recordsToggle.classList.remove('on'); recordsToggle.textContent = '记忆星录'; }
        }
        break;
      case 'memGraphReset':
        if (window.DshMemoryGraph) window.DshMemoryGraph.resetView();
        break;
      case 'memGraphZoomIn':
        if (window.DshMemoryGraph) window.DshMemoryGraph.zoomBy(1.2);
        break;
      case 'memGraphZoomOut':
        if (window.DshMemoryGraph) window.DshMemoryGraph.zoomBy(1 / 1.2);
        break;
      case 'memGraphRotate':
        if (window.DshMemoryGraph) {
          var isRotating = window.DshMemoryGraph.toggleRotation();
          el.textContent = isRotating ? '暂停旋转' : '自动旋转';
          el.classList.toggle('on', isRotating);
          el.setAttribute('aria-pressed', isRotating ? 'true' : 'false');
        }
        break;
      case 'memGraphExpand':
        var graphStage = $('.mem-graph-stage');
        if (graphStage) {
          var isExpanded = graphStage.classList.toggle('is-expanded');
          var graphScreenForExpand = graphStage.closest('.mem-graph-screen');
          if (graphScreenForExpand) graphScreenForExpand.classList.toggle('is-expanded', isExpanded);
          if (window.DshMemoryGraph && window.DshMemoryGraph.setExpanded) window.DshMemoryGraph.setExpanded(isExpanded);
          el.textContent = isExpanded ? '收起视图' : '放大视图';
          el.setAttribute('aria-expanded', isExpanded ? 'true' : 'false');
        }
        break;
      case 'memGraphRecords':
        var graphScreen = $('.mem-graph-screen');
        if (graphScreen) {
          var panelOpen = graphScreen.classList.toggle('show-records');
          el.classList.toggle('on', panelOpen);
          el.textContent = panelOpen ? '收起星录' : '记忆星录';
        }
        break;
      case 'profileOpen': openUserProfile(); break;
      case 'profileAvatarPick':
        call('profileAvatarPick').catch(function (e) { toast(e.message || '无法打开头像选择器'); });
        break;
      case 'profileAvatarRemove':
        if (!S.userProfileDraft) S.userProfileDraft = { nickname: profileName(), avatarData: String((S.profile || {}).avatarData || '') };
        S.userProfileDraft.avatarData = '';
        render();
        break;
      case 'profileSaveCard': {
        var profileDraft = S.userProfileDraft || { nickname: profileName(), avatarData: String((S.profile || {}).avatarData || '') };
        var updatedProfile = {};
        Object.keys(S.profile || {}).forEach(function (key) { updatedProfile[key] = S.profile[key]; });
        updatedProfile.nickname = val('#userDisplayName').trim().slice(0, 40);
        updatedProfile.avatarData = profileDraft.avatarData || '';
        call('profileSave', { profile: updatedProfile }).then(function (r) {
          S.profile = r.profile || updatedProfile;
          S.userProfileDraft = null;
          if (S.page === 'userProfile') { _noPush = true; go('home'); }
          else render();
          toast('头像和名字已保存');
        }).catch(function (e) { toast(e.message || '资料保存失败'); });
        break;
      }
      case 'toggleChatHeader':
        S.chatHeaderCollapsed = !S.chatHeaderCollapsed;
        try { localStorage.setItem('chatHeaderCollapsed', S.chatHeaderCollapsed ? '1' : '0'); } catch (_) { }
        render();
        break;
      case 'msgMenuDismiss': closeMsgMenu(); break;
      case 'chatQuoteClear':
        S.chatQuote = null;
        $$('.composer .chat-quote-preview').forEach(function (node) { node.remove(); });
        fitChatTail();
        break;
      case 'qqSave': saveQQForm(false); break;
      case 'qqConnect': saveQQForm(true); break;
      case 'qqPersonaNew':
        openQQPersonaEditor(null);
        break;
      case 'qqPersonaEdit': {
        var editPersonaId = el.getAttribute('data-id') || '';
        var editPersona = (S.qqPersonas || []).filter(function (p) { return p.id === editPersonaId; })[0];
        if (editPersona) openQQPersonaEditor(editPersona);
        break;
      }
      case 'qqPersonaImport':
        call('qqPersonaImport').then(function () { toast('请选择 Character Card JSON、PNG 或 TXT/MD 人设卡'); })
          .catch(function (e) { toast(e.message || '无法打开人设卡文件'); });
        break;
      case 'qqPersonaSave': {
        var personaName = val('#qqPersonaName').trim();
        var personaPrompt = val('#qqPersonaPrompt').trim();
        var personaId = val('#qqPersonaId').trim();
        if (!personaName || !personaPrompt) { toast('请填写人设名称和角色设定'); break; }
        call('qqPersonaSave', { id: personaId, name: personaName, prompt: personaPrompt }).then(function (r) {
          if (!r || !r.ok) throw new Error((r && r.error) || '保存人设失败');
          S.qqPersonas = r.items || [];
          S.qqPersonaSelected = r.selectedId || S.qqPersonaSelected;
          closeModal();
          if (S.page === 'qq') render();
          toast('群聊人设已保存');
        }).catch(function (e) { toast(e.message || '保存人设失败'); });
        break;
      }
      case 'qqPersonaDelete': {
        var deletePersonaId = el.getAttribute('data-id') || '';
        confirmBox('删除这张人设卡？', '删除后它不会再出现在 QQ 连接选择中。群聊历史摘要仍保留。', function () {
          call('qqPersonaDelete', { id: deletePersonaId }).then(function (r) {
            if (!r || !r.ok) throw new Error((r && r.error) || '删除人设失败');
            S.qqPersonas = r.items || [];
            S.qqPersonaSelected = r.selectedId || '';
            if (S.page === 'qq') render();
            toast('人设卡已删除');
          }).catch(function (e) { toast(e.message || '删除人设失败'); });
        });
        break;
      }
      case 'qqStatus':
        toast('正在向 NapCat 查询 QQ 登录信息…');
        call('qqLinkStatus').then(function (r) { S.qqStatus = r || {}; render(); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'qqDisconnect':
        call('qqLinkDisconnect').then(function (r) {
          S.cfg.qqLinkEnabled = false;
          S.qqStatus = r.status || {};
          toast('QQ 连接已断开');
          render();
        }).catch(function (e) { toast(e.message); });
        break;
      case 'toggleFeatureFav': {
        var fp = el.getAttribute('data-page');
        var fi = S.featureFavs.indexOf(fp);
        if (fi >= 0) S.featureFavs.splice(fi, 1);
        else if (featureById(fp)) S.featureFavs.push(fp);
        S.featureFavs = S.featureFavs.slice(-8);
        featureSave('favorites', S.featureFavs);
        render();
        break;
      }
      case 'skinPick': {
        var sid = el.getAttribute('data-id') || '';
        call('skinSelect', { id: sid }).then(function (r) {
          S.skinId = r.skinId || sid;
          toast('已换上' + skinName());
          render();
          loadSkins();
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'skinImport':
        call('skinImport').then(function () { toast('选好图片后会先确认名字'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'skinImportMulti':
        call('skinImportMulti').then(function () { toast('可多选，文件名会作为皮肤名'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'skinImportZip':
        call('skinImportZip').then(function () { toast('皮肤包里每张图都会成为一套皮肤'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'skinImportAnim':
        call('skinImportAnim').then(function () { toast('选一个序列帧皮肤包 zip'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'skinImportLive2d':
        call('skinImportLive2d').then(function () { toast('选一个含 .model3.json 的模型包'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'skinGuide':
        openSkinGuide();
        break;
      case 'skinExportZip':
        call('skinExportZip').then(function () { toast('选择保存位置…'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'skinDel': {
        var delId = el.getAttribute('data-id') || '';
        confirmBox('删除这套自定义皮肤？', '图片和缩略图会从 S-G 删除，删掉后可以重新导入。', function () {
          call('skinDel', { id: delId }).then(function (r) {
            S.skins = r.skins || [];
            S.skinId = r.skinId || 'original';
            toast('已删除');
            render();
          }).catch(function (e) { toast(e.message); });
        });
        break;
      }
      case 'openChat': go('chat'); break;
      case 'openSettings': go('settings'); break;
      case 'openReminders': go('reminders'); break;
      case 'startPet':
        call('startPet').then(function () { S.cfg.running = true; S.state.running = true; render(); toast('我来啦！'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'stopPet':
        call('stopPet').then(function () { S.cfg.running = false; S.state.running = false; render(); toast('我走了~'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'toggleLock':
        var lock = !S.cfg.locked;
        call('setLock', { lock: lock }).then(function () {
          S.cfg.locked = lock; S.state.locked = lock; render(); toast(lock ? '已锁定位置' : '已解锁');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'balance':
        if (!S.cfg.apiKey) { toast('先绑定 API Key'); go('settings'); break; }
        el.disabled = true;
        call('balance').then(function (r) { S.balance = r.text; S.balOff = false; updateTop(); render(); toast(r.text); })
          .catch(function (e) { toast(e.message); })
          .then(function () { el.disabled = false; });
        break;
      case 'balVision':
        el.disabled = true;
        call('balanceVision').then(function (r) {
          S.balanceVision = r.text; render(); toast(r.text);
        }).catch(function (e) {
          S.balanceVision = e.message || '查询失败'; render(); toast(S.balanceVision);
        }).then(function () { el.disabled = false; });
        break;
      case 'modelExport':
        call('modelExport').then(function (r) {
          var json = JSON.stringify(r.model || {}, null, 2);
          openModal('<div class="modal-head"><h3>模型配置</h3><button class="modal-close" data-act="closeModal">×</button></div>' +
            '<div class="faint">复制下面的 JSON 保存，或在另一台设备上导入。</div>' +
            '<textarea style="width:100%;min-height:240px;margin-top:12px;font-family:monospace;font-size:13px" readonly>' + esc(json) + '</textarea>' +
            '<button class="btn primary block" data-act="copyText" data-text="' + esc(json) + '" style="margin-top:12px">复制全部</button>');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'modelImport':
        openModal('<div class="modal-head"><h3>导入模型配置</h3><button class="modal-close" data-act="closeModal">×</button></div>' +
          '<div class="faint">粘贴之前导出的 JSON，包含聊天、视觉和图像生成配置。</div>' +
          '<textarea id="modelImportText" style="width:100%;min-height:240px;margin-top:12px;font-family:monospace;font-size:13px" placeholder="{&quot;apiKey&quot;:&quot;sk-...&quot;,&quot;baseUrl&quot;:&quot;https://...&quot;,&quot;model&quot;:&quot;...&quot;,&quot;imageGenProvider&quot;:&quot;comfyui&quot;,&quot;comfyUiBaseUrl&quot;:&quot;http://192.168.1.20:8188&quot;}"></textarea>' +
          '<button class="btn primary block" data-act="modelImportDo" style="margin-top:12px">导入并应用</button>');
        break;
      case 'modelImportDo': {
        var txt = ($('#modelImportText') || {}).value || '';
        if (!txt.trim()) { toast('先粘贴 JSON'); break; }
        try {
          var m = JSON.parse(txt);
          call('modelImport', { model: m }).then(function (r) {
            S.cfg = r.cfg || S.cfg;
            closeModal();
            render();
            toast('模型配置已导入');
          }).catch(function (e) { toast(e.message); });
        } catch (err) {
          toast('JSON 格式不对');
        }
        break;
      }
      case 'reload':
        boot(true);
        break;
      case 'copyText':
        copyText(el.getAttribute('data-text') || '');
        toast('已复制');
        break;
      case 'longDocOpen': {
        var id = el.getAttribute('data-id') || '';
        call('longDocGet', { id: id }).then(function (r) {
          var body = String(r.text || '');
          openModal('<div class="modal-head"><h3>' + esc(r.title || '长文档') + '</h3><button class="modal-close" data-act="closeModal">×</button></div>' +
            '<div class="faint">' + esc(String(r.format || 'md').toUpperCase()) + ' · 正文保留 7 天；到期后只保留一句话摘要。</div>' +
            '<textarea id="longDocBody" readonly style="width:100%;min-height:45vh;max-height:58vh;margin-top:12px;font-family:monospace;font-size:13px;white-space:pre;overflow:auto">' + esc(body) + '</textarea>' +
            '<button class="btn primary block" data-act="longDocCopy" style="margin-top:10px">复制全文</button>' +
            '<div class="btn-row" style="margin-top:8px">' +
            '<button class="btn ghost" data-act="longDocExport" data-id="' + esc(id) + '" data-format="md">下载 MD</button>' +
            '<button class="btn ghost" data-act="longDocExport" data-id="' + esc(id) + '" data-format="txt">下载 TXT</button>' +
            '<button class="btn ghost" data-act="longDocExport" data-id="' + esc(id) + '" data-format="jar">下载 JAR</button></div>' +
            (String(r.format).toLowerCase() === 'jar' ? '<div class="faint" style="margin-top:8px">JAR 是源码/文档归档，不是可直接安装运行的程序。</div>' : ''));
        }).catch(function (e) { toast(e.message || '文档已过期'); });
        break;
      }
      case 'longDocCopy': {
        var docBody = $('#longDocBody');
        if (!docBody) { toast('文档内容已关闭'); break; }
        copyText(docBody.value || '');
        toast('全文已复制');
        break;
      }
      case 'longDocExport': {
        var exportId = el.getAttribute('data-id') || '';
        var exportFormat = el.getAttribute('data-format') || 'md';
        call('longDocExport', { id: exportId, format: exportFormat }).then(function () {
          toast('请选择保存位置');
        }).catch(function (e) { toast(e.message || '导出失败'); });
        break;
      }
      case 'sendChat': sendChat(); break;
      case 'cancelChat': cancelActiveChat(); break;
      case 'ttsToggle':
        S.cfg.ttsEnabled = !S.cfg.ttsEnabled;
        call('ttsSave', { json: { ttsEnabled: S.cfg.ttsEnabled } }).then(function () {
          render();
          if (S.cfg.ttsEnabled) {
            toast('已开启朗读');
            call('ttsTest').catch(function () { });
          } else toast('已关闭朗读');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'chatVoice': {
        var voiceIdx = parseInt(el.getAttribute('data-idx') || '-1', 10);
        var voiceMsg = (S.chat || [])[voiceIdx];
        if (!voiceMsg || voiceMsg.role !== 'assistant' || !voiceMsg.content) {
          toast('这条消息暂时没有可播放的语音');
          break;
        }
        call('chatVoice', { text: voiceMsg.voiceText || voiceMsg.content }).then(function () {
          toast('正在播放语音消息');
        }).catch(function (e) { toast(e.message || '语音播放失败'); });
        break;
      }
      case 'chatEarlier':
        loadEarlierChat();
        break;
      case 'voiceInputMode':
        if (S.asrOn) {
          S.asrOn = false;
          S.voiceHoldActive = false;
          S.voiceHoldSend = false;
          call('asrStop').catch(function () { });
        }
        S.voiceInputMode = !S.voiceInputMode;
        S.asrText = '';
        render();
        if (S.voiceInputMode && !(S.perms && S.perms.record)) {
          call('requestPerm', { kind: 'record' }).catch(function (e) { toast(e.message); });
        }
        break;
      case 'asrToggle':
        if (S.asrOn) {
          S.asrOn = false;
          call('asrStop').catch(function () { });
          render();
          toast('已停止聆听');
        } else if (!(S.perms && S.perms.record)) {
          // 第一次使用先申请麦克风权限，授权成功后自动开始
          S.asrPending = true;
          toast('请先允许麦克风权限');
          call('requestPerm', { kind: 'record' }).catch(function (e) {
            S.asrPending = false;
            toast(e.message);
          });
        } else {
          startAsr();
        }
        break;
      case 'clearChat':
        confirmBox('清空聊天记录？', '聊天记录会从 S-G 删除，记忆不会。', function () {
          call('clearChat').then(function () { S.chat = []; S.chatCursor = -1; S.chatHasMore = false; refreshChat(); toast('已清空'); });
        });
        break;
      case 'preview':
        call('preview', { slug: el.getAttribute('data-slug') }).then(function () { toast('正在播放'); }).catch(function (e) { toast(e.message); });
        break;
      case 'toggleCat':
        var cat = el.getAttribute('data-cat');
        S.openCats[cat] = !S.openCats[cat];
        el.parentNode.classList.toggle('open');
        break;
      case 'saveVision': {
        var vpatch = {
          visionBaseUrl: ($('#visionBaseUrl') || {}).value || '',
          visionApiKey: ($('#visionApiKey') || {}).value || '',
          visionModel: ($('#visionModel') || {}).value || '',
          visionBalancePath: ($('#visionBalancePath') || {}).value || ''
        };
        S.cfg.visionBaseUrl = vpatch.visionBaseUrl;
        S.cfg.visionApiKey = vpatch.visionApiKey;
        S.cfg.visionModel = vpatch.visionModel;
        S.cfg.visionBalancePath = vpatch.visionBalancePath;
        S.balanceVision = '';
        S.balVOff = false;
        call('saveCfg', { json: vpatch, apply: 'general' }).then(function () {
          toast('视觉模型已保存');
          render();
          if (vpatch.visionApiKey || vpatch.visionBaseUrl) {
            return call('balanceVision').then(function (r) {
              S.balanceVision = r.text; render(); toast(r.text);
            }).catch(function (e) {
              S.balanceVision = e.message || '余额查询失败'; render(); toast(S.balanceVision);
            });
          }
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'saveGeminiVideo': {
        var videoPatch = { geminiVideoModel: String(($('#geminiVideoModel') || {}).value || 'gemini-3.8-flash').trim() };
        var enteredVideoKey = String(($('#geminiVideoApiKey') || {}).value || '').trim();
        if (enteredVideoKey) videoPatch.geminiVideoApiKey = enteredVideoKey;
        S.cfg.geminiVideoModel = videoPatch.geminiVideoModel;
        if (enteredVideoKey) S.cfg.geminiVideoApiKey = enteredVideoKey;
        call('saveCfg', { json: videoPatch, apply: 'general' }).then(function () {
          toast((videoPatch.geminiVideoApiKey || S.cfg.geminiVideoApiKey) ? '视频理解设置已保存' : '模型名称已保存；还需要填写 Gemini API Key');
          render();
        }).catch(function (e) { toast(e.message || '保存视频设置失败'); });
        break;
      }
      case 'clearGeminiVideoKey': {
        S.cfg.geminiVideoApiKey = '';
        call('saveCfg', { json: { geminiVideoApiKey: '' }, apply: 'general' }).then(function () {
          toast('已清除 Gemini 视频 Key'); render();
        }).catch(function (e) { toast(e.message || '清除 Key 失败'); });
        break;
      }
      case 'saveImageGen': {
        var imagePatch = {
          imageGenProvider: ($('#imageGenProvider') || {}).value || 'openai',
          imageGenBaseUrl: ($('#imageGenBaseUrl') || {}).value || '',
          imageGenModel: ($('#imageGenModel') || {}).value || '',
          comfyUiBaseUrl: ($('#comfyUiBaseUrl') || {}).value || '',
          comfyUiWorkflow: ($('#comfyUiWorkflow') || {}).value || '',
          comfyUiPromptNodeIds: ($('#comfyUiPromptNodeIds') || {}).value || ''
        };
        var enteredImageGenKey = ($('#imageGenApiKey') || {}).value || '';
        if (enteredImageGenKey) imagePatch.imageGenApiKey = enteredImageGenKey;
        imagePatch.comfyUiWorkflow = imagePatch.comfyUiWorkflow.trim();
        S.cfg.imageGenBaseUrl = imagePatch.imageGenBaseUrl;
        if (imagePatch.imageGenApiKey) S.cfg.imageGenApiKey = imagePatch.imageGenApiKey;
        S.cfg.imageGenModel = imagePatch.imageGenModel;
        S.cfg.imageGenProvider = imagePatch.imageGenProvider;
        S.cfg.comfyUiBaseUrl = imagePatch.comfyUiBaseUrl;
        S.cfg.comfyUiWorkflow = imagePatch.comfyUiWorkflow;
        S.cfg.comfyUiPromptNodeIds = imagePatch.comfyUiPromptNodeIds;
        call('saveCfg', { json: imagePatch, apply: 'general' }).then(function () {
          var ready = imagePatch.imageGenProvider === 'comfyui'
            ? !!(imagePatch.comfyUiBaseUrl && imagePatch.comfyUiWorkflow)
            : !!((imagePatch.imageGenApiKey || S.cfg.imageGenApiKey) && imagePatch.imageGenBaseUrl && imagePatch.imageGenModel);
          toast(ready ? '图像生成设置已保存' : '已保存；补齐当前绘图方式的配置后即可生成');
          render();
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'comfyUiWorkflowImport':
        call('comfyUiWorkflowImport').then(function () { toast('请选择 ComfyUI 导出的 API 格式工作流 JSON'); })
          .catch(function (e) { toast(e.message || '无法打开工作流文件'); });
        break;
      case 'saveApi':
        var patch = {
          apiKey: ($('#apiKey') || {}).value || '',
          baseUrl: ($('#baseUrl') || {}).value || 'https://api.deepseek.com',
          model: ($('#model') || {}).value || 'deepseek-chat',
          balancePath: ($('#balancePath') || {}).value || ''
        };
        S.cfg.apiKey = patch.apiKey;
        S.cfg.baseUrl = patch.baseUrl;
        S.cfg.model = patch.model;
        S.cfg.balancePath = patch.balancePath;
        S.balOff = false;
        S.balVOff = false;
        S.balance = '';
        S.balanceVision = '';
        call('saveCfg', { json: patch, apply: 'general' }).then(function () {
          toast('已保存');
          updateTop();
          render();
          if (patch.apiKey) {
            if (!window.__balanceTimer) window.__balanceTimer = setInterval(refreshAllBalance, 30000);
            refreshVisionBalance();
            return call('balance').then(function (r) {
              S.balance = r.text; S.balOff = false; updateTop(); render(); toast(r.text);
            }).catch(function (e) {
              var m = (e && e.message) || '余额查询失败';
              if (balUnsupported(m)) { S.balOff = true; updateTop(); render(); }
              toast(m);
            });
          }
        }).catch(function (e) { toast(e.message); });
        break;
      case 'proactiveNow':
        el.disabled = true;
        call('proactiveNow').then(function (r) { toast(r.text || '她开口了'); }).catch(function (e) { toast(e.message); })
          .then(function () { el.disabled = false; });
        break;
      case 'iceAsk':
        el.disabled = true;
        call('proactiveNow').then(function (r) {
          S.iceHint = false;
          if (r.text) S.chat.push({ role: 'assistant', content: r.text, ts: Date.now() });
          render();
        }).catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      case 'iceClose':
        S.iceHint = false;
        render();
        break;
      case 'pomoStart':
        var pw = num(val('#pomoWork'), 25);
        var pr = num(val('#pomoRest'), 5);
        call('pomoStart', { work: pw, rest: pr }).then(function () {
          S.pomo = { running: true, phase: 'work', work: pw, rest: pr, endAt: Date.now() + pw * 60000 };
          render(); toast('开始专注 ' + pw + ' 分钟');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'pomoStop':
        call('pomoStop').then(function () {
          S.pomo = { running: false, phase: 'idle' };
          render(); toast('番茄钟已停止');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'saveSleep':
        var ss = ($('#sleepStart') || {}).value || '23:30';
        var se = ($('#sleepEnd') || {}).value || '07:00';
        S.cfg.sleepStart = ss; S.cfg.sleepEnd = se;
        call('saveCfg', { json: { sleepStart: ss, sleepEnd: se, sleepEnabled: S.cfg.sleepEnabled }, apply: 'general' })
          .then(function () { toast('作息已保存'); }).catch(function (e) { toast(e.message); });
        break;
      case 'openGames': openGames(); break;
      case 'openFreeActionApps': S.freeActionQuery = ''; openFreeActionApps(); break;
      case 'saveFreeActionApps': saveFreeActionApps(); break;
      case 'notifyApps': S.gameQuery = ''; openNotifyApps(); break;
      case 'saveNotifyApps': saveNotifyApps(); break;
      case 'saveGames': saveGames(); break;
      case 'familyCap':
        openFamilyCap();
        break;
      case 'familyCapSave': {
        var fr = $('#familyCapRange');
        var fv = Math.max(1, Math.min(1440, num(fr && fr.value, 60)));
        if (fv >= 1440) {
          closeModal();
          call('familySeal').catch(function () { });
          break;
        }
        call('saveCfg', { json: { familyDayCap: fv }, apply: 'general' }).then(function () {
          S.cfg.familyDayCap = fv;
          if (S.intimacy) {
            S.intimacy.dayCap = fv;
            S.intimacy.familyDayCap = fv;
            if (S.intimacy.stages && S.intimacy.stages[5]) S.intimacy.stages[5].dayCap = fv;
          }
          closeModal();
          render();
          toast('已保存：每天最多 ' + fv + ' 次');
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'savePersona':
        // 只允许改名字；说话风格、开场白、边界由系统每天 0:00 自动演化
        var pname = val('#personaName');
        if (!pname.trim()) { toast('名字不能为空'); break; }
        var persona = {};
        for (var pk in S.persona) persona[pk] = S.persona[pk];
        persona.name = pname.trim();
        call('personaSave', { persona: persona }).then(function (r) {
          S.persona = (r && r.persona) || persona;
          render(); toast('名字已保存');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'saveProfile':
        var profile = {};
        Object.keys(S.profile || {}).forEach(function (key) { profile[key] = S.profile[key]; });
        profile.age = val('#uAge'); profile.job = val('#uJob'); profile.city = val('#uCity');
        profile.likes = val('#uLikes'); profile.dislikes = val('#uDislikes');
        profile.notes = val('#uNotes'); profile.important = val('#uImportant');
        call('profileSave', { profile: profile }).then(function (r) { S.profile = r.profile || profile; toast('资料已保存'); }).catch(function (e) { toast(e.message); });
        break;
      case 'memoryAdd':
        var mt = ($('#memInput') || {}).value || '';
        if (!mt.trim()) { toast('先写点内容'); break; }
        call('memoryAdd', { text: mt }).then(function () { $('#memInput').value = ''; return loadMemories(); })
          .then(function () { render(); toast('已记住'); }).catch(function (e) { toast(e.message); });
        break;
      case 'memoryDel':
        call('memoryDel', { id: el.getAttribute('data-id') }).then(function () { return loadMemories(); })
          .then(function () { render(); toast('已删除'); }).catch(function (e) { toast(e.message); });
        break;
      case 'companionRuleApprove':
        call('companionRuleApprove', { id: el.getAttribute('data-id') }).then(function () { return loadMemories(); })
          .then(function () { render(); toast('已启用这条相处习惯'); }).catch(function (e) { toast(e.message); });
        break;
      case 'companionRuleDismiss':
        call('companionRuleDismiss', { id: el.getAttribute('data-id') }).then(function () { return loadMemories(); })
          .then(function () { render(); toast('已忽略'); }).catch(function (e) { toast(e.message); });
        break;
      case 'companionRuleDelete':
        call('companionRuleDelete', { id: el.getAttribute('data-id') }).then(function () { return loadMemories(); })
          .then(function () { render(); toast('已移除'); }).catch(function (e) { toast(e.message); });
        break;
      case 'memoryExtract':
        el.disabled = true;
        call('memoryExtract').then(function () { toast('正在整理，稍后刷新'); })
          .catch(function (e) { toast(e.message); })
          .then(function () { setTimeout(function () { el.disabled = false; loadMemories().then(render); }, 6000); });
        break;
      case 'memoryClearAll':
        confirmBox('清空全部记忆？', '会删除已记住的事、相处习惯和整理日志；人设、聊天记录和亲密度保留。清空后无法恢复。', function () {
          call('memoryClearAll').then(function () { return loadMemories(); })
            .then(function () { render(); toast('已清空全部记忆'); })
            .catch(function (e) { toast(e.message); });
        });
        break;
      case 'importRules':
        call('adImportFile').then(function () { toast('请选择规则 JSON 文件'); }).catch(function (e) { toast(e.message); });
        break;
      case 'pasteRules': openRulesPaste(); break;
      case 'clearRules':
        confirmBox('清空规则库？', '内置的跳过关键词识别仍会生效。', function () {
          call('adClear').then(function () { S.ad = { count: 0, sample: [] }; render(); toast('已清空'); });
        });
        break;
      case 'saveAdLines':
        var arr = [];
        for (var i = 0; i < 5; i++) arr.push(val('#adLine' + i));
        call('adSaveLines', { lines: arr }).then(function () { S.adLines = arr; toast('台词已保存'); }).catch(function (e) { toast(e.message); });
        break;
      case 'remType':
        var rv = el.getAttribute('data-value') || 'manual';
        var ri = $('#remType');
        if (ri) ri.value = rv;
        $$('#remTypeChips .rchip').forEach(function (b) { b.classList.toggle('on', b === el); });
        break;
      case 'addReminder':
        var title = val('#remTitle');
        if (!title.trim()) { toast('先写标题'); break; }
        var at = val('#remAt') ? new Date(val('#remAt')).getTime() : 0;
        call('reminderAdd', { title: title, detail: val('#remDetail'), type: val('#remType'), at: at || 0 })
          .then(function () { return loadReminders(); }).then(function () { render(); toast(at ? '已添加，到点提醒你' : '已添加'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'delReminder':
        call('reminderDel', { id: el.getAttribute('data-id') }).then(function () { return loadReminders(); })
          .then(function () { render(); toast('已完成'); }).catch(function (e) { toast(e.message); });
        break;
      case 'calendarSync':
        el.disabled = true;
        call('calendarSync').then(function (r) { return call('reminders').then(function (x) { S.reminders = x.reminders || []; render(); toast('同步 ' + (r.count || 0) + ' 条'); }); })
          .catch(function (e) { toast(e.message); })
          .then(function () { el.disabled = false; });
        break;
      case 'openPerm':
        call('openPerm', { kind: el.getAttribute('data-kind') }).catch(function (e) { toast(e.message); });
        break;
      case 'openPerms':
        go('health');
        break;
      case 'openUrl':
        call('openUrl', { url: el.getAttribute('data-url') || '' }).catch(function (e) { toast(e.message); });
        break;
      case 'export':
        call('export').then(function () { toast('选择保存位置'); }).catch(function (e) { toast(e.message); });
        break;
      case 'importZip':
        confirmBox('导入备份会覆盖当前 S-G 数据', '建议先导出当前数据。', function () {
          call('importZip').then(function () { toast('请选择 S-G-backup.zip'); }).catch(function (e) { toast(e.message); });
        });
        break;
      /* ---------- 陪伴阅读 ---------- */
      case 'bookImport':
        call('bookImport').catch(function (e) { toast(e.message); });
        break;
      case 'bookUrl':
        openModal('<div class="modal-head"><h3>网页链接</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
          '<div class="faint">粘贴小说章节或图书直链。会自动识别网页正文、TXT、EPUB、PDF，并保存到书架。</div>' +
          '<label class="field" style="margin-top:12px"><span>链接</span><input id="bookUrlInput" placeholder="https://…"></label>' +
          '<button class="btn primary block" data-act="bookUrlImport">识别并导入</button>');
        break;
      case 'bookUrlImport':
        var bu = ($('#bookUrlInput') || {}).value || '';
        if (!bu.trim()) { toast('先粘贴链接'); break; }
        closeModal();
        toast('正在识别图书…');
        call('bookImportUrl', { url: bu }).then(function (r) {
          reloadShelf();
          if (S.page === 'readerShelf') render();
          toast(r.ok ? '已加入书架' : (r.error || '识别失败'));
        }).catch(function (e) { toast(e.message); });
        break;
      case 'bookScan':
        toast('正在扫描本地书籍…');
        call('bookScan').then(function (r) {
          reloadShelf();
          toast(r.ok ? '扫描完成，导入 ' + num(r.imported, 0) + ' 本' : (r.error || '扫描失败'));
        }).catch(function (e) { toast(e.message); });
        break;
      case 'bookExportAll':
        call('bookExportAll').catch(function (e) { toast(e.message); });
        break;
      case 'bookRestore':
        call('bookRestore').catch(function (e) { toast(e.message); });
        break;
      case 'shelfTab':
        S.shelfTab = el.getAttribute('data-tab') || 'recent';
        render();
        break;
      case 'bookOpen':
        openBook(el.getAttribute('data-id'));
        break;
      case 'bookMenu':
        openBookMenu(el.getAttribute('data-id'));
        break;
      case 'bookSaveMeta':
        var bg = ($('#bookGroup') || {}).value || '';
        call('bookUpdate', { id: el.getAttribute('data-id'), patch: { group: bg } }).then(function () {
          closeModal(); reloadShelf(); toast('已保存');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'bookCoverPick':
        call('bookCoverPick', { id: el.getAttribute('data-id') }).catch(function (e) { toast(e.message); });
        break;
      case 'bookToggleHidden':
      case 'bookToggleFinished':
        var on = el.getAttribute('data-on') === '1';
        var patch = {};
        patch[act === 'bookToggleHidden' ? 'hidden' : 'finished'] = on;
        call('bookUpdate', { id: el.getAttribute('data-id'), patch: patch }).then(function () {
          closeModal(); reloadShelf(); toast(on ? '已更新' : '已取消');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'bookExport':
        call('bookExport', { id: el.getAttribute('data-id') || '', title: el.getAttribute('data-title') || '' }).catch(function (e) { toast(e.message); });
        break;
      case 'bookDelete':
        confirmBox('删除这本书？', '正文、进度和这本书的笔记都会从 S-G 删除。', function () {
          call('bookDelete', { id: el.getAttribute('data-id') }).then(function () {
            closeModal();
            if (S.page === 'reader') go('readerShelf');
            reloadShelf();
            toast('已删除');
          }).catch(function (e) { toast(e.message); });
        });
        break;
      case 'ttsTest':
        flushTts().then(function () { return call('ttsTest'); }).catch(function (e) { toast(e.message); });
        break;
      case 'ttsStyle': {
        var sr = parseFloat(el.getAttribute('data-rate'));
        var sp = parseFloat(el.getAttribute('data-pitch'));
        var sl = el.getAttribute('data-label') || '';
        S.cfg.ttsRate = sr;
        S.cfg.ttsPitch = sp;
        call('ttsSave', { json: { ttsRate: sr, ttsPitch: sp } }).then(function () {
          render();
          call('ttsTest', { text: '我是' + sl + '音色，好听吗？' }).catch(function () { });
          toast('已切换为' + sl + '音色');
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'weatherNow':
        call('weatherCheck').then(function () { toast('正在检查天气…'); }).catch(function (e) { toast(e.message); });
        break;
      case 'readerDrawerOpen': readerDrawerToggle(true); break;
      case 'readerDrawerClose': readerDrawerToggle(false); break;
      case 'readerSettings': readerPanelOpen('settings'); break;
      case 'readerMore': readerPanelOpen('more'); break;
      case 'readerNotes': readerPanelOpen('notes'); break;
      case 'readerHistory': readerPanelOpen('history'); break;
      case 'readerPanelClose': closeReaderPanel(); break;
      // 底栏「上一页 / 下一页」：章边界自动衔接到上/下一章
      case 'readerPrev': readerTurn(-1); break;
      case 'readerNext': readerTurn(1); break;
      case 'readerPdfRetry': loadPdfPage(); break;
      case 'readerJump':
        readerJump(el.getAttribute('data-index'), el.getAttribute('data-term') || '');
        break;
      case 'readerFontStep':
        readerFontStep(num(el.getAttribute('data-step'), 1));
        break;
      case 'readerLineSpace':
        S.bookCfg.lineHeight = flt(el.getAttribute('data-lh'), 1.85);
        saveReaderCfg(); readerApplyStyle(); readerRestyle(); syncReaderUI();
        break;
      case 'readerAnimPick':
        S.bookCfg.anim = el.getAttribute('data-anim') || 'slide';
        saveReaderCfg(); syncReaderUI();
        break;
      case 'readerNight':
        readerToggleNight();
        break;
      case 'readerTheme':
        S.bookCfg.theme = el.getAttribute('data-theme') || 'night';
        // 记住日间主题，供底栏「夜间/日间」一键切回
        if (S.bookCfg.theme !== 'night' && S.bookCfg.theme !== 'system') S.bookCfg.lightTheme = S.bookCfg.theme;
        saveReaderCfg(); readerApplyStyle(); syncReaderUI();
        break;
      case 'readerAlign':
        S.bookCfg.align = el.getAttribute('data-align') || 'justify';
        saveReaderCfg(); readerApplyStyle(); readerRestyle(); syncReaderUI();
        break;
      case 'readerFont':
        S.bookCfg.font = el.getAttribute('data-font') || 'serif';
        saveReaderCfg(); readerApplyStyle(); readerRestyle(); syncReaderUI();
        break;
      case 'readerTrad':
        S.bookCfg.traditional = !S.bookCfg.traditional;
        saveReaderCfg(); readerRestyle(); syncReaderUI();
        break;
      case 'readerAuto':
        toggleAutoReader();
        break;
      case 'readerSearch':
        readerPanelOpen('search');
        break;
      case 'readerSearchDo': doBookSearch(); break;
      case 'readerTts': readerTtsStart(); break;
      case 'readerTtsTimer':
        S.ttsTimerMin = S.ttsTimerMin === 0 ? 5 : S.ttsTimerMin === 5 ? 15 : S.ttsTimerMin === 15 ? 30 : S.ttsTimerMin === 30 ? 60 : 0;
        if (S.ttsPlaying) {
          if (S.ttsTimer) clearTimeout(S.ttsTimer);
          S.ttsTimer = S.ttsTimerMin ? setTimeout(function () { readerTtsStop(); toast('朗读定时结束'); }, S.ttsTimerMin * 60000) : null;
        }
        render();
        break;
      case 'readerBookmark':
        call('bookNoteAdd', { id: S.book.id, chapter: S.bookChapter, type: 'bookmark', quote: currentChapter().title || '', note: '' })
          .then(function () { toast('已加书签'); refreshBookNotes(); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'readerExportNotes':
        call('bookNotesExport', { id: S.book.id, title: S.book.title || '' }).catch(function (e) { toast(e.message); });
        break;
      case 'readerDelete':
        confirmBox('删除《' + (S.book.title || '这本书') + '》？', '正文、进度和笔记都会删除。', function () {
          call('bookDelete', { id: S.book.id }).then(function () {
            S.book = {}; go('readerShelf'); toast('已删除');
          }).catch(function (e) { toast(e.message); });
        });
        break;
      case 'bookNoteDel':
        call('bookNoteDel', { id: el.getAttribute('data-id') }).then(function () { toast('已删除笔记'); refreshBookNotes(); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'selHighlight':
        if (!S.readerSel) break;
        call('bookNoteAdd', { id: S.book.id, chapter: S.readerSel.chapter, type: 'highlight', quote: S.readerSel.text, note: '' })
          .then(function () { hideSelBar(); toast('已划线'); refreshBookNotes(); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'selNote':
        if (!S.readerSel) break;
        openModal('<div class="modal-head"><h3>写笔记</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
          '<div class="quote-box">' + esc(S.readerSel.text) + '</div>' +
          '<label class="field"><span>想法</span><textarea id="readerNoteText" placeholder="写点什么…"></textarea></label>' +
          '<button class="btn primary block" data-act="selNoteSave">保存笔记</button>');
        break;
      case 'selNoteSave':
        var nt = ($('#readerNoteText') || {}).value || '';
        if (!S.readerSel) break;
        call('bookNoteAdd', { id: S.book.id, chapter: S.readerSel.chapter, type: 'note', quote: S.readerSel.text, note: nt })
          .then(function () {
            closeModal(); hideSelBar(); toast('笔记已保存'); refreshBookNotes();
          }).catch(function (e) { toast(e.message); });
        break;
      case 'selCopy':
        if (S.readerSel) copyText(S.readerSel.text);
        hideSelBar();
        break;
      case 'selDict':
      case 'selTranslate':
        if (!S.readerSel) break;
        var mode = act === 'selTranslate' ? 'translate' : 'dict';
        toast(mode === 'translate' ? '正在翻译…' : '正在查词…');
        call('bookLookup', { text: S.readerSel.text, mode: mode }).then(function (r) {
          openModal('<div class="modal-head"><h3>' + (mode === 'translate' ? '翻译' : '词典') + '</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
            '<div class="lookup-text">' + esc(r.text || '') + '</div>');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'selCancel':
        hideSelBar();
        try { window.getSelection().removeAllRanges(); } catch (e2) { }
        break;
      case 'closeModal': closeModal(); break;
      /* ---------- 主动策略 ---------- */
      case 'policySave':
        var pj = {
          polNightStart: val('#polNightStart') || '23:00',
          polNightEnd: val('#polNightEnd') || '07:00',
          polQuiet: !!S.cfg.polQuiet,
          polNightBubbleOnly: !!S.cfg.polNightBubbleOnly,
          polFocusImportant: !!S.cfg.polFocusImportant,
          polMoodAffect: !!S.cfg.polMoodAffect
        };
        call('policySave', { json: pj }).then(function (r) {
          S.policy = r.policy || S.policy; render(); toast('预算已保存');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'policyClear':
        confirmBox('清空主动决策日志？', '只删除记录，不影响设置。', function () {
          call('policyClear').then(function () { S.policy.log = []; render(); toast('已清空'); });
        });
        break;
      case 'policyPause':
        var pauseMin = num(el.getAttribute('data-min'), 60);
        var toMorning = el.getAttribute('data-morning') === '1';
        call('policyPause', { minutes: pauseMin, morning: toMorning }).then(function (r) {
          S.policy = r.policy || S.policy;
          render();
          toast(toMorning ? '到明天早上都不会主动打扰' : '静默 1 小时');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'setProactiveLevel':
        var level = num(el.getAttribute('data-level'), 1);
        toggleCfgKey('proactiveLevel', level).then(function () {
          render();
          toast(level <= 0 ? '已调成安静' : level >= 2 ? '已调成黏人' : '已调成适中');
        });
        break;
      /* ---------- 权限健康 ---------- */
      case 'healthRefresh':
        call('health').then(function (r) { S.health = r.health || []; render(); toast('检查完成'); })
          .catch(function (e) { toast(e.message); });
        break;
      /* ---------- 剪贴板 ---------- */
      case 'clipAdd':
        var ct = val('#clipInput');
        if (!ct.trim()) { toast('先写点内容'); break; }
        call('clipAdd', { text: ct }).then(function () { return call('clips').then(function (r) { S.clips = r.clips || []; }); })
          .then(function () { render(); toast('已保存'); }).catch(function (e) { toast(e.message); });
        break;
      case 'clipCapture':
        call('clipCapture').then(function (r) {
          if (r && r.ok === false) { toast(r.error || '剪贴板是空的'); return; }
          return call('clips').then(function (x) { S.clips = x.clips || []; render(); toast('已收录'); });
        }).catch(function (e) { toast(e.message); });
        break;
      case 'clipDel':
        call('clipDel', { id: el.getAttribute('data-id') }).then(function () {
          S.clips = S.clips.filter(function (c) { return c.id !== el.getAttribute('data-id'); });
          render(); toast('已删除');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'clipClear':
        confirmBox('清空剪贴板记录？', '图片附件也会从 S-G 删除。', function () {
          call('clipClear').then(function () { S.clips = []; render(); toast('已清空'); }).catch(function (e) { toast(e.message); });
        });
        break;
      case 'clipAct':
        el.disabled = true;
        toast('正在总结…');
        call('clipAct', { text: el.getAttribute('data-text'), action: 'summary' }).then(function (r) {
          openModal('<div class="modal-head"><h3>AI 总结</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
            '<div style="font-size:13px;line-height:1.8;white-space:pre-wrap">' + esc(r.text || '') + '</div>');
        }).catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      /* ---------- 备份 ---------- */
      case 'backupExport':
        call('backupExport', { password: val('#backupPass') }).then(function () { toast('选择保存位置'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'backupPreview':
        S.backupPreview = null;
        call('backupPreview', { password: val('#restorePass') }).then(function () { toast('选择备份文件'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'backupApply':
        var mode = el.getAttribute('data-mode') || 'replace';
        confirmBox(mode === 'replace' ? '覆盖导入会替换当前数据' : '合并导入会保留现有数据', '导入前会自动生成一份迁移日志。', function () {
          call('backupApply', { password: val('#restorePass'), mode: mode }).then(function () { toast('选择备份文件'); })
            .catch(function (e) { toast(e.message); });
        });
        break;
      case 'deleteAllData':
        confirmBox('删除全部数据？', '本机保存的人设、聊天、记忆、提醒、任务和 API Key 都会被删除，桌宠会停止。建议先导出备份。', function () {
          confirmBox('再确认一次', '删除后无法恢复，也不能撤回。确定要删除全部数据吗？', function () {
            call('deleteAllData').then(function () {
              toast('已删除，正在重新开始');
              setTimeout(function () { location.reload(); }, 600);
            }).catch(function (e) { toast(e.message); });
          });
        });
        break;
      /* ---------- 任务 ---------- */
      case 'taskAdd':
        var tt = val('#taskTitle');
        if (!tt.trim()) { toast('先写标题'); break; }
        var due = val('#taskDue') ? new Date(val('#taskDue')).getTime() : 0;
        call('taskAdd', { title: tt, detail: val('#taskDetail'), due: due || 0, priority: num(val('#taskPriority'), 1) })
          .then(function () { return call('tasks').then(applyTasks); }).then(function () { render(); toast('已添加'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'taskDone':
        call('taskDone', { id: el.getAttribute('data-id') }).then(function () { return call('tasks').then(applyTasks); })
          .then(function () { render(); toast('已完成'); }).catch(function (e) { toast(e.message); });
        break;
      case 'taskDel':
        call('taskDel', { id: el.getAttribute('data-id') }).then(function () { return call('tasks').then(applyTasks); })
          .then(function () { render(); toast('已删除'); }).catch(function (e) { toast(e.message); });
        break;
      case 'taskFocus':
        call('focusStart', { minutes: 25, taskId: el.getAttribute('data-id'), title: el.getAttribute('data-title') })
          .then(function () { toast('开始专注 25 分钟'); call('tasks').then(applyTasks).then(render); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'focusStart':
        call('focusStart', { minutes: num(val('#focusMin'), 25) })
          .then(function () { toast('开始专注'); call('tasks').then(applyTasks).then(render); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'focusStop':
        call('focusStop').then(function () { toast('已结束专注'); call('tasks').then(applyTasks).then(render); })
          .catch(function (e) { toast(e.message); });
        break;
      /* ---------- 我们的关系：亲密度 / 日记 / 性格 ---------- */
      case 'relationRefresh':
        S.diaryPeek = false;
        call('relations').then(function (r) {
          S.intimacy = r.intimacy || {}; S.traits = r.traits || {};
          S.diary = r.diary || {}; S.timeline = r.timeline || {}; S.caring = r.caring || {};
          S.projects = r.projects || [];
          return call('diaryPeek').then(function (p) {
            S.diaryPeek = !!(p && p.peeked);
            return loadRecap();
          });
        }).then(function () {
          render(); toast('已刷新');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'setTraitSpeed':
        var tsp = num(el.getAttribute('data-speed'), 1);
        toggleCfgKey('traitSpeed', tsp).then(function () {
          S.traits.speed = tsp;
          render();
          toast(tsp <= 0 ? '性格变化调慢' : tsp >= 2 ? '性格变化加快' : '标准速度');
        });
        break;
      case 'traitsReset':
        confirmBox('重置到人格种子？',
          '会回到首次填写时的性格、说话风格和开场白；记忆、亲密度、聊天记录保留，旧数据会归档。',
          function () {
            call('traitsReset').then(function (r) {
              S.traits = r.traits || {};
              S.persona = r.persona || S.persona;
              S.emotion = r.emotion || S.emotion;
              render();
              toast('已回到人格种子');
            }).catch(function (e) { toast(e.message); });
          });
        break;
      /* ---------- 共同经历 / 相处回顾 ---------- */
      case 'momentFilter':
        S.momentFilter = el.getAttribute('data-filter') || 'all';
        render();
        break;
      case 'momentAdd':
        openMomentForm('');
        break;
      case 'momentMenu':
        openMomentMenu(el.getAttribute('data-key') || '');
        break;
      case 'momentEdit':
        openMomentForm(el.getAttribute('data-key') || '');
        break;
      case 'momentSave':
        var mKey = el.getAttribute('data-key') || '';
        var mText = val('#momentText').trim();
        if (mText.length < 2) { toast('至少写两个字'); break; }
        var mDay = val('#momentDay');
        var mNote = val('#momentNote');
        if (mKey) {
          var patch = { text: mText, note: mNote };
          if (mDay) patch.day = mDay;
          call('momentEdit', { key: mKey, patch: patch }).then(function (r) {
            S.timeline = r.timeline || S.timeline;
            closeModal(); render(); toast('已保存');
          }).catch(function (e) { toast(e.message); });
        } else {
          call('momentAdd', { text: mText, day: mDay, note: mNote }).then(function (r) {
            S.timeline = r.timeline || S.timeline;
            closeModal(); render(); toast('已记进共同经历');
          }).catch(function (e) { toast(e.message); });
        }
        break;
      case 'momentDel':
        var dKey = el.getAttribute('data-key') || '';
        confirmBox('删除这条共同经历？', '只删除这一条记录，聊天、日记和记忆都不会被改动。', function () {
          call('momentDel', { key: dKey }).then(function (r) {
            S.timeline = r.timeline || S.timeline;
            closeModal(); render(); toast('已删除');
          }).catch(function (e) { toast(e.message); });
        });
        break;
      case 'recapPeriod':
        loadRecap(el.getAttribute('data-period') || '7d').then(function () {
          render();
        }).catch(function (e) { toast(e.message); });
        break;
      case 'recapRefresh':
        call('relations').then(function (r) {
          S.intimacy = r.intimacy || S.intimacy;
          S.traits = r.traits || S.traits;
          S.caring = r.caring || S.caring;
          S.timeline = r.timeline || S.timeline;
          S.projects = r.projects || S.projects;
          return loadRecap();
        }).then(function () {
          render(); toast('已刷新');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'recapSay':
        toast('她在想…');
        call('recapSay', { period: S.recapPeriod }).then(function (r) {
          S.recapAi = r.text && r.text !== r.local ? r.text : '';
          render();
          if (!S.recapAi) toast('她把整理版说了一遍');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'doneConfirm':
        var dId = el.getAttribute('data-id') || '';
        var dOk = el.getAttribute('data-done') === '1';
        call('doneConfirm', { id: dId, done: dOk }).then(function (r) {
          S.caring = r.caring || S.caring;
          S.timeline = r.timeline || S.timeline;
          render();
          toast(dOk ? '已记进共同经历' : '已隐藏这条猜测');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'markChatMoment':
        markChatMoment(num(el.getAttribute('data-idx'), -1));
        break;
      case 'copyChat':
        copyChatText(num(el.getAttribute('data-idx'), -1));
        break;
      case 'quoteChat':
        quoteChatText(num(el.getAttribute('data-idx'), -1));
        break;
      case 'improvReply':
        // 点即兴台词 = 打开桌宠长按菜单里的聊天窗口
        if ($('#modal') && !$('#modal').classList.contains('hidden')) break;
        call('openPetChat').catch(function () {
          var improvInput = $('#chatInput');
          if (improvInput) {
            improvInput.focus();
            improvInput.scrollIntoView({ block: 'nearest' });
          }
        });
        break;
      case 'diaryWrite':
        call('diaryWrite').then(function () {
          toast('她正在写今天的日记…');
          setTimeout(function () {
            call('relations').then(function (r) {
              S.intimacy = r.intimacy || {}; S.traits = r.traits || {};
              S.diary = r.diary || {}; S.timeline = r.timeline || {}; S.caring = r.caring || {};
              render();
            }).catch(function () { });
          }, 6000);
        }).catch(function (e) { toast(e.message); });
        break;
      case 'caringDel':
        call('caringDel', { id: el.getAttribute('data-id') }).then(function () {
          S.caring.items = (S.caring.items || []).filter(function (c) { return c.id !== el.getAttribute('data-id'); });
          S.caring.total = Math.max(0, num(S.caring.total, 1) - 1);
          render(); toast('已忘记这件事');
        }).catch(function (e) { toast(e.message); });
        break;
      /* ---------- 记忆 ---------- */
      case 'memAddNew':
        var mt2 = val('#memText');
        if (!mt2.trim()) { toast('先写点内容'); break; }
        call('memAdd', { text: mt2, type: val('#memTypeSel') }).then(function () {
          return reloadMemList();
        }).then(function () {
          render(); toast('已记住');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'memDel':
        var delId = el.getAttribute('data-id');
        var delImportant = el.getAttribute('data-pinned') === '1' || el.getAttribute('data-heavy') === '1';
        confirmBox(delImportant ? '删除这条重要记忆？' : '删除这条记忆？',
          delImportant ? '它是固定/重要记忆，删除后不会再参与对话，也无法恢复。' : '删除后不会再参与对话，也无法恢复。',
          function () {
            call('memDel', { id: delId }).then(function () {
              return reloadMemList();
            }).then(function () { render(); toast('已删除'); }).catch(function (e) { toast(e.message); });
          });
        break;
      case 'memArchive':
        call('memArchive', { id: el.getAttribute('data-id'), archived: true }).then(function () {
          return reloadMemList();
        }).then(function () { render(); toast('已归档'); }).catch(function (e) { toast(e.message); });
        break;
      case 'memUnarchive':
        call('memArchive', { id: el.getAttribute('data-id'), archived: false }).then(function () {
          return reloadMemList();
        }).then(function () { render(); toast('已恢复'); }).catch(function (e) { toast(e.message); });
        break;
      case 'memPin':
        var pinId = el.getAttribute('data-id');
        var pinned = el.getAttribute('data-pinned') === '1';
        call('memPin', { id: pinId, pinned: !pinned }).then(function () {
          return reloadMemList();
        }).then(function () { render(); toast(pinned ? '已取消固定' : '已固定'); }).catch(function (e) { toast(e.message); });
        break;
      case 'memFilter':
        S.memType = el.getAttribute('data-type') || 'all';
        reloadMemList().then(render).catch(function (e) { toast(e.message); });
        break;
      case 'memArchivedToggle':
        S.memShowArchived = S.memShowArchived ? 0 : 1;
        reloadMemList().then(render).catch(function (e) { toast(e.message); });
        break;
      case 'memEditOpen':
        var editId = el.getAttribute('data-id');
        call('memGet', { id: editId }).then(function (r) {
          var m = r.memory || {};
          var types = [['fact', '事实'], ['preference', '偏好'], ['person', '人物'], ['project', '项目'],
            ['event', '事件'], ['plan', '计划'], ['concern', '惦记']];
          var opts = types.map(function (t) {
            return '<option value="' + t[0] + '"' + ((m.type || 'fact') === t[0] ? ' selected' : '') + '>' + t[1] + '</option>';
          }).join('');
          var h = '<div class="modal-head"><h3>编辑记忆</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
            '<label class="field"><span>内容</span><textarea id="memEditText" maxlength="120" style="min-height:110px">' + esc(m.text || '') + '</textarea></label>' +
            '<label class="field"><span>类型</span><select id="memEditType">' + opts + '</select></label>' +
            '<div class="faint" style="margin:8px 0">原话：' + esc(m.sourceQuote || '（没有记录原话）') + '</div>' +
            '<div class="faint">置信度 ' + Math.round(flt(m.confidence, 0.7) * 100) + '% · ' + esc(memStatusName(m.status || 'active')) + ' · ' + fmt(m.ts) + '</div>';
          var hist = r.history || [];
          if (hist.length) {
            h += '<div class="divider"></div><div class="faint" style="margin-bottom:6px">修改记录</div>';
            hist.slice().reverse().forEach(function (x) {
              h += '<div class="faint" style="margin-bottom:6px">· ' + esc(x.text || '') + '（' + esc(x.reason || '修改') + '）</div>';
            });
          }
          h += '<button class="btn primary block" style="margin-top:14px" data-act="memEditSave" data-id="' + esc(editId) + '">保存</button>';
          openModal(h);
        }).catch(function (e) { toast(e.message); });
        break;
      case 'memEditSave':
        call('memEdit', {
          id: el.getAttribute('data-id'),
          patch: { text: val('#memEditText'), type: val('#memEditType') }
        }).then(function () {
          closeModal(); return reloadMemList();
        }).then(function () { render(); toast('已保存'); }).catch(function (e) { toast(e.message); });
        break;
      case 'memCheckup':
        openModal('<div class="modal-head"><h3>记忆体检</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
          '<p class="faint" style="margin:0 0 12px">这些记忆很久没用到，可以归档或直接删除。</p>' +
          '<div class="list">' + (S.memReview || []).map(function (m) {
            return '<div class="list-item"><div class="li-main"><b style="white-space:normal">' + esc(m.text) + '</b><span>' + esc(memTypeName(m.type || 'fact')) + ' · ' + fmt(m.ts) + '</span></div>' +
              '<button class="li-act danger" data-act="memDel" data-id="' + esc(m.id) + '">删除</button></div>';
          }).join('') + '</div>' +
          '<button class="btn primary block" data-act="memKeepAll" style="margin-top:12px">都保留，标记已体检</button>');
        break;
      case 'memKeepAll':
        call('memReviewed').then(function () {
          S.memReviewDue = false; closeModal(); render(); toast('已标记体检完成');
        }).catch(function (e) { toast(e.message); });
        break;
      /* ---------- 周报 / 心情 ---------- */
      case 'reportGen':
        el.disabled = true;
        call('report').then(function (r) { S.report = r || {}; render(); toast('周报已生成'); })
          .catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      case 'moodPick':
        openModal('<div class="modal-head"><h3>现在心情</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
          '<div class="btn-row"><button class="btn" data-act="moodSet" data-mood="happy">开心</button>' +
          '<button class="btn" data-act="moodSet" data-mood="calm">平静</button><button class="btn" data-act="moodSet" data-mood="tired">累</button>' +
          '<button class="btn" data-act="moodSet" data-mood="annoyed">烦躁</button><button class="btn" data-act="moodSet" data-mood="focused">专注</button></div>');
        break;
      case 'moodSet':
        call('moodLog', { mood: el.getAttribute('data-mood') }).then(function () {
          closeModal(); toast('已记录'); call('report').then(function (r) { S.report = r || {}; render(); });
        }).catch(function (e) { toast(e.message); });
        break;
      /* ---------- 通知摘要 ---------- */
      case 'notifRefresh':
        call('notifList').then(function (r) {
          S.notif = { unread: r.unread, items: r.list || [], muted: r.muted || [] };
          S.notifGroups = r.group || [];
          S.notifAi = r.ai || {};
          render(); toast('已刷新');
          if (S.cfg.notifAi !== false) {
            call('notifAi').then(function (x) {
              S.notifAi = x.ai || S.notifAi;
              if (S.page === 'notif') render();
            }).catch(function () { });
          }
        }).catch(function (e) { toast(e.message); });
        break;
      case 'notifAppRead':
        call('notifAppRead', { pkg: el.getAttribute('data-pkg') }).then(function () {
          return call('notifList').then(function (r) {
            S.notif = { unread: r.unread, items: r.list || [], muted: r.muted || [] };
            S.notifGroups = r.group || [];
          });
        }).then(render).catch(function (e) { toast(e.message); });
        break;
      case 'notifMute':
        var onMute = el.getAttribute('data-on') === '1';
        call('notifMute', { pkg: el.getAttribute('data-pkg'), on: onMute }).then(function () {
          S.cfg.notifMuted = S.cfg.notifMuted || [];
          if (onMute) S.cfg.notifMuted.push(el.getAttribute('data-pkg'));
          else S.cfg.notifMuted = S.cfg.notifMuted.filter(function (p) { return p !== el.getAttribute('data-pkg'); });
          render(); toast(onMute ? '已静音' : '已取消静音');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'notifSnooze':
        call('notifSnooze', { key: el.getAttribute('data-key'), minutes: 10 }).then(function () {
          return call('notifList').then(function (r) {
            S.notif = { unread: r.unread, items: r.list || [], muted: r.muted || [] };
            S.notifGroups = r.group || [];
            render();
          });
        }).then(function () { toast('10 分钟后再提醒'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'notifToTask':
        call('notifToTask', { key: el.getAttribute('data-key') }).then(function () {
          return call('notifList').then(function (r) {
            S.notif = { unread: r.unread, items: r.list || [], muted: r.muted || [] };
            S.notifGroups = r.group || [];
            render();
          });
        }).then(function () { toast('已转为待办'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'notifClear':
        confirmBox('清空通知摘要？', '不会删除系统里的原始通知。', function () {
          call('notifClear').then(function () { S.notif = { unread: 0, items: [] }; S.notifGroups = []; render(); toast('已清空'); });
        });
        break;
      /* ---------- 位置 ---------- */
      case 'geoPoint':
        el.disabled = true;
        call('geoPoint', { kind: el.getAttribute('data-kind'), name: '' }).then(function (r) {
          if (r && r.ok === false) { toast(r.message || '定位失败'); return; }
          return call('geoState').then(function (x) { S.geo = x.geo || {}; render(); toast('已设置'); });
        }).catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      case 'geoAdd':
        var placeName = val('#geoPlaceName').trim();
        if (!placeName) { toast('先写一个地点名称'); break; }
        el.disabled = true;
        call('geoPoint', { kind: 'p_' + Date.now().toString(36), name: placeName }).then(function (r) {
          if (r && r.ok === false) { toast(r.message || '正在定位'); return; }
          return call('geoState').then(function (x) { S.geo = x.geo || {}; render(); toast('地点已添加'); });
        }).catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      case 'geoDelete':
        var geoId = el.getAttribute('data-id');
        confirmBox('删除这个地点？', '删除后不会再收到它的到达提醒。', function () {
          call('geoDelete', { id: geoId }).then(function () { return call('geoState'); })
            .then(function (x) { S.geo = x.geo || {}; render(); toast('已删除'); })
            .catch(function (e) { toast(e.message); });
        });
        break;
      /* ---------- 健康 ---------- */
      case 'wellSave':
        var wj = {
          wellSitMin: num(val('#wellSitMin'), 50),
          wellWaterMin: num(val('#wellWaterMin'), 90),
          wellEyeMin: num(val('#wellEyeMin'), 45)
        };
        call('wellnessSave', { json: wj }).then(function () { S.cfg.wellSitMin = wj.wellSitMin; S.cfg.wellWaterMin = wj.wellWaterMin; S.cfg.wellEyeMin = wj.wellEyeMin; toast('已保存'); })
          .catch(function (e) { toast(e.message); });
        break;
      case 'wellPlay':
        call('preview', { slug: el.getAttribute('data-action') }).then(function () { toast('正在播放'); }).catch(function (e) { toast(e.message); });
        break;
      case 'wellDo':
        var wt = el.getAttribute('data-type');
        call('wellnessLog', { type: wt, action: 'manual' }).then(function () {
          if (wt === 'stretch') return call('preview', { slug: 'a005' });
        }).then(function () { return call('wellness').then(function (r) { S.well = r.well || {}; S.wellSteps = r.steps || []; render(); }); })
          .catch(function (e) { toast(e.message); });
        break;
      /* ---------- 音乐 ---------- */
      case 'musicPrev':
        call('musicxPrev').then(function (r) { toast(r.ok ? '已切换到上一首' : '当前没有可控播放器'); }).catch(function (e) { toast(e.message); });
        break;
      case 'musicNext':
        call('musicxNext').then(function (r) { toast(r.ok ? '已切换到下一首' : '当前没有可控播放器'); }).catch(function (e) { toast(e.message); });
        break;
      case 'musicTr':
        el.disabled = true; toast('正在翻译…');
        call('musicxTr').then(function (r) { openModal('<div class="modal-head"><h3>歌词翻译</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div><div style="font-size:13px;line-height:1.8">' + esc((r && r.text) || '') + '</div>'); })
          .catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      case 'noise':
        call('musicxNoise', { kind: el.getAttribute('data-kind') }).then(function () {
          return call('musicx').then(function (r) { S.musicx = r.musicx || {}; render(); });
        }).catch(function (e) { toast(e.message); });
        break;
      /* ---------- 插件 ---------- */
      case 'pluginImport':
        call('pluginImport').then(function () { toast('选择插件 JSON 或 ZIP'); }).catch(function (e) { toast(e.message); });
        break;
      case 'astrbotGuide':
        openModal('<div class="modal-head"><h3>远端 AstrBot 上手步骤</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 18L6 6"/></svg></button></div>' +
          '<div class="faint" style="line-height:1.8">1. 在 Windows 电脑安装并启动 AstrBot Launcher，登录密码从启动日志查看；先修改初始密码。<br>' +
          '2. 先在 AstrBot「模型提供商 → 对话 → 新增」配置模型服务商，保存并获取模型后启用；再到「配置文件 → AI → 模型」选中它并保存。先确认 AstrBot 自带聊天能回复。<br>' +
          '3. 手机与电脑连同一个可信 Wi-Fi。电脑端设置 AstrBot 的 dashboard.host 为 0.0.0.0、端口 6185；Windows 防火墙只允许“专用网络”访问 TCP 6185。不要把端口开放到公网。<br>' +
          '4. 在电脑运行 ipconfig，找到 Wi-Fi IPv4 地址；手机地址填写 http://电脑IPv4:6185，不要填 127.0.0.1。跨公网访问须使用有效 HTTPS 域名。<br>' +
          '5. AstrBot「设置 → OpenAPI」创建 API Key，勾选 chat 和 plugin。更换服务器地址时填写新服务器的 Key。<br>' +
          '6. 回到本页保存地址和 Key，检查连接；然后搜索插件、确认来源与依赖，再交给 AstrBot 安装并查看实际状态。<br>' +
          '7. 如需让 AstrBot 操作手机，点“导出配套 AstrBot 插件”，把 ZIP 传到电脑并解压到 AstrBot 的 data/plugins/astrbot_plugin_dsh_mobile，然后重载插件，并在当前人格中允许 dsh_mobile_action 工具。<br>8. 启用远端智能体后，点“检查桥接”，再单独启用手机工具。工具只在每轮聊天期间按需连接；每一步要在桌宠浮窗点“允许这一步”。读取屏幕文字会发送给 AstrBot；QQ 群消息不会转发或操作手机。桌宠文本和本应用 QQ 私聊可共用 AstrBot 会话。</div>' +
          '<div class="notice" style="margin-top:10px">连不上时先查同 Wi-Fi、电脑 IPv4、AstrBot 是否运行、dashboard.host 和专用网络防火墙。HTTP 403 通常表示 API Key 少了 chat 或 plugin scope。</div>' +
          '<div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="openUrl" data-url="https://docs.astrbot.app/deploy/astrbot/launcher.html">AstrBot 安装指南</button><button class="btn ghost" data-act="openUrl" data-url="https://docs.astrbot.app/providers/start.html">模型接入指南</button><button class="btn ghost" data-act="openUrl" data-url="https://docs.astrbot.app/dev/openapi.html">OpenAPI 指南</button></div>');
        break;
      case 'pluginDshBoxRefresh':
        el.disabled = true;
        call('dshboxStatus').then(function (r) { S.dshbox = r || {}; render(); toast('已重新检测 DSHBox'); })
          .catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      case 'astrbotRefresh':
        el.disabled = true;
        call('astrbotApiPlugins').then(function (r) {
          S.astrbotApiPlugins = r || {};
          return call('astrbotConfig');
        }).then(function (r) {
          S.astrbotConfig = r || {};
          render();
          if (S.astrbotApiPlugins.ok) toast('远端 AstrBot 已连通');
          else toast(S.astrbotApiPlugins.error || 'AstrBot 连接检查失败');
        }).catch(function (e) { toast(e.message || '连接检查失败'); }).then(function () { el.disabled = false; });
        break;
      case 'astrbotConfigOpen': {
        var currentAstrConfig = S.astrbotConfig || {};
        openModal('<div class="modal-head"><h3>连接远端 AstrBot</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 18L6 6"/></svg></button></div>' +
          '<div class="faint" style="margin-bottom:10px">填写 AstrBot 地址，例如 https://astrbot.example.com、局域网 http://192.168.1.20:6185，或本机运行管理器 http://127.0.0.1:6185。公网连接使用 HTTPS；API Key 请授予 chat、plugin 权限。</div>' +
          '<input id="astrbotBaseUrl" type="url" autocomplete="url" value="' + esc(currentAstrConfig.baseUrl || '') + '" placeholder="https://你的 AstrBot 地址" style="width:100%;padding:13px;border-radius:13px;border:1px solid var(--line);background:rgba(0,0,0,.25);color:var(--ink)">' +
          '<input id="astrbotApiKey" type="password" autocomplete="off" placeholder="OpenAPI Key（同一服务可留空；更换服务时填写新 Key）" style="width:100%;margin-top:8px;padding:13px;border-radius:13px;border:1px solid var(--line);background:rgba(0,0,0,.25);color:var(--ink)">' +
          '<div class="btn-row" style="margin-top:12px"><button class="btn primary" data-act="astrbotConfigSave">保存并检查连接</button></div>');
        break;
      }
      case 'astrbotConfigSave': {
        var astrBase = val('#astrbotBaseUrl').trim();
        var astrKey = val('#astrbotApiKey').trim();
        call('astrbotApiConfigure', { baseUrl: astrBase, apiKey: astrKey }).then(function (r) {
          if (!r || !r.ok) throw new Error((r && r.error) || '配置保存失败');
          return call('astrbotApiPlugins');
        }).then(function (r) {
          S.astrbotApiPlugins = r;
          return call('astrbotConfig').then(function (config) { S.astrbotConfig = config || {}; closeModal(); render(); });
        }).then(function () {
          if (!S.astrbotApiPlugins.ok) toast('已保存，但 API 检查失败：' + (S.astrbotApiPlugins.error || '未知错误'));
          else toast('远端 AstrBot 已连接');
        }).catch(function (e) { toast(e.message || '连接失败'); });
        break;
      }
      case 'astrbotAgentToggle': {
        var agentOn = !(S.astrbotConfig && S.astrbotConfig.agentEnabled);
        if (agentOn) {
          openModal('<div class="modal-head"><h3>启用远端智能体</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 18L6 6"/></svg></button></div>' +
            '<div class="notice">开启后，桌宠普通文字与本应用收到的 QQ 私聊文字会发送到你配置的 AstrBot 服务，并共用稳定的 WebChat 用户与会话。本机长期记忆或人设变化后，下一轮会附加更新后的限长摘要到同一会话，不上传整份聊天记录。直接接入 AstrBot 的原生 QQ 会话仍是另一条会话，本应用也不支持由 AstrBot 原生接管 QQ。QQ 群消息默认留在手机本机、按群隔离，只有为群聊开启「群聊使用 AstrBot 插件」时才会发送；桌宠聊天图片、文件和屏幕内容不会发送给 AstrBot；经 NapCat 收到的 QQ 图片由手机视觉模型识别并留在本机聊天。QQ 回复目前只发文字。</div>' +
            '<div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="closeModal">暂不启用</button><button class="btn primary" data-act="astrbotAgentEnableConfirm">启用并共享</button></div>');
          break;
        }
        call('astrbotAgentEnable', { enabled: agentOn }).then(function (r) {
          if (!r || !r.ok) throw new Error((r && r.error) || '无法切换远端智能体');
          return call('astrbotConfig');
        }).then(function (r) { S.astrbotConfig = r || {}; render(); toast(agentOn ? '桌宠文本与 QQ 私聊将共用 AstrBot 会话' : '已关闭远端智能体，恢复本机聊天模型'); })
          .catch(function (e) { toast(e.message || '无法切换远端智能体'); });
        break;
      }
      case 'astrbotAgentEnableConfirm':
        closeModal();
        call('astrbotAgentEnable', { enabled: true }).then(function (r) {
          if (!r || !r.ok) throw new Error((r && r.error) || '无法启用远端智能体');
          return call('astrbotConfig');
        }).then(function (r) { S.astrbotConfig = r || {}; render(); toast('桌宠文本与 QQ 私聊将共用 AstrBot 会话'); })
          .catch(function (e) { toast(e.message || '无法启用远端智能体'); });
        break;
      case 'astrbotMobilePluginExport':
        call('astrbotMobilePluginExport').then(function () { toast('请选择保存位置；然后把 ZIP 传到运行 AstrBot 的电脑'); })
          .catch(function (e) { toast(e.message || '无法导出配套插件'); });
        break;
      case 'astrbotMobileBridgeCheck':
        el.disabled = true;
        call('astrbotMobileBridgeStatus').then(function (r) {
          S.astrbotMobileBridgeStatus = r || { ok: false, error: '没有收到桥接状态' };
          render();
          if (S.astrbotMobileBridgeStatus.ok && S.astrbotMobileBridgeStatus.available) toast('DSH 手机桥接插件已加载');
          else toast(S.astrbotMobileBridgeStatus.error || '未检测到配套 AstrBot 插件');
        }).catch(function (e) {
          S.astrbotMobileBridgeStatus = { ok: false, error: e.message || '检查桥接失败' };
          render();
          toast(S.astrbotMobileBridgeStatus.error);
        }).then(function () { if (el) el.disabled = false; });
        break;
      case 'astrbotMobileToolsToggle': {
        var mobileToolsOn = !(S.astrbotConfig && S.astrbotConfig.mobileToolsEnabled);
        if (!mobileToolsOn) {
          call('astrbotMobileToolsEnable', { enabled: false }).then(function () {
            return call('astrbotConfig');
          }).then(function (r) { S.astrbotConfig = r || {}; render(); toast('手机工具已关闭'); })
            .catch(function (e) { toast(e.message || '关闭手机工具失败'); });
          break;
        }
        call('astrbotMobileBridgeStatus').then(function (r) {
          S.astrbotMobileBridgeStatus = r || { ok: false, error: '未收到桥接状态' };
          render();
          if (!r || !r.ok || !r.available) throw new Error((r && r.error) || '请先导出并安装 AstrBot 配套插件，再重载并允许 dsh_mobile_action 工具');
          openModal('<div class="modal-head"><h3>允许 AstrBot 请求手机操作</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 18L6 6"/></svg></button></div>' +
            '<div class="notice">开启后，只有桌宠或 QQ 私聊正在进行一轮远端 AstrBot 对话时，手机才短时等待手机工具请求。读取屏幕会把当前应用可见文字发送到你配置的 AstrBot。打开应用、点击文字、滑动和返回都要在桌宠浮窗逐次确认；离开页面、拒绝或超时就取消。工具没有任意坐标、Shell、脚本或 DEX 执行能力。群聊不会触发手机工具。</div>' +
            '<div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="closeModal">暂不启用</button><button class="btn primary" data-act="astrbotMobileToolsEnableConfirm">启用手机工具</button></div>');
        }).catch(function (e) { toast(e.message || '手机桥接未就绪'); });
        break;
      }
      case 'astrbotMobileToolsEnableConfirm':
        closeModal();
        call('astrbotMobileToolsEnable', { enabled: true }).then(function (r) {
          if (!r || !r.ok) throw new Error((r && r.error) || '无法启用手机工具');
          return call('astrbotConfig');
        }).then(function (r) { S.astrbotConfig = r || {}; render(); toast('手机工具已启用；每一步仍需浮窗确认'); })
          .catch(function (e) { toast(e.message || '无法启用手机工具'); });
        break;
      case 'astrbotPlugins':
        call('astrbotApiPlugins').then(function (r) {
          S.astrbotApiPlugins = r; render();
          if (!r.ok) toast(r.error || '插件列表读取失败');
          else toast('已读取 AstrBot 插件状态');
        }).catch(function (e) { toast(e.message || '插件列表读取失败'); });
        break;
      case 'astrbotApiMarket': {
        S.astrbotMarketKeyword = val('#astrbotMarketKeyword').trim();
        S.astrbotApiMarketPage = 1;
        el.disabled = true; el.textContent = '正在搜索…';
        call('astrbotApiMarket', { page: S.astrbotApiMarketPage || 1, keyword: S.astrbotMarketKeyword }).then(function (r) {
          S.astrbotApiMarket = r || { ok: false, error: 'AstrBot 市场无响应' };
          S.astrbotApiMarketPage = Math.max(1, parseInt(S.astrbotApiMarket.page || 1, 10) || 1);
          render();
          if (!S.astrbotApiMarket.ok) toast(S.astrbotApiMarket.error || 'AstrBot 市场读取失败');
        }).catch(function (e) { toast(e.message || 'AstrBot 市场读取失败'); })
          .then(function () { if (el) { el.disabled = false; el.textContent = '搜索'; } });
        break;
      }
      case 'astrbotApiMarketPage': {
        var marketDelta = parseInt(el.getAttribute('data-page-delta') || '0', 10);
        S.astrbotApiMarketPage = Math.max(1, (S.astrbotApiMarketPage || 1) + marketDelta);
        el.disabled = true;
        call('astrbotApiMarket', { page: S.astrbotApiMarketPage, keyword: S.astrbotMarketKeyword || '' }).then(function (r) {
          S.astrbotApiMarket = r || { ok: false, error: 'AstrBot 市场无响应' };
          S.astrbotApiMarketPage = Math.max(1, parseInt(S.astrbotApiMarket.page || 1, 10) || 1);
          render();
          if (!S.astrbotApiMarket.ok) toast(S.astrbotApiMarket.error || 'AstrBot 市场读取失败');
        }).catch(function (e) { toast(e.message || 'AstrBot 市场读取失败'); });
        break;
      }
      case 'astrbotInstallMarketOpen': {
        var marketIndex = parseInt(el.getAttribute('data-market-index') || '-1', 10);
        var marketEntry = astrbotMarketEntries(S.astrbotApiMarket)[marketIndex];
        var marketRepo = astrbotMarketRepository(marketEntry);
        if (!marketEntry || !marketRepo) { toast('该记录没有受支持的 GitHub 仓库地址'); break; }
        var marketId = astrbotMarketIdentity(marketEntry);
        var marketRegistry = astrbotMarketRegistry(marketEntry, S.astrbotApiMarket);
        if (!marketId) { toast('AstrBot 返回的市场记录缺少插件身份，已阻止安装'); break; }
        var marketEntryMeta = marketEntry.metadata || marketEntry.meta || {};
        S.astrbotInstallPending = {
          repository: marketRepo,
          pluginId: marketId,
          registryUrl: marketRegistry,
          downloadUrl: /^https:\/\/[^\s]+$/.test(String(marketEntry.download_url || marketEntryMeta.download_url || '')) ? (marketEntry.download_url || marketEntryMeta.download_url) : '',
          name: marketEntry.display_name || marketEntry.title || marketEntry.name || marketEntryMeta.display_name || marketEntryMeta.name || marketId || '未命名插件',
          version: marketEntry.version || marketEntry.latest_version || marketEntryMeta.version || '未标注',
          dependencies: marketEntry.dependencies || marketEntry.python_dependencies || marketEntry.pip_dependencies || marketEntry.requirements || marketEntryMeta.dependencies || []
        };
        var pendingDeps = S.astrbotInstallPending.dependencies;
        if (!Array.isArray(pendingDeps)) pendingDeps = typeof pendingDeps === 'string' ? [pendingDeps] : Object.keys(pendingDeps || {});
        openModal('<div class="modal-head"><h3>安装前确认</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 18L6 6"/></svg></button></div>' +
          '<div class="notice"><b>' + esc(S.astrbotInstallPending.name) + '</b> · v' + esc(S.astrbotInstallPending.version) + '<br><span class="faint" style="word-break:break-all">插件身份：' + esc(marketId) + '</span><br><span class="faint" style="word-break:break-all">市场：' + esc(marketRegistry || 'AstrBot 默认插件市场') + '</span><br><span class="faint" style="word-break:break-all">仓库：' + esc(marketRepo) + '</span>' +
          '<br><span class="faint">依赖：' + esc(pendingDeps.length ? pendingDeps.join(', ') : '未声明') + '</span></div>' +
          '<div class="notice" style="margin-top:10px;color:#ffe4b8">继续后，AstrBot 会从该仓库下载插件并在 AstrBot 进程中加载其代码；插件可能安装额外依赖，并获得 AstrBot 进程可访问的权限。仅对你信任的来源继续。DSH 会在安装后重新读取已安装与失败列表。</div>' +
          '<div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="closeModal">取消</button><button class="btn primary" data-act="astrbotInstallMarketConfirm">确认并交给 AstrBot 安装</button></div>');
        break;
      }
      case 'astrbotInstallMarketConfirm': {
        var pendingInstall = S.astrbotInstallPending;
        if (!pendingInstall || !pendingInstall.repository) { toast('安装信息已失效，请重新选择插件'); break; }
        el.disabled = true; el.textContent = 'AstrBot 安装中…';
        call('astrbotApiInstallGithub', { repository: pendingInstall.repository, pluginId: pendingInstall.pluginId || '', registryUrl: pendingInstall.registryUrl || '', downloadUrl: pendingInstall.downloadUrl || '' }).then(function (r) {
          if (!r || !r.ok) throw new Error((r && r.error) || 'AstrBot 插件安装失败');
          r.repository = pendingInstall.repository;
          r.pluginId = pendingInstall.pluginId;
          S.astrbotInstallResult = r;
          S.astrbotApiPlugins = r.installedState || null;
          closeModal(); render();
          if (r.verification === 'loaded') toast('AstrBot 明确报告插件已加载');
          else if (r.verification === 'disabled') toast('已找到插件，但 AstrBot 报告其已停用');
          else if (r.verification === 'installed') toast('已在安装列表中找到插件，运行状态未提供');
          else if (r.verification === 'failed') toast('AstrBot 安装后报告加载失败，请查看最近安装状态');
          else toast('安装请求已返回，尚未能确认插件状态');
          if (r.activation === 'failed') toast('插件已安装，但自动启用失败：' + (r.activationError || '未知原因'));
        }).catch(function (e) { toast(e.message || 'AstrBot 插件安装失败'); })
          .then(function () { if (el) { el.disabled = false; el.textContent = '确认并交给 AstrBot 安装'; } });
        break;
      }
      case 'astrbotPluginToggle': {
        var toggleName = el.getAttribute('data-plugin-name') || '';
        var toggleOn = el.getAttribute('data-enabled') === '1';
        if (!toggleName) { toast('缺少插件标识，无法切换'); break; }
        el.disabled = true;
        call('astrbotPluginEnable', { pluginName: toggleName, enabled: toggleOn }).then(function (r) {
          toast(toggleOn ? ('已启用 ' + toggleName + '；现在可以在聊天里使用它的指令') : ('已停用 ' + toggleName));
          render();
          call('astrbotApiPlugins').then(function (state) {
            S.astrbotApiPlugins = state || {};
            render();
          }).catch(function () { });
        }).catch(function (e) { toast(e.message || '切换插件状态失败'); })
          .then(function () { if (el) el.disabled = false; });
        break;
      }
      case 'astrbotInstallVerify': {
        var lastInstall = S.astrbotInstallResult || {};
        if (!lastInstall.repository) { toast('没有可核验的安装记录'); break; }
        el.disabled = true; el.textContent = '正在核验…';
        call('astrbotApiVerifyInstall', { repository: lastInstall.repository, pluginId: lastInstall.pluginId || '' }).then(function (r) {
          if (!r || !r.ok) throw new Error((r && r.error) || '核验失败');
          lastInstall.verification = r.verification || 'pending';
          lastInstall.failedMessage = r.failedMessage || '';
          S.astrbotApiPlugins = r.installedState || S.astrbotApiPlugins;
          render();
          if (lastInstall.verification === 'loaded') toast('AstrBot 明确报告插件已加载');
          else if (lastInstall.verification === 'disabled') toast('已找到插件，但 AstrBot 报告其已停用');
          else if (lastInstall.verification === 'installed') toast('已找到插件，但运行状态未提供');
          else if (lastInstall.verification === 'failed') toast('AstrBot 报告插件加载失败');
          else toast('仍未能确认插件状态');
        }).catch(function (e) { toast(e.message || '核验失败'); })
          .then(function () { if (el) { el.disabled = false; el.textContent = '重新核验安装状态'; } });
        break;
      }
      case 'pluginDshBoxAnalyzeOpen':
        openDshBoxAnalyze();
        break;
      case 'pluginDshBoxAnalyze': {
        var dshboxTask = val('#dshBoxTask').trim();
        if (!dshboxTask) { toast('先写下插件问题或贴入报错'); break; }
        el.disabled = true;
        el.textContent = '正在分析…';
        call('dshboxAnalyze', { text: dshboxTask }).then(function (r) {
          var resultText = (r && r.text) || 'DSHBox 没有返回分析内容。';
          openModal('<div class="modal-head"><h3>DSHBox 分析结果</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
            '<div style="white-space:pre-wrap;word-break:break-word;max-height:62vh;overflow:auto;line-height:1.75;font-size:13px">' + esc(resultText) + '</div>' +
            '<div class="faint" style="margin-top:12px">这是 DSHBox 的建议；实际安装或修改仍需回到桌宠插件流程检查。</div>');
        }).catch(function (e) { toast(e.message || 'DSHBox 分析失败'); })
          .then(function () { if (el) { el.disabled = false; el.textContent = '开始分析'; } });
        break;
      }
      case 'chatPick':
        closeChatAttachments();
        call('chatPickImage').then(function () { toast('选一张图片'); }).catch(function (e) { toast(e.message); });
        break;
      case 'chatCamera':
        closeChatAttachments();
        call('chatTakePhoto').catch(function (e) { toast(e.message || '无法打开相机'); });
        break;
      case 'chatFile':
        closeChatAttachments();
        call('chatPickFile').catch(function (e) { toast(e.message || '无法打开文件选择器'); });
        break;
      case 'chatPickVideo':
        closeChatAttachments();
        call('chatPickVideo').then(function () { toast('选择视频；完整视频会在发送后交给 Gemini 分析'); })
          .catch(function (e) { toast(e.message || '无法打开视频选择器'); });
        break;
      case 'toggleChatAttachments':
        toggleChatAttachments();
        break;
      case 'stickerPanel':
        toggleStickerPanel(el.getAttribute('data-manage') === '1');
        break;
      case 'stickerManageClose':
        S.stickerManageOpen = false;
        closeStickerPanel();
        break;
      case 'sendSticker':
        sendSticker(el.getAttribute('data-name'));
        break;
      case 'chatImgClear':
        S.chatImg = '';
        render();
        break;
      case 'chatFileClear':
        S.chatFile = null;
        render();
        break;
      case 'chatVideoClear':
        if (S.chatVideo && S.chatVideo.id) call('chatVideoDelete', { id: S.chatVideo.id }).catch(function () { });
        S.chatVideo = null;
        render();
        break;
      case 'pluginToggle':
        var toggleId = el.getAttribute('data-id'), wantOn = !!el.checked, toggleItem = null;
        (S.plugins || []).some(function (p) { if (p.id === toggleId) { toggleItem = p; return true; } return false; });
        function applyPluginToggle() {
          call('pluginToggle', { id: toggleId, on: wantOn, nativeConfirmed: !!(wantOn && toggleItem && toggleItem.native) }).then(function () { toast(wantOn ? '已启用' : '已停用'); })
            .catch(function (e) {
              el.checked = !wantOn;
              toast(e.message);
              if (wantOn) call('plugins').then(function (r) { S.plugins = r.plugins || []; render(); });
            });
        }
        if (wantOn && toggleItem && toggleItem.native) {
          el.checked = false;
          confirmBox('启用原生插件？', '它会在桌宠进程中运行，拥有与应用相同的权限。本机只做了静态检查，没有试跑；请只启用你信任的插件。', applyPluginToggle);
        } else applyPluginToggle();
        break;
      case 'pluginDel':
        confirmBox('删除插件？', '只会删除插件配置，不影响聊天和记忆。', function () {
          call('pluginDel', { id: el.getAttribute('data-id') }).then(function () {
            S.plugins = (S.plugins || []).filter(function (p) { return p.id !== el.getAttribute('data-id'); });
            render(); toast('已删除');
          }).catch(function (e) { toast(e.message); });
        });
        break;
      /* ---------- 今日使用 ---------- */
      case 'usageRefresh':
        loadUsage().then(function () { toast('已刷新'); });
        break;
      /* ---------- 记账 ---------- */
      case 'ledCat':
        S.ledCat = el.getAttribute('data-cat') || '其他';
        render();
        break;
      case 'ledType':
        S.ledType = el.getAttribute('data-type') === 'income' ? 'income' : 'expense';
        render();
        break;
      case 'ledgerAdd': {
        var lamt = flt(val('#ledAmt'), 0);
        if (!(lamt > 0)) { toast('金额要大于 0'); break; }
        call('ledgerAdd', { amount: lamt, type: S.ledType, cat: S.ledCat, note: val('#ledNote') }).then(function (r) {
          S.ledger = r.ledger || S.ledger;
          render();
          toast((r.result && r.result.over) || '已记下');
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'ledgerBudget': {
        var lb = flt(val('#ledBudget'), 0);
        if (lb < 0) { toast('预算不能是负数'); break; }
        call('ledgerCfg', { monthly: lb, auto: !!(S.ledger && S.ledger.auto) }).then(function (r) {
          S.ledger = r.ledger || S.ledger;
          render();
          toast('预算已保存');
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'ledgerAuto':
        call('ledgerCfg', { monthly: (S.ledger && S.ledger.budget) || 0, auto: !!el.checked }).then(function (r) {
          S.ledger = r.ledger || S.ledger;
          render();
          toast(el.checked ? '自动记账已开启' : '自动记账已关闭');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'ledgerDel':
        confirmBox('删除这笔记录？', '只删除本地账本里的这一笔，不影响预算。', function () {
          call('ledgerDel', { id: el.getAttribute('data-id') }).then(function (r) {
            S.ledger = r.ledger || S.ledger;
            render();
            toast('已删除');
          }).catch(function (e) { toast(e.message); });
        });
        break;
      /* ---------- 应用感知 ---------- */
      case 'monitorRefresh':
        loadMonitor().then(function () { toast('已刷新'); });
        break;
      case 'monitorToggle': {
        var mon = !!el.checked;
        call('saveCfg', { json: { watchApp: mon } }).then(function () {
          S.cfg.watchApp = mon;
          return call('monitor');
        }).then(function (r) {
          S.monitor = r.monitor || {};
          S.monitorPkg = r.pkg || '';
          S.monitorPkgName = r.pkgName || r.pkg || '';
          S.monitorText = r.text || '';
          if (S.page === 'monitor') render();
          toast(mon ? '她开始留意了' : '已关闭应用内感知');
        }).catch(function (e) { toast(e.message); });
        break;
      }
      case 'monitorClear':
        confirmBox('清空她的观察记录？', '清空后她不记得你最近在应用里做过什么，聊天和账本不受影响。', function () {
          call('monitorClear').then(function (r) {
            S.monitor = r.monitor || {};
            render();
            toast('已清空');
          }).catch(function (e) { toast(e.message); });
        });
        break;
      /* ---------- 表情包 ---------- */
      case 'stickerImport':
        call('stickerImport').then(function () { toast('选择一张表情图片'); }).catch(function (e) { toast(e.message); });
        break;
      case 'stickerDel':
        confirmBox('删除这个表情包？', '删除后她就不会在聊天里再用它。', function () {
          call('stickerDel', { id: el.getAttribute('data-id') }).then(function (r) {
            S.stickers = r.stickers || [];
            if (S.page === 'chat') refreshStickerPanel(); else render();
            toast('已删除');
          }).catch(function (e) { toast(e.message); });
        });
        break;
      case 'stickerBatch':
        call('stickerBatchImport').then(function () { toast('选择多张表情图片'); }).catch(function (e) { toast(e.message); });
        break;
      case 'stickerDescribeAll':
        el.disabled = true;
        call('stickerDescribeAll').then(function () { toast('开始逐个识别…'); }).catch(function (e) {
          el.disabled = false;
          toast(e.message);
        });
        break;
      case 'stickerOpen':
        stickerOpen(el.getAttribute('data-id'));
        break;
      case 'stickerRefresh':
        call('stickerDescribe', { id: el.getAttribute('data-id') }).then(function () {
          toast('正在重新识别…');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'stickerReplace':
        call('stickerReplace', { id: el.getAttribute('data-id') }).then(function () {
          toast('选择新的图片');
        }).catch(function (e) { toast(e.message); });
        break;
      case 'stickerEdit':
        stickerEditDesc(el.getAttribute('data-id'));
        break;
      /* ---------- 同步 ---------- */
      case 'syncSave':
        var sj = { davUrl: val('#davUrl'), davUser: val('#davUser'), syncAuto: !!S.cfg.syncAuto };
        if (val('#davPass')) sj.davPass = val('#davPass');
        if (val('#syncPass')) sj.syncPass = val('#syncPass');
        call('syncSave', { json: sj }).then(function () {
          return call('syncState').then(function (r) { S.sync = r.sync || {}; render(); toast('配置已保存'); });
        }).catch(function (e) { toast(e.message); });
        break;
      case 'syncTest':
      case 'syncUpload':
      case 'syncDownload':
        el.disabled = true; toast('正在处理…');
        call(act).then(function (r) { toast(r.message || '完成'); return call('syncState').then(function (x) { S.sync = x.sync || {}; render(); }); })
          .catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      /* ---------- 开发者 ---------- */
      case 'devRefresh':
        call('devState').then(function (r) { S.dev = r; S.ad = r.ad || S.ad; render(); toast('已刷新'); }).catch(function (e) { toast(e.message); });
        break;
      case 'diagExport':
        call('diagExport').then(function () { toast('诊断已写入 S-G/diag_report.json'); }).catch(function (e) { toast(e.message); });
        break;
      case 'adTestRun':
        el.disabled = true;
        call('adTestRun', { pkg: S.state.pkg || '' }).then(function (r) { S.adTestResult = r.result || {}; render(); toast('测试完成'); })
          .catch(function (e) { toast(e.message); }).then(function () { el.disabled = false; });
        break;
      case 'adLog':
        call('adLog').then(function (r) { S.adLog = r.log || []; render(); }).catch(function (e) { toast(e.message); });
        break;
      case 'gameToggle':
        if (!S.gameSel) S.gameSel = {};
        var gp = el.getAttribute('data-pkg');
        if (el.checked) S.gameSel[gp] = 1; else delete S.gameSel[gp];
        break;
      case 'applyGamesSearch':
        break;
      case 'savePastedRules':
        var txt = ($('#rulesText') || {}).value || '';
        if (!txt.trim()) { toast('先粘贴规则'); break; }
        call('adImportText', { text: txt }).then(function (r) {
          S.ad = { count: r.count, sample: r.sample }; closeModal(); render(); toast('已导入 ' + (r.count || 0) + ' 条规则');
        }).catch(function (e) { toast(e.message); });
        break;
      default: break;
    }
  }

  function val(id) { var el = $(id); return el ? el.value : ''; }

  function loadMemories() {
    return Promise.all([
      call('memories').then(function (r) { S.memories = r.memories || []; }),
      call('companionRules').then(function (r) { S.companionRules = r.rules || []; })
    ]);
  }
  function applyTasks(r) {
    S.tasks = r.tasks || [];
    S.taskFocus = r.focus || {};
    S.taskSummary = r.summary || {};
    S.events = r.events || [];
  }
  function loadReminders() {
    return call('reminders').then(function (r) { S.reminders = r.reminders || []; });
  }

  /* ---------------- 弹层 ---------------- */
  function openModal(html) {
    $('#modalCard').innerHTML = html;
    $('#modal').classList.remove('hidden');
  }
  function closeModal() { $('#modal').classList.add('hidden'); S.viewSticker = ''; }
  function confirmBox(title, desc, ok) {
    openModal('<div class="modal-head"><h3>' + esc(title) + '</h3><button class="modal-close" data-act="closeModal">' +
      '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<p class="faint" style="margin:0 0 16px">' + esc(desc) + '</p>' +
      '<div class="btn-row"><button class="btn ghost" data-act="closeModal">取消</button><button class="btn danger" id="confirmOk">确定</button></div>');
    $('#confirmOk').onclick = function () { closeModal(); ok(); };
  }
  function openRulesPaste() {
    openModal('<div class="modal-head"><h3>粘贴规则 JSON</h3><button class="modal-close" data-act="closeModal">' +
      '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<label class="field"><span>支持数组 / 对象 / 李跳跳风格</span><textarea id="rulesText" style="min-height:220px" placeholder=\'[{"package":"com.example","keywords":["跳过广告"]}]\'></textarea></label>' +
      '<button class="btn primary block" data-act="savePastedRules">导入</button>');
  }

  function openGames() {
    if (!S.apps) {
      openModal('<div class="modal-head"><h3>选择游戏应用</h3><button class="modal-close" data-act="closeModal">' +
        '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div><div class="empty">正在读取应用列表…</div>');
      call('apps').then(function (r) {
        S.apps = r.apps || [];
        S.gameSel = {};
        (S.cfg.games || []).forEach(function (p) { S.gameSel[p] = 1; });
        renderGames();
      }).catch(function (e) { toast(e.message); });
    } else {
      S.gameSel = {};
      (S.cfg.games || []).forEach(function (p) { S.gameSel[p] = 1; });
      renderGames();
    }
  }
  function renderGames() {
    var q = S.gameQuery.toLowerCase();
    var list = (S.apps || []).filter(function (a) {
      return !q || (a.name || '').toLowerCase().indexOf(q) >= 0 || (a.pkg || '').toLowerCase().indexOf(q) >= 0;
    });
    var html = '<div class="modal-head"><h3>选择游戏应用</h3><button class="modal-close" data-act="closeModal">' +
      '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<div class="search"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>' +
      '<input id="gameSearch" placeholder="搜索应用…" value="' + esc(S.gameQuery) + '"></div><div class="list" id="appList">';
    list.forEach(function (a) {
      var on = S.gameSel[a.pkg];
      html += '<label class="list-item"><div class="li-ico">' + (a.icon ? '<img src="' + a.icon + '" alt="">' : esc((a.name || '?').slice(0, 1))) + '</div>' +
        '<div class="li-main"><b>' + esc(a.name) + '</b><span>' + esc(a.pkg) + '</span></div>' +
        '<input type="checkbox" class="game-check" data-pkg="' + esc(a.pkg) + '" ' + (on ? 'checked' : '') + '></label>';
    });
    if (!list.length) html += '<div class="empty">没有找到应用</div>';
    html += '</div><div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="closeModal">取消</button><button class="btn primary" data-act="saveGames">保存</button></div>';
    openModal(html);
    var gs = $('#gameSearch');
    if (gs) {
      gs.addEventListener('input', function () {
        S.gameQuery = gs.value;
        renderGames();
        setTimeout(function () { var x = $('#gameSearch'); if (x) { x.focus(); x.setSelectionRange(x.value.length, x.value.length); } }, 0);
      });
    }
  }
  function saveGames() {
    var arr = Object.keys(S.gameSel || {});
    call('gamesSave', { games: arr }).then(function () {
      S.cfg.games = arr;
      closeModal();
      render();
      toast('已保存 ' + arr.length + ' 个游戏');
    }).catch(function (e) { toast(e.message); });
  }

  function openFreeActionApps() {
    openModal('<div class="modal-head"><h3>允许她探索的应用</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 18L6 6"/></svg></button></div><div class="empty">正在读取可选应用…</div>');
    call('freeActionAppOptions').then(function (r) {
      S.freeActionAppsList = r.apps || [];
      S.freeActionSel = {};
      (S.cfg.freeActionApps || []).forEach(function (p) { S.freeActionSel[p] = 1; });
      renderFreeActionApps();
    }).catch(function (e) { toast(e.message); });
  }
  function renderFreeActionApps() {
    var q = (S.freeActionQuery || '').toLowerCase();
    var list = (S.freeActionAppsList || []).filter(function (a) {
      return !q || (a.name || '').toLowerCase().indexOf(q) >= 0 || (a.pkg || '').toLowerCase().indexOf(q) >= 0;
    });
    var html = '<div class="modal-head"><h3>允许她探索的应用</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 18L6 6"/></svg></button></div>' +
      '<div class="notice violet">勾选后会限制她只能进入这些应用；全部不勾选表示允许所有通过安全筛选的应用。聊天、支付、系统设置、购物等敏感应用不会列入选项。探索时的截图和文字会发送到你配置的 AI 服务。</div>' +
      '<div class="search" style="margin-top:10px"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg><input id="freeActionSearch" placeholder="搜索应用…" value="' + esc(S.freeActionQuery || '') + '"></div><div class="list" id="appList">';
    list.forEach(function (a) {
      var on = S.freeActionSel && S.freeActionSel[a.pkg];
      html += '<label class="list-item"><div class="li-ico">' + (a.icon ? '<img src="' + a.icon + '" alt="">' : esc((a.name || '?').slice(0, 1))) + '</div>' +
        '<div class="li-main"><b>' + esc(a.name) + '</b><span>' + esc(a.pkg) + '</span></div>' +
        '<input type="checkbox" class="free-action-check" data-pkg="' + esc(a.pkg) + '" ' + (on ? 'checked' : '') + '></label>';
    });
    if (!list.length) html += '<div class="empty">没有找到可探索的应用</div>';
    html += '</div><div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="closeModal">取消</button><button class="btn primary" data-act="saveFreeActionApps">保存</button></div>';
    openModal(html);
    var input = $('#freeActionSearch');
    if (input) input.addEventListener('input', function () {
      S.freeActionQuery = input.value || '';
      renderFreeActionApps();
      setTimeout(function () {
        var el = $('#freeActionSearch');
        if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length); }
      }, 0);
    });
  }
  function saveFreeActionApps() {
    var arr = Object.keys(S.freeActionSel || {});
    call('freeActionAppsSave', { apps: arr }).then(function (r) {
      S.cfg.freeActionApps = arr;
      closeModal();
      render();
      toast('已保存 ' + num(r.count, arr.length) + ' 个可探索应用');
    }).catch(function (e) { toast(e.message); });
  }

  function openNotifyApps() {
    if (!S.apps) {
      openModal('<div class="modal-head"><h3>接收应用</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div><div class="empty">正在读取应用列表…</div>');
      call('apps').then(function (r) {
        S.apps = r.apps || [];
        S.notifySel = {};
        (S.cfg.notifyApps || []).forEach(function (p) { S.notifySel[p] = 1; });
        renderNotifyApps();
      }).catch(function (e) { toast(e.message); });
    } else {
      S.notifySel = {};
      (S.cfg.notifyApps || []).forEach(function (p) { S.notifySel[p] = 1; });
      renderNotifyApps();
    }
  }
  function renderNotifyApps() {
    var q = (S.gameQuery || '').toLowerCase();
    var list = (S.apps || []).filter(function (a) {
      return !q || (a.name || '').toLowerCase().indexOf(q) >= 0 || (a.pkg || '').toLowerCase().indexOf(q) >= 0;
    });
    var html = '<div class="modal-head"><h3>接收哪些应用的消息</h3><button class="modal-close" data-act="closeModal"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button></div>' +
      '<div class="faint">不勾选任何应用 = 接收全部；只勾选微信/QQ/外卖/快递即可只看重要的。</div>' +
      '<div class="search" style="margin-top:10px"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>' +
      '<input id="notifySearch" placeholder="搜索应用…" value="' + esc(S.gameQuery) + '"></div><div class="list" id="appList">';
    list.forEach(function (a) {
      var on = S.notifySel && S.notifySel[a.pkg];
      html += '<label class="list-item"><div class="li-ico">' + (a.icon ? '<img src="' + a.icon + '" alt="">' : esc((a.name || '?').slice(0, 1))) + '</div>' +
        '<div class="li-main"><b>' + esc(a.name) + '</b><span>' + esc(a.pkg) + '</span></div>' +
        '<input type="checkbox" class="notify-check" data-pkg="' + esc(a.pkg) + '" ' + (on ? 'checked' : '') + '></label>';
    });
    if (!list.length) html += '<div class="empty">没有找到应用</div>';
    html += '</div><div class="btn-row" style="margin-top:12px"><button class="btn ghost" data-act="closeModal">取消</button><button class="btn primary" data-act="saveNotifyApps">保存</button></div>';
    openModal(html);
    var ns = $('#notifySearch');
    if (ns) ns.addEventListener('input', function () {
      S.gameQuery = ns.value;
      renderNotifyApps();
      setTimeout(function () { var x = $('#notifySearch'); if (x) { x.focus(); x.setSelectionRange(x.value.length, x.value.length); } }, 0);
    });
  }
  function saveNotifyApps() {
    var arr = Object.keys(S.notifySel || {});
    call('notifyAppsSave', { apps: arr }).then(function () {
      S.cfg.notifyApps = arr;
      closeModal();
      render();
      toast(arr.length ? '只接收 ' + arr.length + ' 个应用' : '接收全部应用');
    }).catch(function (e) { toast(e.message); });
  }

  /* ---------------- 全局事件 ---------------- */
  var nativeKb = null; // 原生报上来的键盘高度（dp），比 visualViewport 准
  var kbBase = window.innerHeight || 0; // 初始化基准，避免第一次弹键盘时把整段 IME 高度重复加到页面上
  function applyKeyboard(dp) {
    var raw = Math.max(0, Math.round(dp));
    if (raw < 60) raw = 0;
    if (raw > 0 && S.page === 'chat') {
      closeChatAttachments();
      closeStickerPanel();
    }
    if (!raw) kbBase = window.innerHeight;
    var shrink = kbBase ? Math.max(0, kbBase - window.innerHeight) : 0;
    // Activity 使用 adjustNothing，由页面按 IME inset 定位；保留差值补偿以兼容厂商仍然缩小 WebView 的实现。
    var kb = Math.max(0, raw - shrink);
    var main = S.page === 'chat' ? $('#msgs') : $('#main');
    var nearBottom = true;
    if (S.page === 'chat' && main) {
      nearBottom = (main.scrollHeight - main.clientHeight - main.scrollTop) < 90;
    }
    document.documentElement.style.setProperty('--kb', kb + 'px');
    document.body.classList.toggle('keyboard-open', raw > 0);
    // 键盘高度一变，输入条位置跟着变，底部留白也要重新量
    if (S.page === 'chat') setTimeout(fitChatTail, 30);
    if (S.page === 'chat' && nearBottom) {
      setTimeout(function () {
        var m = $('#msgs');
        if (m) m.scrollTop = m.scrollHeight;
      }, 40);
    }
  }
  function syncKeyboard() {
    if (nativeKb !== null && nativeKb > 0) {
      applyKeyboard(nativeKb);
      return;
    }
    var vv = window.visualViewport;
    var kb = vv ? Math.round(window.innerHeight - vv.height - (vv.offsetTop || 0)) : 0;
    if (kb < 80) kb = 0;
    applyKeyboard(kb);
  }
  window.addEventListener('resize', syncKeyboard);
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', syncKeyboard);
    window.visualViewport.addEventListener('scroll', syncKeyboard);
  }
  document.addEventListener('focusin', function (e) {
    if (e.target && e.target.id === 'chatInput') {
      closeChatAttachments();
      closeStickerPanel();
      setTimeout(syncKeyboard, 300);
    }
    if (e.target && e.target.tagName === 'SELECT') markSelectOpen();
  });
  document.addEventListener('focusout', function (e) {
    if (e.target && e.target.id === 'chatInput') setTimeout(syncKeyboard, 300);
    if (e.target && e.target.tagName === 'SELECT') setTimeout(flushPendingRender, 50);
  });
  document.addEventListener('change', function (e) {
    if (e.target && e.target.tagName === 'SELECT') setTimeout(flushPendingRender, 0);
  });
  /* 有些机型弹下拉层时元素不拿焦点，按下就标记，防止页面重排把弹层关掉 */
  document.addEventListener('pointerdown', function (e) {
    if (e.target && e.target.tagName === 'SELECT') markSelectOpen();
  }, true);

  var msgPressTimer = null;
  var msgPressStart = null;
  var msgPressIdx = -1;
  document.addEventListener('pointerdown', function (e) {
    var b = e.target && e.target.closest ? e.target.closest('#voiceHoldBtn') : null;
    if (!b) return;
    e.preventDefault();
    beginVoiceHold();
  }, true);
  document.addEventListener('pointerup', function (e) {
    if (S.voiceHoldActive) endVoiceHold(false);
  }, true);
  document.addEventListener('pointercancel', function () {
    if (S.voiceHoldActive) endVoiceHold(true);
  }, true);
  document.addEventListener('touchstart', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('.msg[data-idx]') : null;
    if (!el) return;
    var idx = num(el.getAttribute('data-idx'), -1);
    if (idx < 0) return;
    var t = e.touches && e.touches[0];
    if (!t) return;
    msgPressIdx = idx;
    msgPressStart = { x: t.clientX, y: t.clientY };
    clearTimeout(msgPressTimer);
    msgPressTimer = setTimeout(function () {
      msgPressTimer = null;
      msgPressStart = null;
      var pressed = msgPressIdx;
      msgPressIdx = -1;
      openMsgMenu(pressed);
    }, 500);
  }, { passive: true });
  document.addEventListener('contextmenu', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('.msg[data-idx]') : null;
    if (!el) return;
    e.preventDefault();
    openMsgMenu(num(el.getAttribute('data-idx'), -1));
  });
  document.addEventListener('touchmove', function (e) {
    if (!msgPressStart) return;
    var t = e.touches && e.touches[0];
    if (!t) return;
    var dx = t.clientX - msgPressStart.x;
    var dy = t.clientY - msgPressStart.y;
    if (dx * dx + dy * dy > 100) {
      clearTimeout(msgPressTimer);
      msgPressTimer = null;
      msgPressStart = null;
      msgPressIdx = -1;
    }
  }, { passive: true });
  document.addEventListener('touchend', function () {
    clearTimeout(msgPressTimer);
    msgPressTimer = null;
    msgPressStart = null;
  }, { passive: true });
  document.addEventListener('touchcancel', function () {
    clearTimeout(msgPressTimer);
    msgPressTimer = null;
    msgPressStart = null;
    msgPressIdx = -1;
  }, { passive: true });

  function openMsgMenu(idx) {
    var m = (S.chat || [])[idx];
    if (!m || !m.content) return;
    closeMsgMenu();
    var target = document.querySelector('.msg[data-idx="' + idx + '"]');
    var layer = document.createElement('div');
    layer.id = 'msgContextLayer';
    layer.className = 'msg-context-layer';
    layer.innerHTML = '<button class="msg-context-dismiss" data-act="msgMenuDismiss" aria-label="关闭操作菜单"></button>' +
      '<div class="msg-context-menu" role="menu" aria-label="消息操作">' +
      '<button data-act="quoteChat" data-idx="' + idx + '" role="menuitem"><span class="msg-context-icon">❝</span><span>引用</span></button>' +
      '<button data-act="copyChat" data-idx="' + idx + '" role="menuitem"><span class="msg-context-icon">▢</span><span>复制</span></button>' +
      '<button data-act="markChatMoment" data-idx="' + idx + '" role="menuitem"><span class="msg-context-icon">✦</span><span>标记为重要时刻</span></button>' +
      '</div>';
    document.body.appendChild(layer);
    var menu = layer.querySelector('.msg-context-menu');
    var rect = target ? target.getBoundingClientRect() : null;
    var width = menu.offsetWidth;
    var height = menu.offsetHeight;
    var left = rect ? rect.left + (rect.width - width) / 2 : (window.innerWidth - width) / 2;
    left = Math.max(12, Math.min(window.innerWidth - width - 12, left));
    var top = rect ? rect.top - height - 8 : (window.innerHeight - height) / 2;
    if (top < 16) top = rect ? rect.bottom + 8 : 16;
    top = Math.max(16, Math.min(window.innerHeight - height - 16, top));
    menu.style.left = Math.round(left) + 'px';
    menu.style.top = Math.round(top) + 'px';
  }
  function closeMsgMenu() {
    var layer = document.getElementById('msgContextLayer');
    if (!layer) return false;
    layer.remove();
    return true;
  }
  function showChatQuotePreview() {
    $$('.composer .chat-quote-preview').forEach(function (node) { node.remove(); });
    var composer = $('.composer');
    if (composer && S.chatQuote) composer.insertAdjacentHTML('afterbegin', quotePreviewHtml(S.chatQuote, 'composer'));
    fitChatTail();
  }
  function copyChatText(idx) {
    var m = (S.chat || [])[idx];
    if (!m) return;
    var text = String(m.content || '');
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text);
      } else {
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      closeMsgMenu();
      toast('已复制');
    } catch (e) {
      toast('复制失败');
    }
  }
  function quoteChatText(idx) {
    var m = (S.chat || [])[idx];
    if (!m) return;
    var input = $('#chatInput');
    if (!input) { closeMsgMenu(); toast('切换到文字输入后即可引用'); return; }
    var quote = String(m.content || '').trim();
    if (!quote) return;
    var role = m.role === 'user' ? 'user' : 'assistant';
    S.chatQuote = { role: role, name: quoteAuthorName(role), content: quote, ts: m.ts || 0 };
    closeMsgMenu();
    showChatQuotePreview();
    input.focus();
  }

  document.addEventListener('click', function (e) {
    var el = e.target.closest ? e.target.closest('[data-act]') : null;
    if (el) {
      // 具体页面操作优先处理。子页面按钮也可能带 data-tab 作为自己的选项值，
      // 不能先把它误当成底部主导航，否则会跳回首页。
      var tag = (el.tagName || '').toUpperCase();
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      e.preventDefault();
      handleAct(el.getAttribute('data-act'), el, e);
      return;
    }
    var tab = e.target.closest ? e.target.closest('[data-tab]') : null;
    if (tab) { goTab(tab.getAttribute('data-tab')); return; }
    // 首次表单的选项 chip
    var chip = e.target.closest ? e.target.closest('[data-ob]') : null;
    if (chip) {
      e.preventDefault();
      var group = chip.getAttribute('data-ob');
      var val = chip.getAttribute('data-val');
      $$('.ob-chip[data-ob="' + group + '"]').forEach(function (b) { b.classList.toggle('on', b === chip); });
      chip._selected = val;
    }
  });

  document.addEventListener('change', function (e) {
    var el = e.target;
    if (!el) return;
    if (el.id === 'shelfSort') {
      S.shelfSort = el.value || 'recent';
      render();
      return;
    }
    if (el.id === 'ttsEngine') {
      S.cfg.ttsEngine = el.value;
      call('ttsSave', { json: { ttsEngine: el.value } }).then(function () {
        render();
        if (el.value === 'online' && !(S.cfg.ttsOnlineUrl || '').trim()) return;
        call('ttsTest', { text: '我是现在的发音，好听吗？' }).catch(function () { });
      }).catch(function (e) { toast(e.message); });
      return;
    }
    if (el.id === 'ttsSysEngine') {
      S.cfg.ttsSysEngine = el.value;
      S.cfg.ttsVoice = '';
      S.tts = S.tts || {};
      S.tts.voiceError = '';
      call('ttsSave', { json: { ttsSysEngine: el.value, ttsVoice: '' } }).then(function (r) {
        if (r && r.tts) S.tts = r.tts;
        render();
      }).catch(function (e) { toast(e.message); });
      return;
    }
    if (el.id === 'ttsOnlineUrl' || el.id === 'ttsOnlineModel' || el.id === 'ttsOnlineVoice' || el.id === 'ttsOnlineKey') {
      var ov = el.value.trim();
      if (el.id === 'ttsOnlineKey' && !ov) return;
      var op = {};
      op[el.id] = ov;
      S.cfg[el.id] = ov;
      call('ttsSave', { json: op }).then(function (r) {
        if (r && r.tts) S.tts = r.tts;
        render();
        call('ttsTest', { text: '我是现在的发音，好听吗？' }).catch(function () { });
      }).catch(function (e) { toast(e.message); });
      return;
    }
    if (el.id === 'edgeVoice') {
      S.cfg.edgeVoice = el.value;
      call('ttsSave', { json: { edgeVoice: el.value } }).then(function () {
        render();
        call('ttsTest', { text: '我是现在的发音，好听吗？' }).catch(function () { });
      }).catch(function (e) { toast(e.message); });
      return;
    }
    if (el.id === 'ttsVoice') {
      S.cfg.ttsVoice = el.value;
      call('ttsSave', { json: { ttsVoice: el.value } }).then(function (r) {
        render();
        var err = r && r.tts && r.tts.voiceError;
        if (err) toast(err);
        call('ttsTest', { text: '我是现在的发音，好听吗？' }).catch(function () { });
      }).catch(function (e) { toast(e.message); });
      return;
    }
    if (el.getAttribute('data-tts')) {
      var tk = el.getAttribute('data-tts');
      var tv = parseFloat(el.value);
      S.cfg[tk] = tv;
      if (el._tt) { clearTimeout(el._tt); el._tt = null; }
      var to = {};
      to[tk] = tv;
      call('ttsSave', { json: to }).catch(function (e) { toast(e.message); });
      return;
    }
    if (el.id === 'readerAutoSec') {
      S.bookCfg.autoSec = num(el.value, 25);
      saveReaderCfg();
      if (S.autoOn) { toggleAutoReader(); toggleAutoReader(); }
      return;
    }
    if (el.id === 'aiActionGlobal') {
      toggleCfgKey('aiActionLines', el.checked);
      return;
    }
    if (el.getAttribute('data-act') === 'toggleCfg' && el.getAttribute('data-key')) {
      var ck = el.getAttribute('data-key');
      S.cfg[ck] = el.checked;
      toggleCfgKey(ck, el.checked);
      // 刚打开 AI 识别：马上给这批未读通知跑一次，不用等下次刷新
      if (ck === 'notifAi' && el.checked) {
        call('notifAi').then(function (r) {
          S.notifAi = r.ai || S.notifAi;
          if (S.page === 'notif') render();
        }).catch(function () { });
      }
      return;
    }
    if (el.id === 'adEnabled') {
      S.cfg.adEnabled = el.checked;
      call('adEnabled', { enabled: el.checked }).then(function () { toast(el.checked ? '已启用跳过' : '已关闭跳过'); }).catch(function (x) { toast(x.message); });
      return;
    }
    if (el.classList.contains('act-ai')) {
      var slug = el.getAttribute('data-slug');
      var row = document.querySelector('.act[data-slug="' + slug + '"]');
      var input = row ? row.querySelector('.act-input') : null;
      if (input) saveActionLine(slug, input);
      return;
    }
    if (el.classList.contains('act-input')) {
      saveActionLine(el.getAttribute('data-slug'), el);
      return;
    }
    if (el.classList.contains('game-check')) {
      if (!S.gameSel) S.gameSel = {};
      if (el.checked) S.gameSel[el.getAttribute('data-pkg')] = 1;
      else delete S.gameSel[el.getAttribute('data-pkg')];
      return;
    }
    if (el.classList && el.classList.contains('notify-check')) {
      if (!S.notifySel) S.notifySel = {};
      if (el.checked) S.notifySel[el.getAttribute('data-pkg')] = 1;
      else delete S.notifySel[el.getAttribute('data-pkg')];
      return;
    }
    if (el.classList && el.classList.contains('free-action-check')) {
      if (!S.freeActionSel) S.freeActionSel = {};
      if (el.checked) S.freeActionSel[el.getAttribute('data-pkg')] = 1;
      else delete S.freeActionSel[el.getAttribute('data-pkg')];
      return;
    }
    if (el.id === 'sleepStart' || el.id === 'sleepEnd') {
      return;
    }
    var da = el.getAttribute('data-act');
    if (da) handleAct(da, el, e);
  });

  document.addEventListener('input', function (e) {
    var el = e.target;
    if (el.id === 'featureHomeSearch' || el.id === 'featureHubSearch') {
      S.featureQuery = el.value || '';
      var target = document.getElementById(el.id === 'featureHomeSearch' ? 'featureHomeResults' : 'featureHubResults');
      if (target) target.innerHTML = S.featureQuery.trim() ? featureSearchHtml(S.featureQuery, false) : (el.id === 'featureHubSearch' ? featureGroupsHtml() : '');
      return;
    }
    if (el.id === 'actSearch') {
      S.actQuery = el.value;
      filterActions(el.value);
      return;
    }
    if (el.id === 'shelfSearch') {
      S.shelfQuery = el.value;
      filterShelf(el.value);
      return;
    }
    if (el.id === 'readerSearchInput') {
      S.readerSearch = el.value;
      return;
    }
    if (el.id === 'familyCapRange') {
      var fv = Math.max(1, Math.min(1440, num(el.value, 60)));
      var fl = $('#familyCapVal');
      if (fl) fl.textContent = fv;
      var fh = $('#familyCapHint');
      if (fh) fh.textContent = fv >= 1440 ? '她说：家人吗，真好。保存后会有特别的事发生。' : '拖到 1440 会有特别的事发生…';
      if (fv >= 1440 && !S.familyMaxSaid) {
        S.familyMaxSaid = true;
        call('say', { text: '家人吗，真好' }).catch(function () { });
      }
      return;
    }
    if (el.getAttribute('data-rset')) {
      var rk = el.getAttribute('data-rset');
      var rv = parseFloat(el.value);
      S.bookCfg = S.bookCfg || {};
      S.bookCfg[rk] = rv;
      var lab = document.getElementById('rv' + rk.charAt(0).toUpperCase() + rk.slice(1));
      if (lab) lab.textContent = rk === 'brightness' ? Math.round(rv * 100) + '%' : (rk === 'lineHeight' ? rv.toFixed(2) : (rk === 'ttsRate' ? rv.toFixed(1) + 'x' : rv));
      readerApplyStyle();
      // 排版参数变化：防抖后按字符锚点重新分页，拖动过程中不会跳页
      if (rk === 'fontSize' || rk === 'lineHeight' || rk === 'paraSpace' || rk === 'margin') {
        clearTimeout(el._rp);
        el._rp = setTimeout(readerRestyle, 110);
      }
      // 字号/行距滑块也要同步 A± 数值与三档高亮
      if (rk === 'fontSize' || rk === 'lineHeight') syncReaderUI();
      clearTimeout(el._rt);
      el._rt = setTimeout(saveReaderCfg, 500);
      return;
    }
    if (el.getAttribute('data-tts')) {
      var tk = el.getAttribute('data-tts');
      var tv = parseFloat(el.value);
      S.cfg[tk] = tv;
      var tr = el.closest('.row');
      var trs = tr ? tr.querySelector('.rs') : null;
      if (trs) trs.textContent = tv.toFixed(1) + 'x';
      clearTimeout(el._tt);
      el._tt = setTimeout(function () {
        var tp = {};
        tp[tk] = tv;
        call('ttsSave', { json: tp }).catch(function (e) { toast(e.message); });
      }, 400);
      return;
    }
    if (el.id === 'chatInput') {
      autoGrow(el);
      return;
    }
    if (el.getAttribute('data-set')) {
      var key = el.getAttribute('data-set');
      var v = num(el.value, 0);
      S.cfg[key] = v;
      var row = el.closest('.row');
      var rs = row ? row.querySelector('.rs') : null;
      if (rs) {
        if (key === 'musicHumSec') rs.textContent = '每 ' + v + ' 秒做一次哼歌动作';
        else if (key === 'lyricGapSec') rs.textContent = '每 ' + v + ' 秒最多飘一句歌词（5–30）';
        else if (key === 'proactiveMin') rs.textContent = '每 ' + v + ' 分钟（3–120）';
        else if (key === 'freeActionIntervalSec') rs.textContent = '每 ' + v + ' 秒判断一次；默认 60 秒，间隔越短越费电、AI 用量越高';
        else if (key === 'bubbleSec') rs.textContent = '每秒停留 ' + v + ' 秒';
        else if (key === 'speakCoolSec') rs.textContent = '每 ' + v + ' 秒最多主动说一句';
        else if (key === 'screenScanSec') rs.textContent = '每 ' + v + ' 秒读取一次；锁屏或灭屏时不识别';
      }
      clearTimeout(el._t);
      el._t = setTimeout(function () {
        var patch = {};
        patch[key] = v;
        call('saveCfg', { json: patch, apply: el.getAttribute('data-apply') || 'general' }).catch(function (x) { toast(x.message); });
      }, 450);
      return;
    }
    if (el.id === 'sizeRange' || el.id === 'opacityRange') {
      var lab2 = el.closest('.row');
      if (lab2) {
        var rs2 = lab2.querySelector('.rs');
        if (rs2) rs2.textContent = el.id === 'sizeRange' ? el.value + ' dp' : el.value + '%';
      }
      var sv = el.id === 'sizeRange' ? num(el.value, 150) : num(el.value, 100) / 100;
      if (el.id === 'sizeRange') S.cfg.sizeDp = sv; else S.cfg.opacity = sv;
      clearTimeout(el._t);
      el._t = setTimeout(function () {
        var patch = el.id === 'sizeRange' ? { sizeDp: num(el.value, 150) } : { opacity: num(el.value, 100) / 100 };
        call('saveCfg', { json: patch, apply: el.id === 'sizeRange' ? 'size' : 'opacity' }).catch(function (x) { toast(x.message); });
      }, 350);
    }
  });

  document.addEventListener('selectionchange', function () {
    if (S.page !== 'reader') return;
    clearTimeout(window.__selTimer);
    window.__selTimer = setTimeout(function () {
      if (S.page === 'reader' && window.getSelection().toString().trim()) showSelBar();
    }, 260);
  });

  /* 所有范围滑块统一为「按住圆点拖动」：点击轨道不改变值。
     用当前值估算圆点中心；只有在圆点附近按下才开始拖动。 */
  var _slDrag = null;
  function slFire(el, type) {
    try { el.dispatchEvent(new Event(type, { bubbles: true })); } catch (_) { }
  }
  document.addEventListener('pointerdown', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('input[type=range]') : null;
    if (!el || el.disabled) return;
    if (typeof e.button === 'number' && e.button > 0) return;
    var r = el.getBoundingClientRect();
    if (!r.width) return;
    e.preventDefault();
    e.stopPropagation();
    var min = parseFloat(el.min), max = parseFloat(el.max), value = parseFloat(el.value);
    if (!isFinite(min)) min = 0;
    if (!isFinite(max) || max <= min) max = min + 100;
    if (!isFinite(value)) value = min;
    var thumbRadius = 10;
    var center = r.left + thumbRadius + Math.max(0, Math.min(1, (value - min) / (max - min))) * Math.max(0, r.width - thumbRadius * 2);
    // 触屏圆点命中区略大于视觉圆点，方便手指按中，同时避免点轨道改变值。
    if (Math.abs(e.clientX - center) > 15) return;
    el.style.pointerEvents = 'none';
    _slDrag = { el: el, r: r, x: e.clientX, y: e.clientY, moved: false };
  }, true);
  document.addEventListener('pointermove', function (e) {
    var d = _slDrag;
    if (!d) return;
    if (!d.moved) {
      if (Math.abs(e.clientX - d.x) + Math.abs(e.clientY - d.y) < 8) return;
      d.moved = true;
    }
    e.preventDefault();
    var el = d.el, r = d.r;
    // 这里必须用 parseFloat：行距/亮度/语速这些滑块的 step 是 0.05、0.1
    var min = parseFloat(el.min), max = parseFloat(el.max), step = parseFloat(el.step);
    if (!isFinite(min)) min = 0;
    if (!isFinite(max)) max = 100;
    if (!isFinite(step) || step <= 0) step = 1;
    var thumbRadius = 10;
    var p = (e.clientX - r.left - thumbRadius) / Math.max(1, r.width - thumbRadius * 2);
    p = Math.max(0, Math.min(1, p));
    var v = min + Math.round(p * (max - min) / step) * step;
    v = Math.round(v * 1000) / 1000;
    var vs = String(v);
    if (vs !== String(el.value)) {
      el.value = v;
      slFire(el, 'input');
    }
  }, true);
  function slEnd(e) {
    var d = _slDrag;
    if (!d) return;
    _slDrag = null;
    try { d.el.style.pointerEvents = ''; } catch (_) { }
    if (d.moved && e && e.type === 'pointerup') slFire(d.el, 'change');
  }
  document.addEventListener('pointerup', slEnd, true);
  document.addEventListener('pointercancel', slEnd, true);
  window.addEventListener('blur', function () { slEnd(null); });

  // Android WebView can still apply a native range tap from touch events even
  // when pointerdown was cancelled. Cancel off-thumb touches at the touch layer.
  document.addEventListener('touchstart', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('input[type=range]') : null;
    var t = e.touches && e.touches[0];
    if (!el || !t) return;
    var r = el.getBoundingClientRect();
    var min = parseFloat(el.min), max = parseFloat(el.max), value = parseFloat(el.value);
    if (!isFinite(min)) min = 0;
    if (!isFinite(max) || max <= min) max = min + 100;
    if (!isFinite(value)) value = min;
    var radius = el.classList.contains('slider') ? 9.5 : 10;
    var center = r.left + radius + Math.max(0, Math.min(1, (value - min) / (max - min))) * Math.max(1, r.width - radius * 2);
    if (Math.abs(t.clientX - center) > 18) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, { capture: true, passive: false });

  /* 「更多权限」折叠状态：整页重绘（重新检查、权限变化）时保留展开/收起 */
  document.addEventListener('toggle', function (e) {
    var d = e.target;
    if (d && d.classList && d.classList.contains('more-wrap')) _healthMore = !!d.open;
    if (d && d.matches && d.matches('details.companion-fold')) {
      try { localStorage.setItem('companionFold:' + d.getAttribute('data-companion-fold'), d.open ? '1' : '0'); } catch (_) { }
    }
  }, true);

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && collapseMemoryGraphView()) {
      e.preventDefault();
      return;
    }
    if (e.key === 'Escape' && closeMsgMenu()) {
      e.preventDefault();
      return;
    }
    if (e.target && e.target.id === 'chatInput' && e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendChat();
      return;
    }
    if (S.page === 'reader') {
      var tag = e.target && e.target.tagName ? e.target.tagName.toUpperCase() : '';
      if (e.target && e.target.id === 'readerSearchInput' && e.key === 'Enter') {
        e.preventDefault(); doBookSearch(); return;
      }
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
        if (e.key === 'Escape') { hideSelBar(); }
        return;
      }
      if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') {
        e.preventDefault(); readerTurn(1);
      } else if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
        e.preventDefault(); readerTurn(-1);
      } else if (e.key === 'ArrowDown') {
        e.preventDefault(); readerTurn(1);
      } else if (e.key === 'ArrowUp') {
        e.preventDefault(); readerTurn(-1);
      } else if (e.key === 'Escape') {
        if (readerOverlayClose()) { /* 面板或目录已关闭 */ }
        else hideSelBar();
      }
    }
  });

  window.__event = function (name, data) {
    data = data || {};
    if (name === 'memoryRecall') {
      S.memGraphPendingIds = Array.isArray(data.ids) ? data.ids.slice(0, 8) : [];
      S.memGraphPendingAt = Date.now();
      if (S.page === 'memGraph' && window.DshMemoryGraph) {
        window.DshMemoryGraph.highlight(S.memGraphPendingIds);
      }
    } else if (name === 'profileAvatarPicked') {
      if (data.ok && data.data) {
        if (!S.userProfileDraft) S.userProfileDraft = { nickname: profileName(), avatarData: '' };
        S.userProfileDraft.avatarData = data.data;
        if (S.page === 'userProfile') render();
      } else if (data.error) toast(data.error);
    } else if (name === 'qqLink') {
      S.qqStatus = data || {};
      if (S.page === 'qq' || S.page === 'settings') render();
    } else if (name === 'qqPersonaImported') {
      if (data && data.ok) {
        S.qqPersonas = data.items || [];
        S.qqPersonaSelected = data.selectedId || S.qqPersonaSelected;
        toast('人设卡已导入，请在连接设置中选择');
      } else toast((data && data.error) || '人设卡导入失败');
      if (S.page === 'qq') render();
    } else if (name === 'comfyWorkflowImported') {
      if (data && data.ok && typeof data.workflow === 'string') {
        var workflowField = $('#comfyUiWorkflow');
        if (workflowField) workflowField.value = data.workflow;
        toast('工作流已导入，请保存图像生成设置');
      } else toast((data && data.error) || '工作流导入失败');
    } else if (name === 'chatDelta') {
      if (!S.streamId || data.streamId !== S.streamId || !S.sending) return;
      S.streamText += String(data.delta || '');
      var draftCut = draftCutIndex(S.streamText);
      if (draftCut >= 0) {
        // 分隔线出现：草稿到此为止，丢弃草稿，后面的正文正常流式显示
        if (!S.streamDraftCut) {
          S.streamDraftCut = true;
          S.streamSuppressed = false;
          var draftPreview = $('#chatStreamPreview');
          if (draftPreview) draftPreview.remove();
          S.streamBubbleNodes = [];
          S.streamPartialNode = null;
          S.streamRenderedCount = 0;
        }
        S.streamText = S.streamText.slice(draftCut);
      } else if (!S.streamDraftCut && !S.streamSuppressed) {
        // 还判断不出前面是不是草稿：先不打字，只显示“正在输入”，避免草稿被看到
        S.streamSuppressed = true;
        var hiddenPreview = $('#chatStreamPreview');
        if (hiddenPreview) hiddenPreview.remove();
        S.streamBubbleNodes = [];
        S.streamPartialNode = null;
        S.streamRenderedCount = 0;
        S.replyStarted = false;
        if (S.page === 'chat') refreshChat();
      }
      if (!S.streamPreviewShown) {
        stopTypingLines();
        S.streamPreviewShown = true;
      }
      if (!S.streamSuppressed && (S.streamText.length > 1000 || S.streamText.indexOf('```') >= 0)) {
        S.streamSuppressed = true;
        var preview = $('#chatStreamPreview');
        if (preview) preview.remove();
        S.streamBubbleNodes = [];
        S.streamPartialNode = null;
        S.streamRenderedCount = 0;
        S.replyStarted = false;
        S.typingLine = '正在整理成长文卡片…';
        if (S.page === 'chat') refreshChat();
      } else if (S.streamSuppressed) {
        S.replyStarted = false;
      } else if (!S.streamSuppressed) {
        S.replyStarted = true;
        updateStreamPreview();
      }
      updateStreamStopButton();
    } else if (name === 'state') {
      S.state.running = !!data.running;
      S.state.locked = !!data.locked;
      // 长按桌宠/通知栏/悬浮面板都能改锁定，配置也要跟着同步，
      // 否则首页按钮还停在「锁定位置」。
      S.cfg.locked = !!data.locked;
      if (typeof data.size === 'number') S.state.size = data.size;
      if (typeof data.days === 'number') S.state.days = data.days;
      if (typeof data.affection === 'number') S.state.affection = data.affection;
      if (S.page === 'home') render();
    } else if (name === 'music') {
      var old = S.state.music || {};
      var changed = old.playing !== data.playing || old.title !== data.title || old.artist !== data.artist;
      // 事件里只有歌名/歌手/进度，保留上一次状态里的歌词来源与当前歌词，避免状态行变空
      var merged = {};
      for (var mk in old) merged[mk] = old[mk];
      for (var mk2 in data) merged[mk2] = data[mk2];
      S.state.music = merged;
      if (S.page === 'home') {
        if (changed) render();
      } else if (S.page === 'companion') {
        var ae = document.activeElement;
        if (changed && (!ae || ae.tagName !== 'INPUT')) render();
      }
    } else if (name === 'reminders') {
      if (typeof data.count === 'number') S.state.pending = data.count;
      if (typeof data.unread === 'number') S.state.unread = data.unread;
      if (S.page === 'home' || S.page === 'reminders') loadReminders().then(render);
    } else if (name === 'ad_skipped') {
      S.state.stats = S.state.stats || {};
      // Java has already persisted the total. The event carries that absolute
      // value so a UI refresh cannot count the same skip a second time.
      if (typeof data.skips === 'number') S.state.stats.skips = data.skips;
      toast('广告已跳过');
      if (S.page === 'adskip') render();
    } else if (name === 'adrules') {
      S.ad = { count: data.count, sample: data.sample };
      if (S.page === 'adskip') render();
    } else if (name === 'persona') {
      S.persona = data;
      if (S.page === 'persona' || S.page === 'home') render();
      toast('人设已更新');
    } else if (name === 'pomo') {
      S.pomo = data;
      if (S.page === 'companion') render();
    } else if (name === 'mood') {
      S.mood = data;
      if (S.page === 'home') render();
    } else if (name === 'memory') {
      loadMemories().then(function () {
        if (S.page === 'persona') render();
        if (S.page === 'mem') reloadMemList().then(render).catch(function () { });
      });
    } else if (name === 'toast') {
      toast(data.message || '');
    } else if (name === 'error') {
      toast(data.message || '操作失败');
    } else if (name === 'stickers') {
      call('stickerList').then(function (r) {
        S.stickers = r.stickers || [];
        if (S.page === 'stickers') render();
        else if (S.page === 'chat') refreshStickerPanel();
        // 大图弹层开着时，识别/换图完成后把内容刷成最新的
        if (S.viewSticker && !$('#modal').classList.contains('hidden')) stickerOpen(S.viewSticker);
      });
    } else if (name === 'stickerProg') {
      var sbBtn = $('#stickerAllBtn');
      if (sbBtn) sbBtn.textContent = '识别中 ' + (data.done || 0) + '/' + (data.total || 0);
    } else if (name === 'stickerBatchDone') {
      var sbDone = $('#stickerAllBtn');
      if (sbDone) {
        sbDone.disabled = false;
        sbDone.textContent = '一键批量识别（重新识别全部 ' + (S.stickers || []).length + ' 个）';
      }
    } else if (name === 'qqChatUpdated') {
      if (S.page === 'chat') refreshChat();
    } else if (name === 'drawMessage') {
      var drawText = (data && data.text) || '';
      if (drawText) {
        S.chat.push({ role: 'assistant', content: drawText, ts: Date.now() });
        if (S.page === 'chat') refreshChat();
      }
    } else if (name === 'sessionInsightCard') {
      if (data && data.cardType === 'session_insights') {
        S.chat.push(data);
        if (S.page === 'chat') refreshChat();
      }
    } else if (name === 'chatSticker') {
      /* 联网或本地绘制的表情已经保存：刷新索引后再显示，确保 file 路径存在 */
      var csName = (data && data.name) || '';
      if (csName) {
        call('stickerList').then(function (r) {
          S.stickers = r.stickers || [];
          S.chat.push({ role: 'assistant', content: '[[表情:' + csName + ']]', ts: Date.now() });
          if (S.page === 'chat') refreshChat();
          else if (S.page === 'stickers') render();
        }).catch(function () {
          S.chat.push({ role: 'assistant', content: '[[表情:' + csName + ']]', ts: Date.now() });
          if (S.page === 'chat') refreshChat();
        });
      }
    } else if (name === 'chatStickerFailed') {
      var failText = (data && data.text) || '我没找到合适的表情，稍后可以再试一次。';
      S.chat.push({ role: 'assistant', content: failText, ts: Date.now() });
      if (S.page === 'chat') refreshChat();
    } else if (name === 'skins') {
      S.skins = data.skins || [];
      S.skinId = data.skinId || S.skinId;
      if (data.live2dSupported != null) S.live2dOk = !!data.live2dSupported;
      if (S.page === 'dress' || S.page === 'home') render();
    } else if (name === 'imported') {
      boot(true);
    } else if (name === 'hostPause') {
      if (S.page === 'memGraph' && window.DshMemoryGraph) window.DshMemoryGraph.pause();
    } else if (name === 'resume') {
      if (S.page === 'memGraph') {
        call('memoryGraphMode', { on: true }).catch(function () { });
        if (window.DshMemoryGraph) window.DshMemoryGraph.resume();
      }
      if (S.page === 'health') {
        // 从系统设置授权/关闭回来，重新体检一次
        call('health').then(function (r) { S.health = r.health || []; render(); }).catch(function () { });
      }
      if (S.page === 'usage') loadUsage();
      else if (S.page === 'monitor') loadMonitor();
      else if (!S.sending) setTimeout(function () { boot(true); }, 160);
      else refreshPerms();
    } else if (name === 'context') {
      S.state.pkg = data.pkg;
      S.state.screenText = data.text;
      if (S.page === 'companion') {
        var pkgLabel = $('#companionCurrentPkg');
        if (pkgLabel) pkgLabel.textContent = S.state.pkg || '未知';
        var textLabel = $('#companionScreenText');
        if (textLabel) {
          textLabel.textContent = (S.state.screenText || '').slice(0, 110);
          textLabel.hidden = !S.state.screenText;
        }
      }
    } else if (name === 'a11y' || name === 'listener') {
      refreshPerms();
    } else if (name === 'perms') {
      S.perms = data || {};
      if (S.asrPending) {
        S.asrPending = false;
        if (S.perms.record) startAsr();
        else toast('没有麦克风权限，语音输入不可用');
      }
      if (S.page === 'settings' || S.page === 'companion' || S.page === 'reminders' || S.page === 'health' || S.page === 'geo' || S.page === 'musicx') render();
    } else if (name === 'asr') {
      if (data.error) {
        if (S.asrFinishTimer) clearTimeout(S.asrFinishTimer);
        S.asrFinishTimer = null;
        S.asrOn = false;
        S.asrPending = false;
        S.voiceHoldActive = false;
        S.voiceHoldSend = false;
        toast(data.error);
        if (S.page === 'chat') render();
      } else {
        S.asrText = data.text || '';
        var ci2 = $('#chatInput');
        if (ci2) { ci2.value = S.asrText; autoGrow(ci2); }
        var holdBtn = $('#voiceHoldBtn');
        if (holdBtn && S.voiceHoldActive) holdBtn.textContent = '正在听… ' + (S.asrText ? '松开发送' : '请继续说');
        if (data.final) {
          if (S.asrFinishTimer) clearTimeout(S.asrFinishTimer);
          S.asrFinishTimer = null;
          S.asrOn = false;
          var sendVoice = S.voiceHoldSend;
          S.voiceHoldActive = false;
          S.voiceHoldSend = false;
          if (S.page === 'chat') render();
          if (sendVoice || S.cfg.asrAutoSend) setTimeout(sendChat, 120);
        }
      }
    } else if (name === 'clip') {
      if (S.page === 'clip') call('clips').then(function (r) { S.clips = r.clips || []; render(); });
      toast('已收纳到剪贴板');
    } else if (name === 'share') {
      if (S.page === 'clip') call('clips').then(function (r) { S.clips = r.clips || []; render(); });
    } else if (name === 'backupPreview') {
      S.backupPreview = data || {};
      if (S.page === 'backup') render();
      toast('备份文件已读取');
    } else if (name === 'backupApplied') {
      toast('备份已导入');
      boot(true);
    } else if (name === 'notif') {
      S.notifGroups = data.groups || [];
      if (S.page === 'notif') {
        call('notifList').then(function (r) {
          S.notif = { unread: r.unread, items: r.list || [], muted: r.muted || [] };
          S.notifGroups = r.group || [];
          render();
        });
      } else if (S.page === 'home') render();
    } else if (name === 'notifAi') {
      S.notifAi = data || {};
      if (S.page === 'notif') render();
    } else if (name === 'books') {
      var all = data.all || data;
      if (all && all.books) {
        S.books = all.books || [];
        S.bookStats = all.stats || {};
        S.bookCfg = all.config || S.bookCfg;
        if (S.page === 'readerShelf') render();
      } else {
        reloadShelf();
      }
    } else if (name === 'geo') {
      toast('你' + (data.event === 'enter' ? '到' : '离开') + (data.name || '地点') + '了');
      if (S.page === 'geo') call('geoState').then(function (r) { S.geo = r.geo || {}; render(); });
    } else if (name === 'geoPoint') {
      if (S.page === 'geo') call('geoState').then(function (r) { S.geo = r.geo || {}; render(); });
    } else if (name === 'quiet') {
      if (S.page === 'policy') call('policy').then(function (r) { S.policy = r.policy || {}; render(); });
    } else if (name === 'tasks') {
      if (S.page === 'tasks' || S.page === 'home') call('tasks').then(function (r) { applyTasks(r); if (S.page === 'tasks') render(); });
    } else if (name === 'kb') {
      nativeKb = num(data && data.h, 0);
      applyKeyboard(nativeKb);
    } else if (name === 'chatImage') {
      if (data && data.data) {
        if (S.chatVideo && S.chatVideo.id) call('chatVideoDelete', { id: S.chatVideo.id }).catch(function () { });
        S.chatVideo = null;
        S.chatFile = null;
        S.chatImg = data.data;
        if (S.page !== 'chat') S.page = 'chat';
        render();
      } else if (data && data.cancel) {
        toast('没选图片');
      }
    } else if (name === 'chatTextFile') {
      if (data && data.ok) {
        if (S.chatVideo && S.chatVideo.id) call('chatVideoDelete', { id: S.chatVideo.id }).catch(function () { });
        S.chatVideo = null;
        S.chatImg = '';
        S.page = 'chat';
        S.chatFile = { id: data.id, name: data.name, summary: data.summary, expiresAt: data.expiresAt };
        closeChatAttachments();
        render();
        toast('文件已附加；应用不限制文件字节数，单轮分析受模型上下文限制');
      } else toast((data && data.error) || '文件读取失败');
    } else if (name === 'chatVideo') {
      if (data && data.ok) {
        if (S.chatVideo && S.chatVideo.id) call('chatVideoDelete', { id: S.chatVideo.id }).catch(function () { });
        S.chatImg = '';
        S.chatFile = null;
        S.chatVideo = { id: data.id, name: data.name, mimeType: data.mimeType, bytes: data.bytes, expiresAt: data.expiresAt };
        S.page = 'chat';
        closeChatAttachments();
        render();
        toast('视频已附加；发送后将分析整段视频和音轨');
      } else toast((data && data.error) || '视频读取失败');
    } else if (name === 'plugins') {
      if (S.page === 'plugins') {
        call('plugins').then(function (r) { S.plugins = r.plugins || []; render(); });
        call('dshboxStatus').then(function (r) { S.dshbox = r || {}; render(); });
      }
    } else if (name === 'balance') {
      if (data.text) { S.balance = data.text; updateTop(); }
    } else if (name === 'balanceVision') {
      // 同一地址时只显示一条，视觉事件不再单独更新
      if (data.text && !sameBal()) { S.balanceVision = data.text; updateTop(); }
    } else if (name === 'tts') {
      S.tts = data || {};
      if (data && data.voices) S.voices = data.voices;
      if (S.page === 'companion') render();
    } else if (name === 'moodlog') {
      if (S.page === 'report' || S.page === 'home') render();
    } else if (name === 'policy') {
      if (S.page === 'policy') call('policy').then(function (r) { S.policy = r.policy || {}; render(); });
    }
  };

  function refreshPerms() {
    call('perms').then(function (r) {
      S.perms = r.perms || {};
      if (S.page === 'settings' || S.page === 'companion' || S.page === 'reminders') render();
    }).catch(function () { });
  }

  /* ---------------- 启动 ---------------- */
  function boot(silent) {
    return call('boot').then(function (r) {
      S.boot = r;
      S.cfg = r.cfg || {};
      var qqPersonaState = r.qqPersonas || {};
      S.qqPersonas = qqPersonaState.items || [];
      S.qqPersonaSelected = qqPersonaState.selectedId || S.cfg.qqGroupPersonaId || '';
      S.skinId = S.cfg.skinId || 'original';
      S.persona = r.persona || {};
      S.personaSeed = r.personaSeed || {};
      S.profile = r.profile || {};
      S.perms = r.perms || {};
      S.state = { running: !!(r.state && r.state.running), locked: !!(r.cfg && r.cfg.locked), size: r.cfg && r.cfg.sizeDp, music: r.music || {}, storage: r.storage || {}, stats: r.stats || {}, days: r.days, pkg: (r.state && r.state.pkg) || '', screenText: (r.state && r.state.screenText) || '' };
      S.catalog = r.catalog || [];
      S.memories = r.memories || [];
      S.companionRules = r.companionRules || [];
      S.reminders = r.reminders || [];
      S.ad = r.ad || {};
      S.adLines = r.adLines || [];
      S.chat = expandStoredChatBubbles(r.chat || []);
      S.mood = r.mood || {};
      S.pomo = r.pomo || { running: false };
      S.policy = r.policy || {};
      S.policyJson = r.policyJson || {};
      S.health = r.health || [];
      S.plugins = r.plugins || [];
      S.stickers = r.stickers || [];
      S.tasks = r.tasks || [];
      S.taskFocus = r.taskFocus || {};
      S.taskSummary = r.taskSummary || {};
      S.events = r.events || [];
      S.notif = r.notif || {};
      S.notifGroups = r.notifList || [];
      S.geo = r.geo || {};
      S.well = r.well || {};
      S.wellSteps = r.wellSteps || [];
      S.musicx = r.musicx || {};
      S.sync = r.sync || {};
      S.tts = r.tts || {};
      S.voices = r.voices || [];
      S.clips = r.clips || [];
      S.moods = r.moods || [];
      S.memSummary = r.memSummary || {};
      S.intimacy = r.intimacy || {};
      S.traits = r.traits || {};
      S.diary = r.diary || {};
      S.timeline = r.timeline || {};
      S.caring = r.caring || {};
      S.contract = r.contract || { isSet: false };
      if (S.contract.needsSeed) {
        // 旧版覆盖安装：弹出一次人格升级表单，保存后永久锁定
        S.showOnboarding = 1;
        S.seedUpgrade = 1;
        S.obSel = null;
        S.obStep = 1;
      }
      S.emotion = r.emotion || {};
      S.memReviewDue = !!r.memReviewDue;
      S.report = r.report || {};
      S.memItems = [];
      S.state.unread = num(r.unread, 0);
      S.state.pending = (r.reminders || []).length;
      S.apps = null;
      S.gameQuery = '';
      S.gameSel = null;
      if (!silent) { S.page = 'home'; }
      var b = $('#boot');
      if (b) { b.classList.add('gone'); setTimeout(function () { if (b.parentNode) b.parentNode.removeChild(b); }, 500); }
      render();
      loadSkins();
      if (S.cfg.apiKey) {
        setTimeout(refreshAllBalance, 800);
        if (!window.__balanceTimer) window.__balanceTimer = setInterval(refreshAllBalance, 30000);
      }
      if (!silent && !S.cfg.apiKey) {
        setTimeout(function () { toast('先绑定 API Key，解锁完整陪伴'); }, 1200);
      }
    }).catch(function (e) {
      var b = $('#boot');
      if (b) { b.classList.add('gone'); setTimeout(function () { if (b.parentNode) b.parentNode.removeChild(b); }, 500); }
      toast('启动失败：' + e.message);
    });
  }

  /* ========== [任务二] 全局深海星云背景 · 双实现 ==========
   * 实现1（默认，AMBIENT_MODE='webgl'）：纯代码手写 WebGL1 片元着色器实时生成深海星云——
   *   基调深幽蓝 #030816，青色辉光 #00f3ff 与暗紫极光缓慢漂移；
   *   半分辨率渲染 + 约 24fps 限速，页面隐藏/识海全屏时自动停绘，低端机无压力；
   *   不引入任何第三方库 → 无新增文件、无需改动 build.ps1 加密清单、无跨域风险。
   * 实现2（备用，AMBIENT_MODE='css'）：纯 CSS radial-gradient 巨大模糊光斑 + 极慢漂移，
   *   见 app.css 的 body.ambient-css；WebGL 不可用时也会自动回退到这里。 */
  var AMBIENT_MODE = 'off'; /* 'off'=接手原观感（#07090f + 紫/青/粉极光）；改 'webgl' 或 'css' 可重新启用深海星云 */

  function initAmbientBackground() {
    if (!document.body) return;
    if (AMBIENT_MODE === 'off') return;
    if (AMBIENT_MODE !== 'webgl') { document.body.classList.add('ambient-css'); return; }
    var canvas = document.createElement('canvas');
    canvas.id = 'ambientBg';
    canvas.setAttribute('aria-hidden', 'true');
    var aurora = document.querySelector('.aurora');
    if (aurora && aurora.parentNode) aurora.parentNode.insertBefore(canvas, aurora);
    else document.body.insertBefore(canvas, document.body.firstChild);

    var gl = null;
    try {
      gl = canvas.getContext('webgl', { alpha: false, antialias: false, depth: false, stencil: false, powerPreference: 'low-power' })
        || canvas.getContext('experimental-webgl');
    } catch (_) { gl = null; }
    if (!gl) { document.body.classList.add('ambient-css'); return; }
    /* 软件渲染（SwiftShader 等）设备上连半分辨率 shader 背景都是负担，回退纯 CSS 光斑 */
    var glName = '';
    try {
      var dbg = gl.getExtension('WEBGL_debug_renderer_info');
      glName = String(dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : (gl.getParameter(gl.RENDERER) || ''));
    } catch (_) { glName = ''; }
    if (/swiftshader|software|llvmpipe|basic render|microsoft/i.test(glName)) {
      document.body.classList.add('ambient-css');
      return;
    }

    var VS = 'attribute vec2 aPos;void main(){gl_Position=vec4(aPos,0.0,1.0);}';
    var FS = [
      'precision mediump float;',
      'uniform vec2 uRes;uniform float uT;',
      'float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}',
      'float noise(vec2 p){vec2 i=floor(p);vec2 f=fract(p);vec2 u=f*f*(3.0-2.0*f);',
      'return mix(mix(hash(i),hash(i+vec2(1.0,0.0)),u.x),mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0,1.0)),u.x),u.y);}',
      'float fbm(vec2 p){float v=0.0;float a=0.5;for(int i=0;i<3;i++){v+=a*noise(p);p=p*2.03+vec2(1.7,9.2);a*=0.5;}return v;}',
      'void main(){',
      ' vec2 uv=gl_FragCoord.xy/uRes;',
      ' vec2 p=uv-0.5;p.x*=uRes.x/uRes.y;',
      ' float t=uT;',
      ' vec3 col=vec3(0.012,0.031,0.086);', /* #030816 深幽蓝基调 */
      ' vec2 q1=p-vec2(0.20*sin(t*0.05),0.10*cos(t*0.04)+0.10);',
      ' col+=vec3(0.0,0.953,1.0)*exp(-dot(q1,q1)*3.1)*(0.085+0.045*sin(t*0.23));', /* #00f3ff 青色辉光主斑 */
      ' vec2 q2=p-vec2(-0.36+0.12*cos(t*0.037),-0.28+0.10*sin(t*0.045));',
      ' col+=vec3(0.09,0.20,0.62)*exp(-dot(q2,q2)*2.5)*0.17;', /* 深蓝副斑 */
      ' vec2 q3=p-vec2(0.06*sin(t*0.03),-0.06+0.05*cos(t*0.026));',
      ' col+=vec3(0.32,0.18,0.86)*exp(-dot(q3,q3)*1.5)*0.05;', /* 极淡紫极光 */
      ' float n=fbm(p*2.2+vec2(t*0.03,-t*0.02));',
      ' col+=vec3(0.04,0.27,0.38)*n*n*0.11;', /* 细星云纹理 */
      ' float vg=smoothstep(1.30,0.34,length(p));',
      ' col*=0.52+0.48*vg;', /* 暗角，四角沉入深海黑暗 */
      ' gl_FragColor=vec4(col,1.0);}'
    ].join('\n');

    function compile(type, src) {
      var s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) { gl.deleteShader(s); return null; }
      return s;
    }
    var vs = compile(gl.VERTEX_SHADER, VS);
    var fs = compile(gl.FRAGMENT_SHADER, FS);
    var prog = null;
    if (vs && fs) {
      prog = gl.createProgram();
      gl.attachShader(prog, vs);
      gl.attachShader(prog, fs);
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) prog = null;
    }
    if (!prog) { document.body.classList.add('ambient-css'); return; }
    gl.useProgram(prog);

    /* 单个全屏三角形，无需索引缓冲 */
    var buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    var loc = gl.getAttribLocation(prog, 'aPos');
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    var uRes = gl.getUniformLocation(prog, 'uRes');
    var uT = gl.getUniformLocation(prog, 'uT');

    var W = 0, H = 0, last = 0, fitTimer = 0;
    var SCALE = 0.5; /* 半分辨率：低成本且自带朦胧感，正适合背景光斑 */
    function fit() {
      var dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = Math.max(2, Math.floor(window.innerWidth * dpr * SCALE));
      H = Math.max(2, Math.floor(window.innerHeight * dpr * SCALE));
      if (W > 720) { H = Math.floor(H * 720 / W); W = 720; } /* 宽度封顶 720 像素 */
      canvas.width = W;
      canvas.height = H;
      gl.viewport(0, 0, W, H);
    }
    function frame(now) {
      requestAnimationFrame(frame);
      /* 页面隐藏由系统节流；识海全屏自带背景，停绘省电 */
      if (document.hidden || document.body.classList.contains('mem-graph-open')) return;
      if (now - last < 40) return; /* 约 24fps，超慢漂移足够 */
      last = now;
      gl.uniform2f(uRes, W, H);
      gl.uniform1f(uT, now * 0.001);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    fit();
    window.addEventListener('resize', function () {
      clearTimeout(fitTimer);
      fitTimer = setTimeout(fit, 120);
    });
    requestAnimationFrame(frame);
  }

  window.addEventListener('load', function () {
    initAmbientBackground();
    setTimeout(function () { boot(false); }, 80);
  });
})();
