package com.transport.backend.controller;

import com.transport.backend.dto.LocationDto;
import com.transport.backend.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/location")
@Tag(name = "Bus location", description = "Live and historical bus positions")
public class LocationController {

    private final LocationService locationService;

    @Autowired
    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/live")
    @Operation(summary = "Current position of every bus (live updates are pushed on WebSocket /topic/location)")
    public ResponseEntity<List<LocationDto>> getLiveLocations() {
        return ResponseEntity.ok(locationService.getLiveLocations());
    }

    @GetMapping("/history/{busId}")
    @Operation(summary = "Most recent positions of one bus, newest first")
    public ResponseEntity<List<LocationDto>> getLocationHistory(@PathVariable Long busId,
                                                                @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(locationService.getLocationHistory(busId, limit));
    }

    @PostMapping("/update")
    @Operation(summary = "Submit a REAL GPS position for a bus (admin / device). Overrides the simulator for that bus.")
    public ResponseEntity<LocationDto> updateLocation(@Valid @RequestBody LocationDto dto) {
        return ResponseEntity.ok(locationService.updateLocation(dto));
    }
}
