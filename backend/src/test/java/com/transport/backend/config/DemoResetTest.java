package com.transport.backend.config;

import com.transport.backend.IntegrationTestBase;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.Route;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The safe demo reset (scripts/reset-demo.ps1): restores D1-D8 and BUS-101..110, leaves everything else alone. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoResetTest extends IntegrationTestBase {

    @Autowired
    private DataInitializer initializer;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private BusRepository busRepository;

    @Test
    @org.springframework.transaction.annotation.Transactional   // like the startup runner; rolled back afterwards
    void resetRestoresTheDemoNetworkAndIsIdempotent() throws Exception {
        long adminRoute = createRoute();
        long adminBus = createBus(adminRoute);
        long usersBefore = userRepository.count();

        initializer.resetDemoNetwork(initializer.loadNetwork());
        Route d1 = demoRoute("D1");
        d1.setDescription("changed by an admin");
        routeRepository.save(d1);
        initializer.resetDemoNetwork(initializer.loadNetwork());   // second run: restores, creates nothing new

        List<Route> demo = routeRepository.findAll().stream().filter(r -> Boolean.TRUE.equals(r.getDemoRoute())).toList();
        assertThat(demo).extracting(Route::getRouteCode).containsExactlyInAnyOrder("D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8");
        assertThat(demo).allMatch(r -> r.getPathGeometry() != null && r.getStops().size() >= 4);
        assertThat(demoRoute("D1").getDescription()).contains("academic demo, not an official KMT route");

        Map<String, Long> numbers = busRepository.findAll().stream().collect(Collectors.groupingBy(Bus::getBusNumber, Collectors.counting()));
        for (int i = 101; i <= 110; i++) {
            assertThat(numbers.get("BUS-" + i)).as("BUS-" + i + " exactly once").isEqualTo(1L);
        }
        Bus b110 = busRepository.findByBusNumber("BUS-110").orElseThrow();
        assertThat(b110.getStatus()).isEqualTo(Bus.STATUS_MAINTENANCE);
        assertThat(b110.getRoute().getRouteCode()).isEqualTo("D2");
        assertThat(b110.getSeatCount()).isLessThanOrEqualTo(b110.getCapacity());

        // admin-created data and users untouched
        assertThat(routeRepository.findById(adminRoute)).get().extracting(Route::getDemoRoute).isNotEqualTo(Boolean.TRUE);
        assertThat(busRepository.findById(adminBus).orElseThrow().getRoute().getId()).isEqualTo(adminRoute);
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    private Route demoRoute(String code) {
        return routeRepository.findAll().stream().filter(r -> code.equals(r.getRouteCode())).findFirst().orElseThrow();
    }
}
