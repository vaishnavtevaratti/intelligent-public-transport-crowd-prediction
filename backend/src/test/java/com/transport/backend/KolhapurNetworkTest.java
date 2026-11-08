package com.transport.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transport.backend.entity.RouteStop;
import com.transport.backend.service.geo.RoutePath;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the shipped Kolhapur demo network file (demo/kolhapur-network.json): every stop and geometry point lies in
 * Kolhapur, each route's road geometry passes close to its stops, and the stated distance matches the geometry.
 */
class KolhapurNetworkTest {

    private static final double MIN_LAT = 16.55, MAX_LAT = 16.85, MIN_LNG = 74.10, MAX_LNG = 74.40;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes() throws Exception {
        try (InputStream in = new ClassPathResource("demo/kolhapur-network.json").getInputStream()) {
            return (List<Map<String, Object>>) new ObjectMapper().readValue(in, Map.class).get("routes");
        }
    }

    private static boolean inKolhapur(double lat, double lng) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRouteLiesInKolhapurAndFollowsItsStops() throws Exception {
        List<Map<String, Object>> routes = routes();
        assertThat(routes).hasSize(8);
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> r : routes) {
            assertThat(codes.add((String) r.get("code"))).as("unique route code").isTrue();
            List<Map<String, Object>> stops = (List<Map<String, Object>>) r.get("stops");
            List<List<Number>> geometry = (List<List<Number>>) r.get("geometry");
            assertThat(stops.size()).isGreaterThanOrEqualTo(2);
            assertThat(geometry.size()).isGreaterThan(stops.size());

            List<RouteStop> stopList = stops.stream().map(s -> RouteStop.builder().name((String) s.get("name"))
                    .latitude(((Number) s.get("latitude")).doubleValue()).longitude(((Number) s.get("longitude")).doubleValue()).build()).toList();
            for (RouteStop s : stopList) {
                assertThat(inKolhapur(s.getLatitude(), s.getLongitude())).as(s.getName() + " in Kolhapur").isTrue();
            }
            for (List<Number> p : geometry) {
                assertThat(inKolhapur(p.get(0).doubleValue(), p.get(1).doubleValue())).as("geometry point in Kolhapur").isTrue();
            }
            assertThat(((Number) stops.get(0).get("snapDistanceM")).intValue()).isLessThan(600);

            RoutePath path = new RoutePath(stopList, geometry.stream().map(p -> new double[]{p.get(0).doubleValue(), p.get(1).doubleValue()}).toList());
            for (RouteStop s : stopList) {
                assertThat(path.project(s.getLatitude(), s.getLongitude())[1]).as(r.get("code") + " passes " + s.getName()).isLessThan(30);
            }
            for (int i = 1; i < stopList.size(); i++) {
                assertThat(path.distanceAtStop(i)).as("stops in order on " + r.get("code")).isGreaterThan(path.distanceAtStop(i - 1));
            }
            double km = ((Number) r.get("distanceKm")).doubleValue();
            assertThat(Math.abs(path.lengthMeters() / 1000 - km)).as(r.get("code") + " distance matches geometry").isLessThan(0.3);
        }
    }
}
