/* Map Navigator frontend.
 *
 * Talks to the Spring Boot backend (/api, falling back to :8080 during
 * development) for live traffic, weather, incidents, predictive routing and
 * user data. If no backend is reachable it degrades to DEMO MODE: the same
 * Bengaluru network and an equivalent A* router (profiles, alternatives,
 * closures) run entirely in the browser with simulated time-of-day traffic,
 * so the app stays fully usable as a static page.
 */
"use strict";

/* ---- embedded network (mirrors backend RoadNetwork / PostgresDBSetup.sql) ---- */
const NODES = [{"id": 1, "name": "MG Road", "lat": 12.9757, "lon": 77.606}, {"id": 2, "name": "Trinity Circle", "lat": 12.973, "lon": 77.619}, {"id": 3, "name": "Cubbon Park", "lat": 12.9763, "lon": 77.5929}, {"id": 4, "name": "Vidhana Soudha", "lat": 12.9794, "lon": 77.5907}, {"id": 5, "name": "Majestic", "lat": 12.9767, "lon": 77.5713}, {"id": 6, "name": "KR Market", "lat": 12.9622, "lon": 77.575}, {"id": 7, "name": "Lalbagh", "lat": 12.9507, "lon": 77.5848}, {"id": 8, "name": "Jayanagar", "lat": 12.9308, "lon": 77.5838}, {"id": 9, "name": "BTM Layout", "lat": 12.9166, "lon": 77.6101}, {"id": 10, "name": "Koramangala", "lat": 12.9352, "lon": 77.6245}, {"id": 11, "name": "Domlur", "lat": 12.961, "lon": 77.6387}, {"id": 12, "name": "Indiranagar", "lat": 12.9719, "lon": 77.6412}, {"id": 13, "name": "Ulsoor", "lat": 12.9816, "lon": 77.6285}, {"id": 14, "name": "Shivajinagar", "lat": 12.9857, "lon": 77.6057}, {"id": 15, "name": "Cantonment", "lat": 12.9932, "lon": 77.5982}, {"id": 16, "name": "Mekhri Circle", "lat": 13.0068, "lon": 77.5813}, {"id": 17, "name": "Malleshwaram", "lat": 13.0031, "lon": 77.5643}, {"id": 18, "name": "Yeshwanthpur", "lat": 13.023, "lon": 77.552}, {"id": 19, "name": "Hebbal", "lat": 13.0358, "lon": 77.597}, {"id": 20, "name": "Marathahalli", "lat": 12.9569, "lon": 77.7011}, {"id": 21, "name": "HSR Layout", "lat": 12.9116, "lon": 77.6446}, {"id": 22, "name": "Silk Board", "lat": 12.9177, "lon": 77.6233}, {"id": 23, "name": "Richmond Circle", "lat": 12.9634, "lon": 77.5988}, {"id": 24, "name": "Banashankari", "lat": 12.925, "lon": 77.5468}];
const EDGES = [[1, 2, "MG Road", 1.5, 50], [1, 3, "Kasturba Road", 1.5, 40], [1, 13, "Kamaraj Road", 1.5, 40], [1, 23, "Residency Road", 1.6, 40], [2, 11, "Old Airport Road", 2.2, 60], [2, 13, "Ulsoor Road", 1.2, 50], [3, 4, "Ambedkar Veedhi", 0.6, 40], [3, 23, "Nrupathunga Road", 1.6, 40], [4, 5, "KG Road", 2.2, 40], [4, 14, "Cubbon Road", 1.0, 40], [5, 6, "SJP Road", 1.7, 30], [5, 15, "Seshadri Road", 3.0, 40], [5, 17, "Platform Road", 3.5, 40], [6, 7, "RV Road", 1.7, 30], [6, 23, "JC Road", 2.0, 30], [6, 24, "Mysore Road Link", 6.0, 40], [7, 8, "South End Road", 2.4, 40], [7, 23, "Richmond Road", 1.8, 40], [8, 9, "Outer Ring Road South", 3.0, 40], [8, 24, "Kanakapura Cross", 4.2, 50], [9, 10, "Sarjapur Link", 2.6, 40], [9, 22, "BTM Main Road", 1.6, 40], [10, 11, "Inner Ring Road", 3.0, 50], [10, 21, "Sarjapur Road", 3.0, 40], [10, 22, "Hosur Road", 2.2, 40], [11, 12, "CMH Road", 1.4, 50], [11, 20, "Old Airport Road East", 7.2, 60], [12, 13, "100 Feet Road", 1.6, 50], [13, 14, "MM Road", 2.3, 40], [14, 15, "Bellary Road South", 1.2, 40], [15, 16, "Bellary Road", 2.6, 50], [16, 17, "CV Raman Road", 2.2, 50], [16, 19, "Bellary Road North", 3.5, 60], [17, 18, "Tumkur Road Link", 3.0, 50], [18, 19, "Outer Ring Road North", 5.6, 60], [20, 21, "Outer Ring Road East", 6.8, 60], [21, 22, "HSR 27th Main", 2.4, 40]]; // [from, to, road, km, km/h] — bidirectional

const nodeById = new Map(NODES.map(n => [n.id, n]));
const adjacency = new Map();
for (const [a, b, road, dist, spd] of EDGES) {
  if (!adjacency.has(a)) adjacency.set(a, []);
  if (!adjacency.has(b)) adjacency.set(b, []);
  adjacency.get(a).push({ from: a, to: b, road, dist, spd });
  adjacency.get(b).push({ from: b, to: a, road, dist, spd });
}
const MAX_SPEED = Math.max(...EDGES.map(e => e[4]));

