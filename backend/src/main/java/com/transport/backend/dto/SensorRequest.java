package com.transport.backend.dto;

import com.transport.backend.entity.SensorType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SensorRequest {

    @NotBlank(message = "Sensor code is required")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{2,39}$", message = "Sensor code must be 3-40 letters, digits, dashes or underscores")
    private String sensorCode;

    @NotNull(message = "Bus is required")
    private Long busId;

    @NotNull(message = "Sensor type is required")
    private SensorType type;

    @Size(max = 200, message = "Description is too long")
    private String description;

    private Boolean enabled = true;
}
