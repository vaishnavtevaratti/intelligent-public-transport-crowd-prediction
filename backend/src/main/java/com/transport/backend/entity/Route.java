package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "routes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "stops")
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String destination;

    private Double distance;

    private String description;

    /** Short label of a demo route, e.g. "D1" (not an official route number). */
    @Column(length = 20)
    private String routeCode;

    /** True for the fictional academic demo routes (shown with a "Demo route" label). */
    private Boolean demoRoute;

    /**
     * Road geometry the route follows, as JSON [[lat, lng], ...], or null when only the stops are known
     * (then the map draws straight lines between stops, labelled as such). Cleared when the stops change.
     */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String pathGeometry;

    /** Where pathGeometry came from, e.g. "OSRM / OpenStreetMap road network". */
    @Column(length = 120)
    private String pathSource;

    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stopOrder ASC")
    @Builder.Default
    private List<RouteStop> stops = new ArrayList<>();

    /** Replaces all stops, keeping the given order. */
    public void replaceStops(List<RouteStop> newStops) {
        stops.clear();
        for (int i = 0; i < newStops.size(); i++) {
            RouteStop stop = newStops.get(i);
            stop.setRoute(this);
            stop.setStopOrder(i);
            stops.add(stop);
        }
    }
}