/* ------------------------------------------------------------------ state */
const state = {
  api: null,                 // active API base, null = demo mode
  congestion: {},            // "from-to" -> level (>= 1)
  closed: new Set(),         // "from-to" keys of closed directed edges
  incidents: [],
  weather: { condition: "CLEAR", temperatureC: 26, speedFactor: 1 },
  startId: null,
  goalId: null,
  profile: "fastest",
  routes: [],                // active route first, then alternatives
  activeRoute: 0,
  reporting: false,
  reportEdge: null,
  demoIncidentId: 1,
};

const REDUCED_MOTION = matchMedia("(prefers-reduced-motion: reduce)").matches;
const USER = "demo";
const $ = id => document.getElementById(id);

/* ------------------------------------------------------------ backend io */
const API_CANDIDATES = (() => {
  const bases = [];
  if (location.protocol.startsWith("http")) bases.push(`${location.origin}/api`);
  const host = location.hostname || "localhost";
  const proto = location.protocol.startsWith("http") ? location.protocol : "http:";
  const dev = `${proto}//${host}:8080/api`;
  if (!bases.includes(dev)) bases.push(dev);
  return bases;
})();

async function api(path, opts = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 3500);
  try {
    const res = await fetch(state.api + path, { ...opts, signal: controller.signal });
    if (!res.ok) throw Object.assign(new Error(`HTTP ${res.status}`), { status: res.status });
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

async function probeBackend() {
  for (const base of API_CANDIDATES) {
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 2500);
      const res = await fetch(base + "/weather", { signal: controller.signal });
      clearTimeout(timer);
      if (res.ok) { state.api = base; return true; }
    } catch { /* try next */ }
  }
  state.api = null;
  return false;
}

function setModeChip() {
  const chip = $("modeChip");
  chip.textContent = state.api ? "live" : "demo mode";
  chip.className = "mode-chip " + (state.api ? "live" : "demo");
}

/* ------------------------------------------------------- demo-mode engine */
function hourFactor(h) {
  if (h >= 8 && h <= 10) return 1.9;
  if (h === 11 || h === 7) return 1.5;
  if (h >= 17 && h <= 20) return 2.1;
  if (h === 16 || h === 21) return 1.5;
  if (h >= 12 && h <= 15) return 1.25;
  if (h >= 23 || h <= 5) return 1.0;
  return 1.15;
}

function simulateTraffic() {
  const h = new Date().getHours();
  const now = Date.now();
  state.demoIncidents = (state.demoIncidents || []).filter(i => i.endsAtMs > now);
  state.closed = new Set();
  for (const [a, b] of EDGES) {
    const noise = () => 0.9 + Math.random() * 0.35;
    state.congestion[`${a}-${b}`] = hourFactor(h) * noise();
    state.congestion[`${b}-${a}`] = hourFactor(h) * noise();
  }
  for (const i of state.demoIncidents) {
    if (i.type === "CLOSURE") {
      state.closed.add(`${i.from}-${i.to}`);
      state.closed.add(`${i.to}-${i.from}`);
    } else {
      state.congestion[`${i.from}-${i.to}`] += i.severity;
      state.congestion[`${i.to}-${i.from}`] += i.severity;
    }
  }
  state.incidents = state.demoIncidents.map(i => ({ ...i, startedAtMs: i.startedAtMs }));
}

