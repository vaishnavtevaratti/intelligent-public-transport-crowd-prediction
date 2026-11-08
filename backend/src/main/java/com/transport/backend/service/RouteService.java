package com.transport.backend.service;

import com.transport.backend.dto.RouteDto;
import com.transport.backend.dto.RouteStopDto;
import com.transport.backend.entity.Route;
import com.transport.backend.entity.RouteStop;
import com.transport.backend.exception.BadRequestException;
import com.transport.backend.exception.ConflictException;
import com.transport.backend.exception.ResourceNotFoundException;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.CrowdPredictionRepository;
import com.transport.backend.repository.RouteRepository;
import com.transport.backend.service.geo.RoutePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    private final RouteRepository routeRepository;
    private final BusRepository busRepository;
    private final CrowdPredictionRepository crowdPredictionRepository;

    @Autowired
    public RouteService(RouteRepository routeRepository, BusRepository busRepository,
                        CrowdPredictionRepository crowdPredictionRepository) {
        this.routeRepository = routeRepository;
        this.busRepository = busRepository;
        this.crowdPredictionRepository = crowdPredictionRepository;
    }

    @Transactional(readOnly = true)
    public List<RouteDto> getAllRoutes() {
        Map<Long, Long> busCounts = busRepository.findAll().stream()
                .filter(b -> b.getRoute() != null)
                .collect(Collectors.groupingBy(b -> b.getRoute().getId(), Collectors.counting()));
        return routeRepository.findAllWithStops().stream()
                .map(r -> convertToDto(r, busCounts.getOrDefault(r.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RouteDto getRouteById(Long id) {
        return convertToDto(findRoute(id), busRepository.countByRouteId(id));
    }

    @Transactional
    public RouteDto createRoute(RouteDto routeDto) {
        Route route = new Route();
        apply(route, routeDto);
        Route saved = routeRepository.save(route);
        log.info("Route {} created: {} -> {} ({} stops)", saved.getId(), saved.getSource(), saved.getDestination(), saved.getStops().size());
        return convertToDto(saved, 0L);
    }

    @Transactional
    public RouteDto updateRoute(Long id, RouteDto routeDto) {
        Route existingRoute = findRoute(id);
        apply(existingRoute, routeDto);
        Route updatedRoute = routeRepository.save(existingRoute);
        return convertToDto(updatedRoute, busRepository.countByRouteId(id));
    }

    /** A route that still has buses cannot be deleted; its stored predictions are removed with it. */
    @Transactional
    public void deleteRoute(Long id) {
        Route route = findRoute(id);
        long buses = busRepository.countByRouteId(id);
        if (buses > 0) {
            throw new ConflictException("Route has " + buses + " assigned bus(es). Reassign or delete them first.");
        }
        int predictions = crowdPredictionRepository.deleteByRouteId(id);
        routeRepository.delete(route);
        log.info("Route {} deleted with {} predictions", id, predictions);
    }

    private Route findRoute(Long id) {
        return routeRepository.findByIdWithStops(id)
                .orElseThrow(() -> new ResourceNotFoundException("Route", "id", id));
    }

    private void apply(Route route, RouteDto dto) {
        String source = dto.getSource().trim();
        String destination = dto.getDestination().trim();
        if (source.equalsIgnoreCase(destination)) {
            throw new BadRequestException("Source and destination must be different");
        }
        List<RouteStopDto> stopDtos = dto.getStops() != null ? dto.getStops() : List.of();
        if (stopDtos.size() == 1) {
            throw new BadRequestException("A route needs at least two stops (or none)");
        }
        Set<String> names = new HashSet<>();
        for (RouteStopDto stop : stopDtos) {
            if (!names.add(stop.getName().trim().toLowerCase())) {
                throw new BadRequestException("Stop names on a route must be unique: " + stop.getName().trim());
            }
        }

        route.setSource(source);
        route.setDestination(destination);
        route.setDescription(dto.getDescription() != null ? dto.getDescription().trim() : null);
        String stopsBefore = stopSignature(route.getStops());
        route.replaceStops(stopDtos.stream()
                .map(s -> RouteStop.builder().name(s.getName().trim()).latitude(s.getLatitude()).longitude(s.getLongitude()).build())
                .toList());
        if (!stopSignature(route.getStops()).equals(stopsBefore)) {
            // stored road geometry belongs to the old stops; the map now draws straight lines (labelled as such)
            route.setPathGeometry(null);
            route.setPathSource(null);
        }

        Double distance = dto.getDistance();
        if (distance == null) {
            if (!RoutePath.isUsable(route.getStops())) {
                throw new BadRequestException("Distance is required when the route has no stops");
            }
            distance = Math.round(RoutePath.of(route).lengthMeters() / 100.0) / 10.0;
        }
        route.setDistance(distance);
    }

    private static String stopSignature(List<RouteStop> stops) {
        StringBuilder sb = new StringBuilder();
        for (RouteStop s : stops) {
            sb.append(s.getName()).append('|').append(s.getLatitude()).append('|').append(s.getLongitude()).append(';');
        }
        return sb.toString();
    }

    public RouteDto convertToDto(Route route, Long busCount) {
        return RouteDto.builder()
                .id(route.getId())
                .source(route.getSource())
                .destination(route.getDestination())
                .distance(route.getDistance())
                .description(route.getDescription())
                .routeCode(route.getRouteCode())
                .demoRoute(Boolean.TRUE.equals(route.getDemoRoute()))
                .pathGeometry(RoutePath.geometryOf(route))
                .pathSource(route.getPathSource())
                .stops(route.getStops().stream().map(toStopDto()).toList())
                .busCount(busCount)
                .build();
    }

    private static Function<RouteStop, RouteStopDto> toStopDto() {
        return s -> RouteStopDto.builder()
                .id(s.getId())
                .stopOrder(s.getStopOrder())
                .name(s.getName())
                .latitude(s.getLatitude())
                .longitude(s.getLongitude())
                .build();
    }
}
