"""Builds backend/src/main/resources/demo/kolhapur-network.json — the Kolhapur ACADEMIC DEMO network.

* Stop landmarks: coordinates geocoded with OpenStreetMap Nominatim (OSM object id recorded per stop).
* Each stop is snapped to the nearest drivable road with OSRM /nearest (the snap distance is recorded).
* Each route's road geometry, distance and driving time come from OSRM /route over the OpenStreetMap road network.
* The routes are FICTIONAL demo routes for an academic project. They are NOT official KMT (Kolhapur Municipal
  Transport) routes, route numbers or timetables.

Data (c) OpenStreetMap contributors, ODbL 1.0. Routing: OSRM demo server (light, one-off use, 1 request/s).
    python scripts/build_kolhapur_network.py            (needs internet; run again only to rebuild the file)
"""
import json
import time
import urllib.request
from pathlib import Path

OSRM = "https://router.project-osrm.org"
UA = "OptiTransit-academic-demo/1.0 (B.Tech project)"
OUT = Path(__file__).resolve().parents[1] / "backend/src/main/resources/demo/kolhapur-network.json"

# name: (lat, lon, OSM reference from Nominatim)
LANDMARKS = {
    "Kolhapur Central Bus Stand (CBS)": (16.70334, 74.24327, "way/433843059"),
    "Kolhapur Railway Station": (16.70269, 74.23777, "Chhatrapati Shahu Maharaj Terminus"),
    "Mahalaxmi Temple": (16.69486, 74.22289, "Mahadwar Chowk"),
    "Rankala Lake (Shalini Palace)": (16.69234, 74.20739, "Shalini Palace"),
    "Tarabai Park": (16.71203, 74.24505, "landuse Tarabai Park"),
    "Shivaji University": (16.67797, 74.25156, "amenity/university"),
    "New Palace": (16.71761, 74.23162, "New Palace"),
    "Bindu Chowk": (16.69588, 74.22719, "highway Bindu Chowk"),
    "Shahupuri": (16.70024, 74.23651, "landuse Shahupuri"),
    "Kolhapur Airport (Airport Road)": (16.66566, 74.28947, "aeroway KLH"),
    "Shivaji Chowk": (16.69739, 74.22408, "highway Shivaji Chowk"),
    "Kalamba": (16.67108, 74.22461, "landuse Kalamba"),
    "Gokul Shirgaon MIDC": (16.64355, 74.29089, "Gokul-Shirgaon Industrial Area"),
    "Kasaba Bawada": (16.73364, 74.24360, "place Kasaba Bawada"),
    "Phulewadi": (16.68914, 74.18995, "landuse Phulewadi"),
    "Shivaji Peth": (16.69083, 74.22018, "landuse Shivaji Peth"),
    "Uchgaon": (16.69699, 74.27496, "landuse Uchgaon"),
    "Ujalaiwadi": (16.66463, 74.27069, "place Ujalaiwadi"),
    "Sane Guruji Vasahat": (16.67739, 74.20302, "landuse Sane Guruji Vasahat"),
    "CPR Hospital": (16.70180, 74.22675, "amenity/hospital CPR Hospital"),
    "Rajarampuri": (16.69458, 74.24535, "place Rajarampuri"),
    "Udyam Nagar": (16.69455, 74.23411, "landuse Udyam Nagar"),
    "Town Hall": (16.70147, 74.22399, "leisure Town Hall"),
    "Dasara Chowk": (16.70243, 74.22808, "highway Dasara Chowk"),
    "Shahu Market Yard": (16.70563, 74.26796, "place Shahu Market Yard"),
    "Sambhaji Nagar": (16.68246, 74.21844, "landuse Sambhaji Nagar"),
    "Rajarshi Shahu Stadium": (16.69322, 74.23053, "leisure/stadium"),
    "Rajaram College": (16.68801, 74.25489, "amenity/college"),
    "Laxmipuri": (16.69911, 74.22958, "landuse Laxmipuri"),
    "Line Bazar": (16.72921, 74.24568, "landuse Line Bazar"),
    "Nagala Park": (16.71071, 74.23539, "landuse Nagala Park"),
}