function haversineKm(a, b) {
  const R = 6371, rad = x => (x * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat), dLon = rad(b.lon - a.lon);
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

function edgeMinutes(e, congestion, weatherFactor) {
  const speed = Math.max(1, (e.spd * weatherFactor) / congestion);
  return (e.dist / speed) * 60;
}

function edgeCost(profile, e, congestion, weatherFactor) {
  if (profile === "shortest") return e.dist;
  if (profile === "eco") {
    const cruise = e.spd * weatherFactor;
    const speedFactor = 1 + ((cruise - 60) / 75) ** 2;
    return e.dist * speedFactor * (1 + 0.35 * (congestion - 1));
  }
  return edgeMinutes(e, congestion, weatherFactor);
}

function heuristicPerKm(profile) {
  return profile === "fastest" ? 60 / MAX_SPEED : 1;
}

/* A* mirroring the backend: settled set, profile cost models, closures,
 * optional edge penalties (for alternatives). */
function localAstar(from, to, { profile, weatherFactor, penalty = null }) {
  const goal = nodeById.get(to);
  const hRate = heuristicPerKm(profile);
  const h = id => haversineKm(nodeById.get(id), goal) * hRate;
  const g = new Map([[from, 0]]);
  const came = new Map();
  const settled = new Set();
  const open = [{ id: from, f: h(from) }];

  while (open.length) {
    let bestIdx = 0;
    for (let i = 1; i < open.length; i++) if (open[i].f < open[bestIdx].f) bestIdx = i;
    const { id: cur } = open.splice(bestIdx, 1)[0];
    if (settled.has(cur)) continue;
    settled.add(cur);
    if (cur === to) break;
    for (const e of adjacency.get(cur) || []) {
      const key = `${cur}-${e.to}`;
      if (state.closed.has(key) || settled.has(e.to)) continue;
      const c = Math.max(1, state.congestion[key] || 1);
      let cost = edgeCost(profile, e, c, weatherFactor);
      if (penalty) cost *= penalty.get(key) || 1;
      const t = g.get(cur) + cost;
      if (t < (g.get(e.to) ?? Infinity)) {
        g.set(e.to, t);
        came.set(e.to, e);
        open.push({ id: e.to, f: t + h(e.to) });
      }
    }
  }
  if (!came.has(to) && from !== to) return null;

  const segments = [];
  let cursor = to;
  while (cursor !== from) {
    const e = came.get(cursor);
    const c = Math.max(1, state.congestion[`${e.from}-${e.to}`] || 1);
    const minutes = edgeMinutes(e, c, state.weather.speedFactor);
    segments.unshift({ from: e.from, to: e.to, roadName: e.road, distanceKm: e.dist,
      congestion: +c.toFixed(2), minutes: +minutes.toFixed(2) });
    cursor = e.from;
  }
  const path = [from, ...segments.map(s => s.to)].map(id => {
    const n = nodeById.get(id);
    return { id: n.id, name: n.name, lat: n.lat, lon: n.lon };
  });
  const eta = segments.reduce((s, x) => s + x.minutes, 0);
  return {
    from: nodeById.get(from).name, to: nodeById.get(to).name,
    profile, predictive: false, departInMinutes: 0, path, segments,
    totalDistanceKm: +segments.reduce((s, x) => s + x.distanceKm, 0).toFixed(2),
    etaMinutes: +eta.toFixed(1),
    etaConfidenceMinutes: demoConfidence(segments, 0),
  };
}

/* Same shape as the backend band: per-segment sensitivity times a horizon-
 * growing spread, root-sum-squared, capped at 35% of the ETA. */
function demoConfidence(segments, departIn) {
  let variance = 0, minutesInto = departIn;
  for (const s of segments) {
    const perUnit = s.minutes / Math.max(1, s.congestion); // minutes per congestion unit
    const sd = Math.min(2, 0.35 * (1 + minutesInto / 45));
    variance += (perUnit * sd) ** 2;
    minutesInto += s.minutes;
  }
  const eta = segments.reduce((sum, s) => sum + s.minutes, 0);
  return +Math.max(0.5, Math.min(1.28 * Math.sqrt(variance), eta * 0.35)).toFixed(1);
}

function localAlternatives(from, to, profile, maxRoutes) {
  const opts = { profile, weatherFactor: state.weather.speedFactor };
  const best = localAstar(from, to, opts);
  if (!best) return [];
  const routes = [best];
  const penalty = new Map();
  for (let attempt = 0; routes.length < maxRoutes && attempt < maxRoutes * 3; attempt++) {
    for (const s of routes[routes.length - 1].segments) {
      for (const k of [`${s.from}-${s.to}`, `${s.to}-${s.from}`]) {
        penalty.set(k, (penalty.get(k) || 1) * 1.45);
      }
    }
    const cand = localAstar(from, to, { ...opts, penalty });
    if (!cand) break;
    const seen = new Set(routes.flatMap(r => r.segments.map(s => `${s.from}-${s.to}`)));
    const shared = cand.segments.filter(s => seen.has(`${s.from}-${s.to}`))
      .reduce((sum, s) => sum + s.distanceKm, 0);
    const overlap = shared / Math.max(cand.totalDistanceKm, 1e-9);
    const distinct = routes.every(r =>
      r.path.map(p => p.id).join(",") !== cand.path.map(p => p.id).join(","));
    if (cand.etaMinutes <= best.etaMinutes * 1.6 && overlap <= 0.75 && distinct) {
      routes.push(cand);
    } else {
      for (const s of cand.segments) {
        const k = `${s.from}-${s.to}`;
        penalty.set(k, (penalty.get(k) || 1) * 1.45);
      }
    }
  }
  return routes;
}

/* ------------------------------------------------------------------ map */
const STYLES = {
  dark: "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json",
  light: "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json",
};

const map = new maplibregl.Map({
  container: "map",
  style: STYLES.dark,
  center: [77.6021, 12.9686],
  zoom: 12.4,
  pitch: REDUCED_MOTION ? 0 : 48,
  bearing: -12,
  attributionControl: { compact: true },
});
map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "top-right");

const startMarker = domMarker("marker-dot start");
const goalMarker = domMarker("marker-dot goal");
const vehicleMarker = domMarker("vehicle");
let incidentMarkers = [];
let hoverPopup = new maplibregl.Popup({ closeButton: false, closeOnClick: false, offset: 10 });

function domMarker(className) {
  const el = document.createElement("div");
  el.className = className;
  return new maplibregl.Marker({ element: el, rotationAlignment: "map" });
}

function lngLat(n) { return [n.lon, n.lat]; }

function emptyFC() { return { type: "FeatureCollection", features: [] }; }

function lineFeature(a, b, properties) {
  return { type: "Feature", properties,
    geometry: { type: "LineString", coordinates: [lngLat(a), lngLat(b)] } };
}

function congestionColor(c) {
  if (c < 1.4) return "#3fb960";
  if (c < 2.0) return "#e8a13c";
  if (c < 3.0) return "#e1662e";
  return "#d8433c";
}
function congestionClass(c) {
  if (c < 1.4) return "c-free";
  if (c < 2.0) return "c-slow";
  if (c < 3.0) return "c-heavy";
  return "c-jam";
}

