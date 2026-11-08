package com.transport.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MlModelDto {
    private String modelVersion;
    private String modelKind;
    private String algorithm;
    private Integer horizonMinutes;
    private String trainedAt;
    private String datasetSource;
    private Map<String, Object> metrics;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
