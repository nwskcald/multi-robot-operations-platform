package com.fleetops.repository;

import com.fleetops.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface ProductionLineRepository 
    extends JpaRepository<ProductionLine, ProductionLineType>{
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductionLine p WHERE p.type = :type")
    Optional<ProductionLine> findByIdForUpdate(@Param("type") ProductionLineType type);
    }
