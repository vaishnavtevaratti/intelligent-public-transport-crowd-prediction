package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A stop on a route. Stops are ordered (stopOrder 0 = route source) and their
 * coordinates define the path that buses follow on the map.
 */
@Entity
@Table(name = "route_stops", indexes = @Index(name = "idx_route_stops_route", columnList = "route_id, stop_order"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "route")
public class RouteStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;
}
