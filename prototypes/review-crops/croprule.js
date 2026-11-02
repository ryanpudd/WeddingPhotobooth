// PROTOTYPE: copied verbatim from prototype/crop-rule (prototypes/crop-rule/index.html)
const CropRule = (() => {
  const HOLDABLE = new Set(["umbrella","frisbee","sports ball","kite","baseball bat","tennis racket","bottle","wine glass",
    "cup","fork","knife","spoon","banana","apple","cell phone","book","clock","scissors","teddy bear","toothbrush","remote","tie"]);
  const box = (x0, y0, x1, y1) => ({ x0, y0, x1, y1 });
  const w = b => b.x1 - b.x0, h = b => b.y1 - b.y0, area = b => Math.max(0, w(b)) * Math.max(0, h(b));
  const union = (a, b) => a ? box(Math.min(a.x0, b.x0), Math.min(a.y0, b.y0), Math.max(a.x1, b.x1), Math.max(a.y1, b.y1)) : { ...b };
  const inter = (a, b) => box(Math.max(a.x0, b.x0), Math.max(a.y0, b.y0), Math.min(a.x1, b.x1), Math.min(a.y1, b.y1));
  const overlaps = (a, b) => w(inter(a, b)) > 0 && h(inter(a, b)) > 0;
  const iou = (a, b) => { const i = area(inter(a, b)); return i / (area(a) + area(b) - i); };
  const clampBox = (b, W, H) => box(Math.max(0, b.x0), Math.max(0, b.y0), Math.min(W, b.x1), Math.min(H, b.y1));
  const fromArr = a => box(a[0], a[1], a[2], a[3]);

  // Detections -> labelled subjects (person, hands, held objects, face, role)
  function subjects(photo, p) {
    const W = photo.w, H = photo.h;
    let people = photo.persons.filter(d => d.score >= p.personScore).sort((a, b) => b.score - a.score);
    people = people.filter((d, i) => !people.slice(0, i).some(k => iou(fromArr(k.box), fromArr(d.box)) > 0.6));
    const objects = photo.objects.filter(o => HOLDABLE.has(o.cls) && o.score >= p.objectScore);
    const subs = people.map(d => {
      const person = fromArr(d.box), k = d.kpts || [];
      const ok = i => k[i] && k[i][2] >= p.kpScore;
      let face = null;
      for (const i of [0, 1, 2, 3, 4]) if (ok(i)) face = union(face, box(k[i][0], k[i][1], k[i][0], k[i][1]));
      if (face) { // pad the face points out to a head box
        const s = Math.max(w(face), h(face), 0.04 * H) * 0.6;
        face = clampBox(box(face.x0 - s, face.y0 - s * 1.6, face.x1 + s, face.y1 + s * 1.2), W, H);
      }
      const hands = [];
      for (const [el, wr, sh] of [[7, 9, 5], [8, 10, 6]]) {
        if (!ok(el) || !ok(wr)) continue;
        const vx = k[wr][0] - k[el][0], vy = k[wr][1] - k[el][1], L = Math.hypot(vx, vy);
        const cx = k[wr][0] + vx * 0.5, cy = k[wr][1] + vy * 0.5, r = L * p.handSize / 2;
        let hb = box(cx - r, cy - r, cx + r, cy + r);
        const raised = ok(sh) && k[wr][1] < k[sh][1];
        if (raised) hb.y0 -= L * p.raisedReach;   // a raised hand may hold a prop on a stick above it
        hands.push(clampBox(hb, W, H));
      }
      const held = objects.filter(o => hands.some(hb => overlaps(hb, fromArr(o.box)))).map(o => fromArr(o.box));
      let extent = { ...person };
      for (const b of [...hands, ...held]) extent = union(extent, b);
      const edge = 3;
      const touchesEdge = person.x0 <= edge || person.x1 >= W - edge || person.y0 <= edge;
      return { score: d.score, person, face, hands, held, extent, touchesEdge, role: null };
    });
    const largest = Math.max(0, ...subs.map(s => area(s.person)));
    for (const s of subs) {
      if (s.touchesEdge && !s.face) s.role = "fragment";
      else if (area(s.person) < p.secondaryArea * largest) s.role = "secondary";
      else s.role = "primary";
    }
    // a small person overlapping a primary is part of the group, not background
    for (const s of subs) if (s.role === "secondary" && subs.some(o => o.role === "primary" && overlaps(o.person, s.person))) s.role = "primary";
    if (!p.includeSecondary) {} else for (const s of subs) if (s.role === "secondary") s.role = "primary";
    if (subs.length && !subs.some(s => s.role === "primary")) subs.reduce((a, b) => area(a.person) > area(b.person) ? a : b).role = "primary";
    return subs;
  }

  // Largest window-shaped rectangle in the photo: the 1x crop
  function baseCrop(photo, windowAspect) {
    const W = photo.w, H = photo.h;
    return W / H > windowAspect ? { w: H * windowAspect, h: H } : { w: W, h: W / windowAspect };
  }

  function zoomLevels(base, p) {
    const maxZoom = Math.min(p.maxZoom, base.w / 960);
    if (p.zoomMode === "fixed") return [2, 1.4, 1].filter(z => z <= maxZoom + 1e-6);
    const out = []; for (let z = maxZoom; z > 1; z -= 0.05) out.push(+z.toFixed(2)); out.push(1); return out;
  }

  // Place a crop of size cw x ch: content R inside the clear area, inside the photo,
  // preferred position from the placement rule, then nudge so optional faces are whole or out.
  function place(photo, R, cw, ch, clear, prefer, optionalFaces) {
    const range = (r0, r1, c0, c1, size, limit) => { // allowed crop origin so [r0,r1] sits within [c0,c1] of the crop
      const lo = Math.max(0, r1 - c1 * size), hi = Math.min(limit - size, r0 - c0 * size);
      return [lo, hi];
    };
    const [xlo, xhi] = range(R.x0, R.x1, clear.x0, clear.x1, cw, photo.w);
    const [ylo, yhi] = range(R.y0, R.y1, clear.y0, clear.y1, ch, photo.h);
    if (xlo > xhi + 0.5 || ylo > yhi + 0.5) return null;
    const pick = (pref, lo, hi, faces, a0, a1, size) => {
      const c = v => Math.min(hi, Math.max(lo, v));
      const cands = [c(pref)];
      for (const f of faces) cands.push(c(f[a0]), c(f[a1] - size), c(f[a1]), c(f[a0] - size)); // include or exclude edges
      const cuts = v => faces.filter(f => f[a0] < v && f[a1] > v || f[a0] < v + size && f[a1] > v + size).length;
      return cands.sort((a, b) => cuts(a) - cuts(b) || Math.abs(a - pref) - Math.abs(b - pref))[0];
    };
    const x = pick(prefer.x, xlo, xhi, optionalFaces.map(f => ({ a: f.x0, b: f.x1 })), "a", "b", cw);
    const y = pick(prefer.y, ylo, yhi, optionalFaces.map(f => ({ a: f.y0, b: f.y1 })), "a", "b", ch);
    return box(x, y, x + cw, y + ch);
  }

  function crop(photo, subs, border, p, forcedZoom) {
    const aspect = border.window.w / border.window.h;
    const clear = { // clear area as fractions of the window
      x0: (border.clear.x - border.window.x) / border.window.w, y0: (border.clear.y - border.window.y) / border.window.h,
      x1: (border.clear.x + border.clear.w - border.window.x) / border.window.w, y1: (border.clear.y + border.clear.h - border.window.y) / border.window.h };
    const base = baseCrop(photo, aspect);
    const full = (flags) => { const b = box((photo.w - base.w) / 2, (photo.h - base.h) / 2, (photo.w + base.w) / 2, (photo.h + base.h) / 2);
      return { crop: b, zoom: 1, R: null, flags }; };
    const primaries = subs.filter(s => s.role === "primary");
    if (!primaries.length) return full(["no people: full frame"]);
    let C = null; for (const s of primaries) C = union(C, s.extent);
    const m = p.margin * photo.h;
    let R = box(C.x0 - m, C.y0 - m - p.headroom * photo.h, C.x1 + m, C.y1 + m);
    R = clampBox(R, photo.w, photo.h);
    const faces = primaries.map(s => s.face).filter(Boolean);
    const optionalFaces = subs.filter(s => s.role !== "primary" && s.face).map(s => s.face);
    const zooms = forcedZoom ? [forcedZoom] : zoomLevels(base, p);
    for (const z of zooms) {
      const cw = base.w / z, ch = base.h / z;
      const cx = (R.x0 + R.x1) / 2, cy = (R.y0 + R.y1) / 2;
      const midX = (clear.x0 + clear.x1) / 2;
      let prefY;
      if (p.vertical === "centre" || !faces.length) prefY = cy - (clear.y0 + clear.y1) / 2 * ch;
      else { // eyes on the upper third line of the clear area
        const eyeY = faces.reduce((a, f) => a + (f.y0 + f.y1) / 2, 0) / faces.length;
        prefY = eyeY - (clear.y0 + (clear.y1 - clear.y0) / 3) * ch;
      }
      const c = place(photo, R, cw, ch, clear, { x: cx - midX * cw, y: prefY }, optionalFaces);
      if (c) return { crop: c, zoom: z, R, flags: forcedZoom ? ["override"] : [] };
    }
    // nothing fits: 1x centred on the content, flagged
    const cw = base.w, ch = base.h;
    const x = Math.min(photo.w - cw, Math.max(0, (R.x0 + R.x1) / 2 - cw / 2)), y = Math.min(photo.h - ch, Math.max(0, (R.y0 + R.y1) / 2 - ch / 2));
    return { crop: box(x, y, x + cw, y + ch), zoom: 1, R, flags: [forcedZoom ? "override doesn't fit" : "content doesn't fit the clear area: review"] };
  }

  return { subjects, crop, area };
})();
