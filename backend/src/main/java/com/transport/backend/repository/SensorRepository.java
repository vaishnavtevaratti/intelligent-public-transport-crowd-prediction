package com.transport.backend.repository;

import com.transport.backend.entity.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SensorRepository extends JpaRepository<Sensor, Long> {

    Optional<Sensor> findBySensorCode(String sensorCode);

    boolean existsBySensorCodeIgnoreCase(String sensorCode);

    List<Sensor> findByBusId(Long busId);

    long countByBusId(Long busId);
}
