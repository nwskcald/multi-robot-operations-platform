package com.fleetops.repository;

import com.fleetops.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RobotRepository extends JpaRepository<Robot, Long>{
    
}