/* DSH PET · living memory constellation
 * Nodes are local memories. Edges are supplied, reproducible relations only.
 * WebGL uses batched THREE.Points / THREE.LineSegments to keep 8,000 nodes light.
 */
(function (root) {
  'use strict';

  var TAU = Math.PI * 2;

  var TYPE_LIST = [
    ['fact', '事实', '#79b9ff'], ['preference', '偏好', '#b08cff'],
    ['person', '人物', '#56dfc4'], ['project', '项目', '#ff86b8'],
    ['event', '事件', '#ffd36f'], ['plan', '计划', '#8fdfa9'],
    ['concern', '惦记', '#ff9d8d'], ['commitment', '承诺', '#f5d27a'],
    ['observation', '印象', '#a8b3cc'], ['emotion', '心情', '#ff6fae'],
    ['daily', '日常', '#4dd8d0'], ['health', '健康', '#ff785e'],
    ['place', '地点', '#66a7ff'], ['learning', '学习', '#95dc8b'],
    ['interest', '兴趣', '#d095ff'], ['goal', '目标', '#ffbe55'],
    ['experience', '经历', '#69dff5']
  ];
  var TYPE_INFO = Object.create(null);
  TYPE_LIST.forEach(function (item) { TYPE_INFO[item[0]] = { label: item[1], color: item[2] }; });
  // dsh-chain-inspired memory neighborhoods: stable pools, deterministic local
  // orbits, and only evidence-backed links between the actual records.
  var POOL_LIST = [
    ['identity', '身份', '#56dfc4', [-0.62, 0.20, 0.76]],
    ['taste', '偏好', '#b08cff', [-0.78, -0.38, 0.22]],
    ['growth', '成长', '#ff86b8', [0.08, 0.76, 0.54]],
    ['intentions', '计划', '#8fdfa9', [0.78, 0.42, 0.30]],
    ['moments', '时刻', '#ffd36f', [0.70, -0.56, -0.32]],
    ['feelings', '情绪', '#ff6fae', [-0.10, -0.70, -0.68]],
    ['world', '生活', '#66a7ff', [-0.72, 0.24, -0.66]],
    ['observations', '观察', '#a8b3cc', [0.44, 0.38, -0.82]]
  ];
  var POOL_BY_TYPE = {
    fact: 'identity', person: 'identity',
    preference: 'taste', interest: 'taste',
    project: 'growth', learning: 'growth',
    plan: 'intentions', goal: 'intentions', commitment: 'intentions',
    event: 'moments', daily: 'moments', experience: 'moments',
    concern: 'feelings', emotion: 'feelings',
    health: 'world', place: 'world', observation: 'observations'
  };
  var POOL_RADIUS = 34;
  var MAX_NODES = 8010;
  var MAX_LINKS = 14400;
  var host = null;
  var renderer = null;
  var scene = null;
  var camera = null;
  var graphGroup = null;
  var nodeGeometry = null;
  var nodeMaterial = null;
  var nodePoints = null;
  var hubGeometry = null;
  var hubPoints = null;
  var categoryGeometry = null;
  var categoryMaterial = null;
  var categoryLines = null;
  var hubPoolNames = [];
  var clusterLabelLayer = null;
  var clusterLabels = Object.create(null);
  var linkGeometry = null;
  var linkMaterial = null;
  var linkLines = null;
  var starGeometry = null;
  var starPoints = null;
  var fallbackCanvas = null;
  var fallbackContext = null;
  var fallbackSprites = Object.create(null);
  var observer = null;
  var raf = 0;
  var lastFrame = 0;
  var selectedId = '';
  var selectedEdges = [];
  var selectionPulseStart = 0;
  var selectionPulseUntil = 0;
  var highlighted = Object.create(null);
  var related = Object.create(null);
  var filterType = 'all';
  var autoRotate = false;
  var suspended = false;
  var destroyed = true;
  var expanded = false;
  var yaw = 0;
  var pitch = 0;
  var zoomScale = 1;
  var fitDistance = 180;
  var layoutRadius = 56;
  var pointers = Object.create(null);
  var gestureDistance = 0;
  var dragMoved = false;
  var suppressClick = false;
  var suppressTimer = 0;
  var clickHandler = null;
  var downHandler = null;
  var moveHandler = null;
  var upHandler = null;
  var wheelHandler = null;
  var visibilityHandler = null;
  var selectionCallback = null;
  var nodes = [];
  var links = [];
  var records = [];
  var nodesById = Object.create(null);
  var nodeIndex = Object.create(null);
  var adjacency = Object.create(null);
  var graphStats = { memories: 0, experiences: 0, topics: 0, sharedLinks: 0, timelineLinks: 0 };
  var mode = 'unavailable';

  function hash(text) {
    var h = 2166136261;
    text = String(text || '');
    for (var i = 0; i < text.length; i++) { h ^= text.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0) / 4294967295;
  }

  function hexRgb(hex) {
    var n = parseInt(String(hex || '#ffffff').slice(1), 16);
    return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255];
  }

  function typeInfo(type) { return TYPE_INFO[type] || TYPE_INFO.fact; }

  function idOf(value) { return String(value && typeof value === 'object' ? value.id : value || ''); }

  function normalizeData(payload) {
    payload = payload || {};
    var source = Array.isArray(payload.nodes) ? payload.nodes.slice(0, MAX_NODES) : [];
    var rawLinks = Array.isArray(payload.links) ? payload.links.slice(0, MAX_LINKS) : [];
    var seen = Object.create(null);
    nodes = source.filter(function (n) {
      if (!n) return false;
      n = n;
      n.id = String(n.id || '');
      if (!n.id || seen[n.id]) return false;
      seen[n.id] = true;
      n.kind = n.kind || 'memory';
      n.type = n.kind === 'experience' ? 'experience' : (TYPE_INFO[n.type] ? n.type : 'fact');
      n.typeLabel = n.typeLabel || typeInfo(n.type).label;
      n.importance = Math.max(0, Math.min(1, Number(n.importance) || 0.25));
      return true;
    });
    nodesById = Object.create(null);
    nodeIndex = Object.create(null);
    adjacency = Object.create(null);
    nodes.forEach(function (n, i) { nodesById[n.id] = n; nodeIndex[n.id] = i; adjacency[n.id] = []; });
    var pairs = Object.create(null);
    links = [];
    rawLinks.forEach(function (link) {
      if (!link || link.relationKind !== 'shared_tags_or_keywords') return;
      var a = idOf(link.source), b = idOf(link.target);
      if (!nodesById[a] || !nodesById[b] || a === b) return;
      var key = a < b ? a + '>' + b : b + '>' + a;
      if (pairs[key]) return;
      pairs[key] = true;
      var item = { source: a, target: b, relation: String(link.relation || ''), sharedCount: Math.max(1, Number(link.sharedCount) || 1) };
      links.push(item);
      adjacency[a].push(b);
      adjacency[b].push(a);
    });
    records = nodes.slice();
    var types = Object.create(null);
    nodes.forEach(function (n) { types[n.type] = true; });
    var memories = nodes.filter(function (n) { return n.kind !== 'experience'; }).length;
    graphStats = {
      memories: memories,
      experiences: nodes.length - memories,
      totalMemories: Math.max(memories, Number(payload.total) || 0),
      truncated: !!payload.truncated || Number(payload.total || 0) > memories,
      topics: Object.keys(types).length,
      sharedLinks: links.length,
      timelineLinks: 0
    };
  }

  function layoutNodes() {
    var groups = Object.create(null);
    var centers = Object.create(null);
    var golden = Math.PI * (3 - Math.sqrt(5));
    POOL_LIST.forEach(function (pool) {
      var v = pool[3], length = Math.hypot(v[0], v[1], v[2]) || 1;
      centers[pool[0]] = [v[0] / length * POOL_RADIUS, v[1] / length * POOL_RADIUS, v[2] / length * POOL_RADIUS];
      groups[pool[0]] = [];
    });
    nodes.forEach(function (n) {
      n._pool = POOL_BY_TYPE[n.type] || 'identity';
      groups[n._pool].push(n);
    });
    POOL_LIST.forEach(function (pool) {
      var name = pool[0], group = groups[name], center = centers[name];
      if (!group.length) return;
      var spread = Math.min(16, Math.max(7.5, Math.sqrt(group.length) * 0.46));
      group.forEach(function (n, j) {
        var t = (j + 0.5) / group.length;
        var sy = 1 - 2 * t;
        var sr = Math.sqrt(Math.max(0, 1 - sy * sy));
        var sa = golden * j + hash(n.id + ':layout') * 0.42;
        var radius = spread * (0.35 + 0.65 * Math.sqrt(t));
        var noise = hash(n.id + ':depth') - 0.5;
        n._base = [center[0] + Math.cos(sa) * sr * radius,
          center[1] + sy * radius,
          center[2] + Math.sin(sa) * sr * radius + noise * spread * 0.24];
        n._p = n._base.slice();
      });
    });

    // Relation-aware relaxation gently pulls only documented neighbors together.
    // No category-wide or guessed semantic edges are ever introduced.
    for (var pass = 0; pass < 5; pass++) {
      var next = new Array(nodes.length);
      nodes.forEach(function (n, i) {
        var near = adjacency[n.id] || [];
        if (!near.length) { next[i] = n._p.slice(); return; }
        var avg = [0, 0, 0], used = 0;
        near.forEach(function (id) {
          var other = nodes[nodeIndex[id]];
          if (!other) return;
          avg[0] += other._p[0]; avg[1] += other._p[1]; avg[2] += other._p[2]; used++;
        });
        if (!used) { next[i] = n._p.slice(); return; }
        var pull = 0.13;
        next[i] = [n._p[0] * (1 - pull) + avg[0] / used * pull,
          n._p[1] * (1 - pull) + avg[1] / used * pull,
          n._p[2] * (1 - pull) + avg[2] / used * pull];
      });
      nodes.forEach(function (n, i) { n._p = next[i]; });
    }
    var maxR = 1;
    nodes.forEach(function (n) { maxR = Math.max(maxR, Math.hypot(n._p[0], n._p[1], n._p[2])); });
    layoutRadius = maxR;
    fitDistance = Math.max(96, maxR / Math.tan(24 * Math.PI / 360) * 1.10);
  }

  function visible(n) { return filterType === 'all' || n.type === filterType; }

  function refreshLegend() {
    if (!root.document || !host) return;
    var legend = host.parentNode && host.parentNode.parentNode && host.parentNode.parentNode.querySelector('.mem-graph-key');
    if (!legend) return;
    legend.textContent = '';
    var counts = Object.create(null);
    nodes.forEach(function (n) { if (visible(n)) counts[n.type] = (counts[n.type] || 0) + 1; });
    TYPE_LIST.forEach(function (item) {
      if (!counts[item[0]]) return;
      var chip = root.document.createElement('span');
      chip.className = 'mem-graph-key-item';
      var dot = root.document.createElement('i');
      dot.style.backgroundColor = item[2];
      dot.style.color = item[2];
      var label = root.document.createElement('span');
      label.textContent = item[1] + ' ' + counts[item[0]];
      chip.appendChild(dot); chip.appendChild(label); legend.appendChild(chip);
    });
    var category = root.document.createElement('span');
    category.className = 'mem-graph-key-item relation';
    var categoryLine = root.document.createElement('i');
    var categoryText = root.document.createElement('span');
    categoryLine.className = 'mg-category-link'; categoryText.textContent = '类别纤维';
    category.appendChild(categoryLine); category.appendChild(categoryText); legend.appendChild(category);
    var relation = root.document.createElement('span');
    relation.className = 'mem-graph-key-item relation';
    var line = root.document.createElement('i');
    var text = root.document.createElement('span');
    line.className = 'mg-link'; text.textContent = '真实关联';
    relation.appendChild(line); relation.appendChild(text); legend.appendChild(relation);
  }

  function updateTypeButtons() {
    if (!root.document || !host) return;
    var screen = host.parentNode && host.parentNode.parentNode;
    var filters = screen && screen.querySelector('.mem-graph-filters');
    if (!filters) return;
    var present = Object.create(null);
    nodes.forEach(function (n) { present[n.type] = true; });
    Array.prototype.forEach.call(filters.querySelectorAll('[data-act="memGraphFilter"]'), function (button) {
      var type = button.getAttribute('data-type');
      button.hidden = type !== 'all' && !present[type];
    });
  }

  function cancelFrame() { if (raf) root.cancelAnimationFrame(raf); raf = 0; }

  function frame(now) {
    raf = 0;
    var pulseActive = now < selectionPulseUntil;
    if (destroyed || suspended || (!autoRotate && !pulseActive) || document.hidden) return;
    if (mode !== '3d' && lastFrame && now - lastFrame < 40) {
      raf = root.requestAnimationFrame(frame);
      return;
    }
    var dt = lastFrame ? Math.min(48, now - lastFrame) : 16;
    lastFrame = now;
    if (autoRotate) {
      if (graphGroup) graphGroup.rotation.y += dt * 0.000055;
      else if (fallbackCanvas) yaw += dt * 0.000055;
    }
    if (nodeMaterial && nodeMaterial.uniforms) nodeMaterial.uniforms.uTime.value += dt * 0.001;
    draw(now);
    if (autoRotate || now < selectionPulseUntil) raf = root.requestAnimationFrame(frame);
  }

  function scheduleFrame() {
    var now = root.performance && root.performance.now ? root.performance.now() : Date.now();
    if (destroyed || suspended || (!autoRotate && now >= selectionPulseUntil) || raf) return;
    lastFrame = 0;
    raf = root.requestAnimationFrame(frame);
  }

  function draw(now) {
    if (renderer && scene && camera && host && !destroyed) {
      try { renderer.render(scene, camera); } catch (ignored) {
        if (activateCanvasFallback() && fallbackContext) drawFallback(now);
      }
    } else if (fallbackCanvas && fallbackContext) drawFallback(now);
    updateClusterLabels();
  }

  function cameraDistance() { return fitDistance * zoomScale * (expanded ? 0.68 : 1); }

  function resize() {
    if (!host || destroyed) return;
    var width = Math.max(1, host.clientWidth || 1), height = Math.max(1, host.clientHeight || 1);
    if (renderer && camera) {
      renderer.setSize(width, height, false);
      camera.aspect = width / height;
      var vTan = Math.tan(24 * Math.PI / 180);
      var horizontalDistance = layoutRadius / Math.max(0.08, vTan * camera.aspect);
      fitDistance = Math.max(100, layoutRadius / vTan * 1.08, horizontalDistance * 1.08);
      camera.position.set(0, 0, cameraDistance());
      camera.updateProjectionMatrix();
    }
    if (fallbackCanvas) {
      var fovTan = Math.tan(24 * Math.PI / 180);
      var horizontalDistance = layoutRadius / Math.max(0.08, fovTan * width / height);
      fitDistance = Math.max(96, layoutRadius / fovTan * 1.10, horizontalDistance * 1.10);
      var ratio = Math.min(1.5, Math.max(1, Number(root.devicePixelRatio) || 1));
      fallbackCanvas.width = Math.max(1, Math.round(width * ratio));
      fallbackCanvas.height = Math.max(1, Math.round(height * ratio));
      fallbackCanvas.style.width = width + 'px';
      fallbackCanvas.style.height = height + 'px';
      fallbackContext.setTransform(ratio, 0, 0, ratio, 0, 0);
    }
    draw();
  }

  function makeNodeShader(THREE) {
    return new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, depthTest: true,
      blending: THREE.AdditiveBlending,
      uniforms: { uPixelRatio: { value: Math.min(root.devicePixelRatio || 1, 1.5) }, uTime: { value: 0 } },
      vertexShader: 'attribute vec3 color; attribute float aSize; attribute float aAlpha; attribute float aEnergy; varying vec3 vColor; varying float vAlpha; varying float vEnergy; uniform float uPixelRatio; uniform float uTime; void main(){ vec4 mv=modelViewMatrix*vec4(position,1.0); vColor=color; vAlpha=aAlpha; vEnergy=aEnergy; float breathe=1.0+0.045*sin(uTime*1.1+aSize*0.37); gl_PointSize=clamp(aSize*breathe*uPixelRatio*(220.0/max(75.0,-mv.z)),3.0,42.0); gl_Position=projectionMatrix*mv; }',
      fragmentShader: 'varying vec3 vColor; varying float vAlpha; varying float vEnergy; void main(){ vec2 p=gl_PointCoord-vec2(0.5); float d=length(p); if(d>0.5 || vAlpha<0.01) discard; float halo=exp(-d*d*31.0)*0.38; float rim=1.0-smoothstep(0.34,0.48,d); float core=1.0-smoothstep(0.02,0.24,d); float a=(halo+rim*0.22+core*0.84)*vAlpha; vec3 c=vColor*(0.74+vEnergy*0.92)+vec3(core*0.46); gl_FragColor=vec4(c,a); }',
      vertexColors: true
    });
  }

  function makeLinkShader(THREE) {
    return new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, depthTest: true,
      blending: THREE.AdditiveBlending,
      vertexShader: 'attribute vec3 color; attribute float aAlpha; varying vec3 vColor; varying float vAlpha; void main(){ vColor=color; vAlpha=aAlpha; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }',
      fragmentShader: 'varying vec3 vColor; varying float vAlpha; void main(){ gl_FragColor=vec4(vColor,vAlpha*0.72); }',
      vertexColors: true
    });
  }

  function buildWebGL(container) {
    var THREE = root.THREE;
    var canvas = root.document.createElement('canvas');
    canvas.className = 'mem-graph-webgl-canvas';
    canvas.setAttribute('aria-label', '可缩放旋转的 3D 本地记忆网络');
    host.textContent = '';
    host.appendChild(canvas);
    try {
      renderer = new THREE.WebGLRenderer({ canvas: canvas, alpha: true, antialias: false,
        powerPreference: 'low-power', preserveDrawingBuffer: false });
    } catch (e) { return false; }
    renderer.setPixelRatio(Math.min(root.devicePixelRatio || 1, 1.5));
    renderer.setClearColor(0x030815, 0);
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(48, 1, 0.1, 900);
    camera.position.set(0, 0, cameraDistance());
    graphGroup = new THREE.Group();
    scene.add(graphGroup);

    var poolCounts = countPools();
    var categoryPositions = new Float32Array(nodes.length * 6);
    var categoryColors = new Float32Array(nodes.length * 6);
    var categoryAlphas = new Float32Array(nodes.length * 2);
    nodes.forEach(function (n, i) {
      var center = centersForPool(n._pool), pool = poolForName(n._pool), rgb = hexRgb(pool ? pool[2] : '#9cb3d8');
      var off = i * 6;
      categoryPositions.set(center, off);
      categoryPositions.set(n._p, off + 3);
      categoryColors.set([rgb[0] * 0.68, rgb[1] * 0.68, rgb[2] * 0.68,
        rgb[0] * 0.82, rgb[1] * 0.82, rgb[2] * 0.82], off);
      categoryAlphas[i * 2] = categoryAlphas[i * 2 + 1] = 0.19;
    });
    categoryGeometry = new THREE.BufferGeometry();
    categoryGeometry.setAttribute('position', new THREE.BufferAttribute(categoryPositions, 3));
    categoryGeometry.setAttribute('color', new THREE.BufferAttribute(categoryColors, 3));
    categoryGeometry.setAttribute('aAlpha', new THREE.BufferAttribute(categoryAlphas, 1));
    categoryMaterial = makeLinkShader(THREE);
    categoryLines = new THREE.LineSegments(categoryGeometry, categoryMaterial);
    graphGroup.add(categoryLines);

    var positions = new Float32Array(nodes.length * 3);
    var colors = new Float32Array(nodes.length * 3);
    var sizes = new Float32Array(nodes.length);
    var alphas = new Float32Array(nodes.length);
    var energies = new Float32Array(nodes.length);
    nodes.forEach(function (n, i) {
      var rgb = hexRgb(typeInfo(n.type).color);
      positions[i * 3] = n._p[0]; positions[i * 3 + 1] = n._p[1]; positions[i * 3 + 2] = n._p[2];
      colors[i * 3] = rgb[0]; colors[i * 3 + 1] = rgb[1]; colors[i * 3 + 2] = rgb[2];
      // Compact memory points keep relation clusters readable; selection adds
      // a restrained size lift in refreshStyles().
      sizes[i] = 5.3 + n.importance * 3.2 + (n.pinned ? 0.8 : 0);
      alphas[i] = 1;
      energies[i] = 0.16;
    });
    nodeGeometry = new THREE.BufferGeometry();
    nodeGeometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    nodeGeometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
    nodeGeometry.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1));
    nodeGeometry.setAttribute('aAlpha', new THREE.BufferAttribute(alphas, 1));
    nodeGeometry.setAttribute('aEnergy', new THREE.BufferAttribute(energies, 1));
    nodeMaterial = makeNodeShader(THREE);
    nodePoints = new THREE.Points(nodeGeometry, nodeMaterial);
    graphGroup.add(nodePoints);

    var edgePositions = new Float32Array(links.length * 6);
    var edgeColors = new Float32Array(links.length * 6);
    var edgeAlphas = new Float32Array(links.length * 2);
    links.forEach(function (link, i) {
      var a = nodes[nodeIndex[link.source]], b = nodes[nodeIndex[link.target]];
      var offset = i * 6;
      edgePositions[offset] = a._p[0]; edgePositions[offset + 1] = a._p[1]; edgePositions[offset + 2] = a._p[2];
      edgePositions[offset + 3] = b._p[0]; edgePositions[offset + 4] = b._p[1]; edgePositions[offset + 5] = b._p[2];
      var ca = hexRgb(typeInfo(a.type).color), cb = hexRgb(typeInfo(b.type).color);
      for (var k = 0; k < 3; k++) { edgeColors[offset + k] = ca[k] * 0.72; edgeColors[offset + 3 + k] = cb[k] * 0.72; }
      edgeAlphas[i * 2] = edgeAlphas[i * 2 + 1] = 0.46;
    });
    linkGeometry = new THREE.BufferGeometry();
    linkGeometry.setAttribute('position', new THREE.BufferAttribute(edgePositions, 3));
    linkGeometry.setAttribute('color', new THREE.BufferAttribute(edgeColors, 3));
    linkGeometry.setAttribute('aAlpha', new THREE.BufferAttribute(edgeAlphas, 1));
    linkMaterial = makeLinkShader(THREE);
    linkLines = new THREE.LineSegments(linkGeometry, linkMaterial);
    graphGroup.add(linkLines);

    var activePools = POOL_LIST.filter(function (pool) { return poolCounts[pool[0]] > 0; });
    hubPoolNames = activePools.map(function (pool) { return pool[0]; });
    var hubPositions = new Float32Array(activePools.length * 3);
    var hubColors = new Float32Array(activePools.length * 3);
    var hubSizes = new Float32Array(activePools.length);
    var hubAlphas = new Float32Array(activePools.length);
    var hubEnergies = new Float32Array(activePools.length);
    activePools.forEach(function (pool, i) {
      var center = centersForPool(pool[0]), rgb = hexRgb(pool[2]), count = poolCounts[pool[0]];
      hubPositions.set(center, i * 3);
      hubColors.set(rgb, i * 3);
      hubSizes[i] = Math.min(38, 23 + Math.sqrt(count) * 1.15);
      hubAlphas[i] = 0.76;
      hubEnergies[i] = 0.68;
    });
    hubGeometry = new THREE.BufferGeometry();
    hubGeometry.setAttribute('position', new THREE.BufferAttribute(hubPositions, 3));
    hubGeometry.setAttribute('color', new THREE.BufferAttribute(hubColors, 3));
    hubGeometry.setAttribute('aSize', new THREE.BufferAttribute(hubSizes, 1));
    hubGeometry.setAttribute('aAlpha', new THREE.BufferAttribute(hubAlphas, 1));
    hubGeometry.setAttribute('aEnergy', new THREE.BufferAttribute(hubEnergies, 1));
    hubPoints = new THREE.Points(hubGeometry, nodeMaterial);
    graphGroup.add(hubPoints);
    buildClusterLabels(activePools, poolCounts);

    var starPos = new Float32Array(520 * 3), starColors = new Float32Array(520 * 3);
    var starSizes = new Float32Array(520), starAlpha = new Float32Array(520), starEnergy = new Float32Array(520);
    for (var s = 0; s < 520; s++) {
      var u = hash('star-u:' + s) * 2 - 1, theta = hash('star-t:' + s) * Math.PI * 2;
      var rr = 105 + hash('star-r:' + s) * 145, sr = Math.sqrt(1 - u * u);
      starPos[s * 3] = Math.cos(theta) * sr * rr;
      starPos[s * 3 + 1] = u * rr;
      starPos[s * 3 + 2] = Math.sin(theta) * sr * rr;
      var tint = s % 5 === 0 ? [0.40, 0.90, 1] : s % 7 === 0 ? [0.72, 0.55, 1] : [0.48, 0.62, 0.82];
      starColors.set(tint, s * 3); starSizes[s] = 1.4 + hash('star-size:' + s) * 2.0;
      starAlpha[s] = 0.10 + hash('star-a:' + s) * 0.18; starEnergy[s] = 0;
    }
    starGeometry = new THREE.BufferGeometry();
    starGeometry.setAttribute('position', new THREE.BufferAttribute(starPos, 3));
    starGeometry.setAttribute('color', new THREE.BufferAttribute(starColors, 3));
    starGeometry.setAttribute('aSize', new THREE.BufferAttribute(starSizes, 1));
    starGeometry.setAttribute('aAlpha', new THREE.BufferAttribute(starAlpha, 1));
    starGeometry.setAttribute('aEnergy', new THREE.BufferAttribute(starEnergy, 1));
    starPoints = new THREE.Points(starGeometry, nodeMaterial);
    scene.add(starPoints);
    updateStyles();
    mode = '3d';
    resize();
    return true;
  }

  function drawFallback(now) {
    if (!fallbackCanvas || !fallbackContext || !host) return;
    var w = host.clientWidth || 1, h = host.clientHeight || 1, ctx = fallbackContext;
    ctx.clearRect(0, 0, w, h);
    var projected = new Array(nodes.length);
    var cy = Math.cos(pitch), sy = Math.sin(pitch), cx = Math.cos(yaw), sx = Math.sin(yaw);
    var distance = cameraDistance();
    var focal = h / (2 * Math.tan(24 * Math.PI / 180));
    function project(x, y, z) {
      var x1 = x * cx - z * sx, z1 = x * sx + z * cx;
      var y1 = y * cy - z1 * sy, z2 = y * sy + z1 * cy;
      var perspective = focal / Math.max(26, distance - z2);
      return { x: w * 0.5 + x1 * perspective, y: h * 0.52 - y1 * perspective, z: z2, perspective: perspective };
    }
    // A restrained, fixed star field and soft pool haze keep sparse memories
    // visible without inventing links or running an idle animation loop.
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    for (var star = 0; star < 58; star++) {
      var starX = hash('canvas-star-x:' + star) * w, starY = hash('canvas-star-y:' + star) * h;
      ctx.globalAlpha = 0.12 + hash('canvas-star-a:' + star) * 0.20;
      ctx.fillStyle = star % 6 === 0 ? '#8defff' : '#9fb7e8';
      ctx.beginPath(); ctx.arc(starX, starY, 0.45 + hash('canvas-star-s:' + star) * 0.65, 0, Math.PI * 2); ctx.fill();
    }
    var poolCounts = Object.create(null);
    nodes.forEach(function (n) { if (visible(n)) poolCounts[n._pool] = (poolCounts[n._pool] || 0) + 1; });
    POOL_LIST.forEach(function (pool) {
      if (!poolCounts[pool[0]]) return;
      var centerPoint = centersForPool(pool[0]);
      var center = project(centerPoint[0], centerPoint[1], centerPoint[2]);
      var radius = Math.min(w, h) * (0.05 + Math.min(0.035, Math.sqrt(poolCounts[pool[0]]) * 0.004));
      var color = hexRgb(pool[2]);
      var haze = ctx.createRadialGradient(center.x, center.y, 0, center.x, center.y, radius);
      haze.addColorStop(0, 'rgba(' + Math.round(color[0] * 255) + ',' + Math.round(color[1] * 255) + ',' + Math.round(color[2] * 255) + ',.075)');
      haze.addColorStop(1, 'rgba(' + Math.round(color[0] * 255) + ',' + Math.round(color[1] * 255) + ',' + Math.round(color[2] * 255) + ',0)');
      ctx.globalAlpha = 1; ctx.fillStyle = haze; ctx.fillRect(center.x - radius, center.y - radius, radius * 2, radius * 2);
    });
    ctx.restore();
    nodes.forEach(function (n, i) {
      var point = project(n._p[0], n._p[1], n._p[2]);
      var nodeScale = Math.max(0.78, Math.min(1.34, 140 / Math.max(90, distance - point.z)));
      projected[i] = { x: point.x, y: point.y, z: point.z,
        size: Math.max(2.8, (2.8 + n.importance * 1.05) * nodeScale), node: n };
    });
    // Category membership fibers lead from each small dated memory point to
    // its larger category orb. Verified record-to-record relations are drawn
    // separately below, with a brighter color and selection pulse.
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    POOL_LIST.forEach(function (pool) {
      var count = poolCounts[pool[0]] || 0;
      if (!count) return;
      var c = centersForPool(pool[0]), anchor = project(c[0], c[1], c[2]);
      ctx.beginPath();
      nodes.forEach(function (n, i) {
        if (visible(n) && n._pool === pool[0] && projected[i]) {
          ctx.moveTo(anchor.x, anchor.y); ctx.lineTo(projected[i].x, projected[i].y);
        }
      });
      var color = pool[2], rgb = hexRgb(color);
      ctx.strokeStyle = 'rgba(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ',.19)';
      ctx.lineWidth = 0.62; ctx.shadowBlur = 1.5; ctx.shadowColor = color; ctx.stroke();
      var hubRadius = Math.min(13, 7.5 + Math.sqrt(count) * 0.72);
      var glow = ctx.createRadialGradient(anchor.x, anchor.y, 0, anchor.x, anchor.y, hubRadius * 2.4);
      glow.addColorStop(0, 'rgba(255,255,255,.96)');
      glow.addColorStop(0.12, 'rgba(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ',.96)');
      glow.addColorStop(0.38, 'rgba(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ',.36)');
      glow.addColorStop(1, 'rgba(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ',0)');
      ctx.globalAlpha = 0.92; ctx.fillStyle = glow;
      ctx.beginPath(); ctx.arc(anchor.x, anchor.y, hubRadius * 2.4, 0, TAU); ctx.fill();
      ctx.globalAlpha = 0.98; ctx.fillStyle = color; ctx.shadowBlur = 9; ctx.shadowColor = color;
      ctx.beginPath(); ctx.arc(anchor.x, anchor.y, Math.max(2.1, hubRadius * 0.28), 0, TAU); ctx.fill();
    });
    ctx.restore();
    ctx.save(); ctx.globalCompositeOperation = 'lighter';
    ctx.beginPath();
    links.forEach(function (link) {
      var a = projected[nodeIndex[link.source]], b = projected[nodeIndex[link.target]];
      if (!a || !b || !visible(a.node) || !visible(b.node)) return;
      var active = highlighted[a.node.id] || highlighted[b.node.id] || selectedId === a.node.id || selectedId === b.node.id;
      if (!active) { ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); }
    });
    ctx.strokeStyle = 'rgba(119,205,239,.58)'; ctx.lineWidth = 1.05; ctx.shadowBlur = 3;
    ctx.shadowColor = 'rgba(82,211,255,.36)'; ctx.stroke();
    ctx.beginPath();
    links.forEach(function (link) {
      var a = projected[nodeIndex[link.source]], b = projected[nodeIndex[link.target]];
      if (!a || !b || !visible(a.node) || !visible(b.node)) return;
      if (!(highlighted[a.node.id] || highlighted[b.node.id] || selectedId === a.node.id || selectedId === b.node.id)) return;
      ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y);
    });
    ctx.strokeStyle = 'rgba(221,255,255,.96)'; ctx.lineWidth = 1.45; ctx.shadowBlur = 8;
    ctx.shadowColor = '#72ecff'; ctx.stroke();
    var frameTime = Number(now) || (root.performance && root.performance.now ? root.performance.now() : Date.now());
    if (selectedEdges.length && frameTime < selectionPulseUntil) {
      var progress = Math.max(0, Math.min(1, (frameTime - selectionPulseStart) / 1100));
      ctx.globalCompositeOperation = 'lighter'; ctx.shadowBlur = 9; ctx.shadowColor = '#d7fbff';
      selectedEdges.forEach(function (link, edgeIndex) {
        var a = projected[nodeIndex[link.source]], b = projected[nodeIndex[link.target]];
        if (!a || !b || !visible(a.node) || !visible(b.node)) return;
        var t = (progress + edgeIndex * 0.22) % 1;
        var x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t;
        ctx.globalAlpha = 0.42 + Math.sin(Math.PI * t) * 0.52;
        ctx.fillStyle = '#e6ffff'; ctx.beginPath(); ctx.arc(x, y, 1.6 + Math.sin(Math.PI * t) * 0.9, 0, Math.PI * 2); ctx.fill();
      });
      ctx.shadowBlur = 0; ctx.globalAlpha = 1;
    }
    projected.forEach(function (p) {
      if (!visible(p.node)) return;
      var color = typeInfo(p.node.type).color;
      var energy = highlighted[p.node.id] ? 1 : selectedId === p.node.id ? 0.85 : related[p.node.id] ? 0.58 : 0.12;
      var r = Math.max(2.7, p.size * (1 + energy * 0.18));
      var sprite = fallbackSprites[p.node.type];
      ctx.globalAlpha = (0.5 + Math.max(0, Math.min(0.48, (p.z + 50) / 240))) * (0.78 + energy * 0.22);
      if (sprite) ctx.drawImage(sprite, p.x - r * 2.45, p.y - r * 2.45, r * 4.9, r * 4.9);
      if (selectedId === p.node.id) {
        ctx.globalAlpha = 0.86; ctx.strokeStyle = '#e9ffff'; ctx.lineWidth = 1.3;
        ctx.shadowBlur = 9; ctx.shadowColor = color;
        ctx.beginPath(); ctx.arc(p.x, p.y, r * 1.25, 0, Math.PI * 2); ctx.stroke();
      }
      ctx.globalAlpha = 0.96; ctx.fillStyle = color; ctx.beginPath(); ctx.arc(p.x, p.y, r * 0.42, 0, Math.PI * 2); ctx.fill();
      ctx.globalAlpha = 0.72; ctx.fillStyle = '#f4ffff'; ctx.beginPath(); ctx.arc(p.x - r * 0.12, p.y - r * 0.13, Math.max(0.7, r * 0.13), 0, Math.PI * 2); ctx.fill();
    });
    ctx.restore();
  }

  function buildFallbackSprites() {
    fallbackSprites = Object.create(null);
    TYPE_LIST.forEach(function (item) {
      var sprite = root.document.createElement('canvas');
      sprite.width = sprite.height = 64;
      var context = sprite.getContext('2d');
      if (!context) return;
      var rgb = hexRgb(item[2]);
      var grad = context.createRadialGradient(32, 32, 0, 32, 32, 32);
      grad.addColorStop(0, 'rgba(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ',.92)');
      grad.addColorStop(0.24, 'rgba(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ',.72)');
      grad.addColorStop(0.58, 'rgba(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ',.22)');
      grad.addColorStop(1, 'rgba(' + Math.round(rgb[0] * 255) + ',' + Math.round(rgb[1] * 255) + ',' + Math.round(rgb[2] * 255) + ',0)');
      context.fillStyle = grad; context.fillRect(0, 0, 64, 64);
      fallbackSprites[item[0]] = sprite;
    });
  }

  function centersForPool(name) {
    for (var i = 0; i < POOL_LIST.length; i++) {
      if (POOL_LIST[i][0] === name) {
        var v = POOL_LIST[i][3], length = Math.hypot(v[0], v[1], v[2]) || 1;
        return [v[0] / length * POOL_RADIUS, v[1] / length * POOL_RADIUS, v[2] / length * POOL_RADIUS];
      }
    }
    return [0, 0, 0];
  }

  function poolForName(name) {
    for (var i = 0; i < POOL_LIST.length; i++) if (POOL_LIST[i][0] === name) return POOL_LIST[i];
    return null;
  }

  function countPools() {
    var counts = Object.create(null);
    nodes.forEach(function (n) { counts[n._pool] = (counts[n._pool] || 0) + 1; });
    return counts;
  }

  function buildClusterLabels(activePools, counts) {
    if (!host || !root.document) return;
    if (clusterLabelLayer && clusterLabelLayer.parentNode) clusterLabelLayer.parentNode.removeChild(clusterLabelLayer);
    clusterLabels = Object.create(null);
    clusterLabelLayer = root.document.createElement('div');
    clusterLabelLayer.className = 'mem-graph-cluster-labels';
    clusterLabelLayer.setAttribute('aria-hidden', 'true');
    activePools.forEach(function (pool) {
      var label = root.document.createElement('span');
      label.className = 'mem-graph-cluster-label';
      label.style.setProperty('--cluster-color', pool[2]);
      var name = root.document.createElement('b'); name.textContent = pool[1];
      var count = root.document.createElement('i'); count.textContent = String(counts[pool[0]] || 0);
      label.appendChild(name); label.appendChild(count);
      clusterLabelLayer.appendChild(label);
      clusterLabels[pool[0]] = label;
    });
    host.appendChild(clusterLabelLayer);
  }

  function updateClusterLabels() {
    if (!clusterLabelLayer || !host) return;
    var rect = host.getBoundingClientRect(), w = rect.width || 1, h = rect.height || 1;
    POOL_LIST.forEach(function (pool) {
      var label = clusterLabels[pool[0]];
      if (!label) return;
      var point;
      if (mode === '3d' && root.THREE && camera) {
        var vector = new root.THREE.Vector3();
        var center = centersForPool(pool[0]);
        vector.set(center[0], center[1], center[2]);
        if (graphGroup) vector.applyEuler(graphGroup.rotation);
        vector.project(camera);
        if (vector.z < -1 || vector.z > 1) { label.style.display = 'none'; return; }
        point = { x: (vector.x * 0.5 + 0.5) * w, y: (-vector.y * 0.5 + 0.5) * h };
      } else {
        var c = centersForPool(pool[0]), cy = Math.cos(pitch), sy = Math.sin(pitch), cx = Math.cos(yaw), sx = Math.sin(yaw);
        var x1 = c[0] * cx - c[2] * sx, z1 = c[0] * sx + c[2] * cx;
        var y1 = c[1] * cy - z1 * sy, z2 = c[1] * sy + z1 * cy;
        var focal = h / (2 * Math.tan(24 * Math.PI / 180));
        var perspective = focal / Math.max(26, cameraDistance() - z2);
        point = { x: w * 0.5 + x1 * perspective, y: h * 0.52 - y1 * perspective };
      }
      label.style.display = 'inline-flex';
      label.style.left = Math.max(38, Math.min(w - 38, point.x)) + 'px';
      label.style.top = Math.max(22, Math.min(h - 28, point.y - 17)) + 'px';
    });
  }

  function activateCanvasFallback() {
    if (!host || destroyed) return false;
    if (fallbackCanvas && fallbackContext) return true;
    [nodeGeometry, hubGeometry, categoryGeometry, linkGeometry, starGeometry].forEach(function (g) { if (g) { try { g.dispose(); } catch (ignored) { } } });
    [nodeMaterial, categoryMaterial, linkMaterial].forEach(function (m) { if (m) { try { m.dispose(); } catch (ignored) { } } });
    if (renderer) { try { renderer.dispose(); } catch (ignored) { } renderer = null; }
    if (scene) { while (scene.children.length) scene.remove(scene.children[0]); }
    scene = camera = graphGroup = nodeGeometry = nodeMaterial = nodePoints = hubGeometry = hubPoints = null;
    categoryGeometry = categoryMaterial = categoryLines = null;
    linkGeometry = linkMaterial = linkLines = starGeometry = starPoints = null;
    host.textContent = '';
    buildFallbackSprites();
    fallbackCanvas = root.document.createElement('canvas');
    fallbackCanvas.className = 'mem-graph-fallback-canvas';
    fallbackCanvas.setAttribute('aria-label', '可旋转缩放的 3D 本地记忆星云');
    host.appendChild(fallbackCanvas);
    fallbackContext = fallbackCanvas.getContext('2d', { alpha: true });
    if (!fallbackContext) {
      fallbackCanvas = null; mode = 'unavailable';
      host.textContent = '当前设备无法创建图形画布；本地记忆仍保存在记忆列表中。';
      return false;
    }
    mode = '3d-canvas';
    var counts = countPools();
    var activePools = POOL_LIST.filter(function (pool) { return counts[pool[0]] > 0; });
    hubPoolNames = activePools.map(function (pool) { return pool[0]; });
    buildClusterLabels(activePools, counts);
    resize();
    return true;
  }

  function refreshStyles() {
    var visiblePoolCounts = Object.create(null);
    nodes.forEach(function (n) { if (visible(n)) visiblePoolCounts[n._pool] = (visiblePoolCounts[n._pool] || 0) + 1; });
    if (nodeGeometry) {
      var alpha = nodeGeometry.getAttribute('aAlpha'), energy = nodeGeometry.getAttribute('aEnergy');
      var size = nodeGeometry.getAttribute('aSize');
      nodes.forEach(function (n, i) {
        var isShown = visible(n);
        alpha.array[i] = isShown ? 1 : 0;
        energy.array[i] = highlighted[n.id] ? 1.0 : selectedId === n.id ? 0.84 : related[n.id] ? 0.55 : 0.12;
      size.array[i] = (4.3 + n.importance * 2.25 + (n.pinned ? 0.6 : 0)) *
          (selectedId === n.id ? 1.42 : related[n.id] ? 1.10 : 1);
      });
      alpha.needsUpdate = true; energy.needsUpdate = true; size.needsUpdate = true;
    }
    if (linkGeometry) {
      var edgeAlpha = linkGeometry.getAttribute('aAlpha');
      links.forEach(function (link, i) {
        var a = nodesById[link.source], b = nodesById[link.target];
        var isShown = a && b && visible(a) && visible(b);
        var active = highlighted[link.source] || highlighted[link.target] || selectedId === link.source || selectedId === link.target;
        edgeAlpha.array[i * 2] = edgeAlpha.array[i * 2 + 1] = isShown ? (active ? 1 : 0.48) : 0;
      });
      edgeAlpha.needsUpdate = true;
    }
    if (categoryGeometry) {
      var fiberAlpha = categoryGeometry.getAttribute('aAlpha');
      nodes.forEach(function (n, i) {
        var a = visible(n) ? 0.19 : 0;
        fiberAlpha.array[i * 2] = fiberAlpha.array[i * 2 + 1] = a;
      });
      fiberAlpha.needsUpdate = true;
    }
    if (hubGeometry) {
      var hubAlpha = hubGeometry.getAttribute('aAlpha');
      hubPoolNames.forEach(function (name, i) { hubAlpha.array[i] = visiblePoolCounts[name] ? 0.76 : 0; });
      hubAlpha.needsUpdate = true;
    }
    POOL_LIST.forEach(function (pool) {
      var label = clusterLabels[pool[0]];
      if (!label) return;
      var count = visiblePoolCounts[pool[0]] || 0;
      label.style.opacity = count ? '1' : '0';
      var countLabel = label.querySelector('i');
      if (countLabel) countLabel.textContent = String(count);
    });
    draw();
    refreshLegend();
  }

  function viewNodeAt(clientX, clientY) {
    if (!host) return null;
    var rect = host.getBoundingClientRect(), mx = clientX - rect.left, my = clientY - rect.top;
    var best = null, bestD = 52;
    if (mode === '3d' && root.THREE && camera && graphGroup) {
      var T = root.THREE, vector = new T.Vector3();
      for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i]; if (!visible(n)) continue;
        vector.set(n._p[0], n._p[1], n._p[2]); vector.applyEuler(graphGroup.rotation).project(camera);
        if (vector.z < -1 || vector.z > 1) continue;
        var px = (vector.x * 0.5 + 0.5) * rect.width, py = (-vector.y * 0.5 + 0.5) * rect.height;
        var d = Math.hypot(px - mx, py - my);
        var hitRadius = Math.max(48, (4.3 + n.importance * 2.25) * 1.4);
        if (d < hitRadius && d < bestD) { bestD = d; best = n; }
      }
    } else if (fallbackCanvas) {
      var w = host.clientWidth || 1, h = host.clientHeight || 1;
      var distance = cameraDistance();
      var focal = h / (2 * Math.tan(24 * Math.PI / 180));
      var cy = Math.cos(pitch), sy = Math.sin(pitch), cx = Math.cos(yaw), sx = Math.sin(yaw);
      nodes.forEach(function (n) {
        if (!visible(n)) return;
        var x = n._p[0], y = n._p[1], z = n._p[2];
        var x1 = x * cx - z * sx, z1 = x * sx + z * cx;
        var y1 = y * cy - z1 * sy, z2 = y * sy + z1 * cy;
        var perspective = focal / Math.max(26, distance - z2);
        var distanceFromPoint = Math.hypot(w * 0.5 + x1 * perspective - mx, h * 0.52 - y1 * perspective - my);
        var scale = Math.max(0.78, Math.min(1.34, 140 / Math.max(90, distance - z2)));
        var hitRadius2d = Math.max(48, (2.8 + n.importance * 1.05) * scale + 30);
        if (distanceFromPoint < hitRadius2d && distanceFromPoint < bestD) { bestD = distanceFromPoint; best = n; }
      });
    }
    return best;
  }

  function selectNode(node) {
    if (!node) return false;
    selectedId = node.id;
    highlighted = Object.create(null); related = Object.create(null);
    highlighted[node.id] = true;
    (adjacency[node.id] || []).forEach(function (id) { related[id] = true; });
    selectedEdges = links.filter(function (link) { return link.source === node.id || link.target === node.id; }).slice(0, 48);
    selectionPulseStart = root.performance && root.performance.now ? root.performance.now() : Date.now();
    selectionPulseUntil = selectionPulseStart + 1100;
    refreshStyles();
    if (selectionCallback) selectionCallback(node);
    scheduleFrame();
    return true;
  }

  function pointerDown(event) {
    if (!host) return;
    event.preventDefault();
    try { host.setPointerCapture(event.pointerId); } catch (ignored) { }
    pointers[event.pointerId] = { x: event.clientX, y: event.clientY, startX: event.clientX, startY: event.clientY };
    var keys = Object.keys(pointers);
    if (keys.length === 2) {
      var p1 = pointers[keys[0]], p2 = pointers[keys[1]];
      gestureDistance = Math.max(1, Math.hypot(p1.x - p2.x, p1.y - p2.y));
      dragMoved = true;
    } else if (keys.length === 1) dragMoved = false;
  }

  function pointerMove(event) {
    if (!pointers[event.pointerId]) return;
    event.preventDefault();
    var previous = pointers[event.pointerId];
    var dx = event.clientX - previous.x, dy = event.clientY - previous.y;
    pointers[event.pointerId] = { x: event.clientX, y: event.clientY, startX: previous.startX, startY: previous.startY };
    var keys = Object.keys(pointers);
    if (keys.length >= 2) {
      dragMoved = true;
      var a = pointers[keys[0]], b = pointers[keys[1]], dist = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
      if (gestureDistance) zoomScale = Math.max(0.55, Math.min(3.2, zoomScale * gestureDistance / dist));
      gestureDistance = dist;
      if (camera) camera.position.z = cameraDistance();
    } else {
      yaw += dx * 0.008;
      // Keep pitch unbounded so a vertical drag can orbit through every side.
      pitch += dy * 0.008;
      if (graphGroup) { graphGroup.rotation.set(pitch, yaw, 0); }
      dragMoved = dragMoved || Math.hypot(pointers[event.pointerId].x - pointers[event.pointerId].startX,
        pointers[event.pointerId].y - pointers[event.pointerId].startY) > 8;
    }
    draw();
  }

  function pointerUp(event) {
    if (!pointers[event.pointerId]) return;
    var activeCount = Object.keys(pointers).length;
    var wasTap = event.type === 'pointerup' && activeCount === 1 && !dragMoved;
    delete pointers[event.pointerId];
    if (Object.keys(pointers).length < 2) gestureDistance = 0;
    if (wasTap) {
      selectNode(viewNodeAt(event.clientX, event.clientY));
      suppressClick = true; clearTimeout(suppressTimer);
      suppressTimer = setTimeout(function () { suppressClick = false; }, 420);
    } else if (dragMoved) {
      suppressClick = true; clearTimeout(suppressTimer);
      suppressTimer = setTimeout(function () { suppressClick = false; }, 420);
    }
    if (!Object.keys(pointers).length) dragMoved = false;
    if (autoRotate) scheduleFrame();
  }

  function onWheel(event) {
    event.preventDefault();
    zoomScale = Math.max(0.55, Math.min(3.2, zoomScale * (event.deltaY < 0 ? 0.88 : 1.14)));
    if (camera) camera.position.z = cameraDistance();
    draw();
  }

  function bindEvents() {
    clickHandler = function (event) {
      if (suppressClick) { suppressClick = false; return; }
      selectNode(viewNodeAt(event.clientX, event.clientY));
    };
    downHandler = pointerDown; moveHandler = pointerMove; upHandler = pointerUp;
    wheelHandler = onWheel;
    host.addEventListener('click', clickHandler);
    host.addEventListener('pointerdown', downHandler, { passive: false });
    host.addEventListener('pointermove', moveHandler, { passive: false });
    host.addEventListener('pointerup', upHandler);
    host.addEventListener('pointercancel', upHandler);
    host.addEventListener('wheel', wheelHandler, { passive: false });
    visibilityHandler = function () {
      suspended = !!root.document.hidden;
      if (suspended) cancelFrame(); else { draw(); scheduleFrame(); }
    };
    root.document.addEventListener('visibilitychange', visibilityHandler);
    if (typeof root.ResizeObserver === 'function') { observer = new root.ResizeObserver(resize); observer.observe(host); }
    root.addEventListener('resize', resize, { passive: true });
  }

  function mount(container, payload, callbacks) {
    destroy();
    if (!container) return false;
    host = container; destroyed = false; expanded = false; zoomScale = 1; yaw = 0; pitch = 0;
    filterType = 'all'; selectedId = ''; highlighted = Object.create(null); related = Object.create(null);
    selectedEdges = []; selectionPulseStart = selectionPulseUntil = 0;
    autoRotate = false; suspended = !!root.document.hidden;
    selectionCallback = callbacks && typeof callbacks.onSelect === 'function' ? callbacks.onSelect : null;
    normalizeData(payload); layoutNodes();
    var THREE = root.THREE;
    var webglOK = false;
    var androidWebView = /Android/i.test(String((root.navigator && root.navigator.userAgent) || ''));
    try { webglOK = !androidWebView && !!(THREE && THREE.WebGLRenderer && THREE.BufferGeometry && THREE.ShaderMaterial); } catch (ignored) { }
    if (webglOK) {
      try { webglOK = buildWebGL(container); } catch (error) { webglOK = false; }
    }
    if (!webglOK) activateCanvasFallback();
    bindEvents(); updateTypeButtons(); refreshLegend(); updateStyles();
    if (autoRotate) scheduleFrame(); else draw();
    return true;
  }

  function destroy() {
    destroyed = true; cancelFrame(); clearTimeout(suppressTimer);
    if (observer) { try { observer.disconnect(); } catch (ignored) { } observer = null; }
    if (root.document && visibilityHandler) root.document.removeEventListener('visibilitychange', visibilityHandler);
    if (root && root.removeEventListener) root.removeEventListener('resize', resize);
    if (host) {
      if (clickHandler) host.removeEventListener('click', clickHandler);
      if (downHandler) host.removeEventListener('pointerdown', downHandler);
      if (moveHandler) host.removeEventListener('pointermove', moveHandler);
      if (upHandler) { host.removeEventListener('pointerup', upHandler); host.removeEventListener('pointercancel', upHandler); }
      if (wheelHandler) host.removeEventListener('wheel', wheelHandler);
    }
    [nodeGeometry, hubGeometry, categoryGeometry, linkGeometry, starGeometry].forEach(function (g) { if (g) { try { g.dispose(); } catch (ignored) { } } });
    [nodeMaterial, categoryMaterial, linkMaterial].forEach(function (m) { if (m) { try { m.dispose(); } catch (ignored) { } } });
    if (renderer) { try { renderer.dispose(); } catch (ignored) { } }
    if (host) host.textContent = '';
    host = renderer = scene = camera = graphGroup = nodeGeometry = nodeMaterial = nodePoints = null;
    hubGeometry = hubPoints = categoryGeometry = categoryMaterial = categoryLines = null;
    linkGeometry = linkMaterial = linkLines = starGeometry = starPoints = null;
    fallbackCanvas = fallbackContext = null; fallbackSprites = Object.create(null); clickHandler = downHandler = moveHandler = upHandler = wheelHandler = visibilityHandler = null;
    clusterLabelLayer = null; clusterLabels = Object.create(null); hubPoolNames = [];
    nodes = []; links = []; records = []; nodesById = Object.create(null); nodeIndex = Object.create(null); adjacency = Object.create(null);
    selectionCallback = null; pointers = Object.create(null); selectedId = '';
    selectedEdges = []; selectionPulseStart = selectionPulseUntil = 0;
    highlighted = Object.create(null); related = Object.create(null); mode = 'unavailable'; expanded = false;
  }

  function updateStyles() { refreshStyles(); }

  function filter(type) {
    filterType = type || 'all';
    if (host) {
      var screen = host.parentNode && host.parentNode.parentNode;
      if (screen) Array.prototype.forEach.call(screen.querySelectorAll('.mem-graph-filter'), function (b) {
        b.classList.toggle('on', b.getAttribute('data-type') === filterType);
      });
    }
    refreshLegend(); updateStyles();
  }

  function highlight(ids) {
    if (!Array.isArray(ids)) return;
    selectedId = ''; highlighted = Object.create(null); related = Object.create(null);
    selectedEdges = []; selectionPulseUntil = 0;
    ids.slice(0, 8).forEach(function (id) {
      id = String(id);
      if (!nodesById[id]) return;
      highlighted[id] = true;
      (adjacency[id] || []).forEach(function (other) { related[other] = true; });
    });
    refreshStyles();
    if (host && host.parentNode) host.parentNode.classList.add('is-recalling');
    if (autoRotate) scheduleFrame();
  }

  function selectRecord(id) {
    var node = nodesById[String(id || '')];
    if (!node) return false;
    if (filterType !== 'all' && node.type !== filterType) filter('all');
    return selectNode(node);
  }

  function toggleRotation() {
    autoRotate = !autoRotate;
    if (autoRotate) scheduleFrame(); else { cancelFrame(); draw(); }
    return autoRotate;
  }

  function zoomBy(factor) {
    factor = Number(factor);
    if (!isFinite(factor) || factor <= 0) return zoomScale;
    zoomScale = Math.max(0.55, Math.min(3.2, zoomScale / factor));
    if (camera) camera.position.z = cameraDistance();
    draw(); return zoomScale;
  }

  function resetView() {
    selectedId = ''; highlighted = Object.create(null); related = Object.create(null);
    selectedEdges = []; selectionPulseUntil = 0;
    yaw = 0; pitch = 0; zoomScale = 1;
    if (graphGroup) graphGroup.rotation.set(0, 0, 0);
    if (camera) camera.position.set(0, 0, cameraDistance());
    updateStyles(); if (autoRotate) scheduleFrame();
  }

  function setExpanded(value) {
    expanded = !!value;
    if (camera) camera.position.z = cameraDistance();
    resize(); draw();
    return expanded;
  }

  function pause() {
    suspended = true; cancelFrame(); draw();
  }

  function resume() {
    suspended = !!root.document.hidden;
    draw(); scheduleFrame();
  }

  root.DshMemoryGraph = {
    mount: mount, destroy: destroy, highlight: highlight, filter: filter,
    records: function () { return records.slice(); }, select: selectRecord,
    toggleRotation: toggleRotation, zoomBy: zoomBy, resetView: resetView,
    setExpanded: setExpanded, isExpanded: function () { return expanded; },
    pause: pause, resume: resume, resize: resize,
    stats: function () { return Object.assign({}, graphStats); },
    renderMode: function () { return mode; }, types: TYPE_LIST.slice()
  };
})(window);
