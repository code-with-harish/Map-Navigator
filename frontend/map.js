/* Map Navigator frontend.
 *
 * Talks to the Spring Boot backend (http://localhost:8080/api) for live
 * traffic, weather, predictive routing, and user data. If the backend is
 * unreachable, it degrades gracefully to DEMO MODE: the same Bengaluru road
 * network and an equivalent A* router run entirely in the browser with
 * simulated time-of-day traffic, so the app is fully usable standalone.
 */
"use strict";

const API = (location.hostname ? `${location.protocol}//${location.hostname}` : "http://localhost") + ":8080/api";
const USER = "demo";

/* ---- embedded network (mirrors backend RoadNetwork / PostgresDBSetup.sql) ---- */
const NODES = [{"id": 1, "name": "MG Road", "lat": 12.9757, "lon": 77.606}, {"id": 2, "name": "Trinity Circle", "lat": 12.973, "lon": 77.619}, {"id": 3, "name": "Cubbon Park", "lat": 12.9763, "lon": 77.5929}, {"id": 4, "name": "Vidhana Soudha", "lat": 12.9794, "lon": 77.5907}, {"id": 5, "name": "Majestic", "lat": 12.9767, "lon": 77.5713}, {"id": 6, "name": "KR Market", "lat": 12.9622, "lon": 77.575}, {"id": 7, "name": "Lalbagh", "lat": 12.9507, "lon": 77.5848}, {"id": 8, "name": "Jayanagar", "lat": 12.9308, "lon": 77.5838}, {"id": 9, "name": "BTM Layout", "lat": 12.9166, "lon": 77.6101}, {"id": 10, "name": "Koramangala", "lat": 12.9352, "lon": 77.6245}, {"id": 11, "name": "Domlur", "lat": 12.961, "lon": 77.6387}, {"id": 12, "name": "Indiranagar", "lat": 12.9719, "lon": 77.6412}, {"id": 13, "name": "Ulsoor", "lat": 12.9816, "lon": 77.6285}, {"id": 14, "name": "Shivajinagar", "lat": 12.9857, "lon": 77.6057}, {"id": 15, "name": "Cantonment", "lat": 12.9932, "lon": 77.5982}, {"id": 16, "name": "Mekhri Circle", "lat": 13.0068, "lon": 77.5813}, {"id": 17, "name": "Malleshwaram", "lat": 13.0031, "lon": 77.5643}, {"id": 18, "name": "Yeshwanthpur", "lat": 13.023, "lon": 77.552}, {"id": 19, "name": "Hebbal", "lat": 13.0358, "lon": 77.597}, {"id": 20, "name": "Marathahalli", "lat": 12.9569, "lon": 77.7011}, {"id": 21, "name": "HSR Layout", "lat": 12.9116, "lon": 77.6446}, {"id": 22, "name": "Silk Board", "lat": 12.9177, "lon": 77.6233}, {"id": 23, "name": "Richmond Circle", "lat": 12.9634, "lon": 77.5988}, {"id": 24, "name": "Banashankari", "lat": 12.925, "lon": 77.5468}];
const EDGES = [[1, 2, "MG Road", 1.5, 50.0], [1, 3, "Kasturba Road", 1.5, 40.0], [1, 13, "Kamaraj Road", 1.5, 40.0], [1, 23, "Residency Road", 1.6, 40.0], [2, 11, "Old Airport Road", 2.2, 60.0], [2, 13, "Ulsoor Road", 1.2, 50.0], [3, 4, "Ambedkar Veedhi", 0.6, 40.0], [3, 23, "Nrupathunga Road", 1.6, 40.0], [4, 5, "KG Road", 2.2, 40.0], [4, 14, "Cubbon Road", 1.0, 40.0], [5, 6, "SJP Road", 1.7, 30.0], [5, 15, "Seshadri Road", 3.0, 40.0], [5, 17, "Platform Road", 3.5, 40.0], [6, 7, "RV Road", 1.7, 30.0], [6, 23, "JC Road", 2.0, 30.0], [6, 24, "Mysore Road Link", 6.0, 40.0], [7, 8, "South End Road", 2.4, 40.0], [7, 23, "Richmond Road", 1.8, 40.0], [8, 9, "Outer Ring Road South", 3.0, 40.0], [8, 24, "Kanakapura Cross", 4.2, 50.0], [9, 10, "Sarjapur Link", 2.6, 40.0], [9, 22, "BTM Main Road", 1.6, 40.0], [10, 11, "Inner Ring Road", 3.0, 50.0], [10, 21, "Sarjapur Road", 3.0, 40.0], [10, 22, "Hosur Road", 2.2, 40.0], [11, 12, "CMH Road", 1.4, 50.0], [11, 20, "Old Airport Road East", 7.2, 60.0], [12, 13, "100 Feet Road", 1.6, 50.0], [13, 14, "MM Road", 2.3, 40.0], [14, 15, "Bellary Road South", 1.2, 40.0], [15, 16, "Bellary Road", 2.6, 50.0], [16, 17, "CV Raman Road", 2.2, 50.0], [16, 19, "Bellary Road North", 3.5, 60.0], [17, 18, "Tumkur Road Link", 3.0, 50.0], [18, 19, "Outer Ring Road North", 5.6, 60.0], [20, 21, "Outer Ring Road East", 6.8, 60.0], [21, 22, "HSR 27th Main", 2.4, 40.0]]; // [from, to, road, distanceKm, speedLimitKmh] — bidirectional

