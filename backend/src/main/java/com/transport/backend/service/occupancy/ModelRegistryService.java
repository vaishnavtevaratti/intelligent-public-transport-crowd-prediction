package com.transport.backend.service.occupancy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transport.backend.dto.MlModelDto;
import com.transport.backend.entity.CrowdPrediction;
import com.transport.backend.entity.MlModel;
import com.transport.backend.exception.ServiceUnavailableException;
import com.transport.backend.repository.MlModelRepository;
import com.transport.backend.service.ml.CrowdModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Stores metadata and evaluation metrics of every ML model version the backend has used. */
@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);

    private final MlModelRepository repository;
    private final CrowdModelClient modelClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ModelRegistryService(MlModelRepository repository, CrowdModelClient modelClient) {
        this.repository = repository;
        this.modelClient = modelClient;
    }

    /** Fetches /model/info and /forecast/info from the ML service and records them. Never throws. */
    @Transactional
    public void syncFromMlService() {
        try {
            record(CrowdPrediction.KIND_TIME_ROUTE, modelClient.modelInfo());
        } catch (ServiceUnavailableException ex) {
            log.debug("Model registry: time-route model unavailable");
        }
        try {
            record(CrowdPrediction.KIND_LIVE_FORECAST, modelClient.forecastInfo());
        } catch (ServiceUnavailableException ex) {
            log.debug("Model registry: forecast model unavailable");
        }
    }

    @SuppressWarnings("unchecked")
    void record(String kind, Map<String, Object> info) {
        String version = String.valueOf(info.get("model_version"));
        LocalDateTime now = LocalDateTime.now();
        MlModel model = repository.findByModelVersion(version).orElseGet(() -> {
            log.info("Registered ML model {} ({})", version, kind);
            return MlModel.builder().modelVersion(version).modelKind(kind).firstSeenAt(now).build();
        });
        Map<String, Object> dataset = info.get("dataset") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        String algorithm = info.get("model_type") != null ? String.valueOf(info.get("model_type"))
                : info.get("test") instanceof Map<?, ?> t && t.get("classification") instanceof Map<?, ?> c
                ? String.valueOf(c.get("model")) : null;
        model.setAlgorithm(algorithm);
        model.setHorizonMinutes(info.get("horizon_minutes") instanceof Number n ? n.intValue() : 0);
        model.setTrainedAt(info.get("trained_at") != null ? String.valueOf(info.get("trained_at")) : null);
        String source = dataset.get("source") != null ? String.valueOf(dataset.get("source")) : null;
        model.setDatasetSource(source != null && source.length() > 120 ? source.substring(0, 120) : source);
        try {
            model.setMetricsJson(objectMapper.writeValueAsString(Map.of(
                    "test", info.getOrDefault("test", Map.of()),
                    "baseline_test", info.getOrDefault("baseline_test", Map.of()),
                    "dataset", dataset)));
        } catch (Exception e) {
            model.setMetricsJson(null);
        }
        model.setLastSeenAt(now);
        repository.save(model);
    }

    @Transactional(readOnly = true)
    public List<MlModelDto> list() {
        return repository.findAllByOrderByLastSeenAtDesc().stream().map(m -> MlModelDto.builder()
                .modelVersion(m.getModelVersion()).modelKind(m.getModelKind()).algorithm(m.getAlgorithm())
                .horizonMinutes(m.getHorizonMinutes()).trainedAt(m.getTrainedAt()).datasetSource(m.getDatasetSource())
                .metrics(parse(m.getMetricsJson())).firstSeenAt(m.getFirstSeenAt()).lastSeenAt(m.getLastSeenAt())
                .build()).toList();
    }

    private Map<String, Object> parse(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
