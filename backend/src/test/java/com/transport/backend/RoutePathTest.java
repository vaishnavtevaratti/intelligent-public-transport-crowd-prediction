package com.transport.backend;

import com.transport.backend.entity.RouteStop;
import com.transport.backend.service.geo.RoutePath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RoutePathTest {

    private static RouteStop stop(String name, double lat, double lng) {
        return RouteStop.builder().name(name).latitude(lat).longitude(lng).build();
    }

    // Two segments along the equator, each 0.01 degrees of longitude (~1.11 km).
    private final RoutePath path = new RoutePath(List.of(stop("A", 0, 0), stop("B", 0, 0.01), stop("C", 0, 0.02)));

    @Test
    void lengthIsSumOfSegments() {
        assertThat(path.lengthMeters()).isCloseTo(2 * 1111.95, within(1.0));
        assertThat(path.distanceAtStop(1)).isCloseTo(1111.95, within(1.0));
    }

    @Test
    void pointAtInterpolatesAndClamps() {
        double[] mid = path.pointAt(path.lengthMeters() / 4);
        assertThat(mid[1]).isCloseTo(0.005, within(1e-6));
        assertThat(path.pointAt(-100)[1]).isEqualTo(0.0);
        assertThat(path.pointAt(1e9)[1]).isEqualTo(0.02);
    }

    @Test
    void projectFindsNearestPositionAndOffset() {
        double[] p = path.project(0.001, 0.015); // ~111 m north of the second segment
        assertThat(p[0]).isCloseTo(path.lengthMeters() * 0.75, within(2.0));
        assertThat(p[1]).isCloseTo(111.2, within(1.0));
    }

    @Test
    void nextStopDependsOnDirection() {
        double between = path.distanceAtStop(1) + 10;
        assertThat(path.nextStopIndex(between, 1)).isEqualTo(2);
        assertThat(path.nextStopIndex(between, -1)).isEqualTo(1);
    }

    @Test
    void roadGeometryIsFollowedAndStopsArePlacedAlongIt() {
        // an L-shaped road: east 0.01 deg, then north 0.01 deg; the middle stop lies at the corner
        List<double[]> road = List.of(new double[]{0, 0}, new double[]{0, 0.005}, new double[]{0, 0.01},
                new double[]{0.005, 0.01}, new double[]{0.01, 0.01});
        RoutePath roads = new RoutePath(List.of(stop("A", 0, 0), stop("Corner", 0, 0.01), stop("B", 0.01, 0.01)), road);
        assertThat(roads.followsRoads()).isTrue();
        assertThat(roads.lengthMeters()).isCloseTo(2 * 1111.95, within(2.0));          // along the road, not the 1572 m diagonal
        assertThat(roads.distanceAtStop(1)).isCloseTo(1111.95, within(2.0));
        double[] onNorthLeg = roads.pointAt(roads.lengthMeters() * 0.75);
        assertThat(onNorthLeg[1]).isCloseTo(0.01, within(1e-9));                       // on the road, not cutting the corner
        assertThat(onNorthLeg[0]).isCloseTo(0.005, within(1e-6));
        assertThat(roads.nextStopIndex(10, 1)).isEqualTo(1);
    }

    @Test
    void unreadableGeometryFallsBackToStraightLines() {
        assertThat(RoutePath.parseGeometry("not json")).isNull();
        assertThat(RoutePath.parseGeometry("[[95, 10], [0, 0]]")).isNull();   // latitude out of range
        assertThat(RoutePath.parseGeometry("[[16.7, 74.2]]")).isNull();       // a single point is no path
        assertThat(RoutePath.parseGeometry("[[16.7, 74.2], [16.71, 74.21]]")).hasSize(2);
    }

    @Test
    void bearingEastIsNinetyDegrees() {
        assertThat(RoutePath.bearing(0, 0, 0, 1)).isCloseTo(90, within(0.01));
    }
}
