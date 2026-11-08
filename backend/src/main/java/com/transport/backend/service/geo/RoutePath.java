package com.transport.backend.service.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transport.backend.entity.Route;
import com.transport.backend.entity.RouteStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The path a route follows, with its stops placed along it.
 *
 * With road geometry (Route.pathGeometry, e.g. from OSRM over the OpenStreetMap road network) the path follows the
 * roads and each stop is placed where it lies on that geometry. Without geometry the path is the straight line
 * between consecutive stops. Positions are "distance along the path" in metres, which makes moving a bus forward,
 * and estimating arrival times, simple arithmetic.
 */
public final class RoutePath {

    private static final Logger log = LoggerFactory.getLogger(RoutePath.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double EARTH_RADIUS_M = 6_371_000;
    /**
     * Paths are immutable, so one per route is shared. The key includes the geometry and the stops, so an edited
     * route never gets a stale path. Parsing a road geometry of a few hundred points on every request (per bus in the
     * bus list, per route in the travel planner) halved the throughput under load before this cache.
     */
    private static final Map<String, RoutePath> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 256;

    private final List<RouteStop> stops;
    /** Polyline the path follows: [lat, lng] per point. */
    private final double[][] points;
    /** cumulative[i] = distance from the first point to point i, in metres. */
    private final double[] cumulative;
    /** stopDistance[i] = distance along the path of stop i, in metres (non-decreasing). */
    private final double[] stopDistance;
    private final boolean roadGeometry;
    private final List<double[]> geometryView;

    /** Straight lines between the stops. */
    public RoutePath(List<RouteStop> stops) {
        this(stops, null);
    }

    /** Follows the given geometry (at least two points) when present, otherwise straight lines between the stops. */
    public RoutePath(List<RouteStop> stops, List<double[]> geometry) {
        if (stops == null || stops.size() < 2) {
            throw new IllegalArgumentException("A route path needs at least two stops");
        }
        this.stops = List.copyOf(stops);
        this.roadGeometry = geometry != null && geometry.size() >= 2;
        if (roadGeometry) {
            this.points = geometry.toArray(new double[0][]);
        } else {
            this.points = stops.stream().map(s -> new double[]{s.getLatitude(), s.getLongitude()}).toArray(double[][]::new);
        }
        this.geometryView = List.of(points);
        this.cumulative = new double[points.length];
        for (int i = 1; i < points.length; i++) {
            cumulative[i] = cumulative[i - 1] + distanceMeters(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1]);
        }
        this.stopDistance = new double[stops.size()];
        if (roadGeometry) {
            // place stops in order: each one is searched from where the previous one was found, so a road
            // that passes the same place twice does not move a stop backwards
            int fromSegment = 0;
            for (int i = 0; i < stops.size(); i++) {
                double[] hit = projectFrom(stops.get(i).getLatitude(), stops.get(i).getLongitude(), fromSegment);
                stopDistance[i] = i == 0 ? hit[0] : Math.max(stopDistance[i - 1], hit[0]);
                fromSegment = (int) hit[2];
            }
            stopDistance[0] = 0;
            stopDistance[stops.size() - 1] = lengthMeters();
        } else {
            for (int i = 0; i < stops.size(); i++) {
                stopDistance[i] = cumulative[i];
            }
        }
    }

