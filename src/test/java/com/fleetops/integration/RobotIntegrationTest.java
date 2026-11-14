package com.fleetops.integration;

import com.fleetops.repository.*;
import com.fleetops.service.*;
import com.fleetops.domain.*;

import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest 
@ActiveProfiles("test")
@Transactional 
class RobotIntegrationTest{

    @Autowired 
    private RobotRepository robotRepository;

    @Autowired
    private RobotService robotService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test 
    void registerRobot_success(){
        Robot registerRobot = robotService.registerRobot();

        assertNotNull(registerRobot.getId());
        assertEquals(RobotOperationStatus.IDLE, registerRobot.getOperationStatus());
        assertEquals(RobotConnectionStatus.ONLINE, registerRobot.getConnectionStatus());
        assertNull(registerRobot.getCurrentTask());

        entityManager.flush();
        entityManager.clear();

        Robot foundRobot = robotRepository.findById(registerRobot.getId())
            .orElseThrow();
        
        assertEquals(registerRobot.getId(), foundRobot.getId());
        assertEquals(RobotOperationStatus.IDLE, foundRobot.getOperationStatus());
        assertEquals(RobotConnectionStatus.ONLINE, foundRobot.getConnectionStatus());
        assertNull(foundRobot.getCurrentTask());
        assertNotSame(registerRobot, foundRobot);
    }
}