/** Re-adds every overlay; runs on first load and after each style switch. */
function addOverlays() {
  add3dBuildings();
  const sources = ["traffic", "traffic-closed", "route-alts", "route", "route-lead", "nodes"];
  for (const id of sources) {
    if (!map.getSource(id)) map.addSource(id, { type: "geojson", data: emptyFC() });
  }
  const casing = document.documentElement.dataset.theme === "dark" ? "#0b0e13" : "#ffffff";

  map.addLayer({ id: "traffic", type: "line", source: "traffic",
    layout: { "line-cap": "round" },
    paint: { "line-color": ["get", "color"], "line-width": 2.5, "line-opacity": 0.55 } });
  map.addLayer({ id: "traffic-closed", type: "line", source: "traffic-closed",
    paint: { "line-color": "#d8433c", "line-width": 3, "line-dasharray": [1.2, 1.6] } });
  map.addLayer({ id: "route-alts", type: "line", source: "route-alts",
    layout: { "line-cap": "round", "line-join": "round" },
    paint: { "line-color": "#8a93a3", "line-width": 4, "line-opacity": 0.5,
             "line-dasharray": [0.8, 1.6] } });
  map.addLayer({ id: "route-casing", type: "line", source: "route",
    layout: { "line-cap": "round", "line-join": "round" },
    paint: { "line-color": casing, "line-width": 11, "line-opacity": 0.9 } });
  map.addLayer({ id: "route-line", type: "line", source: "route",
    layout: { "line-cap": "round", "line-join": "round" },
    paint: { "line-color": ["get", "color"], "line-width": 6 } });
  map.addLayer({ id: "route-lead", type: "line", source: "route-lead",
    layout: { "line-cap": "round" },
    paint: { "line-color": "#f2c14e", "line-width": 3, "line-opacity": 0.9 } });
  map.addLayer({ id: "nodes", type: "circle", source: "nodes",
    paint: { "circle-radius": 4.5,
             "circle-color": document.documentElement.dataset.theme === "dark" ? "#e8edf2" : "#2b3440",
             "circle-stroke-width": 1.5,
             "circle-stroke-color": document.documentElement.dataset.theme === "dark" ? "#39424f" : "#ffffff" } });

  map.getSource("nodes").setData({ type: "FeatureCollection",
    features: NODES.map(n => ({ type: "Feature",
      properties: { id: n.id, name: n.name },
      geometry: { type: "Point", coordinates: lngLat(n) } })) });
  drawTraffic();
  drawRoutes();
}

/** Extrudes the basemap's building footprints; tolerant of style differences. */
function add3dBuildings() {
  const style = map.getStyle();
  const buildingLayer = (style.layers || []).find(
    l => l["source-layer"] === "building" && l.type === "fill");
  if (!buildingLayer || map.getLayer("buildings-3d")) return;
  const labelLayer = (style.layers || []).find(
    l => l.type === "symbol" && l.layout && l.layout["text-field"]);
  const dark = document.documentElement.dataset.theme === "dark";
  map.addLayer({
    id: "buildings-3d",
    type: "fill-extrusion",
    source: buildingLayer.source,
    "source-layer": "building",
    minzoom: 13,
    paint: {
      "fill-extrusion-color": dark ? "#232a33" : "#d9dde3",
      "fill-extrusion-height": ["coalesce", ["get", "render_height"], ["get", "height"], 12],
      "fill-extrusion-base": ["coalesce", ["get", "render_min_height"], 0],
      "fill-extrusion-opacity": 0.75,
    },
  }, labelLayer && labelLayer.id);
}

function drawTraffic() {
  if (!map.getSource("traffic")) return;
  const open = [], closed = [];
  for (const [a, b, road] of EDGES) {
    const na = nodeById.get(a), nb = nodeById.get(b);
    const c = Math.max(state.congestion[`${a}-${b}`] || 1, state.congestion[`${b}-${a}`] || 1);
    const isClosed = state.closed.has(`${a}-${b}`) || state.closed.has(`${b}-${a}`);
    const feature = lineFeature(na, nb,
      { from: a, to: b, road, congestion: +c.toFixed(2), color: congestionColor(c) });
    (isClosed ? closed : open).push(feature);
  }
  map.getSource("traffic").setData({ type: "FeatureCollection", features: open });
  map.getSource("traffic-closed").setData({ type: "FeatureCollection", features: closed });
}

function drawRoutes() {
  if (!map.getSource("route")) return;
  const active = state.routes[state.activeRoute];
  const altFeatures = state.routes
    .filter((_, i) => i !== state.activeRoute)
    .flatMap(r => r.segments.map(s =>
      lineFeature(nodeById.get(s.from), nodeById.get(s.to), { idx: 1 })));
  map.getSource("route-alts").setData({ type: "FeatureCollection", features: altFeatures });
  map.getSource("route").setData(!active ? emptyFC() : {
    type: "FeatureCollection",
    features: active.segments.map(s => lineFeature(nodeById.get(s.from), nodeById.get(s.to),
      { color: congestionColor(s.congestion) })),
  });
  map.getSource("route-lead").setData(emptyFC());
}

