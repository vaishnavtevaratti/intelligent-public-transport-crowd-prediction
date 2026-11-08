package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "buses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bus {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_MAINTENANCE = "MAINTENANCE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String busNumber;

    /** Total passenger capacity (seats + permitted standing). */
    private Integer capacity;

    /** Number of seats. Separate from capacity; null = not configured. */
    private Integer seatCount;

    private Double currentLatitude;

    private Double currentLongitude;

    /** ACTIVE, INACTIVE or MAINTENANCE. Only ACTIVE buses move and get scheduled predictions. */
    private String status;

    /** Time of the latest position update (simulated or GPS). */
    private LocalDateTime lastUpdated;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "route_id")
    private Route route;
}
