package com.fleetops.exception;

import com.fleetops.domain.*;

public class ProductionLineNotFoundException
    extends RuntimeException{

        public ProductionLineNotFoundException(ProductionLineType type){
            super("존재하지 않는 생산라인입니다: " + type);
        }
    }