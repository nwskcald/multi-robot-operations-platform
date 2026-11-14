# ADR-002 Mock AMR 연동 리팩터링 보고서

## 1. 변경 전 아키텍처와 문제점

`MockAmrRunner(mock-demo)`가 로봇 등록 → Task 생성 → 배정 → `new MockAmr(new ArrivalReporter(...))` → 이동 예약을 수행했다. TaskService의 배정만 호출하면 이동은 시작되지 않았다. Runner가 생성한 스케줄러도 Spring이 종료하지 않았다.

ADR-002는 Proposed 상태지만 이번 구현의 기준으로 적용했다. 수정 전 확인한 차이는 다음과 같다.

- 일반 API 배정이 이동으로 이어지지 않는 점은 ADR의 문제 진술과 일치했다.
- 저장소에는 도착 HTTP API만 있고 Task 생성·배정 API는 없었다. ADR의 일반 API 검증을 위해 최소 엔드포인트를 추가했다.
- 작업 트리에는 기존 미커밋 변경이 있었다. TaskService의 completeHandover, ProductionLineService의 requestHandover, ProductionLine의 occupied 및 release 관련 선언은 미완성으로 컴파일되지 않았다. TaskService의 본문 없는 선언을 제거하고, 나머지는 TODO 주석으로 보존했다. 인계·복귀 기능은 구현하지 않았다.

## 2. 변경 후 아키텍처 및 전체 호출 흐름

```text
POST /api/tasks/{taskId}/robots/{robotId}/assignment
  → TaskController → TaskService(@Transactional)
  → Task/Robot 잠금 조회 및 기존 도메인 상태 변경
  → TaskAssignedEvent 발행
  → DB 커밋 성공
  → TaskAssignedListener(AFTER_COMMIT)
  → RobotCommandGateway.deliver
  → MockAmrGateway → MockAmr.simulateDelivery
  → ScheduledExecutorService가 travelSeconds 후 실행
  → ArrivalReporter의 HttpClient POST
  → 기존 RobotController → RobotService.reportArrival
  → Robot: DELIVERING → WAITING_FOR_HANDOVER
     Task: ASSIGNED 유지
```

추가 API는 `POST /api/robots`(201, robotId), `POST /api/tasks`(JSON `{"productionLineType":"LINE_A"}`, 201, taskId), 위 배정 API(204)다. Task 생성 전 ProductionLine은 DB에 등록되어 있어야 한다. 기존 도착 API의 URL·204 응답과 도메인 규칙·조회 잠금은 유지했다. mock-demo 프로필 없이도 Gateway가 등록된다. Runner는 선택적 데모 데이터 생성·배정만 수행한다.

## 3. 생성·수정·삭제 파일과 책임

경로는 별도 표기가 없으면 `src/main/java/com/fleetops/` 기준이다.

|구분|파일|책임 및 변경|
|---|---|---|
|생성|command/RobotCommandGateway.java|로봇 이동 명령 전달 포트|
|생성|command/TaskAssignedEvent.java|robotId, taskId, travelSeconds 불변 스냅샷|
|생성|command/TaskAssignedListener.java|커밋 후 이벤트를 Gateway로 전달|
|생성|mockamr/MockAmrGateway.java|포트를 MockAmr 호출로 구현|
|생성|mockamr/MockAmrConfiguration.java|ArrivalReporter 및 MockAmr 빈 생성·종료 관리|
|생성|controller/TaskController.java|Task 생성 및 배정 HTTP API|
|수정|controller/RobotController.java|로봇 등록 HTTP API 추가|
|수정|service/TaskService.java|ApplicationEventPublisher 주입 및 배정 이벤트 발행, 미완성 선언 제거|
|수정|mockamr/MockAmrRunner.java|직접 MockAmr 생성·이동 호출 제거|
|수정|mockamr/MockAmr.java|종료 시 예약 취소 및 실행 스레드 interrupt 요청|
|수정|domain/ProductionLine.java|컴파일 불가능한 기존 미완성 선언을 TODO로 보존|
|수정|service/ProductionLineService.java|기존 미완성 인계 메서드 선언을 TODO로 보존|
|수정|src/test/java/com/fleetops/service/TaskServiceTest.java|배정 성공 이벤트 및 실패 시 미발행 검증|
|수정|src/test/java/com/fleetops/service/RobotServiceTest.java|미영속 Task의 null ID로 인한 기존 테스트 오류 수정: 테스트 ID 설정|
|수정|src/test/java/com/fleetops/integration/RobotArrivalIntegrationTest.java|직접 시뮬레이션 호출을 일반 생성·배정 API 흐름으로 교체; 랜덤 포트 콜백 설정|
|생성|src/test/java/com/fleetops/command/TaskAssignedListenerTest.java|Spring 트랜잭션 이벤트 커밋·롤백·트랜잭션 없는 발행 검증|
|생성|src/test/java/com/fleetops/mockamr/MockAmrGatewayTest.java|예약 지연 후 실제 로컬 HTTP POST 검증|
|생성|docs/refactoring/002-mock-amr-integration-review.md|본 보고서|

