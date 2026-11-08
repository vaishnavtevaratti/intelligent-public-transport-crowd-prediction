package com.transport.backend.service;

import com.transport.backend.dto.AdminAnalyticsDto;
import com.transport.backend.dto.DashboardDto;
import com.transport.backend.dto.ModelStatusDto;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.CrowdLevel;
import com.transport.backend.entity.CrowdPrediction;
import com.transport.backend.entity.Role;
import com.transport.backend.exception.ServiceUnavailableException;
import com.transport.backend.repository.*;
import com.transport.backend.service.ml.CrowdModelClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class DashboardService {

    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final CrowdPredictionRepository crowdPredictionRepository;
    private final LocationRepository locationRepository;
    private final UserRepository userRepository;
    private final CrowdPredictionService crowdPredictionService;
    private final CrowdModelClient modelClient;
    private final boolean simulationEnabled;

    @Autowired
    public DashboardService(BusRepository busRepository,
                            RouteRepository routeRepository,
                            RouteStopRepository routeStopRepository,
                            CrowdPredictionRepository crowdPredictionRepository,
                            LocationRepository locationRepository,
                            UserRepository userRepository,
                            CrowdPredictionService crowdPredictionService,
                            CrowdModelClient modelClient,
                            @Value("${app.simulation.enabled:true}") boolean simulationEnabled) {
        this.busRepository = busRepository;
        this.routeRepository = routeRepository;
        this.routeStopRepository = routeStopRepository;
        this.crowdPredictionRepository = crowdPredictionRepository;
        this.locationRepository = locationRepository;
        this.userRepository = userRepository;
        this.crowdPredictionService = crowdPredictionService;
        this.modelClient = modelClient;
        this.simulationEnabled = simulationEnabled;
    }

    /** Figures shown on the passenger dashboard; every number comes from the database. */
    @Transactional(readOnly = true)
    public DashboardDto getDashboardStats() {
        return DashboardDto.builder()
                .totalBuses(busRepository.count())
                .totalRoutes(routeRepository.count())
                .activeBuses(busRepository.countByStatus(Bus.STATUS_ACTIVE))
                .totalStops(routeStopRepository.count())
                .crowdSummary(crowdPredictionService.currentCrowdSummary())
                .recentPredictions(crowdPredictionRepository.findTop8ByOrderByTimestampDesc().stream()
                        .map(crowdPredictionService::convertToDto).toList())
                .highCrowdAlerts(crowdPredictionService.recentHighCrowdAlerts())
                .mlServiceAvailable(modelClient.isAvailable())
                .simulationEnabled(simulationEnabled)
                .build();
    }

    @Transactional(readOnly = true)
    public AdminAnalyticsDto getAdminAnalytics() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String status : List.of(Bus.STATUS_ACTIVE, Bus.STATUS_INACTIVE, Bus.STATUS_MAINTENANCE)) {
            byStatus.put(status, busRepository.countByStatus(status));
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> last24h = emptyLevelMap();
        List<Map<String, Object>> byHour = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hour", h);
            row.put("LOW", 0L);
            row.put("MEDIUM", 0L);
            row.put("HIGH", 0L);
            row.put("FULL", 0L);
            byHour.add(row);
        }
        for (CrowdPrediction p : crowdPredictionRepository.findByTimestampAfterOrderByTimestampDesc(now.minusDays(7))) {
            String level = p.getCrowdLevel().name();
            if (p.getTimestamp().isAfter(now.minusHours(24))) {
                last24h.merge(level, 1L, Long::sum);
            }
            LocalDateTime when = p.getPredictedFor() != null ? p.getPredictedFor() : p.getTimestamp();
            Map<String, Object> row = byHour.get(when.getHour());
            row.put(level, (Long) row.get(level) + 1);
        }

        Map<Long, Map<String, Object>> perRoute = new TreeMap<>();
        for (CrowdPrediction p : crowdPredictionService.latestPerBus().values()) {
            Map<String, Object> row = perRoute.computeIfAbsent(p.getRoute().getId(), id -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("routeId", id);
                r.put("label", p.getRoute().getSource() + " → " + p.getRoute().getDestination());
                r.put("LOW", 0L);
                r.put("MEDIUM", 0L);
                r.put("HIGH", 0L);
                r.put("FULL", 0L);
                return r;
            });
            row.put(p.getCrowdLevel().name(), (Long) row.get(p.getCrowdLevel().name()) + 1);
        }

        return AdminAnalyticsDto.builder()
                .totalUsers(userRepository.count())
                .adminUsers(userRepository.countByRole(Role.ADMIN))
                .totalBuses(busRepository.count())
                .totalRoutes(routeRepository.count())
                .totalPredictions(crowdPredictionRepository.count())
                .locationRecords(locationRepository.count())
                .busesByStatus(byStatus)
                .predictionsLast24h(last24h)
                .predictionsByHour(byHour)
                .routeCrowd(new ArrayList<>(perRoute.values()))
                .simulationEnabled(simulationEnabled)
                .model(getModelStatus())
                .build();
    }

    public ModelStatusDto getModelStatus() {
        try {
            Map<String, Object> info = modelClient.modelInfo();
            return ModelStatusDto.builder()
                    .available(true)
                    .modelVersion(String.valueOf(info.get("model_version")))
                    .message("Model loaded")
                    .details(info)
                    .build();
        } catch (ServiceUnavailableException ex) {
            return ModelStatusDto.builder().available(false).message(ex.getMessage()).build();
        }
    }

    private static Map<String, Long> emptyLevelMap() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (CrowdLevel level : CrowdLevel.values()) {
            map.put(level.name(), 0L);
        }
        return map;
    }
}
