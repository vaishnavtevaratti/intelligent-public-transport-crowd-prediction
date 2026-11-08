package com.transport.backend.repository;

import com.transport.backend.entity.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RouteStopRepository extends JpaRepository<RouteStop, Long> {

    long countByRouteId(Long routeId);
}
