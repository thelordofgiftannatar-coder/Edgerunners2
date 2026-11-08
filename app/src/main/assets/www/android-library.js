/* THE WITCHER (Android): remembers your library between launches, keeps access
   to the files you picked, and skips files that are already in it. */
(function () {
  'use strict';
  const N = window.AndroidNative;
  if (!N) return;

  // The app does not need an offline cache, and it must not touch media requests
  try { if (navigator.serviceWorker) navigator.serviceWorker.register = () => Promise.reject(new Error('off')); } catch (e) {}

  const b64 = s => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const mediaUrl = uri => location.origin + '/media/' + b64(uri);
  // same file reached through a folder or through single-file picking -> same key
  const keyOf = uri => {
    const m = String(uri).match(/^content:\/\/([^/]+)\/(?:tree\/[^/]+\/)?document\/(.+)$/);
    return m ? m[1] + '|' + m[2] : String(uri);
  };
  const key2 = i => i.n + '|' + (i.s || 0) + '|' + (i.m || 0);

  function lazyFile(uri) {
    // just enough of a File for the tag reader in the player
    return {
      slice: (a, b) => ({
        arrayBuffer: () => fetch(mediaUrl(uri), { headers: { Range: 'bytes=' + a + '-' + (b - 1) } }).then(r => r.arrayBuffer())
      })
    };
  }

  /* ---------- storage ---------- */
  const DB = 'witcher-library-android', ST = 'k', KEY = 'lib';
  const idb = () => new Promise((res, rej) => {
    const q = indexedDB.open(DB, 1);
    q.onupgradeneeded = () => q.result.createObjectStore(ST);
    q.onsuccess = () => res(q.result);
    q.onerror = () => rej(q.error);
  });
  const dbGet = async () => {
    try {
      const d = await idb();
      return await new Promise(r => {
        const q = d.transaction(ST).objectStore(ST).get(KEY);
        q.onsuccess = () => r(q.result || null);
        q.onerror = () => r(null);
      });
    } catch (e) { return null; }
  };
  const dbSet = async v => {
    try {
      const d = await idb();
      return await new Promise(r => {
        const t = d.transaction(ST, 'readwrite');
        t.objectStore(ST).put(v, KEY);
        t.oncomplete = () => r(true);
        t.onerror = () => r(false);
      });
    } catch (e) { return false; }
  };
  const toData = async u => {
    try {
      const b = await (await fetch(u)).blob();
      return await new Promise(r => {
        const fr = new FileReader();
        fr.onload = () => r(fr.result);
        fr.onerror = () => r(null);
        fr.readAsDataURL(b);
      });
    } catch (e) { return null; }
  };

  let ghost = [];      // saved items whose files cannot be opened right now
  let ghostPls = {};
  let loaded = false, saving = false, timer = 0, lastSave = 0;

  function sched() {
    if (!loaded) return;
    clearTimeout(timer);
    const busy = typeof pq !== 'undefined' && (pq.length || pa);
    const wait = busy ? (Date.now() - lastSave > 10000 ? 200 : 2000) : 1200;
    timer = setTimeout(save, wait);
  }

  async function save() {
    if (!loaded) return;
    if (saving) { sched(); return; }
    saving = true;
    try {
      const items = [], idToUri = new Map();
      for (const it of L) {
        if (!it.p) continue;
        if (typeof it.th === 'string' && it.th.startsWith('blob:')) {
          const d = await toData(it.th);
          if (d) it.th = d;
        }
        idToUri.set(it.id, it.p);
        items.push({
          p: it.p, n: it.n, t: it.t, s: it.s, m: it.m, f: it.f,
          d: it.d, w: it.w, h: it.h,
          th: (typeof it.th === 'string' && !it.th.startsWith('blob:')) ? it.th : null,
          tt: it.tt, ar: it.ar, al: it.al, hid: it.hid ? 1 : 0
        });
      }
      const P = {};
      for (const n in pls) P[n] = (pls[n] || []).map(id => idToUri.get(id)).filter(Boolean);
      for (const n in ghostPls) P[n] = (P[n] || []).concat(ghostPls[n]);
      await dbSet({ v: 1, items: items.concat(ghost), pls: P });
      lastSave = Date.now();
    } catch (e) { /* ignore */ }
    saving = false;
  }

  /* ---------- load on start ---------- */
  async function load() {
    const data = await dbGet();
    if (data && Array.isArray(data.items)) {
      let ok = null;
      try { ok = new Set(JSON.parse(N.readable(JSON.stringify(data.items.map(r => r.p).filter(Boolean))))); } catch (e) {}
      const have = new Set(L.map(i => i.p && keyOf(i.p)).filter(Boolean));
      const byUri = new Map();
      for (const i of L) if (i.p) byUri.set(i.p, i);
      const redo = [];
      for (const r of data.items) {
        if (!r || !r.p) continue;
        const k = keyOf(r.p);
        if (have.has(k)) continue;
        if (ok && !ok.has(r.p)) { ghost.push(r); continue; }
        have.add(k);
        const it = {
          id: nid++, n: r.n, u: mediaUrl(r.p), o: lazyFile(r.p), t: r.t,
          s: r.s, m: r.m, f: r.f || 'Added files', d: r.d || 0, w: r.w || 0, h: r.h || 0,
          th: r.th || null, hid: r.hid ? 1 : 0, p: r.p
        };
        if (r.tt !== undefined) it.tt = r.tt;
        if (r.ar !== undefined) it.ar = r.ar;
        if (r.al !== undefined) it.al = r.al;
        L.push(it);
        byUri.set(r.p, it);
        if (!it.d) redo.push(it);
      }
      if (data.pls) {
        for (const n in data.pls) {
          pls[n] = pls[n] || [];
          for (const p of data.pls[n]) {
            const it = byUri.get(p);
            if (it) { if (!pls[n].includes(it.id)) pls[n].push(it.id); }
            else (ghostPls[n] = ghostPls[n] || []).push(p);
          }
        }
      }
      redo.forEach(it => probe(it));
      if (L.length) draw();
    }
    loaded = true;
    sched();
  }

  /* ---------- adding files (no duplicates) ---------- */
  let cntAdded = 0, cntDup = 0, anyFolder = false;

  function addNative(arr, folder) {
    const have = new Set(), have2 = new Set();
    for (const i of L) if (i.p) { have.add(keyOf(i.p)); have2.add(key2(i)); }
    for (const r of arr) {
      if (!r || !r.uri || !r.name) continue;
      if (!(/^(audio|video)\//.test(r.mime || '') || MEDIA.test(r.name))) continue;
      const k = keyOf(r.uri), n = r.name.replace(/\.[^.]+$/, '');
      const k2 = n + '|' + (r.size || 0) + '|' + (r.mtime || 0);
      if (have.has(k) || have2.has(k2)) { cntDup++; continue; }
      have.add(k); have2.add(k2);
      const t = /^audio\//.test(r.mime || '') || /\.(mp3|m4a|flac|wav|ogg|opus|aac|wma)$/i.test(r.name) ? 'a' : 'v';
      const it = {
        id: nid++, n, u: mediaUrl(r.uri), o: lazyFile(r.uri), t,
        s: r.size || 0, m: r.mtime || 0, f: r.dir || 'Added files',
        d: 0, w: 0, h: 0, th: null, hid: 0, p: r.uri
      };
      L.push(it);
      for (const pn2 in pend) {
        const i = pend[pn2].indexOf(it.n);
        if (i > -1) { pend[pn2].splice(i, 1); (pls[pn2] = pls[pn2] || []).push(it.id); }
      }
      probe(it);
      cntAdded++;
    }
    if (folder) anyFolder = true;
    draw();
  }

  window.__nat = {
    begin() { cntAdded = 0; cntDup = 0; anyFolder = false; toast('Scanning…'); },
    picked(arr, folder) { addNative(arr, folder); },
    end(folder) {
      if (anyFolder || folder) { tab = 'f'; setTab(); }
      toast(cntAdded + ' added' + (cntDup ? ', ' + cntDup + ' already in your library' : ''));
      sched();
    },
    note(msg) { toast(msg); }
  };

  /* ---------- hook the player's buttons ---------- */
  pickStorage = function () { N.pickFolder(); };
  rescan = function () { N.rescan(); };
  const fl = document.getElementById('fl');
  if (fl) fl.addEventListener('click', e => { e.preventDefault(); N.pickFiles(); }, true);

  const _draw = draw;
  draw = function () { _draw(); sched(); };
  document.addEventListener('visibilitychange', () => { if (document.hidden) save(); });
  window.addEventListener('pagehide', () => { save(); });

  load();
})();
