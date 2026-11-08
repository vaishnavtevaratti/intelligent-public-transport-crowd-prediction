package com.transport.backend.service;

import com.transport.backend.dto.BusDto;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.CrowdPrediction;
import com.transport.backend.entity.Route;
import com.transport.backend.exception.BadRequestException;
import com.transport.backend.exception.ConflictException;
import com.transport.backend.exception.ResourceNotFoundException;
import com.transport.backend.repository.BusOccupancyRepository;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.PassengerCountObservationRepository;
import com.transport.backend.repository.SensorRepository;
import com.transport.backend.repository.CrowdPredictionRepository;
import com.transport.backend.repository.LocationRepository;
import com.transport.backend.repository.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class BusService {

    private static final Logger log = LoggerFactory.getLogger(BusService.class);

    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final LocationRepository locationRepository;
    private final CrowdPredictionRepository crowdPredictionRepository;
    private final CrowdPredictionService crowdPredictionService;
    private final BusSimulationService simulationService;
    private final PassengerCountObservationRepository observationRepository;
    private final BusOccupancyRepository occupancyRepository;
    private final SensorRepository sensorRepository;

    @Autowired
    public BusService(BusRepository busRepository, RouteRepository routeRepository,
                      LocationRepository locationRepository, CrowdPredictionRepository crowdPredictionRepository,
                      CrowdPredictionService crowdPredictionService, BusSimulationService simulationService,
                      PassengerCountObservationRepository observationRepository, BusOccupancyRepository occupancyRepository,
                      SensorRepository sensorRepository) {
        this.busRepository = busRepository;
        this.routeRepository = routeRepository;
        this.locationRepository = locationRepository;
        this.crowdPredictionRepository = crowdPredictionRepository;
        this.crowdPredictionService = crowdPredictionService;
        this.simulationService = simulationService;
        this.observationRepository = observationRepository;
        this.occupancyRepository = occupancyRepository;
        this.sensorRepository = sensorRepository;
    }

    @Transactional(readOnly = true)
    public List<BusDto> getAllBuses() {
        Map<Long, CrowdPrediction> latest = crowdPredictionService.latestPerBus();
        return busRepository.findAll().stream()
                .sorted(Comparator.comparing(Bus::getBusNumber, String.CASE_INSENSITIVE_ORDER))
                .map(bus -> convertToDto(bus, latest.get(bus.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public BusDto getBusById(Long id) {
        Bus bus = findBus(id);
        return convertToDto(bus, crowdPredictionRepository.findTopByBusIdOrderByTimestampDesc(id).orElse(null));
    }

    @Transactional
    public BusDto createBus(BusDto busDto) {
        String number = normalizeNumber(busDto.getBusNumber());
        if (busRepository.findByBusNumberIgnoreCase(number).isPresent()) {
            throw new ConflictException("A bus with number " + number + " already exists");
        }
        Route route = resolveRoute(busDto.getRouteId());
        Double lat = busDto.getCurrentLatitude();
        Double lng = busDto.getCurrentLongitude();
        if ((lat == null || lng == null) && route != null && !route.getStops().isEmpty()) {
            // Start a new bus at the first stop of its route.
            lat = route.getStops().get(0).getLatitude();
            lng = route.getStops().get(0).getLongitude();
        }

        Bus bus = Bus.builder()
                .busNumber(number)
                .capacity(busDto.getCapacity())
                .seatCount(validSeatCount(busDto))
                .currentLatitude(lat)
                .currentLongitude(lng)
                .status(busDto.getStatus() != null ? busDto.getStatus() : Bus.STATUS_ACTIVE)
                .route(route)
                .build();

        Bus saved = busRepository.save(bus);
        log.info("Bus {} created (route {})", saved.getBusNumber(), route != null ? route.getId() : "none");
        return convertToDto(saved, null);
    }

    @Transactional
    public BusDto updateBus(Long id, BusDto busDto) {
        Bus existingBus = findBus(id);
        String number = normalizeNumber(busDto.getBusNumber());
        busRepository.findByBusNumberIgnoreCase(number)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException("A bus with number " + number + " already exists");
                });

        // routeId null means "not assigned to any route"
        existingBus.setRoute(resolveRoute(busDto.getRouteId()));
        existingBus.setBusNumber(number);
        existingBus.setCapacity(busDto.getCapacity());
        existingBus.setSeatCount(validSeatCount(busDto));
        if (busDto.getCurrentLatitude() != null) existingBus.setCurrentLatitude(busDto.getCurrentLatitude());
        if (busDto.getCurrentLongitude() != null) existingBus.setCurrentLongitude(busDto.getCurrentLongitude());
        if (busDto.getStatus() != null) existingBus.setStatus(busDto.getStatus());

        Bus updatedBus = busRepository.save(existingBus);
        simulationService.forget(id);
        return convertToDto(updatedBus, crowdPredictionRepository.findTopByBusIdOrderByTimestampDesc(id).orElse(null));
    }

    /** Deletes a bus together with its location history and crowd predictions. */
    @Transactional
    public void deleteBus(Long id) {
        Bus bus = findBus(id);
        int locations = locationRepository.deleteByBusId(id);
        int predictions = crowdPredictionRepository.deleteByBusId(id);
        int observations = observationRepository.deleteByBusId(id);
        occupancyRepository.deleteByBusId(id);
        sensorRepository.findByBusId(id).forEach(sensorRepository::delete);
        busRepository.delete(bus);
        simulationService.forget(id);
        log.info("Bus {} deleted with {} location records, {} predictions and {} occupancy observations",
                bus.getBusNumber(), locations, predictions, observations);
    }

    /** Seats are a separate figure from total passenger capacity and can never exceed it. */
    private Integer validSeatCount(BusDto dto) {
        if (dto.getSeatCount() != null && dto.getCapacity() != null && dto.getSeatCount() > dto.getCapacity()) {
            throw new BadRequestException("Seat count (" + dto.getSeatCount() + ") cannot exceed total passenger capacity ("
                    + dto.getCapacity() + ")");
        }
        return dto.getSeatCount();
    }

    private Bus findBus(Long id) {
        return busRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Bus", "id", id));
    }

    private Route resolveRoute(Long routeId) {
        if (routeId == null) {
            return null;
        }
        return routeRepository.findByIdWithStops(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route", "id", routeId));
    }

    private String normalizeNumber(String number) {
        return number.trim().toUpperCase();
    }

    public BusDto convertToDto(Bus bus, CrowdPrediction latest) {
        Route route = bus.getRoute();
        java.util.Optional<BusSimulationService.LiveInfo> live = simulationService.liveInfo(bus);
        return BusDto.builder()
                .id(bus.getId())
                .busNumber(bus.getBusNumber())
                .capacity(bus.getCapacity())
                .seatCount(bus.getSeatCount())
                .currentLatitude(bus.getCurrentLatitude())
                .currentLongitude(bus.getCurrentLongitude())
                .status(bus.getStatus())
                .routeId(route != null ? route.getId() : null)
                .routeSource(route != null ? route.getSource() : null)
                .routeDestination(route != null ? route.getDestination() : null)
                .routeCode(route != null ? route.getRouteCode() : null)
                .lastUpdated(bus.getLastUpdated())
                .latestCrowdLevel(latest != null ? latest.getCrowdLevel() : null)
                .latestCrowdTime(latest != null ? latest.getTimestamp() : null)
                .nextStop(live.map(BusSimulationService.LiveInfo::nextStop).orElse(null))
                .direction(live.map(BusSimulationService.LiveInfo::direction).orElse(null))
                .speedKmph(live.map(BusSimulationService.LiveInfo::speedKmph).orElse(null))
                .build();
    }
}
