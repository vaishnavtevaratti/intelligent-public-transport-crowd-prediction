package com.transport.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A camera or sensor installed in a bus. Devices authenticate with their sensorCode and a secret key;
 * only the SHA-256 hash of the key is stored.
 */
@Entity
@Table(name = "sensors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "apiKeyHash")
public class Sensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String sensorCode;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "bus_id", nullable = false)
    private Bus bus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SensorType type;

    @Column(length = 200)
    private String description;

    @Column(nullable = false, length = 64)
    private String apiKeyHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private SensorHealth lastHealth;

    @Column(length = 200)
    private String lastMessage;

    /** Highest report sequence number received (used to detect duplicates, gaps and late reports). */
    private Long lastSequence;

    /** CAMERA (live device) or TEST_VIDEO (recorded file replay). */
    @Column(length = 16)
    private String lastMode;

    @Column(length = 60)
    private String detectorModel;
}