    /** Path of a route: its road geometry when stored and readable, otherwise straight lines between stops. */
    public static RoutePath of(Route route) {
        StringBuilder key = new StringBuilder().append(route.getId()).append('|');
        String geometry = route.getPathGeometry();
        key.append(geometry == null ? 0 : geometry.length()).append(':').append(geometry == null ? 0 : geometry.hashCode());
        for (RouteStop s : route.getStops()) {
            key.append('|').append(s.getName()).append(',').append(s.getLatitude()).append(',').append(s.getLongitude());
        }
        RoutePath cached = CACHE.get(key.toString());
        if (cached != null) {
            return cached;
        }
        RoutePath path = new RoutePath(route.getStops(), parseGeometry(geometry));
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();   // simple bound; entries are cheap to rebuild
        }
        CACHE.put(key.toString(), path);
        return path;
    }

    public static boolean isUsable(List<RouteStop> stops) {
        return stops != null && stops.size() >= 2;
    }

    /** Parses JSON [[lat, lng], ...]; returns null (and logs) when missing or invalid. */
    public static List<double[]> parseGeometry(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            double[][] raw = JSON.readValue(json, double[][].class);
            List<double[]> result = new ArrayList<>(raw.length);
            for (double[] p : raw) {
                if (p.length < 2 || Math.abs(p[0]) > 90 || Math.abs(p[1]) > 180) {
                    throw new IllegalArgumentException("invalid coordinate in route geometry");
                }
                result.add(new double[]{p[0], p[1]});
            }
            return result.size() >= 2 ? result : null;
        } catch (Exception e) {
            log.warn("Ignoring unreadable route geometry ({}); using straight lines between stops", e.getMessage());
            return null;
        }
    }

    /** The road geometry points [lat, lng] (shared, do not modify), or null for a straight-line path. */
    public List<double[]> roadGeometry() {
        return roadGeometry ? geometryView : null;
    }

    /** Road geometry of a route for the API, from the shared cache (null when the route has none or < 2 stops). */
    public static List<double[]> geometryOf(Route route) {
        if (route.getPathGeometry() == null || !isUsable(route.getStops())) {
            return null;
        }
        return of(route).roadGeometry();
    }

    /** True when the path follows stored road geometry rather than straight lines between stops. */
    public boolean followsRoads() {
        return roadGeometry;
    }

    public double lengthMeters() {
        return cumulative[cumulative.length - 1];
    }

    public int stopCount() {
        return stops.size();
    }

    public RouteStop stop(int index) {
        return stops.get(index);
    }

    public double distanceAtStop(int index) {
        return stopDistance[index];
    }

    /** Latitude/longitude at a given distance along the path (clamped to the ends). */
    public double[] pointAt(double distance) {
        double d = Math.max(0, Math.min(lengthMeters(), distance));
        int i = pointSegmentAt(d);
        double segmentLength = cumulative[i + 1] - cumulative[i];
        double t = segmentLength == 0 ? 0 : (d - cumulative[i]) / segmentLength;
        return new double[]{
                points[i][0] + (points[i + 1][0] - points[i][0]) * t,
                points[i][1] + (points[i + 1][1] - points[i][1]) * t
        };
    }

    /** Index of the stop that starts the stop-to-stop section containing the given distance. */
    public int segmentAt(double distance) {
        for (int i = 0; i < stopDistance.length - 2; i++) {
            if (distance < stopDistance[i + 1]) {
                return i;
            }
        }
        return stopDistance.length - 2;
    }

    /** The next stop a bus will reach when travelling in the given direction (+1 forward, -1 backward). */
    public int nextStopIndex(double distance, int direction) {
        if (direction > 0) {
            for (int i = 0; i < stopDistance.length; i++) {
                if (stopDistance[i] > distance + 1) {
                    return i;
                }
            }
            return stopDistance.length - 1;
        }
        for (int i = stopDistance.length - 1; i >= 0; i--) {
            if (stopDistance[i] < distance - 1) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Distance along the path closest to the given coordinates, and how far the point is from the path.
     * Uses a flat-earth projection per segment, which is accurate enough for city-scale routes.
     */
    public double[] project(double lat, double lng) {
        double[] hit = projectFrom(lat, lng, 0);
        return new double[]{hit[0], hit[1]};
    }

    /** {distance along path, offset in metres, segment index}, searching segments from fromSegment onwards. */
    private double[] projectFrom(double lat, double lng, int fromSegment) {
        double best = 0;
        double bestOffset = Double.MAX_VALUE;
        int bestSegment = fromSegment;
        for (int i = fromSegment; i < points.length - 1; i++) {
            double[] a = points[i];
            double[] b = points[i + 1];
            double cos = Math.cos(Math.toRadians(a[0]));
            double ax = a[1] * cos, ay = a[0];
            double bx = b[1] * cos, by = b[0];
            double px = lng * cos, py = lat;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            double[] onPath = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            double offset = distanceMeters(lat, lng, onPath[0], onPath[1]);
            if (offset < bestOffset - 0.5) {   // first (earliest) segment wins ties, so stops keep their order
                bestOffset = offset;
                best = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * t;
                bestSegment = i;
            }
        }
        return new double[]{best, bestOffset, bestSegment};
    }

    private int pointSegmentAt(double distance) {
        int lo = 0;
        int hi = cumulative.length - 2;
        while (lo < hi) {   // last segment whose start is <= distance
            int mid = (lo + hi + 1) >>> 1;
            if (cumulative[mid] <= distance) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Great-circle distance (haversine) in metres. */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }

    /** Compass bearing from point 1 to point 2 in degrees (0 = north). */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dLng = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLng) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }
}
