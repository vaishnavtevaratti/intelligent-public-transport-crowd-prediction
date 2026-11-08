# Database schema (MySQL 8, transport_db)

Generated from the live database with `SHOW CREATE TABLE` on 2026-10-23. Tables are created/extended by Hibernate (`ddl-auto=update`, additive only) plus the idempotent step in `backend/.../config/SchemaMigrations.java` (adds `FULL` to `crowd_predictions.crowd_level`).

| Table | Purpose |
|---|---|
| users | accounts (BCrypt password hash, role USER/ADMIN) |
| routes, route_stops | routes and their ordered stops with coordinates |
| buses | buses: total passenger capacity **and** separate seat_count, live position |
| locations | position history (source SIMULATED or GPS), kept 24 h |
| crowd_predictions | ML predictions: model kind, horizon, probabilities, model version |
| sensors | cameras/sensors per bus; only a SHA-256 hash of the device key |
| passenger_count_observations | every sensor report / manual reconciliation and its effect on the onboard estimate; unique (sensor_id, sequence_number) |
| bus_occupancy | current onboard estimate per bus, seats, reconciliation flag |
| ml_models | model versions and evaluation metrics |

## users

```sql
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(255) NOT NULL,
  `name` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `role` enum('USER','ADMIN') NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## routes

```sql
CREATE TABLE `routes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(255) DEFAULT NULL,
  `destination` varchar(255) NOT NULL,
  `distance` double DEFAULT NULL,
  `source` varchar(255) NOT NULL,
  `demo_route` bit(1) DEFAULT NULL,
  `path_geometry` mediumtext,
  `path_source` varchar(120) DEFAULT NULL,
  `route_code` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

- `route_code`: short demo label (D1â€“D8), not an official route number.
- `demo_route`: 1 for the fictional Kolhapur academic demo routes.
- `path_geometry`: road geometry as JSON `[[lat, lng], â€¦]`, from OSRM / OpenStreetMap. It is cleared when an admin changes the stops; the map then draws dashed straight lines.
- `path_source`: where the geometry came from.
- The column was widened from TINYTEXT to MEDIUMTEXT by `SchemaMigrations`.

## route_stops

```sql
CREATE TABLE `route_stops` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `latitude` double NOT NULL,
  `longitude` double NOT NULL,
  `name` varchar(120) NOT NULL,
  `stop_order` int NOT NULL,
  `route_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_route_stops_route` (`route_id`,`stop_order`),
  CONSTRAINT `FK63y33daxb1qs5nbnkuicbpkej` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## buses

```sql
CREATE TABLE `buses` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bus_number` varchar(255) NOT NULL,
  `capacity` int DEFAULT NULL,
  `current_latitude` double DEFAULT NULL,
  `current_longitude` double DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `route_id` bigint DEFAULT NULL,
  `last_updated` datetime(6) DEFAULT NULL,
  `seat_count` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_f0wpejbtx1fk17hi1t6ba5vbv` (`bus_number`),
  KEY `FKryb7cf7s26rht8lyp8ry0ek0g` (`route_id`),
  CONSTRAINT `FKryb7cf7s26rht8lyp8ry0ek0g` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## locations

```sql
CREATE TABLE `locations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `latitude` double NOT NULL,
  `longitude` double NOT NULL,
  `timestamp` datetime(6) NOT NULL,
  `bus_id` bigint NOT NULL,
  `source` varchar(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_locations_bus_time` (`bus_id`,`timestamp`),
  KEY `idx_locations_time` (`timestamp`),
  CONSTRAINT `FK2t2ipkxug8jd7w2a3ks0qt9p3` FOREIGN KEY (`bus_id`) REFERENCES `buses` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## crowd_predictions

```sql
CREATE TABLE `crowd_predictions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `crowd_level` enum('LOW','MEDIUM','HIGH','FULL') NOT NULL,
  `timestamp` datetime(6) NOT NULL,
  `bus_id` bigint NOT NULL,
  `route_id` bigint NOT NULL,
  `confidence` double DEFAULT NULL,
  `model_version` varchar(80) DEFAULT NULL,
  `predicted_for` datetime(6) DEFAULT NULL,
  `probability_high` double DEFAULT NULL,
  `probability_low` double DEFAULT NULL,
  `probability_medium` double DEFAULT NULL,
  `source` varchar(16) DEFAULT NULL,
  `horizon_minutes` int DEFAULT NULL,
  `input_onboard` int DEFAULT NULL,
  `model_kind` varchar(20) DEFAULT NULL,
  `predicted_passengers` double DEFAULT NULL,
  `probability_full` double DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_crowd_time` (`timestamp`),
  KEY `idx_crowd_route_time` (`route_id`,`timestamp`),
  KEY `idx_crowd_bus_time` (`bus_id`,`timestamp`),
  CONSTRAINT `FKlohi38upxj0ayjk1k4494w374` FOREIGN KEY (`bus_id`) REFERENCES `buses` (`id`),
  CONSTRAINT `FKthrs6vrn9u5djg1a4lttb187o` FOREIGN KEY (`route_id`) REFERENCES `routes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## sensors