function drawIncidents() {
  for (const m of incidentMarkers) m.remove();
  incidentMarkers = [];
  for (const i of state.incidents) {
    const a = nodeById.get(i.from), b = nodeById.get(i.to);
    if (!a || !b) continue;
    const el = document.createElement("div");
    el.className = "incident-marker " + (i.type === "CLOSURE" ? "closure" : "accident");
    el.textContent = i.type === "CLOSURE" ? "⛔" : "⚠";
    el.title = `${i.type === "CLOSURE" ? "Road closed" : "Accident"} · ${i.roadName || i.road}`;
    const m = new maplibregl.Marker({ element: el })
      .setLngLat([(a.lon + b.lon) / 2, (a.lat + b.lat) / 2]).addTo(map);
    incidentMarkers.push(m);
  }
}

/* --------------------------------------------------------- route animation */
let animationToken = 0;

function routeCoordinates(route) {
  return route.path.map(p => [p.lon, p.lat]);
}

function animateRouteReveal(route) {
  const token = ++animationToken;
  if (REDUCED_MOTION) return;
  const coords = routeCoordinates(route);
  const lengths = [];
  let total = 0;
  for (let i = 1; i < coords.length; i++) {
    const d = haversineKm({ lat: coords[i - 1][1], lon: coords[i - 1][0] },
                          { lat: coords[i][1], lon: coords[i][0] });
    lengths.push(d); total += d;
  }
  const start = performance.now(), duration = 900;
  function frame(now) {
    if (token !== animationToken || !map.getSource("route-lead")) return;
    const f = Math.min(1, (now - start) / duration);
    map.getSource("route-lead").setData({ type: "FeatureCollection", features: [{
      type: "Feature", properties: {},
      geometry: { type: "LineString", coordinates: sliceCoords(coords, lengths, total * f) },
    }] });
    if (f < 1) requestAnimationFrame(frame);
    else setTimeout(() => {
      if (token === animationToken && map.getSource("route-lead")) {
        map.getSource("route-lead").setData(emptyFC());
      }
    }, 250);
  }
  requestAnimationFrame(frame);
}

function sliceCoords(coords, lengths, targetKm) {
  const out = [coords[0]];
  let walked = 0;
  for (let i = 0; i < lengths.length; i++) {
    if (walked + lengths[i] <= targetKm) {
      out.push(coords[i + 1]);
      walked += lengths[i];
    } else {
      const f = (targetKm - walked) / lengths[i];
      out.push([coords[i][0] + (coords[i + 1][0] - coords[i][0]) * f,
                coords[i][1] + (coords[i + 1][1] - coords[i][1]) * f]);
      break;
    }
  }
  return out.length > 1 ? out : [coords[0], coords[0]];
}

function replayDrive() {
  const route = state.routes[state.activeRoute];
  if (!route || REDUCED_MOTION) return;
  const token = ++animationToken;
  const coords = routeCoordinates(route);
  const segs = route.segments;
  const totalMinutes = Math.max(0.1, route.etaMinutes);
  const duration = Math.min(18000, Math.max(6000, totalMinutes * 500));
  vehicleMarker.setLngLat(coords[0]).addTo(map);
  const start = performance.now();
  function frame(now) {
    if (token !== animationToken) { vehicleMarker.remove(); return; }
    const f = Math.min(1, (now - start) / duration);
    const targetMin = totalMinutes * f;
    let walked = 0, i = 0;
    while (i < segs.length && walked + segs[i].minutes < targetMin) walked += segs[i++].minutes;
    if (i >= segs.length) i = segs.length - 1;
    const segF = Math.min(1, (targetMin - walked) / Math.max(segs[i].minutes, 0.001));
    const a = coords[i], b = coords[i + 1];
    const pos = [a[0] + (b[0] - a[0]) * segF, a[1] + (b[1] - a[1]) * segF];
    vehicleMarker.setLngLat(pos);
    vehicleMarker.setRotation(90 - (Math.atan2(b[1] - a[1], b[0] - a[0]) * 180) / Math.PI);
    if (f < 1) requestAnimationFrame(frame);
    else setTimeout(() => { if (token === animationToken) vehicleMarker.remove(); }, 600);
  }
  requestAnimationFrame(frame);
}

/* ------------------------------------------------------------ routing ui */
function fillSelects() {
  const sorted = [...NODES].sort((a, b) => a.name.localeCompare(b.name));
  for (const sel of [$("fromSel"), $("toSel")]) {
    for (const n of sorted) {
      const o = document.createElement("option");
      o.value = n.id;
      o.textContent = n.name;
      sel.appendChild(o);
    }
  }
}

function setStart(id) {
  state.startId = id;
  $("fromSel").value = id;
  startMarker.setLngLat(lngLat(nodeById.get(id))).addTo(map);
}
function setGoal(id) {
  state.goalId = id;
  $("toSel").value = id;
  goalMarker.setLngLat(lngLat(nodeById.get(id))).addTo(map);
}