/* ------------------------------------------------------------------ state */
const nodeById = new Map(NODES.map(n => [n.id, n]));
let liveMode = false;
let congestion = {};            // "from-to" -> level (>= 1)
let weatherNow = { condition: "CLEAR", temperatureC: 26, speedFactor: 1 };
let startId = null, goalId = null;
let lastRoute = null;

/* ------------------------------------------------------------------ map */
const map = L.map("map", { zoomControl: false }).setView([12.9686, 77.6021], 13);
L.control.zoom({ position: "topright" }).addTo(map);
L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
  maxZoom: 19,
  attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
}).addTo(map);

const trafficLayer = L.layerGroup().addTo(map);
const routeLayer = L.layerGroup().addTo(map);
const markerLayer = L.layerGroup().addTo(map);

for (const n of NODES) {
  L.circleMarker([n.lat, n.lon], {
    radius: 4, color: "#3c4650", weight: 1.5, fillColor: "#fff", fillOpacity: 1,
  }).addTo(map).bindTooltip(n.name, { permanent: false, direction: "top", className: "node-label" });
}

function congestionColor(c) {
  if (c < 1.4) return "#2fa45d";
  if (c < 2.0) return "#e3a008";
  if (c < 3.0) return "#e06a2b";
  return "#d64541";
}
function congestionClass(c) {
  if (c < 1.4) return "c-free";
  if (c < 2.0) return "c-slow";
  if (c < 3.0) return "c-heavy";
  return "c-jam";
}

function drawTraffic() {
  trafficLayer.clearLayers();
  for (const [a, b] of EDGES.map(e => [e[0], e[1]])) {
    const na = nodeById.get(a), nb = nodeById.get(b);
    const c = Math.max(congestion[`${a}-${b}`] || 1, congestion[`${b}-${a}`] || 1);
    L.polyline([[na.lat, na.lon], [nb.lat, nb.lon]], {
      color: congestionColor(c), weight: 3, opacity: 0.55,
    }).addTo(trafficLayer);
  }
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
  for (const [a, b] of EDGES.map(e => [e[0], e[1]])) {
    const noise = () => 0.9 + Math.random() * 0.35;
    congestion[`${a}-${b}`] = hourFactor(h) * noise();
    congestion[`${b}-${a}`] = hourFactor(h) * noise();
  }
}

