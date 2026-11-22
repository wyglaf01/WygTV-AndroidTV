'use strict';
/* Start-up. */
(async function () {
  const D = W.data;
  if (!W.api) {
    document.body.innerHTML = '<p style="padding:40px;color:#fff">WygDesktop must be started as the desktop app.</p>';
    return;
  }
  W.api.net.setUserAgent(W.settings.userAgent || 'WygDesktop/1.0').catch(() => {});
  W.home();
  if (!(W.settings.playlists || []).length) return;
  await D.loadAll();
  if (W.current() && W.current().name === 'home') W.refresh();
  if (W.settings.startup === 'last' && W.recent.length) {
    const ch = D.channel(W.recent[0]);
    if (ch) {
      // Channel up/down goes through Favorites when the last channel is one, else its category.
      const list = W.isFav(ch.key) ? D.channelsFor('favs') : D.channelsFor(`cat:${ch.pid}|${ch.cat}`);
      W.player.playChannel(ch, { full: true, list, sel: W.isFav(ch.key) ? 'favs' : `cat:${ch.pid}|${ch.cat}` });
    }
  }
  // Movies and series load in the background so Continue watching and Search have them.
  setTimeout(async () => {
    await D.loadAllVod('vod'); await D.loadAllVod('series');
    D.emit('loaded');
  }, 2000);
  setTimeout(() => W.checkUpdates(false), 15000);
})();