async function findRoute({ animate = true } = {}) {
  state.startId = +$("fromSel").value || state.startId;
  state.goalId = +$("toSel").value || state.goalId;
  if (!state.startId || !state.goalId) return;
  if (state.startId === state.goalId) return renderNoRoute("Start and destination are the same.");
  setStart(state.startId);
  setGoal(state.goalId);

  const departIn = +$("departIn").value;
  const btn = $("routeBtn");
  btn.disabled = true;
  btn.textContent = "Routing…";
  let routes = [];
  try {
    if (state.api) {
      try {
        const res = await api(`/route?from=${state.startId}&to=${state.goalId}` +
          `&departIn=${departIn}&profile=${state.profile}&alternatives=3`);
        routes = [
          { from: res.from, to: res.to, profile: res.profile, predictive: res.predictive,
            departInMinutes: res.departInMinutes, path: res.path, segments: res.segments,
            totalDistanceKm: res.totalDistanceKm, etaMinutes: res.etaMinutes,
            etaConfidenceMinutes: res.etaConfidenceMinutes },
          ...(res.alternatives || []),
        ];
        state.weather = res.weather || state.weather;
      } catch (err) {
        if (err && err.status === 404) return renderNoRoute("No route — a closure may be blocking the way.");
        state.api = null;
        setModeChip();
      }
    }
    if (!routes.length) routes = localAlternatives(state.startId, state.goalId, state.profile, 3);
    if (!routes.length) return renderNoRoute("No route found between these points.");
    state.routes = routes;
    state.activeRoute = 0;
    renderRoutes();
    if (animate) {
      focusRoute(routes[0]);
      animateRouteReveal(routes[0]);
    }
  } finally {
    btn.disabled = false;
    btn.textContent = "Find route";
  }
}

function focusRoute(route) {
  const bounds = new maplibregl.LngLatBounds();
  for (const p of route.path) bounds.extend([p.lon, p.lat]);
  const desktop = matchMedia("(min-width: 880px)").matches;
  map.fitBounds(bounds, {
    padding: desktop ? { top: 60, bottom: 60, left: 400, right: 60 }
                     : { top: 40, bottom: window.innerHeight * 0.45, left: 30, right: 30 },
    pitch: REDUCED_MOTION ? 0 : 55,
    bearing: map.getBearing(),
    duration: REDUCED_MOTION ? 0 : 1300,
  });
}

function renderNoRoute(message) {
  state.routes = [];
  drawRoutes();
  const card = $("etaCard");
  card.hidden = false;
  $("etaValue").textContent = "—";
  $("etaBand").textContent = "";
  $("etaDistance").textContent = message;
  $("altList").innerHTML = "";
  $("directions").innerHTML = "";
  $("predictiveTag").hidden = true;
}

function renderRoutes() {
  const route = state.routes[state.activeRoute];
  drawRoutes();
  const card = $("etaCard");
  card.hidden = false;
  $("etaValue").textContent = Math.round(route.etaMinutes);
  $("etaBand").textContent = route.etaConfidenceMinutes
    ? `± ${route.etaConfidenceMinutes}` : "";
  $("etaDistance").textContent =
    `${route.totalDistanceKm} km · ${route.from} → ${route.to} · ${route.profile}`;
  $("predictiveTag").hidden = !route.predictive;

  const alts = $("altList");
  alts.innerHTML = "";
  if (state.routes.length > 1) {
    state.routes.forEach((r, i) => {
      const b = document.createElement("button");
      b.className = "alt-btn" + (i === state.activeRoute ? " active" : "");
      b.innerHTML = `<span class="alt-eta">${Math.round(r.etaMinutes)}′</span>
        <span class="alt-via">${viaLabel(r)}</span>`;
      b.onclick = () => {
        state.activeRoute = i;
        renderRoutes();
        focusRoute(r);
      };
      alts.appendChild(b);
    });
  }

  const dirs = $("directions");
  dirs.innerHTML = "";
  for (const s of route.segments) {
    const li = document.createElement("li");
    li.innerHTML = `<span class="road">${s.roadName} → ${nodeById.get(s.to).name}</span>
      <span class="leg ${congestionClass(s.congestion)}">${s.distanceKm} km · ${s.minutes.toFixed(1)}′</span>`;
    dirs.appendChild(li);
  }
}

function viaLabel(route) {
  if (route.path.length <= 2) return "direct";
  const mid = route.path[Math.floor(route.path.length / 2)];
  return "via " + mid.name;
}

/* If a closure lands on the active route, recompute it. */
function rerouteIfBlocked() {
  const route = state.routes[state.activeRoute];
  if (!route) return;
  const blocked = route.segments.some(s =>
    state.closed.has(`${s.from}-${s.to}`) || state.closed.has(`${s.to}-${s.from}`));
  if (blocked) findRoute({ animate: true });
}

/* --------------------------------------------------------------- incidents */
function renderIncidents() {
  drawIncidents();
  const list = $("incidentList");
  list.innerHTML = state.incidents.length ? "" : '<li class="empty">No active incidents.</li>';
  for (const i of state.incidents) {
    const li = document.createElement("li");
    const minutesLeft = Math.max(1, Math.round((i.endsAtMs - Date.now()) / 60000));
    li.innerHTML = `<span class="inc-icon">${i.type === "CLOSURE" ? "⛔" : "⚠"}</span>
      <span class="inc-body">${i.roadName || i.road}
        <small>${i.type === "CLOSURE" ? "closed" : "accident"} · ~${minutesLeft} min left</small></span>`;
    if (state.api || i.local) {
      const clear = document.createElement("button");
      clear.className = "icon-btn";
      clear.textContent = "×";
      clear.title = "Clear incident";
      clear.onclick = () => clearIncident(i.id);
      li.appendChild(clear);
    }
    list.appendChild(li);
  }
}

async function refreshIncidents() {
  if (state.api) {
    try {
      state.incidents = await api("/incidents");
    } catch { /* keep last known */ }
  }
  renderIncidents();
}

