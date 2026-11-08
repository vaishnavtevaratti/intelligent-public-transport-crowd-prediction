package com.transport.backend.repository;

import com.transport.backend.entity.Bus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusRepository extends JpaRepository<Bus, Long> {

    Optional<Bus> findByBusNumber(String busNumber);

    Optional<Bus> findByBusNumberIgnoreCase(String busNumber);

    List<Bus> findByRouteId(Long routeId);

    long countByRouteId(Long routeId);

    List<Bus> findByStatus(String status);

    long countByStatus(String status);
}
