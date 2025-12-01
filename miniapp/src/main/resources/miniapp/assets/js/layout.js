import { fetchJsonCached } from './etagCache.js';

const clubIdInput = document.getElementById('clubId');
const eventSelect = document.getElementById('eventId');
const reloadBtn = document.getElementById('reload');
const statusEl = document.getElementById('status');
const layoutCacheEl = document.getElementById('layout-cache');
const filterHintEl = document.getElementById('filter-hint');
const canvasEl = document.getElementById('canvas');
const layoutEmptyEl = document.getElementById('layout-empty');

const vipCheckbox = document.getElementById('vipOnly');
const nearStageCheckbox = document.getElementById('nearStage');
const minCapacityInput = document.getElementById('minCapacity');
const minTierSelect = document.getElementById('minTier');

const state = { layout: null, geometry: null, etag: null, layoutAbort: null, geometryAbort: null };

const STATUS_COLORS = {
  FREE: '#2f855a',
  HOLD: '#b7791f',
  BOOKED: '#c53030',
};

function parseQueryDefaults() {
  const qs = new URLSearchParams(window.location.search);
  const clubId = qs.get('clubId');
  const eventId = qs.get('eventId');
  if (clubId) clubIdInput.value = clubId;
  if (eventId) {
    const option = document.createElement('option');
    option.value = eventId;
    option.textContent = eventId;
    eventSelect.appendChild(option);
    eventSelect.value = eventId;
  }
  vipCheckbox.checked = qs.get('vip') === '1';
  nearStageCheckbox.checked = qs.get('near_stage') === '1';
  const minCap = qs.get('minCapacity');
  if (minCap) minCapacityInput.value = minCap;
  if (qs.get('minTier')) minTierSelect.value = qs.get('minTier');
}

function syncQueryFromFilters() {
  const params = new URLSearchParams();
  if (clubIdInput.value) params.set('clubId', clubIdInput.value);
  if (eventSelect.value) params.set('eventId', eventSelect.value);
  if (vipCheckbox.checked) params.set('vip', '1');
  if (nearStageCheckbox.checked) params.set('near_stage', '1');
  if (minCapacityInput.value) params.set('minCapacity', minCapacityInput.value);
  if (minTierSelect.value) params.set('minTier', minTierSelect.value);
  const qs = params.toString();
  const nextUrl = qs ? `${window.location.pathname}?${qs}` : window.location.pathname;
  window.history.replaceState(null, '', nextUrl);
}

function setStatus(text) {
  statusEl.textContent = text || '';
}

function setFilterHint(tablesCount, filteredCount) {
  if (!tablesCount) {
    filterHintEl.textContent = '';
    return;
  }
  filterHintEl.textContent = filteredCount === tablesCount
    ? 'Фильтры не применены'
    : `Показано ${filteredCount} из ${tablesCount}`;
}

function stableZoneColor(id) {
  let hash = 0;
  for (let i = 0; i < id.length; i += 1) hash = (hash * 31 + id.charCodeAt(i)) % 360;
  return `hsl(${hash}, 55%, 82%)`;
}

function statusColor(status) {
  if (status === 'BOOKED') return STATUS_COLORS.BOOKED;
  if (status === 'HOLD') return STATUS_COLORS.HOLD;
  return STATUS_COLORS.FREE;
}

function computeViewBox(geometry) {
  const xs = [];
  const ys = [];
  geometry?.zones?.forEach((zone) => {
    zone.polygon.forEach(([x, y]) => {
      xs.push(x);
      ys.push(y);
    });
  });
  geometry?.tables?.forEach((table) => {
    xs.push(table.x);
    ys.push(table.y);
  });
  const maxX = Math.max(...xs, 12);
  const maxY = Math.max(...ys, 8);
  return { width: maxX + 1, height: maxY + 1 };
}

function applyFilters(tables, zonesById) {
  const vipOnly = vipCheckbox.checked;
  const nearStage = nearStageCheckbox.checked;
  const minCapacity = parseInt(minCapacityInput.value, 10) || null;
  const minTier = minTierSelect.value;

  return tables.filter((table) => {
    const zone = zonesById.get(table.zoneId);
    if (!zone) return false;
    const tags = zone.tags || [];
    if (vipOnly && !tags.includes('vip')) return false;
    if (nearStage && !tags.includes('near_stage')) return false;
    if (minCapacity && table.capacity < minCapacity) return false;
    if (minTier && table.minimumTier !== minTier) return false;
    return true;
  });
}

