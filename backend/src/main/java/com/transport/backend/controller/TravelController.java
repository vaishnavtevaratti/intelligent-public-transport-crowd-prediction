package com.transport.backend.controller;

import com.transport.backend.dto.TravelOptionDto;
import com.transport.backend.service.TravelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/travel")
@Tag(name = "Travel information", description = "Stop search, route options, arrival estimates and best travel time")
public class TravelController {

    private final TravelService travelService;

    public TravelController(TravelService travelService) {
        this.travelService = travelService;
    }

    @GetMapping("/stops")
    @Operation(summary = "All stop names, alphabetically")
    public ResponseEntity<List<String>> stops() {
        return ResponseEntity.ok(travelService.listStops());
    }

    @GetMapping("/search")
    @Operation(summary = "Routes connecting two stops with live buses, ETA, current crowd prediction and best hours today")
    public ResponseEntity<List<TravelOptionDto>> search(@RequestParam String from, @RequestParam String to) {
        return ResponseEntity.ok(travelService.search(from, to));
    }
}
