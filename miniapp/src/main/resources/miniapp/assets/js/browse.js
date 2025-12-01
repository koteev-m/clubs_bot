import { fetchJsonCached } from './etagCache.js';
import { todayUtc } from './api.js';

const filtersForm = document.getElementById('filters');
const loadMoreBtn = document.getElementById('load-more');
const clubsListEl = document.getElementById('clubs-list');
const clubsEmptyEl = document.getElementById('clubs-empty');
const eventsListEl = document.getElementById('events-list');
const eventsEmptyEl = document.getElementById('events-empty');
const clubsStatusEl = document.getElementById('clubs-status');
const eventsStatusEl = document.getElementById('events-status');
const cacheHintEl = document.getElementById('cache-hint');

const state = {
  nextPage: 0,
  size: 10,
  loading: false,
  clubs: [],
  debounceTimer: null,
  clubsAbort: null,
  eventsAbort: null,
};

function ensureDateNormalized() {
  const dateInput = document.getElementById('date');
  const raw = dateInput.value;
  if (!raw) {
    dateInput.value = todayUtc();
    return dateInput.value;
  }
  const normalized = new Date(`${raw}T00:00:00Z`).toISOString().slice(0, 10);
  dateInput.value = normalized;
  return normalized;
}

function restoreFiltersFromQuery() {
  const qs = new URLSearchParams(window.location.search);
  if (qs.get('city')) document.getElementById('city').value = qs.get('city');
  if (qs.get('date')) document.getElementById('date').value = qs.get('date');
  if (qs.get('genre')) document.getElementById('genre').value = qs.get('genre');
  if (qs.get('q')) document.getElementById('search').value = qs.get('q');
  if (qs.get('tag') === 'quiet_day') document.getElementById('quiet').checked = true;
  if (qs.get('size')) document.getElementById('size').value = qs.get('size');
}

function collectFilters() {
  const city = document.getElementById('city').value.trim();
  const date = ensureDateNormalized();
  const genre = document.getElementById('genre').value;
  const q = document.getElementById('search').value.trim();
  const quiet = document.getElementById('quiet').checked;
  state.size = parseInt(document.getElementById('size').value, 10) || 10;
  return { city, date, genre, q, quiet, size: state.size };
}

function updateQueryFromFilters(filters) {
  const params = new URLSearchParams();
  if (filters.city) params.set('city', filters.city);
  if (filters.date) params.set('date', filters.date);
  if (filters.genre) params.set('genre', filters.genre);
  if (filters.q) params.set('q', filters.q);
  if (filters.quiet) params.set('tag', 'quiet_day');
  if (filters.size) params.set('size', filters.size.toString());
  const qs = params.toString();
  const nextUrl = qs ? `${window.location.pathname}?${qs}` : window.location.pathname;
  window.history.replaceState(null, '', nextUrl);
}

function buildClubsUrl(resetPage = false) {
  if (resetPage) state.nextPage = 0;
  const params = new URLSearchParams();
  const filters = collectFilters();

  if (filters.city) params.set('city', filters.city);
  if (filters.date) params.set('date', filters.date);
  if (filters.genre) params.set('genre', filters.genre);
  if (filters.q) params.set('q', filters.q);
  if (filters.quiet) params.set('tag', 'quiet_day');
  params.set('page', state.nextPage.toString());
  params.set('size', state.size.toString());

  updateQueryFromFilters(filters);

  return `/api/clubs?${params.toString()}`;
}

function setStatus(el, text) {
  el.textContent = text || '';
}

function renderClubs(clubs, append = false) {
  if (!append) {
    clubsListEl.innerHTML = '';
    state.clubs = [];
  }
  const fragment = document.createDocumentFragment();
  clubs.forEach((club) => {
    state.clubs.push(club);
    const card = document.createElement('div');
    card.className = 'list-item';
    card.dataset.clubId = club.id;

    const heading = document.createElement('h3');
    heading.textContent = club.name;
    const city = document.createElement('p');
    city.className = 'small muted';
    city.textContent = club.city;
    const genreLine = document.createElement('p');
    genreLine.className = 'small';
    genreLine.textContent = `Жанры: ${(club.genres || []).join(', ') || '—'}`;
    const tagsWrap = document.createElement('div');
    (club.tags || []).forEach((tag) => {
      const span = document.createElement('span');
      span.className = 'tag';
      span.textContent = tag;
      tagsWrap.appendChild(span);
    });

    card.appendChild(heading);
    card.appendChild(city);
    card.appendChild(genreLine);
    card.appendChild(tagsWrap);
    card.addEventListener('click', () => loadEventsForClub(club));
    fragment.appendChild(card);
  });
  clubsListEl.appendChild(fragment);
  clubsEmptyEl.hidden = state.clubs.length > 0;
}

