package com.transport.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transport.backend.entity.*;
import com.transport.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Safe, repeatable demo data setup for the KOLHAPUR ACADEMIC DEMO network (demo/kolhapur-network.json).
 * The routes are fictional demo routes between verified Kolhapur landmarks (OpenStreetMap), with road geometry
 * from OSRM; they are not official KMT routes, numbers or timetables.
 *
 * It never deletes users, sensors, predictions, camera counts or admin-created routes:
 *  - demo users are created only when the users table is empty;
 *  - demo routes and buses are created only when the routes table is empty;
 *  - an existing database that still holds the earlier New York demo network is converted ONCE, in place:
 *    the eight legacy demo routes (matched by their exact source/destination) become the Kolhapur routes D1-D8,
 *    keeping their ids, so buses, sensors and history stay linked; their demo buses are moved onto the new routes,
 *    and only those buses' simulated GPS points outside Kolhapur are removed;
 *  - Kolhapur demo routes whose road geometry is missing get it back;
 *  - with app.seed.reset-demo-network=true (scripts/reset-demo.ps1) the demo network D1-D8 and the demo fleet
 *    BUS-101..BUS-110 are put back to the shipped definition (missing ones are re-created). Users, sensors,
 *    predictions, camera counts and admin-created routes and buses are not touched.
 * Running it again changes nothing. Disable completely with SEED_DEMO_DATA=false.
 */
