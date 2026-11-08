package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Metadata of an ML model version the backend has used (metrics copied from the ML service). */
@Entity
@Table(name = "ml_models")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String modelVersion;

    /** TIME_ROUTE or LIVE_FORECAST. */
    @Column(nullable = false, length = 20)
    private String modelKind;

    @Column(length = 60)
    private String algorithm;

    private Integer horizonMinutes;

    private String trainedAt;

    @Column(length = 120)
    private String datasetSource;

    /** Full evaluation metrics as reported by the ML service (JSON). */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String metricsJson;

    @Column(nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;
}
