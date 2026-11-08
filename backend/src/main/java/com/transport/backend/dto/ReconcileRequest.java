package com.transport.backend.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** Authorised manual correction of the onboard count of a bus (e.g. a conductor head count). */
@Data
public class ReconcileRequest {

    @NotNull(message = "Onboard count is required")
    @Min(value = 0, message = "Onboard count must be >= 0")
    @Max(value = 400, message = "Onboard count must be <= 400")
    private Integer onboard;

    /** Optional counted occupied seats; leave empty if not counted. */
    @Min(value = 0, message = "Occupied seats must be >= 0")
    @Max(value = 200, message = "Occupied seats must be <= 200")
    private Integer occupiedSeats;

    @NotBlank(message = "A note explaining the correction is required")
    @Size(max = 255, message = "Note is too long")
    private String note;
}