function haversineKm(a, b) {
  const R = 6371, rad = x => (x * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat), dLon = rad(b.lon - a.lon);
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

function localRoute(from, to) {
  // A* on travel time — mirrors backend AStarNavigator
  const adj = new Map();
  for (const [a, b, road, dist, spd] of EDGES) {
    if (!adj.has(a)) adj.set(a, []);
    if (!adj.has(b)) adj.set(b, []);
    adj.get(a).push({ to: b, road, dist, spd });
    adj.get(b).push({ to: a, road, dist, spd });
  }
  const maxSpd = Math.max(...EDGES.map(e => e[4]));
  const goal = nodeById.get(to);
  const h = id => (haversineKm(nodeById.get(id), goal) / maxSpd) * 60;
  const g = new Map([[from, 0]]);
  const came = new Map();
  const open = [[from, h(from)]];

  while (open.length) {
    open.sort((x, y) => x[1] - y[1]);
    const [cur] = open.shift();
    if (cur === to) break;
    for (const e of adj.get(cur) || []) {
      const c = Math.max(1, congestion[`${cur}-${e.to}`] || 1);
      const minutes = (e.dist / Math.max(1, (e.spd * weatherNow.speedFactor) / c)) * 60;
      const t = g.get(cur) + minutes;
      if (t < (g.get(e.to) ?? Infinity)) {
        g.set(e.to, t);
        came.set(e.to, { from: cur, road: e.road, dist: e.dist, minutes, congestion: c });
      }
      if (t < (g.get(e.to) ?? Infinity) + 1e-9) open.push([e.to, t + h(e.to)]);
    }
  }
  if (!came.has(to) && from !== to) return null;

  const segments = [];
  let cursor = to;
  while (cursor !== from) {
    const s = came.get(cursor);
    segments.unshift({ from: s.from, to: cursor, roadName: s.road, distanceKm: s.dist,
      congestion: +s.congestion.toFixed(2), minutes: +s.minutes.toFixed(2) });
    cursor = s.from;
  }
  const path = [from, ...segments.map(s => s.to)].map(id => nodeById.get(id));
  return {
    from: nodeById.get(from).name, to: nodeById.get(to).name,
    predictive: false, path, segments,
    totalDistanceKm: +segments.reduce((s, x) => s + x.distanceKm, 0).toFixed(2),
    etaMinutes: +segments.reduce((s, x) => s + x.minutes, 0).toFixed(1),
    weather: weatherNow,
  };
}

/* ------------------------------------------------------------ backend io */
async function api(path, opts) {
  const res = await fetch(API + path, opts);
  if (!res.ok) throw new Error(`${res.status}`);
  return res.json();
}

async function refreshLive() {
  try {
    const [t, w] = await Promise.all([api("/traffic"), api("/weather")]);
    congestion = t.congestion;
    weatherNow = w;
    setMode(true);
  } catch {
    if (liveMode) setMode(false);
    simulateTraffic();
  }
  drawTraffic();
  renderWeather();
}

function setMode(live) {
  liveMode = live;
  const chip = document.getElementById("modeChip");
  chip.textContent = live ? "live" : "demo mode";
  chip.className = "mode-chip " + (live ? "live" : "demo");
}

/* ------------------------------------------------------------ routing ui */
const $ = id => document.getElementById(id);

function fillSelects() {
  const sorted = [...NODES].sort((a, b) => a.name.localeCompare(b.name));
  for (const sel of [$("fromSel"), $("toSel")]) {
    for (const n of sorted) {
      const o = document.createElement("option");
      o.value = n.id; o.textContent = n.name;
      sel.appendChild(o);
    }
  }
}

const startMarker = L.circleMarker([0, 0], { radius: 8, color: "#2fa45d", fillColor: "#2fa45d", fillOpacity: .9 });
const goalMarker = L.circleMarker([0, 0], { radius: 8, color: "#d64541", fillColor: "#d64541", fillOpacity: .9 });

function setStart(id) {
  startId = id; $("fromSel").value = id;
  const n = nodeById.get(id);
  startMarker.setLatLng([n.lat, n.lon]).addTo(markerLayer);
}
function setGoal(id) {
  goalId = id; $("toSel").value = id;
  const n = nodeById.get(id);
  goalMarker.setLatLng([n.lat, n.lon]).addTo(markerLayer);
}

map.on("click", ev => {
  let best = null, bestD = Infinity;
  for (const n of NODES) {
    const d = haversineKm({ lat: ev.latlng.lat, lon: ev.latlng.lng }, n);
    if (d < bestD) { bestD = d; best = n; }
  }
  if (!startId || (startId && goalId)) { clearRoute(); setStart(best.id); }
  else { setGoal(best.id); findRoute(); }
});

async function findRoute() {
  startId = +$("fromSel").value || startId;
  goalId = +$("toSel").value || goalId;
  if (!startId || !goalId) return;
  if (startId === goalId) return renderRoute(null, "Start and destination are the same.");
  setStart(startId); setGoal(goalId);

  const departIn = +$("departIn").value;
  let route = null, error = null;
  if (liveMode) {
    try {
      route = await api(`/route?from=${startId}&to=${goalId}&departIn=${departIn}`);
    } catch { setMode(false); }
  }
  if (!route) route = localRoute(startId, goalId);
  if (!route) error = "No route found between these points.";
  renderRoute(route, error);
}

function renderRoute(route, error) {
  routeLayer.clearLayers();
  lastRoute = route;
  const card = $("etaCard");
  if (!route) {
    card.hidden = false;
    $("etaValue").textContent = "—";
    $("etaDistance").textContent = error || "";
    $("directions").innerHTML = "";
    return;
  }
  card.hidden = false;
  $("etaValue").textContent = Math.round(route.etaMinutes);
  $("etaDistance").textContent = `${route.totalDistanceKm} km · ${route.from} → ${route.to}`;
  $("etaWeather").textContent = weatherLabel(route.weather);
  $("predictiveTag").hidden = !route.predictive;

  const dirs = $("directions");
  dirs.innerHTML = "";
  for (const s of route.segments) {
    const li = document.createElement("li");
    li.innerHTML = `<span class="road">${s.roadName} → ${nodeById.get(s.to).name}</span>
      <span class="leg ${congestionClass(s.congestion)}">${s.distanceKm} km · ${s.minutes.toFixed(1)}′</span>`;
    dirs.appendChild(li);
  }

  // the signature: route drawn segment-by-segment in its congestion color
  for (const s of route.segments) {
    const a = nodeById.get(s.from), b = nodeById.get(s.to);
    L.polyline([[a.lat, a.lon], [b.lat, b.lon]], {
      color: "#151a21", weight: 9, opacity: .85,
    }).addTo(routeLayer);
    L.polyline([[a.lat, a.lon], [b.lat, b.lon]], {
      color: congestionColor(s.congestion), weight: 5, opacity: 1,
    }).addTo(routeLayer);
  }
  map.fitBounds(route.path.map(p => [p.lat, p.lon]), { paddingTopLeft: [360, 40], paddingBottomRight: [40, 40] });
}

function clearRoute() {
  startId = goalId = null;
  $("fromSel").value = ""; $("toSel").value = "";
  markerLayer.clearLayers(); routeLayer.clearLayers();
  $("etaCard").hidden = true;
}

/* ------------------------------------------------------- weather display */
const WEATHER_ICON = { CLEAR: "☀", CLOUDY: "☁", LIGHT_RAIN: "🌦", HEAVY_RAIN: "🌧", FOG: "🌫" };
function weatherLabel(w) {
  return `${WEATHER_ICON[w.condition] || ""} ${String(w.condition).replaceAll("_", " ").toLowerCase()}`;
}
function renderWeather() {
  $("weatherLine").textContent =
    `${weatherLabel(weatherNow)} · ${weatherNow.temperatureC}°C · speeds ×${weatherNow.speedFactor}`;
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
    try { localStorage.setItem("mapnav-user", JSON.stringify({
      places: this.places, history: this.history, nextId: this.nextId })); } catch { }
  },
};

