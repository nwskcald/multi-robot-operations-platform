/* 

# V1 정상 운송 시나리오

## 1-1. Task 생성 (TaskService)

1. Platform이 API 요청을 받아 Task를 생성한다.
2. Task의 초기 상태를 설정한다.
   - `id`: DB에서 자동 생성
   - `status`: WAITING
   - `productionLine`: API 요청으로 지정
   - `robotId`: null
3. TaskRepository를 통해 PostgreSQL에 저장한다.
4. WAITING 상태의 Task들이 논리적인 TaskQueue를 구성한다.

## 1-2. Robot 등록 (RobotService)

1. Platform이 API 요청을 받아 Robot을 등록한다.
2. Robot의 초기상태를 설정한다.
   - id : Db에서 자동생성
   - operationStatus: IDLE
   - connectionStatus: ONLINE
   - task: null
3. RobotRepository를 통해 PostgreSQL에 저장한다.

## 2. Robot에게 Task 배정 (TaskService)

1. TaskScheduler가 일정 주기로 TaskRepository에서 WAITING Task를 FIFO 순서로 조회한다.
2. 작업 가능한 IDLE Robot을 조회한다.
3. Task와 Robot이 모두 존재하면 배정을 시도한다.
4. TaskService가 하나의 트랜잭션 안에서 각각의 도메인 메서드를 호출한다.
   - `Task.assignTo(robot)`
   - `Robot.startDelivery(task)`
5. Task와 Robot이 상대방의 PK를 참조한다.
6. 배정 성공 후 Platform이 Mock AMR에 운송 시작 명령을 전달한다.

**불변식**
- 하나의 Task는 동시에 하나의 Robot에만 배정된다.
- 하나의 Robot은 동시에 하나의 활성 Task만 수행한다.

## 3. Robot의 Task 수행 (RobotService)

### 3-1. 목적지 이동

1. Mock AMR이 ProductionLine의 `travelSeconds`를 기준으로 이동을 시뮬레이션한다.
2. 이동 중 RobotStatus는 DELIVERING을 유지한다.
3. Mock AMR이 목적지 도착을 Platform에 보고한다.
4. Platform은 보고를 검증하고 RobotStatus를 WAITING_FOR_HANDOVER로 변경한다.

### 3-2. 자재 인계

1. Platform이 목적지 ProductionLine의 점유 상태를 확인한다.
2. 점유 가능하면 원자적으로 점유 상태를 변경한다.
3. 점유 중이면 Robot은 WAITING_FOR_HANDOVER 상태로 대기한다.
4. 점유에 성공하면 Platform이 Mock AMR에 자재 인계를 승인한다.
5. Mock AMR은 ProductionLine의 `handoverSeconds`만큼 인계 작업을 시뮬레이션한다.

**불변식**
- 하나의 ProductionLine에서는 동시에 하나의 Robot만 인계 작업을 수행할 수 있다.

### 3-3. 자재 인계 완료

1. Mock AMR이 인계 완료를 Platform에 보고한다.
2. Platform이 Task와 Robot의 상태 및 보고 내용을 검증한다.
3. TaskStatus를 COMPLETED로 변경한다.
4. ProductionLine의 점유를 해제한다.
5. RobotStatus를 RETURNING으로 변경한다.
6. Platform이 Mock AMR에 복귀 명령을 전달한다.

### 3-4. Robot 복귀

1. Mock AMR이 동일한 `travelSeconds`를 기준으로 복귀를 시뮬레이션한다.
2. Mock AMR이 복귀 완료를 Platform에 보고한다.
3. Platform이 보고를 검증하고 RobotStatus를 IDLE로 변경한다.
4. Robot의 `currentTaskId`를 null로 초기화한다.
5. Task의 `robotId`는 운송 이력으로 유지한다.

## 4. 완료 Task 관리

1. 완료된 Task는 PostgreSQL에 그대로 보관한다.
2. TaskScheduler는 WAITING 상태의 Task만 조회한다.
3. 완료된 Task는 운송 이력 및 KPI 측정에 활용한다.
4. 별도 이력 테이블이나 데이터 보관 정책은 V1 초기 범위에서 제외한다.

## 5. 추후 설계할 항목

- Platform과 Mock AMR 간 작업 명령 전달 방식
- 동시 배정 방지 및 생산라인 점유 동시성 제어
- 중복 이벤트 처리와 통신 장애 복구
- Task 상태 전이 및 예외 처리 세부 규칙

/*
Robot
-id(pk)
-RobotStatus(IDLE, DELIVERING, WAITING_FOR_HANDOVER, HANDOVER, RETURNING)
-ConnectionStatus(ONLINE, OFFLINE)
-Task(Null 허용)

Task
-id(pk)
-TaskStatus(WAITING, ASSIGNED, COMPLETED)
-ProductionLine
-Robot(Null 허용)

ProductionLine
-ProductionLineType(pk)
-ProductionLineStatus(AVAILABLE, OCCUPIED)
-travelSeconds
-handoverSeconds

*/