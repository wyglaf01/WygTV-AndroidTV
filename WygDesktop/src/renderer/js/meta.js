'use strict';
/* Movie and series details: the provider's own info, OMDb ratings (IMDb, Rotten Tomatoes, Metacritic) and TMDb art. */
(function () {
  const M = W.meta = {};
  const cache = W.load('meta', {});
  const DAY = 86400e3;
  const enc = encodeURIComponent;

  function remember(key, data) {
    cache[key] = { t: Date.now(), data };
    const keys = Object.keys(cache);
    if (keys.length > 600) keys.sort((a, b) => cache[a].t - cache[b].t).slice(0, 100).forEach((k) => delete cache[k]);
    W.save('meta', cache);
  }

  /** "EN - The Matrix (1999) [4K]" → { title: "The Matrix", year: "1999" }. */
  M.clean = (name) => {
    let s = (name || '').trim();
    s = s.replace(/^\s*(\[[^\]]{1,12}\]|\|[^|]{1,12}\||\([A-Z]{2,4}\))\s*[-:|]?\s*/, '');
    s = s.replace(/^\s*[A-Z]{2,4}\s*[-:|]\s+/, '');
    let year = '';
    const y = /[([\s-]((?:19|20)\d{2})[)\]]?\s*$/.exec(s) || /\(((?:19|20)\d{2})\)/.exec(s);
    if (y) { year = y[1]; s = s.slice(0, y.index) + s.slice(y.index + y[0].length); }
    s = s.replace(/\[[^\]]*\]|\((?:4k|uhd|hd|fhd|multi[^)]*|sub[^)]*|dub[^)]*)\)/gi, '').replace(/\b(4k|uhd|fhd|1080p|720p|hevc|x265|h264)\b/gi, '');
    s = s.replace(/\s*[-:|]\s*$/, '').replace(/\s{2,}/g, ' ').trim();
    return { title: s || name, year };
  };

  M.omdbKey = () => (W.settings.omdbKey || '').trim() || W.DEFAULT_OMDB_KEY;

  M.omdb = async (title, year, type, imdbId) => {
    const ck = 'o|' + (imdbId || `${type}|${title}|${year}`).toLowerCase();
    const hit = cache[ck];
    if (hit && Date.now() - hit.t < 14 * DAY) return hit.data;
    const q = imdbId ? 'i=' + enc(imdbId) : 't=' + enc(title) + (year ? '&y=' + year : '') + (type ? '&type=' + type : '');
    let data = null;
    try {
      let r = await W.api.net.json(`https://www.omdbapi.com/?apikey=${enc(M.omdbKey())}&plot=full&${q}`, 15000);
      if (r && r.Response === 'False' && year && !imdbId) r = await W.api.net.json(`https://www.omdbapi.com/?apikey=${enc(M.omdbKey())}&plot=full&t=${enc(title)}&type=${type}`, 15000);
      if (r && r.Response !== 'False') data = r;
    } catch { return null; }
    remember(ck, data);
    return data;
  };

  M.tmdb = async (title, year, type, tmdbId) => {
    const key = (W.settings.tmdbKey || '').trim();
    if (!key) return null;
    const t = type === 'series' ? 'tv' : 'movie';
    const ck = 'm|' + t + '|' + (tmdbId || `${title}|${year}`).toLowerCase();
    const hit = cache[ck];
    if (hit && Date.now() - hit.t < 14 * DAY) return hit.data;
    let data = null;
    try {
      let id = tmdbId;
      if (!id) {
        const s = await W.api.net.json(`https://api.themoviedb.org/3/search/${t}?api_key=${enc(key)}&query=${enc(title)}` + (year ? (t === 'tv' ? '&first_air_date_year=' : '&year=') + year : ''), 15000);
        id = s && s.results && s.results[0] && s.results[0].id;
      }
      if (id) {
        const d = await W.api.net.json(`https://api.themoviedb.org/3/${t}/${id}?api_key=${enc(key)}&append_to_response=credits,videos,external_ids`, 15000);
        const img = (p, size) => p ? `https://image.tmdb.org/t/p/${size}${p}` : '';
        const trailer = (d.videos && d.videos.results || []).find((v) => v.site === 'YouTube' && /trailer/i.test(v.type));
        data = {
          overview: d.overview || '', backdrop: img(d.backdrop_path, 'w1280'), poster: img(d.poster_path, 'w500'),
          genres: (d.genres || []).map((g) => g.name).join(', '), vote: d.vote_average || 0,
          year: (d.release_date || d.first_air_date || '').slice(0, 4), runtime: d.runtime || (d.episode_run_time || [])[0] || 0,
          cast: (d.credits && d.credits.cast || []).slice(0, 8).map((c) => c.name).join(', '),
          director: (d.credits && d.credits.crew || []).filter((c) => c.job === 'Director').map((c) => c.name).join(', '),
          imdb: d.imdb_id || (d.external_ids && d.external_ids.imdb_id) || '', trailer: trailer ? trailer.key : '',
        };
      }
    } catch { return null; }
    remember(ck, data);
    return data;
  };

  /** Everything known about a movie or series, merged: provider info, then TMDb, then OMDb ratings. */
  M.details = async (item, providerInfo) => {
    const p = providerInfo || {};
    const { title, year: y } = M.clean(item.name);
    const year = y || (p.releasedate || p.releaseDate || p.release_date || item.year || '').slice(0, 4);
    const type = item.kind === 'series' ? 'series' : 'movie';
    const tm = await M.tmdb(title, year, type, p.tmdb_id || item.tmdb || '');
    const om = await M.omdb(title, year, type, (tm && tm.imdb) || '');
    const backdrop = (tm && tm.backdrop) || (Array.isArray(p.backdrop_path) ? p.backdrop_path[0] : p.backdrop_path) || '';
    const ratings = [];
    if (om && om.imdbRating && om.imdbRating !== 'N/A') ratings.push({ src: 'IMDb', val: om.imdbRating + '/10' });
    for (const r of (om && om.Ratings) || []) {
      if (r.Source === 'Rotten Tomatoes') ratings.push({ src: 'Rotten Tomatoes', val: r.Value });
      if (r.Source === 'Metacritic') ratings.push({ src: 'Metacritic', val: r.Value });
    }
    if (!ratings.length && tm && tm.vote) ratings.push({ src: 'TMDb', val: tm.vote.toFixed(1) + '/10' });
    if (!ratings.length && (p.rating || item.rating)) ratings.push({ src: 'Rating', val: (+(p.rating || item.rating)).toFixed(1) + '/10' });
    const na = (v) => (v && v !== 'N/A' ? v : '');
    return {
      title: (om && na(om.Title)) || title,
      year: year || (om && na(om.Year)) || (tm && tm.year) || '',
      plot: (tm && tm.overview) || p.plot || p.description || item.plot || (om && na(om.Plot)) || '',
      genre: p.genre || item.genre || (tm && tm.genres) || (om && na(om.Genre)) || '',
      cast: p.cast || p.actors || (tm && tm.cast) || (om && na(om.Actors)) || '',
      director: p.director || (tm && tm.director) || (om && na(om.Director)) || '',
      runtime: (om && na(om.Runtime)) || (tm && tm.runtime ? tm.runtime + ' min' : '') || (p.duration || ''),
      rated: om && na(om.Rated) || '',
      poster: item.poster || (tm && tm.poster) || (om && na(om.Poster)) || p.movie_image || p.cover || '',
      backdrop,
      ratings,
      trailer: p.youtube_trailer || (tm && tm.trailer) || '',
    };
  };
})();
