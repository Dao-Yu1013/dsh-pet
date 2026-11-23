/*
 * DSH opening starfield, adapted from leesykes/starfield-animation.
 * Upstream: https://github.com/leesykes/starfield-animation
 * MIT license and copyright notice: ../../licenses/leesykes-starfield-animation-MIT.txt
 * The original 500-star perspective expansion is retained and tuned for a
 * transparent, touch-first Android WebView intro.
 */
(function (root) {
  'use strict';

  var TAU = Math.PI * 2;
  var PALETTE = [
    [117, 255, 236], [101, 205, 255], [177, 139, 255],
    [255, 151, 218], [255, 215, 125]
  ];
  var CLUSTER_COLORS = [0, 2, 3, 0, 4, 3, 1, 2];

  function clusterAnchor(index, width, height) {
    var angle = -Math.PI * 0.5 + index * Math.PI * 2 / 8;
    return { x: width * (0.5 + Math.cos(angle) * 0.21), y: height * (0.48 + Math.sin(angle) * 0.19) };
  }

  function Starfield(canvas, options) {
    this.canvas = canvas;
    this.ctx = canvas && canvas.getContext ? canvas.getContext('2d', { alpha: true }) : null;
    this.options = options || {};
    this.mode = this.options.mode === 'converge' ? 'converge' : 'warp';
    this.duration = Math.max(5000, Number(this.options.duration) || 8600);
    this.count = Math.max(120, Math.min(520, Number(this.options.count) || 380));
    this.stars = [];
    this.frame = 0;
    this.running = false;
    this.destroyed = false;
    this.lastFrameAt = 0;
    this.startedAt = 0;
    this.width = 0;
    this.height = 0;
    this.ratio = 1;
    this.glows = [];
    for (var g = 0; g < PALETTE.length; g++) this.glows.push(this.makeGlow(PALETTE[g]));
    this.boundFrame = this.draw.bind(this);
    this.boundResize = this.resize.bind(this);
    this.boundVisibility = this.visibility.bind(this);
    this.resize();
    this.bind();
    for (var i = 0; i < this.count; i++) this.stars.push(this.mode === 'converge' ? this.makeConvergentStar() : this.makeStar(true));
  }

  Starfield.prototype.bind = function () {
    root.addEventListener('resize', this.boundResize, { passive: true });
    document.addEventListener('visibilitychange', this.boundVisibility, true);
  };

  Starfield.prototype.makeGlow = function (color) {
    var tile = document.createElement('canvas');
    tile.width = tile.height = 64;
    var ctx = tile.getContext('2d');
    if (!ctx) return null;
    var glow = ctx.createRadialGradient(32, 32, 0, 32, 32, 32);
    glow.addColorStop(0, 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',1)');
    glow.addColorStop(0.18, 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',0.44)');
    glow.addColorStop(1, 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',0)');
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, 64, 64);
    return tile;
  };

  Starfield.prototype.resize = function () {
    if (!this.canvas || !this.ctx) return;
    var rect = this.canvas.getBoundingClientRect();
    this.width = Math.max(1, rect.width || root.innerWidth || 1);
    this.height = Math.max(1, rect.height || root.innerHeight || 1);
    this.ratio = Math.min(1.25, Math.max(1, root.devicePixelRatio || 1));
    this.canvas.width = Math.round(this.width * this.ratio);
    this.canvas.height = Math.round(this.height * this.ratio);
    this.ctx.setTransform(this.ratio, 0, 0, this.ratio, 0, 0);
    this.ctx.clearRect(0, 0, this.width, this.height);
  };

  Starfield.prototype.makeStar = function (far) {
    var angle = Math.random() * TAU;
    var z = far ? 900 + Math.random() * 620 : 18 + Math.random() * 1500;
    var palette = PALETTE[(Math.random() * PALETTE.length) | 0];
    return {
      angle: angle,
      z: z,
      previousX: 0,
      previousY: 0,
      speed: 2.0 + Math.random() * 3.1,
      size: 0.55 + Math.random() * 1.45,
      hue: palette,
      phase: Math.random() * TAU
    };
  };

  Starfield.prototype.makeConvergentStar = function () {
    // Converge into eight calm, color-coded category neighborhoods. Each
    // group resolves into a bright center and a halo of smaller memory lights.
    var cluster = Math.floor(Math.random() * 8);
    var clusterAngle = Math.random() * TAU;
    var radius = Math.random() < 0.085 ? Math.random() * 0.009 : 0.012 + Math.random() * 0.042;
    var anchor = clusterAnchor(cluster, 1, 1);
    var targetX = anchor.x + Math.cos(clusterAngle) * radius;
    var y = anchor.y + Math.sin(clusterAngle) * radius * 0.82;
    var colorIndex = CLUSTER_COLORS[cluster];
    var flightAngle = Math.random() * TAU;
    return {
      cluster: cluster,
      targetX: targetX,
      targetY: y,
      angle: flightAngle,
      delay: Math.random() * 0.30,
      size: 0.62 + Math.random() * 1.28,
      speed: 0.9 + Math.random() * 1.1,
      hue: PALETTE[colorIndex],
      glowIndex: colorIndex,
      phase: Math.random() * TAU,
      hub: radius < 0.012
    };
  };

  Starfield.prototype.visibility = function () {
    if (document.hidden) this.stop();
    else if (!this.destroyed && this.canvas && this.canvas.isConnected) this.start();
  };

  Starfield.prototype.start = function () {
    if (this.destroyed || this.running || document.hidden || !this.ctx) return;
    this.running = true;
    this.lastFrameAt = 0;
    if (!this.startedAt) this.startedAt = root.performance && root.performance.now ? root.performance.now() : Date.now();
    this.frame = root.requestAnimationFrame(this.boundFrame);
  };

  Starfield.prototype.stop = function () {
    if (this.frame) root.cancelAnimationFrame(this.frame);
    this.frame = 0;
    this.running = false;
  };

  Starfield.prototype.draw = function (now) {
    if (!this.running || this.destroyed || !this.ctx) return;
    this.frame = 0;
    var elapsed = this.lastFrameAt ? Math.min(34, Math.max(0, now - this.lastFrameAt)) : 16.7;
    this.lastFrameAt = now;
    var dt = elapsed / 16.7;
    var ctx = this.ctx;
    var cx = this.width * 0.5;
    var cy = this.height * 0.49;
    ctx.clearRect(0, 0, this.width, this.height);
    ctx.globalCompositeOperation = 'lighter';

    if (this.mode === 'converge') {
      this.drawConvergence(ctx, now, dt);
      ctx.globalCompositeOperation = 'source-over';
      // A convergence intro is a one-shot animation. Hold on its final frame
      // until the owner fades/destroys the canvas instead of pulsing forever.
      if (now - this.startedAt >= this.duration) {
        this.stop();
        return;
      }
      this.frame = root.requestAnimationFrame(this.boundFrame);
      return;
    }

    // Downloaded open-source starfield perspective: depth shrinks toward a
    // vanishing point, then the same star is recycled to the far distance.
    for (var i = 0; i < this.stars.length; i++) {
      var star = this.stars[i];
      star.z -= star.speed * dt;
      if (star.z <= 10) {
        star.z = 1450;
        star.angle = Math.random() * TAU;
        star.previousX = cx;
        star.previousY = cy;
      }
      var progress = 1 - star.z / 1500;
      var spread = 7 + progress * Math.min(this.width, this.height) * 0.82;
      var scale = this.width / (star.z + 130);
      var x = cx + Math.cos(star.angle) * spread * scale;
      var y = cy + Math.sin(star.angle) * spread * scale;
      var radius = star.size * (0.32 + progress * 2.1);
      var alpha = Math.min(0.9, 0.10 + progress * 0.84) * (0.78 + Math.sin(now * 0.0017 + star.phase) * 0.22);
      var color = star.hue;

      if (progress > 0.67 && star.previousX && i % 2 === 0) {
        ctx.strokeStyle = 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',' + (alpha * 0.25) + ')';
        ctx.lineWidth = Math.max(0.35, radius * 0.34);
        ctx.beginPath();
        ctx.moveTo(star.previousX, star.previousY);
        ctx.lineTo(x, y);
        ctx.stroke();
      }
      var glowTile = this.glows[(PALETTE.indexOf(color) + PALETTE.length) % PALETTE.length];
      if (glowTile) {
        ctx.globalAlpha = alpha;
        var glowSize = radius * 8.4;
        ctx.drawImage(glowTile, x - glowSize * 0.5, y - glowSize * 0.5, glowSize, glowSize);
        ctx.globalAlpha = 1;
      }
      star.previousX = x;
      star.previousY = y;
    }
    ctx.globalCompositeOperation = 'source-over';
    this.frame = root.requestAnimationFrame(this.boundFrame);
  };

  Starfield.prototype.drawConvergence = function (ctx, now, dt) {
    var elapsed = Math.max(0, now - this.startedAt);
    var overall = Math.min(1, elapsed / this.duration);
    var cx = this.width * 0.5;
    var cy = this.height * 0.48;
    var radius = Math.hypot(this.width, this.height) * 0.68;
    this.drawCategoryAnchors(ctx, now, overall);
    for (var i = 0; i < this.stars.length; i++) {
      var star = this.stars[i];
      var local = Math.max(0, Math.min(1, (overall - star.delay) / (1 - star.delay)));
      var eased = 1 - Math.pow(1 - local, 3.1);
      var startX = cx + Math.cos(star.angle) * radius;
      var startY = cy + Math.sin(star.angle) * radius;
      var targetX = star.targetX * this.width;
      var targetY = star.targetY * this.height;
      var x = startX + (targetX - startX) * eased;
      var y = startY + (targetY - startY) * eased;
      var trail = (0.045 + (1 - local) * 0.11) * star.speed;
      var tailX = x + (startX - x) * trail;
      var tailY = y + (startY - y) * trail;
      var pulse = 0.72 + Math.sin(now * 0.0018 + star.phase) * 0.28;
      var alpha = (0.16 + local * 0.68) * pulse;
      var color = star.hue;
      // Keep each streak luminous without allocating a CanvasGradient for every
      // particle on every frame; the precomputed glow sprite supplies the bloom.
      ctx.strokeStyle = 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',' + (alpha * 0.66) + ')';
      ctx.lineWidth = Math.max(0.45, star.size * (0.45 + local * 0.48));
      ctx.beginPath();
      ctx.moveTo(tailX, tailY);
      ctx.lineTo(x, y);
      ctx.stroke();
      var glowTile = this.glows[star.glowIndex];
      if (glowTile) {
        ctx.globalAlpha = alpha;
        var glowSize = star.size * (star.hub ? 11.5 : 5.5 + local * 4.5);
        ctx.drawImage(glowTile, x - glowSize * 0.5, y - glowSize * 0.5, glowSize, glowSize);
        ctx.globalAlpha = 1;
      }
      if (local > 0.94) {
        var arrival = (local - 0.94) / 0.06;
        ctx.fillStyle = 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',' + (alpha * arrival) + ')';
        ctx.beginPath();
        ctx.arc(x, y, Math.max(0.6, star.size * 0.65), 0, TAU);
        ctx.fill();
      }
    }
    // A restrained core pulse holds the converged category constellation.
    var beat = 0.5 + 0.5 * Math.sin(now * 0.00135);
    var halo = ctx.createRadialGradient(cx, cy, 1, cx, cy, Math.min(this.width, this.height) * (0.10 + beat * 0.025));
    halo.addColorStop(0, 'rgba(162,255,245,' + (0.028 + beat * 0.018) + ')');
    halo.addColorStop(0.36, 'rgba(81,202,255,' + (0.018 + beat * 0.012) + ')');
    halo.addColorStop(1, 'rgba(106,77,255,0)');
    ctx.fillStyle = halo;
    ctx.fillRect(0, 0, this.width, this.height);
  };

  Starfield.prototype.drawCategoryAnchors = function (ctx, now, progress) {
    var fade = Math.max(0, Math.min(1, (progress - 0.28) / 0.58));
    if (!fade) return;
    fade = fade * fade * (3 - 2 * fade);
    ctx.save();
    for (var i = 0; i < 8; i++) {
      var anchor = clusterAnchor(i, this.width, this.height);
      var color = PALETTE[CLUSTER_COLORS[i]], beat = 0.78 + Math.sin(now * 0.0015 + i * 0.9) * 0.22;
      var halo = ctx.createRadialGradient(anchor.x, anchor.y, 0, anchor.x, anchor.y, Math.min(this.width, this.height) * 0.072);
      halo.addColorStop(0, 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',' + (0.12 * fade * beat) + ')');
      halo.addColorStop(0.18, 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',' + (0.055 * fade * beat) + ')');
      halo.addColorStop(1, 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',0)');
      ctx.globalAlpha = 1; ctx.fillStyle = halo;
      ctx.fillRect(anchor.x - this.width * 0.08, anchor.y - this.height * 0.08, this.width * 0.16, this.height * 0.16);
      ctx.globalAlpha = fade * beat; ctx.fillStyle = '#f4ffff';
      ctx.shadowColor = 'rgb(' + color[0] + ',' + color[1] + ',' + color[2] + ')'; ctx.shadowBlur = 12;
      ctx.beginPath(); ctx.arc(anchor.x, anchor.y, 1.6 + beat * 1.2, 0, TAU); ctx.fill();
    }
    ctx.restore();
  };

  Starfield.prototype.destroy = function () {
    if (this.destroyed) return;
    this.stop();
    root.removeEventListener('resize', this.boundResize);
    document.removeEventListener('visibilitychange', this.boundVisibility, true);
    if (this.ctx && this.canvas) this.ctx.clearRect(0, 0, this.width, this.height);
    this.stars.length = 0;
    this.glows.length = 0;
    this.ctx = null;
    this.canvas = null;
    this.destroyed = true;
  };

  root.DshOssStarfield = {
    create: function (canvas, options) {
      var field = new Starfield(canvas, options);
      field.start();
      return field;
    }
  };
})(window);