async function reportIncident(edge, type, durationMinutes) {
  if (state.api) {
    try {
      await api("/incidents", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ from: edge.from, to: edge.to, type, durationMinutes }),
      });
      return refreshAll();
    } catch { /* fall through to local */ }
  }
  state.demoIncidents = state.demoIncidents || [];
  state.demoIncidents.push({
    id: state.demoIncidentId++, from: edge.from, to: edge.to, roadName: edge.road,
    type: type.toUpperCase(), severity: 2.5, local: true,
    startedAtMs: Date.now(), endsAtMs: Date.now() + durationMinutes * 60000,
  });
  simulateTraffic();
  drawTraffic();
  renderIncidents();
  rerouteIfBlocked();
}

async function clearIncident(id) {
  if (state.api) {
    try { await api(`/incidents/${id}`, { method: "DELETE" }); } catch { /* refresh anyway */ }
    return refreshAll();
  }
  state.demoIncidents = (state.demoIncidents || []).filter(i => i.id !== id);
  simulateTraffic();
  drawTraffic();
  renderIncidents();
}

function setReporting(on) {
  state.reporting = on;
  $("reportHint").hidden = !on;
  $("reportBtn").textContent = on ? "Cancel" : "Report";
  map.getCanvas().style.cursor = on ? "crosshair" : "";
}

/* ------------------------------------------------------- weather display */
const WEATHER_ICON = { CLEAR: "☀", CLOUDY: "☁", LIGHT_RAIN: "🌦", HEAVY_RAIN: "🌧", FOG: "🌫" };

function renderWeather() {
  const w = state.weather;
  $("weatherLine").textContent =
    `${WEATHER_ICON[w.condition] || ""} ${String(w.condition).replaceAll("_", " ").toLowerCase()}` +
    ` · ${w.temperatureC}°C · speeds ×${w.speedFactor}`;
  const fx = $("weatherFx");
  fx.className = "";
  if (w.condition === "LIGHT_RAIN" || w.condition === "HEAVY_RAIN") {
    fx.className = "rain" + (w.condition === "HEAVY_RAIN" ? " heavy" : "");
  } else if (w.condition === "FOG") {
    fx.className = "fog";
  }
}

/* --------------------------------------------- saved places & history */
const localStore = {
  places: [], history: [], nextId: 1,
  read() {
    try {
      const raw = localStorage.getItem("mapnav-user");
      if (raw) Object.assign(this, JSON.parse(raw));
    } catch { /* private browsing etc. */ }
  },
  write() {
    try {
      localStorage.setItem("mapnav-user", JSON.stringify(
        { places: this.places, history: this.history, nextId: this.nextId }));
    } catch { /* ignore */ }
  },
};

async function loadUserData() {
  if (state.api) {
    try {
      const [places, history] = await Promise.all([
        api(`/users/${USER}/places`), api(`/users/${USER}/history?limit=8`)]);
      return { places, history };
    } catch { /* fall through */ }
  }
  localStore.read();
  return { places: localStore.places, history: localStore.history.slice(0, 8) };
}

async function savePlace(nodeId, label) {
  if (state.api) {
    try {
      await api(`/users/${USER}/places`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ label, nodeId }) });
      return refreshUserData();
    } catch { /* fall through */ }
  }
  const n = nodeById.get(nodeId);
  localStore.places.push({ id: localStore.nextId++, label, nodeId, nodeName: n.name });
  localStore.write();
  refreshUserData();
}

async function deletePlace(id) {
  if (state.api) {
    try {
      await api(`/users/${USER}/places/${id}`, { method: "DELETE" });
      return refreshUserData();
    } catch { /* fall through */ }
  }
  localStore.places = localStore.places.filter(p => p.id !== id);
  localStore.write();
  refreshUserData();
}

async function recordHistory(route) {
  const entry = {
    fromNodeId: route.path[0].id,
    toNodeId: route.path.at(-1).id,
    etaMinutes: route.etaMinutes,
  };
  if (state.api) {
    try {
      await api(`/users/${USER}/history`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(entry) });
      return refreshUserData();
    } catch { /* fall through */ }
  }
  localStore.history.unshift({ id: localStore.nextId++, ...entry,
    fromName: route.from, toName: route.to, timestampMs: Date.now() });
  localStore.history = localStore.history.slice(0, 25);
  localStore.write();
  refreshUserData();
}

async function refreshUserData() {
  const { places, history } = await loadUserData();

  const pl = $("placesList");
  pl.innerHTML = places.length ? "" : '<li class="empty">Find a route, then save the destination.</li>';
  for (const p of places) {
    const li = document.createElement("li");
    li.innerHTML = `<span>${p.label} · ${p.nodeName}</span>`;
    const go = document.createElement("button");
    go.textContent = "→";
    go.title = `Route to ${p.nodeName}`;
    go.className = "icon-btn";
    go.onclick = () => { setGoal(p.nodeId); if (state.startId) findRoute(); };
    const del = document.createElement("button");
    del.textContent = "×";
    del.title = "Remove";
    del.className = "icon-btn";
    del.onclick = () => deletePlace(p.id);
    li.append(go, del);
    pl.appendChild(li);
  }

  const hl = $("historyList");
  hl.innerHTML = history.length ? "" : '<li class="empty">Routes you request appear here.</li>';
  for (const h of history) {
    const li = document.createElement("li");
    li.innerHTML = `<span class="route-pair">${h.fromName} → ${h.toName}</span>
      <span class="eta">${Math.round(h.etaMinutes)}′</span>`;
    li.onclick = () => { setStart(h.fromNodeId); setGoal(h.toNodeId); findRoute(); };
    hl.appendChild(li);
  }
}

