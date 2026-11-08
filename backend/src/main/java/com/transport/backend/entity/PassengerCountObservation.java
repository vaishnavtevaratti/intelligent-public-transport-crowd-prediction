package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One report from a sensor (or one manual reconciliation), and how it changed the onboard estimate.
 * The (sensor_id, sequence_number) unique key makes duplicate deliveries impossible to apply twice.
 */
@Entity
@Table(name = "passenger_count_observations",
        uniqueConstraints = @UniqueConstraint(name = "uk_observation_sensor_sequence", columnNames = {"sensor_id", "sequence_number"}),
        indexes = {
                @Index(name = "idx_observation_bus_time", columnList = "bus_id, observed_at"),
                @Index(name = "idx_observation_received", columnList = "received_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PassengerCountObservation {

    public static final String MODE_CAMERA = "CAMERA";
    public static final String MODE_TEST_VIDEO = "TEST_VIDEO";
    /** Laptop webcam demonstration: real inference on live frames, but not a camera installed in a bus. */
    public static final String MODE_WEBCAM_DEMO = "WEBCAM_DEMO";
    public static final String MODE_SEAT_SENSOR = "SEAT_SENSOR";
    public static final String MODE_MANUAL = "MANUAL";

    /** Entries/exits were added to the onboard estimate. */
    public static final String STATUS_APPLIED = "APPLIED";
    /** Arrived after a newer report: entries/exits still added (sums do not depend on order), snapshot values ignored. */
    public static final String STATUS_APPLIED_LATE = "APPLIED_LATE";
    /** The estimate would have become negative and was clamped to 0 (needs reconciliation). */
    public static final String STATUS_CLAMPED_NEGATIVE = "CLAMPED_NEGATIVE";
    /** Cabin/seat snapshot stored; no entry/exit arithmetic. */
    public static final String STATUS_SNAPSHOT = "SNAPSHOT";
    /** Health report only (camera down, inference error). */
    public static final String STATUS_HEALTH_ONLY = "HEALTH_ONLY";
    /** Authorised manual reconciliation. */
    public static final String STATUS_MANUAL = "MANUAL_RECONCILIATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for manual reconciliations. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_id")
    private Sensor sensor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bus_id", nullable = false)
    private Bus bus;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    /** People visible in the camera view at observedAt (cabin camera), if reported. */
    private Integer visibleCount;

    /** Entries and exits counted since the previous report (door camera). */
    private Integer entries;
    private Integer exits;

    /** Occupied seats (seat sensors or manual count), if reported. */
    private Integer occupiedSeats;

    private Double meanConfidence;
    private Double frameQuality;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private SensorHealth health;

    @Column(nullable = false, length = 16)
    private String sourceMode;

    @Column(nullable = false, length = 24)
    private String status;

    private Integer previousOnboard;
    private Integer updatedOnboard;

    @Column(length = 120)
    private String performedBy;

    @Column(length = 255)
    private String note;
}