# Fictional academic demo routes (code, description, ordered stops)
ROUTES = [
    ("D1", "Demo route: bus stand to Shivaji University", ["Kolhapur Central Bus Stand (CBS)", "Rajarampuri", "Rajaram College", "Shivaji University"]),
    ("D2", "Demo route: bus stand to Rankala Lake via Mahalaxmi Temple", ["Kolhapur Central Bus Stand (CBS)", "Kolhapur Railway Station", "Dasara Chowk", "Bindu Chowk", "Mahalaxmi Temple", "Shivaji Peth", "Rankala Lake (Shalini Palace)"]),
    ("D3", "Demo route: railway station to the airport", ["Kolhapur Railway Station", "Kolhapur Central Bus Stand (CBS)", "Rajarampuri", "Uchgaon", "Ujalaiwadi", "Kolhapur Airport (Airport Road)"]),
    ("D4", "Demo route: Kasaba Bawada to Mahalaxmi Temple via New Palace", ["Kasaba Bawada", "Line Bazar", "New Palace", "Nagala Park", "Town Hall", "Mahalaxmi Temple"]),
    ("D5", "Demo route: Tarabai Park to Kalamba", ["Tarabai Park", "Kolhapur Central Bus Stand (CBS)", "Shahupuri", "Udyam Nagar", "Sambhaji Nagar", "Kalamba"]),
    ("D6", "Demo route: Phulewadi to the bus stand", ["Phulewadi", "Rankala Lake (Shalini Palace)", "Shivaji Chowk", "CPR Hospital", "Kolhapur Central Bus Stand (CBS)"]),
    ("D7", "Demo route: market yard to Gokul Shirgaon MIDC (industrial shift)", ["Shahu Market Yard", "Uchgaon", "Ujalaiwadi", "Gokul Shirgaon MIDC"]),
    ("D8", "Demo route: Sane Guruji Vasahat to the railway station", ["Sane Guruji Vasahat", "Sambhaji Nagar", "Rajarshi Shahu Stadium", "Laxmipuri", "Kolhapur Railway Station"]),
]


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=30) as r:
        data = json.load(r)
    time.sleep(1.1)
    if data.get("code") != "Ok":
        raise RuntimeError(f"OSRM answered {data.get('code')}: {data.get('message')} for {url}")
    return data


def main():
    snapped = {}
    for name, (lat, lon, ref) in LANDMARKS.items():
        w = get(f"{OSRM}/nearest/v1/driving/{lon},{lat}?number=1")["waypoints"][0]
        slon, slat = w["location"]
        snapped[name] = {"name": name, "latitude": round(slat, 6), "longitude": round(slon, 6),
                         "landmarkLatitude": lat, "landmarkLongitude": lon, "osmReference": ref,
                         "snapDistanceM": round(w["distance"]), "road": w.get("name") or ""}
        print(f"{name:34} snapped {w['distance']:6.0f} m to '{w.get('name') or '(unnamed road)'}'")

    routes = []
    for code, description, stops in ROUTES:
        coords = ";".join(f"{snapped[s]['longitude']},{snapped[s]['latitude']}" for s in stops)
        r = get(f"{OSRM}/route/v1/driving/{coords}?overview=full&geometries=geojson&steps=false")["routes"][0]
        geometry = [[round(lat, 6), round(lon, 6)] for lon, lat in r["geometry"]["coordinates"]]
        routes.append({"code": code, "source": stops[0], "destination": stops[-1], "description": description,
                       "distanceKm": round(r["distance"] / 1000, 1), "drivingMinutes": round(r["duration"] / 60, 1),
                       "stops": [snapped[s] for s in stops], "geometry": geometry})
        print(f"{code} {stops[0]} -> {stops[-1]}: {r['distance'] / 1000:.1f} km, {len(geometry)} geometry points")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "dataset": "Kolhapur academic demo network",
        "notice": "Fictional demo routes for an academic project; not official KMT routes, numbers or timetables.",
        "attribution": "Map data (c) OpenStreetMap contributors (ODbL 1.0); geocoding Nominatim; road geometry OSRM",
        "builtOn": time.strftime("%Y-%m-%d"),
        "routes": routes,
    }, indent=1), encoding="utf8")
    print(f"written {OUT}")


if __name__ == "__main__":
    main()