/* ------------------------------------------------------------ refresh loop */
async function refreshAll() {
  const wasLive = !!state.api;
  if (!state.api) await probeBackend();
  if (state.api) {
    try {
      const [t, w, incidents] = await Promise.all([
        api("/traffic"), api("/weather"), api("/incidents")]);
      state.congestion = t.congestion;
      state.closed = new Set(t.closed || []);
      state.weather = w;
      state.incidents = incidents;
    } catch {
      state.api = null;
    }
  }
  if (!state.api) simulateTraffic();
  if (wasLive !== !!state.api) refreshUserData();
  setModeChip();
  drawTraffic();
  renderWeather();
  renderIncidents();
  rerouteIfBlocked();
}

/* ------------------------------------------------------------------ wiring */
map.on("load", addOverlays);
map.on("style.load", addOverlays);

map.on("click", ev => {
  if (state.reporting) {
    const pad = 8;
    const features = map.queryRenderedFeatures(
      [[ev.point.x - pad, ev.point.y - pad], [ev.point.x + pad, ev.point.y + pad]],
      { layers: ["traffic", "traffic-closed"] });
    if (features.length) {
      state.reportEdge = features[0].properties;
      $("incidentRoad").textContent = `Report on ${state.reportEdge.road}`;
      $("incidentDialog").showModal();
    }
    return;
  }
  const pad = 12;
  const hits = map.queryRenderedFeatures(
    [[ev.point.x - pad, ev.point.y - pad], [ev.point.x + pad, ev.point.y + pad]],
    { layers: ["nodes"] });
  if (!hits.length) return;
  const id = hits[0].properties.id;
  if (!state.startId || (state.startId && state.goalId)) {
    clearRoute();
    setStart(id);
  } else {
    setGoal(id);
    findRoute();
  }
});

map.on("mousemove", ev => {
  const hits = map.queryRenderedFeatures(
    [[ev.point.x - 6, ev.point.y - 6], [ev.point.x + 6, ev.point.y + 6]],
    { layers: ["nodes"] });
  map.getCanvas().style.cursor = state.reporting ? "crosshair" : (hits.length ? "pointer" : "");
  if (hits.length) {
    hoverPopup.setLngLat(hits[0].geometry.coordinates)
      .setText(hits[0].properties.name).addTo(map);
  } else {
    hoverPopup.remove();
  }
});

function clearRoute() {
  state.startId = state.goalId = null;
  state.routes = [];
  animationToken++;
  vehicleMarker.remove();
  $("fromSel").value = "";
  $("toSel").value = "";
  startMarker.remove();
  goalMarker.remove();
  drawRoutes();
  $("etaCard").hidden = true;
}

$("routeBtn").onclick = () => findRoute();
$("clearBtn").onclick = clearRoute;
$("swapBtn").onclick = () => {
  const a = $("fromSel").value, b = $("toSel").value;
  $("fromSel").value = b;
  $("toSel").value = a;
  if (a && b) { state.startId = +b; state.goalId = +a; findRoute(); }
};
$("replayBtn").onclick = replayDrive;
$("departIn").oninput = () => {
  const v = +$("departIn").value;
  $("departLabel").textContent = v === 0 ? "now" : `in ${v} min`;
};
$("departIn").onchange = () => { if (state.routes.length) findRoute({ animate: false }); };
$("saveRouteBtn").onclick = () => {
  const route = state.routes[state.activeRoute];
  if (route) recordHistory(route);
  if (state.goalId) {
    const label = prompt("Label this destination (e.g. Work):");
    if (label && label.trim()) savePlace(state.goalId, label.trim().slice(0, 40));
  }
};
$("reportBtn").onclick = () => setReporting(!state.reporting);

for (const btn of document.querySelectorAll(".profile-btn[data-profile]")) {
  btn.onclick = () => {
    for (const b of document.querySelectorAll(".profile-btn[data-profile]")) {
      b.classList.toggle("active", b === btn);
      b.setAttribute("aria-checked", b === btn ? "true" : "false");
    }
    state.profile = btn.dataset.profile;
    if (state.routes.length) findRoute({ animate: false });
  };
}

let incidentType = "accident";
for (const btn of document.querySelectorAll("[data-itype]")) {
  btn.onclick = () => {
    for (const b of document.querySelectorAll("[data-itype]")) b.classList.toggle("active", b === btn);
    incidentType = btn.dataset.itype;
  };
}
$("incidentDialog").addEventListener("close", () => {
  setReporting(false);
  if ($("incidentDialog").returnValue === "report" && state.reportEdge) {
    reportIncident(state.reportEdge, incidentType, +$("incidentDuration").value);
  }
  state.reportEdge = null;
});

$("themeBtn").onclick = () => {
  const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem("mapnav-theme", next); } catch { /* ignore */ }
  map.setStyle(STYLES[next]); // overlays return via the style.load handler
};

(async function init() {
  try {
    const saved = localStorage.getItem("mapnav-theme");
    if (saved === "light" || saved === "dark") {
      document.documentElement.dataset.theme = saved;
      if (saved === "light") map.setStyle(STYLES.light);
    }
  } catch { /* ignore */ }
  fillSelects();
  simulateTraffic(); // instant paint; replaced by live data if the backend is up
  renderWeather();
  await refreshAll();
  await refreshUserData();
  setInterval(refreshAll, 5000);
})();