function renderEvents(events) {
  eventsListEl.innerHTML = '';
  if (!events || events.length === 0) {
    eventsEmptyEl.hidden = false;
    eventsEmptyEl.textContent = 'Нет ивентов для выбранного клуба.';
    return;
  }

  eventsEmptyEl.hidden = true;
  const fragment = document.createDocumentFragment();
  events.forEach((event) => {
    const card = document.createElement('div');
    card.className = 'list-item';
    const start = new Date(event.startUtc).toISOString();
    const end = new Date(event.endUtc).toISOString();

    const title = document.createElement('h4');
    title.textContent = event.title || 'Без названия';
    const period = document.createElement('p');
    period.className = 'small';
    period.textContent = `${start} → ${end}`;
    const idBadge = document.createElement('span');
    idBadge.className = 'badge';
    idBadge.textContent = `#${event.id}`;
    card.appendChild(title);
    card.appendChild(period);
    card.appendChild(idBadge);
    if (event.isSpecial) {
      const special = document.createElement('span');
      special.className = 'badge status-hold';
      special.textContent = 'special';
      card.appendChild(special);
    }
    const clubId = document.createElement('div');
    clubId.className = 'small muted';
    clubId.textContent = `clubId=${event.clubId}`;
    card.appendChild(clubId);

    fragment.appendChild(card);
  });
  eventsListEl.appendChild(fragment);
}

async function loadClubs({ reset = false } = {}) {
  if (state.loading && state.clubsAbort) {
    state.clubsAbort.abort();
  }
  const url = buildClubsUrl(reset);
  const controller = new AbortController();
  state.clubsAbort = controller;
  state.loading = true;
  loadMoreBtn.disabled = true;
  cacheHintEl.hidden = true;
  setStatus(clubsStatusEl, 'Загрузка...');
  let hasMore = true;
  try {
    const { data, cached } = await fetchJsonCached(url, { signal: controller.signal });
    if (state.clubsAbort !== controller) return;
    cacheHintEl.hidden = !cached;
    if (reset) {
      renderClubs(data || [], false);
    } else {
      renderClubs(data || [], true);
    }
    hasMore = Array.isArray(data) && data.length >= state.size;
    state.nextPage += 1;
    setStatus(clubsStatusEl, cached ? 'ответ 304 → кэш' : '');
  } catch (e) {
    if (e.name === 'AbortError') {
      setStatus(clubsStatusEl, '');
    } else {
      console.error(e);
      setStatus(clubsStatusEl, 'Ошибка загрузки');
      hasMore = false;
    }
  } finally {
    if (state.clubsAbort === controller) {
      state.loading = false;
      loadMoreBtn.disabled = !hasMore;
    }
  }
}

function dateRangeFromFilter() {
  const date = ensureDateNormalized();
  if (!date) return {};
  const from = `${date}T00:00:00Z`;
  const toDate = new Date(`${date}T00:00:00Z`);
  toDate.setUTCDate(toDate.getUTCDate() + 1);
  const to = toDate.toISOString();
  return { from, to };
}

async function loadEventsForClub(club) {
  if (!club?.id) return;
  eventsStatusEl.textContent = 'Загрузка...';
  eventsEmptyEl.hidden = true;
  if (state.eventsAbort) state.eventsAbort.abort();
  const controller = new AbortController();
  state.eventsAbort = controller;
  const params = new URLSearchParams({ clubId: club.id.toString(), page: '0', size: '20' });
  const { from, to } = dateRangeFromFilter();
  if (from) params.set('from', from);
  if (to) params.set('to', to);

  try {
    const { data, cached } = await fetchJsonCached(`/api/events?${params.toString()}`, { signal: controller.signal });
    if (state.eventsAbort !== controller) return;
    renderEvents(data || []);
    eventsStatusEl.textContent = cached ? '304 → из кэша' : '';
    if (!data || data.length === 0) {
      eventsEmptyEl.hidden = false;
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      console.error(e);
      eventsStatusEl.textContent = 'Ошибка загрузки ивентов';
      eventsEmptyEl.hidden = false;
      eventsEmptyEl.textContent = 'Не удалось загрузить ивенты';
    }
  }
}

filtersForm.addEventListener('submit', (e) => {
  e.preventDefault();
  state.nextPage = 0;
  loadClubs({ reset: true });
});

document.getElementById('search').addEventListener('input', () => {
  if (state.debounceTimer) clearTimeout(state.debounceTimer);
  state.debounceTimer = setTimeout(() => {
    state.nextPage = 0;
    loadClubs({ reset: true });
  }, 300);
});

loadMoreBtn.addEventListener('click', () => {
  loadClubs({ reset: false });
});

filtersForm.addEventListener('input', () => updateQueryFromFilters(collectFilters()));

document.addEventListener('DOMContentLoaded', () => {
  restoreFiltersFromQuery();
  ensureDateNormalized();
  loadClubs({ reset: true });
});
