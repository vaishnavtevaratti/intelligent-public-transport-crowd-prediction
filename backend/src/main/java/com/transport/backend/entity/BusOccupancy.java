package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Current onboard estimate of one bus. Every change is logged in passenger_count_observations. */
@Entity
@Table(name = "bus_occupancy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusOccupancy {

    /** Running total from door entry/exit counts. */
    public static final String METHOD_DOOR_COUNT = "DOOR_COUNT";
    /** People visible to a cabin camera: a lower bound, not the full onboard count. */
    public static final String METHOD_CABIN_VISIBLE = "CABIN_VISIBLE";
    /** Set by an authorised operator. */
    public static final String METHOD_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "bus_id", nullable = false, unique = true)
    private Bus bus;

    /** Estimated passengers onboard; null = unknown. */
    private Integer estimatedOnboard;

    @Column(length = 16)
    private String onboardMethod;

    /** 0..1, how much the estimate can be trusted. */
    private Double confidence;

    /** Measured occupied seats (seat sensors or manual count); null = unknown. */
    private Integer occupiedSeats;

    @Column(length = 16)
    private String seatSource;

    private LocalDateTime seatUpdatedAt;

    private Integer lastVisibleCount;
    private LocalDateTime lastVisibleAt;

    /** CAMERA, TEST_VIDEO, SEAT_SENSOR or MANUAL: where the latest onboard value came from. */
    @Column(length = 16)
    private String dataMode;

    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private boolean needsReconciliation;

    @Column(length = 255)
    private String reconciliationReason;
}
