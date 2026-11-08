package com.transport.backend.service;

import com.transport.backend.dto.LocationDto;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.Location;
import com.transport.backend.exception.ResourceNotFoundException;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.LocationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class LocationService {

    public static final String LOCATION_TOPIC = "/topic/location";

    private final LocationRepository locationRepository;
    private final BusRepository busRepository;
    private final BusSimulationService simulationService;
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public LocationService(LocationRepository locationRepository, BusRepository busRepository,
                           BusSimulationService simulationService, SimpMessagingTemplate messagingTemplate) {
        this.locationRepository = locationRepository;
        this.busRepository = busRepository;
        this.simulationService = simulationService;
        this.messagingTemplate = messagingTemplate;
    }

    /** Current position of every bus that has coordinates, with the time of its last update. */
    @Transactional(readOnly = true)
    public List<LocationDto> getLiveLocations() {
        return busRepository.findAll().stream()
                .filter(bus -> bus.getCurrentLatitude() != null && bus.getCurrentLongitude() != null)
                .map(bus -> LocationDto.builder()
                        .busId(bus.getId())
                        .busNumber(bus.getBusNumber())
                        .routeId(bus.getRoute() != null ? bus.getRoute().getId() : null)
                        .latitude(bus.getCurrentLatitude())
                        .longitude(bus.getCurrentLongitude())
                        .timestamp(bus.getLastUpdated())
                        .status(bus.getStatus())
                        .build())
                .toList();
    }

    /** Most recent positions of a bus (newest first), at most {@code limit}. */
    @Transactional(readOnly = true)
    public List<LocationDto> getLocationHistory(Long busId, int limit) {
        if (!busRepository.existsById(busId)) {
            throw new ResourceNotFoundException("Bus", "id", busId);
        }
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return locationRepository.findByBusIdOrderByTimestampDesc(busId, PageRequest.of(0, safeLimit)).stream()
                .map(this::convertToDto)
                .toList();
    }

    /**
     * Records a REAL GPS position (e.g. from a device on the bus) and broadcasts it.
     * The simulator stops moving this bus while real fixes keep arriving.
     */
    @Transactional
    public LocationDto updateLocation(LocationDto dto) {
        Bus bus = busRepository.findById(dto.getBusId())
                .orElseThrow(() -> new ResourceNotFoundException("Bus", "id", dto.getBusId()));
        LocalDateTime now = LocalDateTime.now();

        bus.setCurrentLatitude(dto.getLatitude());
        bus.setCurrentLongitude(dto.getLongitude());
        bus.setLastUpdated(now);
        busRepository.save(bus);
        simulationService.recordGpsFix(bus.getId());

        Location location = Location.builder()
                .bus(bus)
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .timestamp(now)
                .source(Location.SOURCE_GPS)
                .build();

        LocationDto saved = convertToDto(locationRepository.save(location));
        saved.setStatus(bus.getStatus());
        saved.setRouteId(bus.getRoute() != null ? bus.getRoute().getId() : null);
        saved.setSpeedKmph(dto.getSpeedKmph());
        saved.setHeading(dto.getHeading());
        messagingTemplate.convertAndSend(LOCATION_TOPIC, saved);
        return saved;
    }

    public LocationDto convertToDto(Location location) {
        return LocationDto.builder()
                .id(location.getId())
                .busId(location.getBus().getId())
                .busNumber(location.getBus().getBusNumber())
                .latitude(location.getLatitude())
                .longitude(location.getLongitude())
                .timestamp(location.getTimestamp())
                .source(location.getSource())
                .build();
    }
}