function renderLayout() {
  canvasEl.innerHTML = '';
  if (!state.layout || !state.geometry) {
    layoutEmptyEl.hidden = false;
    return;
  }
  layoutEmptyEl.hidden = true;
  const { width, height } = computeViewBox(state.geometry);
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'layout');
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');

  const zonesById = new Map(state.layout.zones.map((z) => [z.id, z]));
  state.geometry.zones.forEach((zone) => {
    const polygon = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
    polygon.setAttribute('points', zone.polygon.map(([x, y]) => `${x},${y}`).join(' '));
    polygon.setAttribute('fill', stableZoneColor(zone.id));
    polygon.setAttribute('stroke', '#ccc');
    polygon.setAttribute('stroke-width', '0.05');
    svg.appendChild(polygon);
    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    const [x, y] = zone.polygon[0];
    label.setAttribute('x', x + 0.2);
    label.setAttribute('y', y + 0.7);
    label.setAttribute('font-size', '0.4');
    label.textContent = zonesById.get(zone.id)?.name || zone.id;
    svg.appendChild(label);
  });

  const geometryTables = new Map(state.geometry.tables.map((t) => [t.id, t]));
  const filteredTables = applyFilters(state.layout.tables, zonesById);
  filteredTables.forEach((table) => {
    const geom = geometryTables.get(table.id);
    if (!geom) return;
    const node = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    node.setAttribute('cx', geom.x);
    node.setAttribute('cy', geom.y);
    node.setAttribute('r', '0.35');
    node.setAttribute('fill', statusColor(table.status));
    node.setAttribute('class', `table-node ${table.status === 'BOOKED' ? 'booked' : ''}`);
    node.setAttribute('stroke', '#111');
    node.setAttribute('stroke-width', '0.05');
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
    title.textContent = `${table.label} • ${table.capacity} мест • tier: ${table.minimumTier}`;
    node.appendChild(title);
    if (table.status === 'BOOKED') node.style.pointerEvents = 'none';
    svg.appendChild(node);
  });

  canvasEl.appendChild(svg);
  setFilterHint(state.layout.tables.length, filteredTables.length);
}

async function loadGeometry(url) {
  if (!url) throw new Error('geometryUrl missing');
  if (state.geometryAbort) state.geometryAbort.abort();
  const controller = new AbortController();
  state.geometryAbort = controller;
  const { data } = await fetchJsonCached(url, { signal: controller.signal });
  if (state.geometryAbort !== controller) return;
  state.geometry = data;
}

async function loadEventsOptions(clubId) {
  if (!clubId) return;
  const params = new URLSearchParams({ clubId: clubId.toString(), page: '0', size: '30' });
  const { data } = await fetchJsonCached(`/api/events?${params.toString()}`);
  if (!Array.isArray(data)) return;
  const current = eventSelect.value;
  eventSelect.innerHTML = '<option value="">(нет)</option>';
  data.forEach((evt) => {
    const option = document.createElement('option');
    option.value = evt.id;
    const start = new Date(evt.startUtc);
    option.textContent = `${evt.id} • ${start.toISOString()}`;
    eventSelect.appendChild(option);
  });
  if (current && [...eventSelect.options].some((o) => o.value === current)) {
    eventSelect.value = current;
  }
}

async function loadLayout() {
  const clubId = parseInt(clubIdInput.value, 10);
  const eventId = eventSelect.value || null;
  if (!clubId) {
    setStatus('Укажите clubId');
    layoutEmptyEl.hidden = false;
    return;
  }

  layoutCacheEl.hidden = true;
  setStatus('Загрузка...');
  const qs = new URLSearchParams();
  if (eventId) qs.set('eventId', eventId);
  const url = qs.toString() ? `/api/clubs/${clubId}/layout?${qs.toString()}` : `/api/clubs/${clubId}/layout`;
  syncQueryFromFilters();
  if (state.layoutAbort) state.layoutAbort.abort();
  if (state.geometryAbort) state.geometryAbort.abort();
  const controller = new AbortController();
  state.layoutAbort = controller;
  try {
    const { data, cached, etag } = await fetchJsonCached(url, { signal: controller.signal });
    if (state.layoutAbort !== controller) return;
    if (etag === state.etag && cached) {
      setStatus('304 → из кэша');
      layoutCacheEl.hidden = false;
      return;
    }
    layoutCacheEl.hidden = !cached;
    if (!data) throw new Error('empty layout');
    state.layout = data;
    state.etag = etag || null;
    await loadGeometry(data.assets.geometryUrl);
    populateTiers(data.tables);
    renderLayout();
    setStatus(cached ? '304 → из кэша' : '');
    await loadEventsOptions(clubId);
  } catch (e) {
    if (e.name !== 'AbortError') {
      console.error(e);
      setStatus('Ошибка загрузки layout');
      layoutEmptyEl.hidden = false;
    }
  }
}

function populateTiers(tables) {
  const desired = minTierSelect.value;
  const tiers = Array.from(new Set((tables || []).map((t) => t.minimumTier))).sort();
  minTierSelect.innerHTML = '<option value="">Любой</option>';
  tiers.forEach((tier) => {
    const option = document.createElement('option');
    option.value = tier;
    option.textContent = tier;
    minTierSelect.appendChild(option);
  });
  if (desired && [...minTierSelect.options].some((o) => o.value === desired)) {
    minTierSelect.value = desired;
  }
}

reloadBtn.addEventListener('click', () => {
  loadLayout();
});

[vipCheckbox, nearStageCheckbox, minCapacityInput, minTierSelect].forEach((el) => {
  el.addEventListener('change', () => {
    if (state.layout && state.geometry) renderLayout();
    syncQueryFromFilters();
  });
});

eventSelect.addEventListener('change', () => {
  syncQueryFromFilters();
  loadLayout();
});

clubIdInput.addEventListener('input', () => {
  syncQueryFromFilters();
});

parseQueryDefaults();
if (clubIdInput.value) {
  loadLayout();
}
