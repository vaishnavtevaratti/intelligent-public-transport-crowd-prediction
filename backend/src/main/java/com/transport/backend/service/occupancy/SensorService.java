package com.transport.backend.service.occupancy;

import com.transport.backend.dto.SensorDto;
import com.transport.backend.dto.SensorRequest;
import com.transport.backend.entity.Bus;
import com.transport.backend.entity.Sensor;
import com.transport.backend.entity.SensorHealth;
import com.transport.backend.exception.ConflictException;
import com.transport.backend.exception.ResourceNotFoundException;
import com.transport.backend.exception.UnauthorizedException;
import com.transport.backend.repository.BusRepository;
import com.transport.backend.repository.SensorRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Registration and authentication of cameras/sensors. Each device gets a random secret key that is
 * shown to the administrator once; only its SHA-256 hash is stored.
 */
@Service
public class SensorService {

    private static final Logger log = LoggerFactory.getLogger(SensorService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SensorRepository sensorRepository;
    private final BusRepository busRepository;
    private final EntityManager entityManager;
    private final long offlineAfterSeconds;

    public SensorService(SensorRepository sensorRepository, BusRepository busRepository, EntityManager entityManager,
                         @Value("${app.sensors.offline-after-seconds:60}") long offlineAfterSeconds) {
        this.sensorRepository = sensorRepository;
        this.busRepository = busRepository;
        this.entityManager = entityManager;
        this.offlineAfterSeconds = offlineAfterSeconds;
    }

    @Transactional(readOnly = true)
    public List<SensorDto> list() {
        return sensorRepository.findAll(Sort.by("sensorCode")).stream().map(s -> toDto(s, null)).toList();
    }

    @Transactional
    public SensorDto create(SensorRequest request) {
        String code = request.getSensorCode().trim().toUpperCase();
        if (sensorRepository.existsBySensorCodeIgnoreCase(code)) {
            throw new ConflictException("A sensor with code " + code + " already exists");
        }
        Bus bus = busRepository.findById(request.getBusId())
                .orElseThrow(() -> new ResourceNotFoundException("Bus", "id", request.getBusId()));
        String key = newKey();
        Sensor sensor = sensorRepository.save(Sensor.builder()
                .sensorCode(code)
                .bus(bus)
                .type(request.getType())
                .description(request.getDescription())
                .apiKeyHash(hash(key))
                .enabled(request.getEnabled() == null || request.getEnabled())
                .createdAt(LocalDateTime.now())
                .lastHealth(SensorHealth.UNKNOWN)
                .build());
        log.info("Sensor {} ({}) registered for bus {}", code, sensor.getType(), bus.getBusNumber());
        return toDto(sensor, key);
    }

    @Transactional
    public SensorDto update(Long id, SensorRequest request) {
        Sensor sensor = find(id);
        Bus bus = busRepository.findById(request.getBusId())
                .orElseThrow(() -> new ResourceNotFoundException("Bus", "id", request.getBusId()));
        sensor.setBus(bus);
        sensor.setType(request.getType());
        sensor.setDescription(request.getDescription());
        sensor.setEnabled(request.getEnabled() == null || request.getEnabled());
        return toDto(sensorRepository.save(sensor), null);
    }

    @Transactional
    public SensorDto rotateKey(Long id) {
        Sensor sensor = find(id);
        String key = newKey();
        sensor.setApiKeyHash(hash(key));
        log.info("Key rotated for sensor {}", sensor.getSensorCode());
        return toDto(sensorRepository.save(sensor), key);
    }

    /** Deletes a sensor; its past observations are kept (sensor reference cleared). */
    @Transactional
    public void delete(Long id) {
        Sensor sensor = find(id);
        entityManager.createQuery("update PassengerCountObservation o set o.sensor = null where o.sensor.id = :id")
                .setParameter("id", id).executeUpdate();
        sensorRepository.delete(sensor);
        log.info("Sensor {} deleted", sensor.getSensorCode());
    }

    /** Identifies the device sending a report. Throws 401 for unknown, disabled or wrong-key sensors. */
    @Transactional(readOnly = true)
    public Sensor authenticate(String sensorCode, String apiKey) {
        if (sensorCode == null || apiKey == null || sensorCode.isBlank() || apiKey.isBlank()) {
            throw new UnauthorizedException("Sensor code and key are required (headers X-Sensor-Code, X-Sensor-Key)");
        }
        Sensor sensor = sensorRepository.findBySensorCode(sensorCode.trim().toUpperCase())
                .orElseThrow(() -> new UnauthorizedException("Unknown sensor or invalid key"));
        boolean keyOk = MessageDigest.isEqual(sensor.getApiKeyHash().getBytes(StandardCharsets.US_ASCII),
                hash(apiKey).getBytes(StandardCharsets.US_ASCII));
        if (!keyOk) {
            throw new UnauthorizedException("Unknown sensor or invalid key");
        }
        if (!sensor.isEnabled()) {
            throw new UnauthorizedException("Sensor " + sensor.getSensorCode() + " is disabled");
        }
        return sensor;
    }

    public boolean isOnline(Sensor sensor) {
        return sensor.getLastSeenAt() != null
                && sensor.getLastSeenAt().isAfter(LocalDateTime.now().minusSeconds(offlineAfterSeconds));
    }

    public SensorDto toDto(Sensor s, String plainKey) {
        return SensorDto.builder()
                .id(s.getId())
                .sensorCode(s.getSensorCode())
                .busId(s.getBus().getId())
                .busNumber(s.getBus().getBusNumber())
                .type(s.getType())
                .description(s.getDescription())
                .enabled(s.isEnabled())
                .createdAt(s.getCreatedAt())
                .lastSeenAt(s.getLastSeenAt())
                .online(isOnline(s))
                .lastHealth(s.getLastHealth())
                .lastMessage(s.getLastMessage())
                .lastMode(s.getLastMode())
                .detectorModel(s.getDetectorModel())
                .lastSequence(s.getLastSequence())
                .apiKey(plainKey)
                .build();
    }

    private Sensor find(Long id) {
        return sensorRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sensor", "id", id));
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
