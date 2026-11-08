package com.transport.backend.repository;

import com.transport.backend.entity.CrowdLevel;
import com.transport.backend.entity.CrowdPrediction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CrowdPredictionRepository extends JpaRepository<CrowdPrediction, Long> {

    Optional<CrowdPrediction> findTopByBusIdOrderByTimestampDesc(Long busId);

    Optional<CrowdPrediction> findTopByBusIdAndModelKindOrderByTimestampDesc(Long busId, String modelKind);

    List<CrowdPrediction> findByModelKindAndPredictedForBetweenOrderByPredictedForAsc(String modelKind, LocalDateTime from, LocalDateTime to);

    @Query("""
            select p from CrowdPrediction p
            where (:routeId is null or p.route.id = :routeId)
              and (:busId is null or p.bus.id = :busId)
              and (:level is null or p.crowdLevel = :level)
              and (:source is null or p.source = :source)
            """)
    Page<CrowdPrediction> search(Long routeId, Long busId, CrowdLevel level, String source, Pageable pageable);

    /**
     * Latest prediction of every bus (one row per bus). The grouped subquery is evaluated once and uses the
     * (bus_id, timestamp) index; a correlated subquery per row took ~130 ms at 1,200 predictions and grew with history.
     */
    @Query("""
            select p from CrowdPrediction p
            where (p.bus.id, p.timestamp) in (select p2.bus.id, max(p2.timestamp) from CrowdPrediction p2 group by p2.bus.id)
            """)
    List<CrowdPrediction> findLatestPerBus();

    List<CrowdPrediction> findByTimestampAfterOrderByTimestampDesc(LocalDateTime since);

    List<CrowdPrediction> findByCrowdLevelAndTimestampAfterOrderByTimestampDesc(CrowdLevel level, LocalDateTime since, Pageable pageable);

    List<CrowdPrediction> findTop8ByOrderByTimestampDesc();

    /** Historical crowd levels of a route grouped by hour of the travel time (SRS: travel suggestion). */
    @Query("""
            select extract(hour from coalesce(p.predictedFor, p.timestamp)), p.crowdLevel, count(p)
            from CrowdPrediction p
            where p.route.id = :routeId and p.timestamp >= :since
            group by extract(hour from coalesce(p.predictedFor, p.timestamp)), p.crowdLevel
            """)
    List<Object[]> countByHourAndLevel(Long routeId, LocalDateTime since);

    @Modifying
    @Query("delete from CrowdPrediction p where p.bus.id = :busId")
    int deleteByBusId(Long busId);

    @Modifying
    @Query("delete from CrowdPrediction p where p.route.id = :routeId")
    int deleteByRouteId(Long routeId);

    @Modifying
    @Query("delete from CrowdPrediction p where p.source = :source and p.timestamp < :cutoff")
    int deleteBySourceOlderThan(String source, LocalDateTime cutoff);

    long countByBusId(Long busId);

    long countByRouteId(Long routeId);
}
