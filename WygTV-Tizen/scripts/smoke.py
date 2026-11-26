"""Loads the built TV app (build/app) in headless Chromium at 1920x1080 and fails on any script error.
Run after scripts/build.mjs:  python3 scripts/smoke.py build/app [screenshot.png]"""
import asyncio, functools, http.server, sys, threading
from playwright.async_api import async_playwright

root = sys.argv[1]
shot = sys.argv[2] if len(sys.argv) > 2 else None
http.server.SimpleHTTPRequestHandler.log_message = lambda *a: None
srv = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(http.server.SimpleHTTPRequestHandler, directory=root))
threading.Thread(target=srv.serve_forever, daemon=True).start()

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page(viewport={'width': 1920, 'height': 1080})
        errs = []
        pg.on('pageerror', lambda e: errs.append('pageerror: ' + str(e)))
        pg.on('console', lambda m: errs.append('console.error: ' + m.text) if m.type == 'error' else None)
        await pg.goto(f'http://127.0.0.1:{srv.server_address[1]}/index.html')
        await pg.wait_for_timeout(2500)
        info = await pg.evaluate("() => ({ bridge: !!window.wyg, tv: !!(window.W && W.isTV), tablo: !!(window.wyg && wyg.tablo), text: (document.querySelector('#screen') || {}).innerText || '' })")
        # The remote's Back key (10009) must reach the screens as Escape.
        await pg.evaluate("() => window.dispatchEvent(new KeyboardEvent('keydown', { keyCode: 10009, bubbles: true }))")
        if shot: await pg.screenshot(path=shot)
        await b.close()
    print(info)
    ok = info['bridge'] and info['tv'] and info['tablo'] and 'Welcome to WygTV' in info['text'] and 'Tablo' in info['text'] and not errs
    if errs: print('\n'.join(errs))
    sys.exit(0 if ok else 1)

asyncio.run(main())
