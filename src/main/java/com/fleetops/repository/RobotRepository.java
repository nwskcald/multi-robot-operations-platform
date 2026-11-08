package com.fleetops.repository;

import com.fleetops.domain.*;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RobotRepository extends JpaRepository<Robot, Long>{
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Robot r WHERE r.id = :id")
    Optional<Robot> findByIdForUpdate(@Param("id") Long id);
}