package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "crowd_predictions", indexes = {
        @Index(name = "idx_crowd_time", columnList = "timestamp"),
        @Index(name = "idx_crowd_route_time", columnList = "route_id, timestamp"),
        @Index(name = "idx_crowd_bus_time", columnList = "bus_id, timestamp")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrowdPrediction {

    /** Requested by a user from the Crowd Prediction page. */
    public static final String SOURCE_ON_DEMAND = "ON_DEMAND";
    /** Produced periodically by the scheduler for every active bus. */
    public static final String SOURCE_SCHEDULED = "SCHEDULED";

    /** Time-and-route model (ml-service /predict): uses no live occupancy. */
    public static final String KIND_TIME_ROUTE = "TIME_ROUTE";
    /** Short-horizon model (ml-service /forecast): uses the live onboard estimate. */
    public static final String KIND_LIVE_FORECAST = "LIVE_FORECAST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "bus_id", nullable = false)
    private Bus bus;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrowdLevel crowdLevel;

    /** When the prediction was made. */
    @Column(nullable = false)
    private LocalDateTime timestamp;

    /** The travel time the prediction is for (may be in the future). */
    private LocalDateTime predictedFor;

    /** Model probability of the predicted level (0..1). */
    private Double confidence;

    private Double probabilityLow;
    private Double probabilityMedium;
    private Double probabilityHigh;
    private Double probabilityFull;

    /** TIME_ROUTE or LIVE_FORECAST; null for legacy records. */
    @Column(length = 20)
    private String modelKind;

    /** Minutes between the observation and the predicted time (15 for live forecasts, 0 = "now"). */
    private Integer horizonMinutes;

    /** Live forecast only: predicted passengers onboard at predictedFor. */
    private Double predictedPassengers;

    /** Live forecast only: the onboard estimate the forecast started from. */
    private Integer inputOnboard;

    @Column(length = 80)
    private String modelVersion;

    /** ON_DEMAND or SCHEDULED; null for records created before the ML model was introduced. */
    @Column(length = 16)
    private String source;
}