삭제한 파일은 없다. 기존 build.gradle, ArrivalReporter, 도메인 등의 다른 미커밋 변경을 되돌리지 않았다.

## 4. 사용한 Spring 기능과 이유

- `ApplicationEventPublisher`: TaskService가 MockAmr이나 Gateway 구현체를 알지 않고 배정 사실만 알린다.
- `@TransactionalEventListener(phase = AFTER_COMMIT)`: 정상 커밋 이후만 명령을 전달한다. 기본 fallbackExecution=false이므로 트랜잭션 밖 발행은 무시된다.
- `@Component`와 생성자 주입: 리스너는 인터페이스에 의존하고 Spring은 현재 단일 구현체인 MockAmrGateway를 연결한다.
- `@Configuration`, `@Bean(destroyMethod="shutdown")`: Spring 컨텍스트가 MockAmr 생명주기를 소유한다.
- `@Value`: `mock-amr.base-url`로 도착 보고 주소를 설정한다. 기본값은 localhost와 server.port(미설정 시 8080)이다.

별도 @Async는 사용하지 않는다. 커밋 후 리스너는 요청 스레드에서 예약만 수행하고, 실제 지연과 HTTP는 기존 스케줄러가 처리한다.

## 5. 트랜잭션과 이벤트 처리 순서

1. TaskService 프록시가 트랜잭션을 시작한다. 외부 트랜잭션이 있다면 참여한다.
2. Task와 Robot의 기존 규칙을 적용한 뒤 이벤트를 발행한다. 이벤트 발행 자체는 트랜잭션 안이다.
3. Spring은 이 이벤트의 리스너 실행을 커밋 이후로 미룬다. 발행 시점에는 이동 예약도 하지 않는다.
4. 실제 최외곽 트랜잭션 커밋이 성공한 뒤 리스너가 Gateway를 호출한다. 롤백되면 호출하지 않는다.
5. 스케줄러가 나중에 HTTP로 도착을 보고하고, 도착 API는 별도 트랜잭션으로 상태를 저장한다.

AFTER_COMMIT 시점에는 원 트랜잭션의 리소스 정리가 아직 남을 수 있지만 DB 커밋은 완료되어 있다. 리스너는 DB 변경이나 JPA 지연 로딩을 하지 않는다. 이벤트에는 엔티티 대신 트랜잭션 안에서 읽어 둔 값만 담는다.

## 6. Gateway 인터페이스와 구현체

RobotCommandGateway는 `deliver(robotId, taskId, travelSeconds)` 계약만 정의한다. TaskAssignedListener는 이 계약에만 의존한다. MockAmrGateway가 MockAmr에 위임하며, TaskService는 이벤트 타입과 Spring 이벤트 발행기에만 의존한다. 향후 구현체를 교체할 때 빈 선택도 정해야 한다. 여러 구현체를 동시에 등록하면 명확한 선택 없이 주입할 수 없다.

## 7. Mock AMR 생명주기와 스레드

Spring이 ArrivalReporter와 MockAmr을 애플리케이션당 하나씩 생성한다. MockAmr은 기존과 같이 크기 2의 ScheduledExecutorService를 소유한다. 명령마다 travelSeconds 이후 HTTP 도착 보고 작업을 예약한다. 실패는 기존 방식으로 로그에 출력하고 재시도하지 않는다.

컨텍스트 종료 시 destroyMethod가 shutdown을 호출한다. 내부 shutdownNow는 대기 예약을 취소하고 실행 중 작업에는 interrupt를 요청한다. 완료 대기를 보장하지는 않는다. 단순 shutdown은 미래 예약을 남길 수 있으므로 종료 시 취소하도록 바꿨다. 예약 작업과 진행 상황은 메모리에만 존재한다.

