package com.transport.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Health of the Python ML service and, when it is up, the model's evaluation details. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ModelStatusDto {

    private boolean available;
    private String modelVersion;
    private String message;
    /** The ML service's /model/info document (type, features, dataset source, metrics). */
    private Map<String, Object> details;
}
