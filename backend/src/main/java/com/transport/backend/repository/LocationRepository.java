package com.transport.backend.repository;

import com.transport.backend.entity.Location;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {

    List<Location> findByBusIdOrderByTimestampDesc(Long busId, Pageable pageable);

    @Modifying
    @Query("delete from Location l where l.bus.id = :busId")
    int deleteByBusId(Long busId);

    @Modifying
    @Query("delete from Location l where l.timestamp < :cutoff")
    int deleteOlderThan(LocalDateTime cutoff);

    long countByBusId(Long busId);

    /** Removes the given buses' location points that lie outside the area (used once by the Kolhapur demo migration). */
    @Modifying
    @Query("""
            delete from Location l where l.bus.id in :busIds
              and (l.latitude < :minLat or l.latitude > :maxLat or l.longitude < :minLng or l.longitude > :maxLng)
            """)
    int deleteOutsideArea(List<Long> busIds, double minLat, double maxLat, double minLng, double maxLng);
}