## 8. 테스트 방법과 실제 검증 결과

실행 환경은 Gradle 9.8.2 / Java toolchain 21이다.

- 최초 `bash gradlew test --offline`는 캐시 잠금 파일 쓰기 권한 때문에 실패했다. 권한 승인 후 재실행했다.
- 첫 컴파일은 기존 미완성 선언 때문에 실패했다. 위 표의 최소 문법 정리 후 main/test 컴파일이 성공했다.
- 최초 단위 검증은 20개 중 기존 RobotServiceTest 1개가 실패했다. DB에 저장하지 않은 Task의 ID가 null이어서 비교 중 NPE가 발생했다. 테스트 픽스처에 ID를 설정해 잘못된 Task ID 검증 의도를 복원했다. 운영 도착 로직은 바꾸지 않았다.
- 최종 `bash gradlew test --offline`: **31개 실행, 20개 통과, 11개 실패**. 도메인·서비스·새 이벤트·새 HTTP 테스트 20개는 통과했다.
- 11개 실패는 애플리케이션 컨텍스트 및 PostgreSQL 통합 테스트다. `localhost:5432` 연결 거부로 JDBC 메타데이터를 읽지 못해 Hibernate 초기화가 실패했고, 후속 테스트는 컨텍스트 실패 임계값으로 실행되지 않았다. TEST_DB_PASSWORD도 환경에 설정되지 않았다.
- 따라서 API 배정 → 실제 PostgreSQL 커밋 → 자동 도착 → DB 상태 변경 전체 흐름의 성공은 아직 검증하지 못했다. RobotArrivalIntegrationTest에 해당 검증을 작성했지만 성공했다고 간주하지 않는다.
- 새 이벤트 테스트는 DB 대신 Spring AbstractPlatformTransactionManager 테스트 구현을 사용한다. Spring 리스너의 커밋 전 미호출·커밋 후 호출·롤백 및 무트랜잭션 미호출을 검증하며, 실제 DB 커밋을 대체 검증하지 않는다.
- 새 HTTP 테스트는 실제 로컬 HTTP 서버를 열어 1초 예약 명령이 즉시 도착하지 않고 이후 정확한 경로에 POST하는지 검증한다. 플랫폼의 DB 상태 변경 검증은 아니다.
- `git diff --check` 통과. 커밋하지 않았다.

재검증하려면 localhost:5432에 fleetops_demo/fleetops_test와 fleetops 사용자를 준비하고 DB_PASSWORD/TEST_DB_PASSWORD를 설정한 뒤 `bash gradlew test --offline`를 실행한다. 테스트 설정은 create-drop이므로 테스트 전용 DB를 사용한다. RobotArrivalIntegrationTest는 mock-demo 없이 랜덤 포트로 실행되며 테스트 전용 ArrivalReporter 빈이 실제 서버 포트로 보고한다.

## 9. 남은 한계와 향후 개선

- 커밋 후 예약 실패나 HTTP 실패는 DB 배정을 되돌리지 않는다. 전송 보장·재시도·중복 방지·복구가 없고, 메모리 이벤트와 예약은 프로세스 종료 시 유실될 수 있다.
- AFTER_COMMIT은 커밋과 전송의 원자성을 보장하지 않는다. 향후 필요하면 outbox 등 별도 설계를 검토한다. Kafka, Retry/DLQ, 별도 프로세스는 이번 구현에 도입하지 않았다.
- 기본 스케줄러 스레드는 2개이며 동기 HTTP 호출이 길어지면 다른 도착 보고도 지연될 수 있다. HTTP 타임아웃·운영 모니터링·명령별 경합 검증은 추가 과제다.
- 운영 포트 변경은 server.port 또는 mock-amr.base-url로 반영한다. 랜덤 포트(server.port=0), 프록시, 별도 context path 환경에서는 실제 콜백 주소를 지정해야 한다. 테스트에서는 랜덤 포트용 빈을 별도로 사용한다.
- 기존 인계·복귀 관련 미완성 코드는 TODO 상태다. 이번에는 최초 배송 명령만 연결했다.
- 배정은 커밋되었어도 도착은 travelSeconds와 HTTP 처리 이후이므로 배정 API 응답 시점에 도착 상태를 기대하면 안 된다.
- 전체 DB 통합 검증은 DB 환경을 준비한 후 다시 수행해야 한다.
