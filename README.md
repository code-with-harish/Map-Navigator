# Map Navigator

A real-world map navigation system with live traffic simulation, IoT sensor fusion, and predictive routing, built on a road network of central Bengaluru.

Pick any two points on the map and get the fastest route for **right now** — or slide the departure time forward and get the route for **45 minutes from now**, computed against machine-learned traffic predictions instead of the current snapshot.

## What's inside

```
backend/    Java 21 · Spring Boot 3 REST API + routing/simulation core
frontend/   Leaflet map UI (plain HTML/CSS/JS, no build step)
database/   PostgreSQL schema + seed data (optional network source)
```

### Routing engine
`AStarNavigator` runs A* over travel *time*, not distance. Edge cost is `distance / (speedLimit × weatherFactor / congestion)`, with a haversine-over-max-speed heuristic that is admissible, so routes are provably optimal for the assumed traffic. Congestion is supplied per-query as a function, which is what makes both live and predictive routing possible with one engine.

### Live traffic
`TrafficDataService` recomputes congestion for every road segment every 5 seconds by fusing a time-of-day baseline (rush-hour peaks at 8–10 and 17–20) with readings from `IoTIntegration` — a simulated network of roadside sensors that report measured average speeds and occasionally produce incidents (a stalled vehicle spiking congestion on one road for a while). Where a sensor exists, its measurement overrides the model, just as probe data would in a production system.

### Predictive routing (the ML part)
`MLModel` learns online from every traffic observation: a 24-bucket hourly congestion profile per road segment (slow exponential moving average — the seasonal pattern) plus a fast-adapting short-term deviation (how different traffic is right now from the usual, e.g. because of an incident). A forecast for *t* minutes ahead blends the two, decaying the short-term deviation with a 30-minute half-life. So the system correctly predicts both "the incident on MG Road will have cleared in an hour" and "but you'll be arriving at Silk Board during evening rush."

### Weather
`WeatherService` runs a sticky Markov chain over conditions (clear → cloudy → rain…) with a network-wide speed factor — heavy rain slows every road by 30%, which changes ETAs and sometimes the chosen route. Swapping in a real provider (Open-Meteo, OpenWeatherMap) only requires replacing its `tick()`.

### Frontend
Full-bleed OpenStreetMap with a control panel: click two points (or pick from the lists), see the route drawn segment-by-segment **in its congestion color**, a big ETA readout, turn-by-turn legs, live weather, saved places, and route history. If the backend isn't running it drops into **demo mode**: the identical network and an equivalent A* run in the browser with simulated traffic, so `index.html` is fully functional opened standalone.

## Running it

### Backend (needs JDK 21 + Maven)
```bash
cd backend
mvn spring-boot:run          # serves http://localhost:8080
```

### Frontend
Open `frontend/index.html` in a browser, or serve the folder:
```bash
cd frontend && python3 -m http.server 3000
```
The header chip shows `live` when the backend is reachable, `demo mode` otherwise.

### PostgreSQL (optional)
The backend ships with the network embedded — zero setup. To load it from a database instead:
```bash
createdb mapnavigator
psql -d mapnavigator -f database/PostgresDBSetup.sql
```
then set `db.enabled=true` in `backend/src/main/resources/application.properties`.

## API

| Endpoint | Description |
|---|---|
| `GET /api/network` | Nodes and roads for drawing the map |
| `GET /api/route?from=5&to=9` | Fastest route on live traffic |
| `GET /api/route?from=5&to=9&departIn=45` | Predictive route for departure in 45 min |
| `GET /api/route?fromLat=..&fromLon=..&toLat=..&toLon=..` | Route between arbitrary coordinates (snapped to nearest nodes) |
| `GET /api/traffic` | Live congestion per road segment |
| `GET /api/weather` | Current condition, temperature, speed factor |
| `GET /api/sensors` | Latest IoT sensor readings |
| `GET /api/predict?from=1&to=2` | Learned 24-hour congestion profile for a segment |
| `GET/POST /api/users/{user}/places` | Saved places |
| `DELETE /api/users/{user}/places/{id}` | Remove a saved place |
| `GET/POST /api/users/{user}/history` | Recent routes |

Example:
```bash
curl 'http://localhost:8080/api/route?from=5&to=9&departIn=45'
```

## Tests

The routing/simulation core is deliberately framework-free, so it compiles and tests with nothing but a JDK:

```bash
cd backend
javac -d /tmp/out src/main/java/com/mapnavigator/{Graph,RoadNetwork,AStarNavigator,MLModel,IoTIntegration,WeatherService,TrafficDataService}.java src/test/java/com/mapnavigator/CoreTest.java
java -cp /tmp/out com.mapnavigator.CoreTest
```

15 checks cover A* optimality and rerouting around jams, weather scaling, rush-hour vs. night congestion, ML prediction behavior (incident decay + learned rush-hour profile), and coordinate snapping.

## Road network

24 major intersections and 37 roads of central Bengaluru (Majestic, MG Road, Koramangala, Silk Board, Hebbal, …) with real coordinates and realistic distances and speed limits. The dataset is defined once in `RoadNetwork.java` and mirrored exactly in `database/PostgresDBSetup.sql` and `frontend/map.js`.

## Extending toward production

- **3D maps**: the frontend uses Leaflet (2D). For a true 3D view, swap the map layer for MapLibre GL / Mapbox GL with `pitch`/`bearing` and building extrusion — the panel and API calls are map-library-agnostic.
- **Real traffic**: replace `IoTIntegration.poll()` with a feed from TomTom/HERE/Google traffic APIs; the fusion and prediction layers are unchanged.
- **Bigger networks**: import OpenStreetMap extracts into the `nodes`/`edges` tables; A* scales fine, and the heuristic already generalizes.

## License

See [LICENSE](LICENSE).
