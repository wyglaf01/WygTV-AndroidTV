'use strict';
/*
 * WygTV for Samsung TVs: type into WygTV's forms on a phone instead of with the remote.
 *
 * "📱 Type on your phone" in a form shows a QR code. The phone opens docs/send.html (WygTV's GitHub Pages site),
 * shows the same fields, and sends what was typed back to the TV through ntfy.sh, a free open-source message
 * relay. It is encrypted on the phone first (tweetnacl secretbox) with a key that only the QR code carries, in the
 * part of the address after "#", which phones never send to any server. So ntfy.sh, GitHub and anyone else only
 * ever see scrambled data; only this TV, for this one form, can read it. Each QR code has its own random channel
 * and key, and passwords are never put in the QR code itself.
 */
(function () {
  if (!window.nacl || !window.qrcode) return;
  const PAGE = 'https://wyglaf01.github.io/WygTV-AndroidTV/send.html';
  const RELAY = 'https://ntfy.sh';

  const b64url = (bytes) => btoa(String.fromCharCode.apply(null, bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const fromB64 = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
  const utf8 = (s) => new TextEncoder().encode(s);

  /** The address the QR code opens: everything the phone needs is after "#". */
  function link(topic, key, title, fields) {
    // Kept short, so the QR code stays easy for a phone to read: no empty values, no default type.
    const f = fields.map((x) => {
      const o = { k: x.key, l: x.label };
      if (x.type && x.type !== 'text') o.t = x.type;
      if (x.type !== 'password' && x.value) o.v = x.value;
      return o;
    });
    return PAGE + '#v=1&t=' + topic + '&k=' + b64url(key) + '&n=' + encodeURIComponent(title) + '&f=' + b64url(utf8(JSON.stringify(f)));
  }

  /** The QR code as a canvas, dark on light with a quiet border, sized for reading from across the room. */
  function qrCanvas(text) {
    const qr = qrcode(0, 'M');
    qr.addData(text);
    qr.make();
    const n = qr.getModuleCount(), cell = Math.max(3, Math.floor(340 / (n + 8))), size = (n + 8) * cell;
    const c = document.createElement('canvas');
    c.width = c.height = size;
    c.className = 'phone-qr';
    const g = c.getContext('2d');
    g.fillStyle = '#fff'; g.fillRect(0, 0, size, size);
    g.fillStyle = '#000';
    for (let r = 0; r < n; r++) for (let col = 0; col < n; col++) if (qr.isDark(r, col)) g.fillRect((col + 4) * cell, (r + 4) * cell, cell, cell);
    return c;
  }

  /** Opens a message from the phone: base64url(nonce ‖ secretbox). Returns { key: value } or null. */
  function open(message, key) {
    try {
      const all = fromB64(String(message).trim());
      const box = nacl.secretbox.open(all.subarray(nacl.secretbox.nonceLength), all.subarray(0, nacl.secretbox.nonceLength), key);
      if (!box) return null;
      const j = JSON.parse(new TextDecoder().decode(box));
      return j && j.v === 1 && j.values && typeof j.values === 'object' ? j.values : null;
    } catch (e) { return null; }
  }

  /**
   * Listens on the relay for the phone's message (since the QR code was shown, so a message sent while
   * reconnecting isn't missed), until stop() is called. Calls onValues once with what the phone sent.
   */
  function listen(topic, key, onValues) {
    // A plain GET (no extra headers or cache options), so the browser sends it without a CORS preflight.
    const since = Math.floor(Date.now() / 1000) - 5;
    let stopped = false, ac = null, timer = 0;
    async function once() {
      ac = typeof AbortController === 'function' ? new AbortController() : null;
      const r = await fetch(RELAY + '/' + topic + '/json?since=' + since, { signal: ac ? ac.signal : undefined });
      if (!r.ok || !r.body) throw new Error('relay ' + r.status);
      const rd = r.body.getReader(), td = new TextDecoder();
      let buf = '';
      for (;;) {
        const { done, value } = await rd.read();
        if (done || stopped) return;
        buf += td.decode(value, { stream: true });
        let i;
        while ((i = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, i); buf = buf.slice(i + 1);
          let ev = null;
          try { ev = JSON.parse(line); } catch (e) { }
          if (!ev || ev.event !== 'message') continue;
          const values = open(ev.message, key);
          if (values && !stopped) { stopped = true; onValues(values); return; }
        }
      }
    }
    (function loop() {
      if (stopped) return;
      once().catch((e) => { if (!stopped) console.warn('phone relay', e && e.message || e); })
        .then(() => { if (!stopped) timer = setTimeout(loop, 1500); });
    })();
    return () => { stopped = true; clearTimeout(timer); try { if (ac) ac.abort(); } catch (e) { } };
  }

  /**
   * "📱 Type on your phone" for a W.form: shows the QR code and fills the form's inputs with what the phone sends.
   * fields: the form's [{ key, label, type, value }]; inputs: { key: <input> }.
   */
  window.wygPhoneFill = function (title, fields, inputs) {
    const key = nacl.randomBytes(nacl.secretbox.keyLength);
    const topic = 'wygtv-' + b64url(nacl.randomBytes(16));
    const url = link(topic, key, title, fields);
    const done = W.menu('Type on your phone', [{ label: 'Cancel' }], {
      text: 'Scan this code with your phone’s camera, type “' + title + '” there and press Send. It fills in here.\n' +
        'What you type is encrypted on your phone; only this TV can read it.',
    });
    const box = document.querySelector('#layers > .layer:last-child .dialog');
    if (box) {
      box.classList.add('phone-dialog');
      box.insertBefore(qrCanvas(url), box.querySelector('.menu-list'));
    }
    let filled = false;
    const stop = listen(topic, key, (values) => {
      filled = true;
      for (const k of Object.keys(values)) {
        const el = inputs[k];
        if (!el || typeof values[k] !== 'string') continue;
        el.value = values[k];
        el.dispatchEvent(new Event('input', { bubbles: true }));
      }
      const layer = box && box.closest('.layer');
      if (layer && layer._close) layer._close();
      W.toast('Filled in from your phone');
    });
    done.then(() => { stop(); return filled; });
    window.__wygPhoneLast = { url, topic }; // for the browser tests
    return done;
  };
})();
