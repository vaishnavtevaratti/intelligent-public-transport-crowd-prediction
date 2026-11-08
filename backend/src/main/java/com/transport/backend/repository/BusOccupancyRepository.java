package com.transport.backend.repository;

import com.transport.backend.entity.BusOccupancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface BusOccupancyRepository extends JpaRepository<BusOccupancy, Long> {

    Optional<BusOccupancy> findByBusId(Long busId);

    /** Row lock so concurrent sensor reports for one bus are applied one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from BusOccupancy o where o.bus.id = :busId")
    Optional<BusOccupancy> findByBusIdForUpdate(Long busId);

    @Modifying
    @Query("delete from BusOccupancy o where o.bus.id = :busId")
    int deleteByBusId(Long busId);
}
