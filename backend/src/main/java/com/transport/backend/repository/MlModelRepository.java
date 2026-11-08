package com.transport.backend.repository;

import com.transport.backend.entity.MlModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MlModelRepository extends JpaRepository<MlModel, Long> {

    Optional<MlModel> findByModelVersion(String modelVersion);

    List<MlModel> findAllByOrderByLastSeenAtDesc();
}