@Component
@Order(10)
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final String NETWORK_FILE = "demo/kolhapur-network.json";
    private static final String GEOMETRY_SOURCE = "OSRM / OpenStreetMap road network";

    /** Kolhapur city area used to recognise positions that do not belong to the demo network. */
    static final double MIN_LAT = 16.55, MAX_LAT = 16.85, MIN_LNG = 74.10, MAX_LNG = 74.40;

    /** The earlier New York demo routes, in the order of the Kolhapur routes that replace them (D1..D8). */
    private static final List<String[]> LEGACY_ROUTES = List.of(
            new String[]{"Downtown Terminal", "Tech Park"}, new String[]{"Central Station", "Airport Hub"},
            new String[]{"University Campus", "Metro Center"}, new String[]{"North Heights", "City Square"},
            new String[]{"Westside Mall", "Eastside Industrial"}, new String[]{"Harbor Front", "Financial District"},
            new String[]{"South Gateway", "General Hospital"}, new String[]{"Suburban Depot", "Central Station"});

    /** Demo fleet for a fresh database: bus number, route index, capacity, seats, status. */
    private static final Object[][] DEMO_FLEET = {
            {"BUS-101", 0, 50, 40, Bus.STATUS_ACTIVE}, {"BUS-102", 1, 50, 40, Bus.STATUS_ACTIVE},
            {"BUS-103", 2, 60, 44, Bus.STATUS_ACTIVE}, {"BUS-104", 3, 40, 32, Bus.STATUS_ACTIVE},
            {"BUS-105", 4, 50, 40, Bus.STATUS_ACTIVE}, {"BUS-106", 5, 40, 32, Bus.STATUS_ACTIVE},
            {"BUS-107", 6, 60, 44, Bus.STATUS_ACTIVE}, {"BUS-108", 7, 40, 32, Bus.STATUS_ACTIVE},
            {"BUS-109", 0, 50, 40, Bus.STATUS_ACTIVE}, {"BUS-110", 1, 50, 40, Bus.STATUS_MAINTENANCE}};

    /** Demo buses created for the webcam demonstration (no route); parked at the bus stand if outside Kolhapur. */
    private static final List<String> PARKED_DEMO_BUSES = List.of("DEMO-WEBCAM", "DEMO-WEBDOOR");

    record NetworkStop(String name, double latitude, double longitude) {
    }

    record NetworkRoute(String code, String source, String destination, String description, double distanceKm,
                        List<NetworkStop> stops, List<double[]> geometry) {
    }

    private final UserRepository userRepository;
    private final RouteRepository routeRepository;
    private final BusRepository busRepository;
    private final LocationRepository locationRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final boolean seedDemoData;
    private final boolean resetDemoNetwork;

    @Autowired
    public DataInitializer(UserRepository userRepository,
                           RouteRepository routeRepository,
                           BusRepository busRepository,
                           LocationRepository locationRepository,
                           PasswordEncoder passwordEncoder,
                           ObjectMapper objectMapper,
                           @Value("${app.seed.demo-data:true}") boolean seedDemoData,
                           @Value("${app.seed.reset-demo-network:false}") boolean resetDemoNetwork) {
        this.userRepository = userRepository;
        this.routeRepository = routeRepository;
        this.busRepository = busRepository;
        this.locationRepository = locationRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.seedDemoData = seedDemoData;
        this.resetDemoNetwork = resetDemoNetwork;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedDemoData) {
            log.info("Demo data seeding disabled");
            return;
        }
        if (userRepository.count() == 0) {
            seedUsers();
        }
        List<NetworkRoute> network = loadNetwork();
        if (routeRepository.count() == 0) {
            seedRoutesAndBuses(network);
        } else {
            convertLegacyNetwork(network);
            restoreMissingGeometry(network);
        }
        if (resetDemoNetwork) {
            resetDemoNetwork(network);
        }
    }

    /** Puts the demo routes D1-D8 and the demo fleet back to the shipped definition; re-creates missing ones. */
    void resetDemoNetwork(List<NetworkRoute> network) {
        List<Route> demoRoutes = new ArrayList<>();
        int created = 0;
        for (NetworkRoute n : network) {
            Route route = routeRepository.findAll().stream()
                    .filter(r -> Boolean.TRUE.equals(r.getDemoRoute()) && n.code().equals(r.getRouteCode()))
                    .findFirst().orElse(null);
            if (route == null) {
                route = Route.builder().build();
                created++;
            }
            applyNetworkRoute(route, n);
            demoRoutes.add(routeRepository.save(route));
        }
        int busesCreated = 0;
        for (Object[] f : DEMO_FLEET) {
            Bus bus = busRepository.findByBusNumber((String) f[0]).orElse(null);
            if (bus == null) {
                bus = Bus.builder().busNumber((String) f[0]).build();
                busesCreated++;
            }
            Route assigned = demoRoutes.get((Integer) f[1]);
            RouteStop start = assigned.getStops().get(0);
            bus.setCapacity((Integer) f[2]);
            bus.setSeatCount((Integer) f[3]);
            bus.setStatus((String) f[4]);
            bus.setRoute(assigned);
            bus.setCurrentLatitude(start.getLatitude());
            bus.setCurrentLongitude(start.getLongitude());
            busRepository.save(bus);
        }
        log.info("Demo network reset: {} demo routes ({} re-created) and {} demo buses ({} re-created) restored to the "
                + "shipped Kolhapur definition; users, sensors, history and admin-created data untouched",
                demoRoutes.size(), created, DEMO_FLEET.length, busesCreated);
    }

    /** Reads the Kolhapur demo network shipped with the backend. */
    List<NetworkRoute> loadNetwork() {
        try (InputStream in = new ClassPathResource(NETWORK_FILE).getInputStream()) {
            Map<?, ?> root = objectMapper.readValue(in, Map.class);
            List<NetworkRoute> routes = new ArrayList<>();
            for (Object o : (List<?>) root.get("routes")) {
                Map<?, ?> r = (Map<?, ?>) o;
                List<NetworkStop> stops = new ArrayList<>();
                for (Object s : (List<?>) r.get("stops")) {
                    Map<?, ?> stop = (Map<?, ?>) s;
                    stops.add(new NetworkStop((String) stop.get("name"), ((Number) stop.get("latitude")).doubleValue(),
                            ((Number) stop.get("longitude")).doubleValue()));
                }
                List<double[]> geometry = new ArrayList<>();
                for (Object p : (List<?>) r.get("geometry")) {
                    List<?> point = (List<?>) p;
                    geometry.add(new double[]{((Number) point.get(0)).doubleValue(), ((Number) point.get(1)).doubleValue()});
                }
                routes.add(new NetworkRoute((String) r.get("code"), (String) r.get("source"), (String) r.get("destination"),
                        (String) r.get("description"), ((Number) r.get("distanceKm")).doubleValue(), stops, geometry));
            }
            return routes;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read " + NETWORK_FILE + ": " + e.getMessage(), e);
        }
    }

    private void seedUsers() {
        // LOCAL DEVELOPMENT ONLY demo accounts - change these passwords before any real deployment.
        User admin = User.builder()
                .name("System Admin")
                .email("admin@transport.com")
                .password(passwordEncoder.encode("admin123"))
                .phone("+910000000001")
                .role(Role.ADMIN)
                .build();

        User user = User.builder()
                .name("Demo Passenger")
                .email("user@transport.com")
                .password(passwordEncoder.encode("user123"))
                .phone("+910000000002")
                .role(Role.USER)
                .build();

        userRepository.saveAll(List.of(admin, user));
        log.info("Seeded demo users admin@transport.com and user@transport.com");
    }

    private void seedRoutesAndBuses(List<NetworkRoute> network) {
        List<Route> routes = new ArrayList<>();
        for (NetworkRoute demo : network) {
            Route route = Route.builder().build();
            applyNetworkRoute(route, demo);
            routes.add(route);
        }
        List<Route> savedRoutes = routeRepository.saveAll(routes);

        List<Bus> buses = new ArrayList<>();
        for (Object[] f : DEMO_FLEET) {
            Route assigned = savedRoutes.get((Integer) f[1]);
            RouteStop start = assigned.getStops().get(0);
            buses.add(Bus.builder()
                    .busNumber((String) f[0]).capacity((Integer) f[2]).seatCount((Integer) f[3]).status((String) f[4])
                    .currentLatitude(start.getLatitude()).currentLongitude(start.getLongitude())
                    .route(assigned)
                    .build());
        }
        busRepository.saveAll(buses);
        log.info("Seeded the Kolhapur demo network: {} routes, {} buses", savedRoutes.size(), buses.size());
    }

    /** One-time, in-place conversion of the earlier New York demo network (keeps ids and all linked history). */
    private void convertLegacyNetwork(List<NetworkRoute> network) {
        List<Long> movedBuses = new ArrayList<>();
        int converted = 0;
        for (int i = 0; i < LEGACY_ROUTES.size() && i < network.size(); i++) {
            Optional<Route> legacy = routeRepository.findBySourceAndDestination(LEGACY_ROUTES.get(i)[0], LEGACY_ROUTES.get(i)[1]);
            if (legacy.isEmpty()) {
                continue;
            }
            Route route = legacy.get();
            applyNetworkRoute(route, network.get(i));
            routeRepository.save(route);
            converted++;
            List<Bus> onRoute = new ArrayList<>(busRepository.findByRouteId(route.getId()));
            onRoute.sort(java.util.Comparator.comparing(Bus::getId));
            for (int b = 0; b < onRoute.size(); b++) {
                Bus bus = onRoute.get(b);
                RouteStop start = route.getStops().get(b % 2 == 0 ? 0 : route.getStops().size() - 1);
                bus.setCurrentLatitude(start.getLatitude());
                bus.setCurrentLongitude(start.getLongitude());
                if (bus.getSeatCount() == null && bus.getCapacity() != null && bus.getCapacity() >= 40) {
                    bus.setSeatCount((int) Math.round(bus.getCapacity() * 0.8)); // configured seats, not a detection
                }
                movedBuses.add(bus.getId());
            }
            busRepository.saveAll(onRoute);
        }
        int parked = 0;
        Route depotRoute = routeRepository.findAll().stream()
                .filter(r -> "D1".equals(r.getRouteCode())).findFirst().orElse(null);
        for (String number : PARKED_DEMO_BUSES) {
            Bus bus = busRepository.findByBusNumber(number).orElse(null);
            if (bus != null && depotRoute != null && bus.getRoute() == null && !insideKolhapur(bus.getCurrentLatitude(), bus.getCurrentLongitude())) {
                RouteStop cbs = depotRoute.getStops().get(0);   // parked at the central bus stand
                bus.setCurrentLatitude(cbs.getLatitude());
                bus.setCurrentLongitude(cbs.getLongitude());
                busRepository.save(bus);
                movedBuses.add(bus.getId());
                parked++;
            }
        }
        if (converted == 0 && parked == 0) {
            return;
        }
        int removed = locationRepository.deleteOutsideArea(movedBuses, MIN_LAT, MAX_LAT, MIN_LNG, MAX_LNG);
        log.info("Converted {} legacy New York demo routes to the Kolhapur demo network; moved {} demo buses "
                + "(parked {} without route at the bus stand); removed {} of their simulated GPS points outside Kolhapur",
                converted, movedBuses.size(), parked, removed);
    }

    /** Puts back road geometry of Kolhapur demo routes when it is missing (e.g. after an older backup was restored). */
    private void restoreMissingGeometry(List<NetworkRoute> network) {
        for (Route route : routeRepository.findAll()) {
            if (!Boolean.TRUE.equals(route.getDemoRoute()) || route.getPathGeometry() != null) {
                continue;
            }
            network.stream().filter(n -> n.code().equals(route.getRouteCode())).findFirst().ifPresent(n -> {
                if (sameStops(route, n)) {   // only when the admin has not changed the stops
                    route.setPathGeometry(toJson(n.geometry()));
                    route.setPathSource(GEOMETRY_SOURCE);
                    routeRepository.save(route);
                    log.info("Restored road geometry of demo route {}", n.code());
                }
            });
        }
    }

    private void applyNetworkRoute(Route route, NetworkRoute demo) {
        route.setSource(demo.source());
        route.setDestination(demo.destination());
        route.setDescription(demo.description() + " (academic demo, not an official KMT route)");
        route.setDistance(demo.distanceKm());
        route.setRouteCode(demo.code());
        route.setDemoRoute(true);
        route.setPathGeometry(toJson(demo.geometry()));
        route.setPathSource(GEOMETRY_SOURCE);
        List<RouteStop> stops = new ArrayList<>();
        for (NetworkStop s : demo.stops()) {
            stops.add(RouteStop.builder().name(s.name()).latitude(s.latitude()).longitude(s.longitude()).build());
        }
        route.replaceStops(stops);
    }

    private static boolean sameStops(Route route, NetworkRoute demo) {
        if (route.getStops().size() != demo.stops().size()) {
            return false;
        }
        for (int i = 0; i < demo.stops().size(); i++) {
            RouteStop a = route.getStops().get(i);
            NetworkStop b = demo.stops().get(i);
            if (!a.getName().equals(b.name()) || Math.abs(a.getLatitude() - b.latitude()) > 1e-6
                    || Math.abs(a.getLongitude() - b.longitude()) > 1e-6) {
                return false;
            }
        }
        return true;
    }

    static boolean insideKolhapur(Double lat, Double lng) {
        return lat != null && lng != null && lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }

    private String toJson(List<double[]> geometry) {
        try {
            return objectMapper.writeValueAsString(geometry);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialise route geometry", e);
        }
    }
}
