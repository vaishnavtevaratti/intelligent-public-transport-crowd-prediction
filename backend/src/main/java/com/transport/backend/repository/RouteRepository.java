package com.transport.backend.repository;

import com.transport.backend.entity.Route;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {

    /** Loads routes together with their ordered stops in one query. */
    @EntityGraph(attributePaths = "stops")
    @Query("select distinct r from Route r order by r.id")
    List<Route> findAllWithStops();

    @EntityGraph(attributePaths = "stops")
    @Query("select r from Route r where r.id = :id")
    Optional<Route> findByIdWithStops(Long id);

    Optional<Route> findBySourceAndDestination(String source, String destination);
}
