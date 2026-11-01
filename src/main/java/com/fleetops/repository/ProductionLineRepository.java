package com.fleetops.repository;

import com.fleetops.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionLineRepository 
    extends JpaRepository<ProductionLine, ProductionLineType>{
        
    }