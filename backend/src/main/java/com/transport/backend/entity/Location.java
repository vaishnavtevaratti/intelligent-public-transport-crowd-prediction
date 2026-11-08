package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "locations", indexes = {
        @Index(name = "idx_locations_bus_time", columnList = "bus_id, timestamp"),
        @Index(name = "idx_locations_time", columnList = "timestamp")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Location {

    public static final String SOURCE_SIMULATED = "SIMULATED";
    public static final String SOURCE_GPS = "GPS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "bus_id", nullable = false)
    private Bus bus;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    /** SIMULATED (built-in simulator) or GPS (posted by a real device). Null for legacy rows. */
    @Column(length = 16)
    private String source;
}
