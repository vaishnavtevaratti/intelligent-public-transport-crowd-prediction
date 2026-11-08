package com.transport.backend.repository;

import com.transport.backend.entity.PassengerCountObservation;
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
public interface PassengerCountObservationRepository extends JpaRepository<PassengerCountObservation, Long> {

    Optional<PassengerCountObservation> findBySensorIdAndSequenceNumber(Long sensorId, Long sequenceNumber);

    Page<PassengerCountObservation> findByBusIdOrderByReceivedAtDesc(Long busId, Pageable pageable);

    /** Latest onboard value at or before a time (used for the 15-minute-ago forecast input and evaluation). */
    @Query("""
            select o from PassengerCountObservation o
            where o.bus.id = :busId and o.updatedOnboard is not null and o.observedAt between :from and :to
            order by o.observedAt desc
            """)
    List<PassengerCountObservation> findOnboardBetween(Long busId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    @Modifying
    @Query("delete from PassengerCountObservation o where o.bus.id = :busId")
    int deleteByBusId(Long busId);

    @Modifying
    @Query("delete from PassengerCountObservation o where o.sensor.id = :sensorId")
    int deleteBySensorId(Long sensorId);

    @Modifying
    @Query("delete from PassengerCountObservation o where o.receivedAt < :cutoff")
    int deleteOlderThan(LocalDateTime cutoff);
}