async function loadUserData() {
  if (liveMode) {
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
  if (liveMode) {
    try { await api(`/users/${USER}/places`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ label, nodeId }) }); return refreshUserData();
    } catch { /* fall through */ }
  }
  const n = nodeById.get(nodeId);
  localStore.places.push({ id: localStore.nextId++, label, nodeId, nodeName: n.name });
  localStore.write(); refreshUserData();
}

async function deletePlace(id) {
  if (liveMode) {
    try { await fetch(`${API}/users/${USER}/places/${id}`, { method: "DELETE" }); return refreshUserData(); }
    catch { /* fall through */ }
  }
  localStore.places = localStore.places.filter(p => p.id !== id);
  localStore.write(); refreshUserData();
}

async function recordHistory(route) {
  const entry = {
    fromNodeId: route.path[0].id,
    toNodeId: route.path.at(-1).id,
    etaMinutes: route.etaMinutes,
  };
  if (liveMode) {
    try { await api(`/users/${USER}/history`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify(entry) }); return refreshUserData();
    } catch { /* fall through */ }
  }
  localStore.history.unshift({ id: localStore.nextId++, ...entry,
    fromName: route.from, toName: route.to, timestampMs: Date.now() });
  localStore.history = localStore.history.slice(0, 25);
  localStore.write(); refreshUserData();
}

async function refreshUserData() {
  const { places, history } = await loadUserData();

  const pl = $("placesList");
  pl.innerHTML = places.length ? "" : '<li class="empty">Nothing saved yet — find a route, then star a stop.</li>';
  for (const p of places) {
    const li = document.createElement("li");
    li.innerHTML = `<span>${p.label} · ${p.nodeName}</span>`;
    const go = document.createElement("button");
    go.textContent = "→"; go.title = `Route to ${p.nodeName}`;
    go.onclick = () => { setGoal(p.nodeId); if (startId) findRoute(); };
    const del = document.createElement("button");
    del.textContent = "×"; del.title = "Remove";
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

/* ------------------------------------------------------------ wiring */
$("routeBtn").onclick = findRoute;
$("clearBtn").onclick = clearRoute;
$("departIn").oninput = () => {
  const v = +$("departIn").value;
  $("departLabel").textContent = v === 0 ? "now" : `in ${v} min`;
};
$("departIn").onchange = () => { if (lastRoute) findRoute(); };
$("saveRouteBtn").onclick = () => {
  if (lastRoute) recordHistory(lastRoute);
  if (goalId) {
    const label = prompt("Label this destination (e.g. Work):");
    if (label) savePlace(goalId, label.trim().slice(0, 30));
  }
};

(async function init() {
  fillSelects();
  simulateTraffic();     // instant paint; replaced by live data if backend is up
  drawTraffic();
  renderWeather();
  await refreshLive();
  await refreshUserData();
  setInterval(refreshLive, 5000);
})();
