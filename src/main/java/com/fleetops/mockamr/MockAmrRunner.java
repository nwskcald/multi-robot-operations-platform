package com.fleetops.mockamr;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fleetops.domain.ProductionLine;
import com.fleetops.domain.ProductionLineType;
import com.fleetops.domain.Robot;
import com.fleetops.domain.Task;
import com.fleetops.repository.ProductionLineRepository;
import com.fleetops.service.RobotService;
import com.fleetops.service.TaskService;

@Component
@Profile("mock-demo")
public class MockAmrRunner implements ApplicationRunner {

    private final RobotService robotService;
    private final TaskService taskService;
    private final ProductionLineRepository lineRepository;

    public MockAmrRunner(
            RobotService robotService,
            TaskService taskService,
            ProductionLineRepository lineRepository
    ) {
        this.robotService = robotService;
        this.taskService = taskService;
        this.lineRepository = lineRepository;
    }

    @Override
    public void run(ApplicationArguments args) {

        ProductionLineType type = ProductionLineType.LINE_A;

        lineRepository.findById(type)
            .orElseGet(() ->
                lineRepository.save(new ProductionLine(type))
            );

        Robot robot = robotService.registerRobot();
        Task task = taskService.createTask(type);

        taskService.assignTaskToRobot(
            task.getId(),
            robot.getId()
        );

        int travelSeconds = type.getTravelSeconds();

        System.out.println(
            "[DEMO] Robot ID = " + robot.getId()
            + ", Task ID = " + task.getId()
            + ", Travel = " + travelSeconds + "s"
        );
    }
}