```sql
CREATE TABLE `sensors` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `api_key_hash` varchar(64) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `description` varchar(200) DEFAULT NULL,
  `detector_model` varchar(60) DEFAULT NULL,
  `enabled` bit(1) NOT NULL,
  `last_health` enum('UNKNOWN','OK','DEGRADED','CAMERA_UNAVAILABLE','INFERENCE_ERROR') DEFAULT NULL,
  `last_message` varchar(200) DEFAULT NULL,
  `last_mode` varchar(16) DEFAULT NULL,
  `last_seen_at` datetime(6) DEFAULT NULL,
  `last_sequence` bigint DEFAULT NULL,
  `sensor_code` varchar(40) NOT NULL,
  `type` enum('DOOR_CAMERA','CABIN_CAMERA','SEAT_SENSOR') NOT NULL,
  `bus_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_dd7b5fxu6ns8r34qjvghhubn9` (`sensor_code`),
  KEY `FKhafn9mv51dntc6t10l5jagj9t` (`bus_id`),
  CONSTRAINT `FKhafn9mv51dntc6t10l5jagj9t` FOREIGN KEY (`bus_id`) REFERENCES `buses` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## passenger_count_observations

```sql
CREATE TABLE `passenger_count_observations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `entries` int DEFAULT NULL,
  `exits` int DEFAULT NULL,
  `frame_quality` double DEFAULT NULL,
  `health` enum('UNKNOWN','OK','DEGRADED','CAMERA_UNAVAILABLE','INFERENCE_ERROR') DEFAULT NULL,
  `mean_confidence` double DEFAULT NULL,
  `note` varchar(255) DEFAULT NULL,
  `observed_at` datetime(6) NOT NULL,
  `occupied_seats` int DEFAULT NULL,
  `performed_by` varchar(120) DEFAULT NULL,
  `previous_onboard` int DEFAULT NULL,
  `received_at` datetime(6) NOT NULL,
  `sequence_number` bigint DEFAULT NULL,
  `source_mode` varchar(16) NOT NULL,
  `status` varchar(24) NOT NULL,
  `updated_onboard` int DEFAULT NULL,
  `visible_count` int DEFAULT NULL,
  `bus_id` bigint NOT NULL,
  `sensor_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_observation_sensor_sequence` (`sensor_id`,`sequence_number`),
  KEY `idx_observation_bus_time` (`bus_id`,`observed_at`),
  KEY `idx_observation_received` (`received_at`),
  CONSTRAINT `FK9m3j8u2mlqu4fy6pgsjf1ph8c` FOREIGN KEY (`sensor_id`) REFERENCES `sensors` (`id`),
  CONSTRAINT `FKcj7o3o2svgaff3p2iyj35pwt0` FOREIGN KEY (`bus_id`) REFERENCES `buses` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## bus_occupancy

```sql
CREATE TABLE `bus_occupancy` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `confidence` double DEFAULT NULL,
  `data_mode` varchar(16) DEFAULT NULL,
  `estimated_onboard` int DEFAULT NULL,
  `last_visible_at` datetime(6) DEFAULT NULL,
  `last_visible_count` int DEFAULT NULL,
  `needs_reconciliation` bit(1) NOT NULL,
  `occupied_seats` int DEFAULT NULL,
  `onboard_method` varchar(16) DEFAULT NULL,
  `reconciliation_reason` varchar(255) DEFAULT NULL,
  `seat_source` varchar(16) DEFAULT NULL,
  `seat_updated_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `bus_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_na7ni4ta6xu6peosf7not51en` (`bus_id`),
  CONSTRAINT `FK6hid8laej91h8j6gcywkkj3bg` FOREIGN KEY (`bus_id`) REFERENCES `buses` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```

## ml_models

```sql
CREATE TABLE `ml_models` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `algorithm` varchar(60) DEFAULT NULL,
  `dataset_source` varchar(120) DEFAULT NULL,
  `first_seen_at` datetime(6) NOT NULL,
  `horizon_minutes` int DEFAULT NULL,
  `last_seen_at` datetime(6) NOT NULL,
  `metrics_json` text,
  `model_kind` varchar(20) NOT NULL,
  `model_version` varchar(100) NOT NULL,
  `trained_at` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_4rfs9i7cb1ck26hhwu4wkbjuf` (`model_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
```
