# ADR-002 Mock AMR 연동 및 Fleet V1 자재 인계 검증 보고서

최종 판정: **PASS** (요청한 V1 범위). 전체 **47 PASS / 0 FAIL / 0 SKIP**.

검증일: 2026-11-14. 이번 작업은 자재 인계 흐름 완성 및 이에 필요한 배정·도착 회귀 검증에 한정한다. 개발자의 도메인 모델과 Service를 유지했고, 커밋·Push는 하지 않았다.

## 1. ADR-002 설계 결정 및 변경 이력

ADR 원문: `docs/adr/002-mock-amr-integration.md` (현재 Accepted).

최초 구조는 MockAmrRunner가 로봇 등록 → Task 생성 → 배정 → MockAmr 직접 생성 → 이동 예약을 수행했다. 일반 API 배정만으로는 시뮬레이션이 시작되지 않았고 Runner가 만든 스케줄러의 종료 관리도 없었다.

이전 ADR-002 리팩터링에서 다음을 적용했다.

- RobotCommandGateway / MockAmrGateway, TaskAssignedEvent / TaskAssignedListener를 도입했다. 이벤트는 엔티티 대신 robotId, taskId, travelSeconds 스냅샷을 담는다.
- TaskService가 트랜잭션 안에서 이벤트를 발행하고 AFTER_COMMIT 리스너만 외부 명령을 전달한다.
- Configuration이 ArrivalReporter와 MockAmr을 생성하고 destroyMethod=shutdown으로 예약 작업을 취소한다.
- Task 생성·배정 API와 로봇 등록 API를 추가했다. 기존 도착 POST API 및 HTTP 204 계약을 유지했다.
- Runner는 `mock-demo` 프로필에서만 동작하는 선택적 데이터 생성·배정 실행기로 변경했다. Kafka, 재시도, 별도 시뮬레이터 프로세스는 도입하지 않았다.
- 당시 미완성 인계 선언은 컴파일을 위해 TODO로 보존했다. 기존 RobotServiceTest의 미영속 Task ID 픽스처 오류를 수정했다.
- 당시 검증 기록은 전체 31개 중 20 PASS, 11 FAIL이었다. PostgreSQL 연결 거부 및 TEST_DB_PASSWORD 미설정으로 DB 전체 흐름을 검증하지 못했다. 이벤트 테스트는 테스트 트랜잭션 매니저, HTTP 테스트는 로컬 HTTP 서버를 사용했으므로 DB 정합성 검증을 대체하지 않았다.

이번 작업 시작 시 개발자가 인계 도메인·서비스·이벤트·Reporter를 이미 추가한 미커밋 상태였다. 그 변경을 보존하고 누락된 연결과 실제 결함을 보완했다. 이전 보고서의 스케줄러 2개 설명과 인계 TODO 설명은 현재 코드에 해당하지 않는다. 현재 스케줄러 크기는 개발자가 작성한 3개다.

## 2. 목적·범위 및 전체 실행 흐름

```text
POST /api/robots/{robotId}/tasks/{taskId}/handover → HTTP 204
 → RobotController → ProductionLineService.requestHandover (@Transactional)
 → Robot 잠금 → Task 잠금
 → Robot.currentTask 및 Task.robot 양방향 배정·Robot WAITING_FOR_HANDOVER·Task ASSIGNED 검증
 → ProductionLine PESSIMISTIC_WRITE 잠금
 → ProductionLine.occupy(robotId): AVAILABLE → OCCUPIED
 → Robot.startHandover(): WAITING_FOR_HANDOVER → HANDOVER
 → ProductionLineOccupiedEvent(robotId, handoverSeconds) 발행
 → DB COMMIT
 → ProductionLineOccupiedListener (AFTER_COMMIT)
 → RobotCommandGateway.startHandover → MockAmrGateway
 → MockAmr.simulateHandover → scheduler 지연 실행
 → HandoverCompletedReporter: POST /api/robots/{robotId}/handover-completion
 → RobotController → RobotService.completeHandover(robotId) (@Transactional)
 → Robot 잠금 → currentTask null 검사 → Task 잠금 → Task.robot 검증 → ProductionLine 잠금
 → Robot HANDOVER → RETURNING
 → Task ASSIGNED → COMPLETED
 → 점유 상태 및 점유 Robot ID 검증 후 ProductionLine OCCUPIED → AVAILABLE
 → occupyingRobotId = null → COMMIT → HTTP 204
```

완료 API는 robotId만 받는다. Task는 Robot.currentTask로 식별한다. RETURNING에서 IDLE로 돌아가는 복귀 흐름은 이번 범위가 아니다. Task 생성 전에 해당 ProductionLine이 DB에 존재해야 한다.

## 3. 최초 발견 문제와 수정 후 상태

|최초 발견|수정 후|
|---|---|
|MockAmrConfiguration이 기존 1인자 생성자를 호출해 main 컴파일 실패|HandoverCompletedReporter Bean 등록 및 2인자 생성자 주입|
|기존 MockAmrGatewayTest도 1인자 생성자 사용|기존 배송 검증을 유지하며 새 의존성 전달|
|인계 시작 Controller API 없음|RobotController에 시작 POST API 추가|
|release()가 Long을 `!=`로 비교: 값이 같아도 다른 객체면 거부|ID 값 비교로 수정, null 요청과 점유 상태 검사 유지|
|인계 시작은 Robot/Task 일반 조회, 완료는 Robot→Task 잠금, 배정은 Task→Robot 잠금|모든 관련 쓰기 경로의 순서를 Robot→Task→ProductionLine으로 정렬|
|시작은 currentTask만 비교, 완료는 Task.robot 확인 없음|시작·완료에서 역방향 배정 관계도 검증|
|인계 시뮬레이션 로그가 도착 보고로 표시|인계 완료 보고로 수정|
|currentTask null NPE 우려|최초 코드에 이미 명시적 IllegalStateException 방어가 있었음. 수정하지 않고 PostgreSQL 테스트로 확인|
|신규 테스트 첫 실행: 동일 트랜잭션에서 ProductionLine 삭제·재생성 시 EntityNotFoundException|테스트 준비의 삭제와 재생성을 별도 트랜잭션으로 분리. 운영 코드 결함과 구분|

핵심 비즈니스 규칙을 변경하거나 새 정책을 도입하지 않았다. 잠금 순서 수정은 기존 완료의 Robot 우선 순서와 배정의 역순 간 교착 가능성을 줄이기 위한 최소 수정이다.

## 4. 이번에 실제 수정한 파일

경로는 별도 표기가 없으면 `src/main/java/com/fleetops/` 기준이다. 아래는 이번 작업의 변경이며, 초기 작업 트리의 모든 변경을 이번 작업으로 주장하지 않는다.

|파일|변경|
|---|---|
|mockamr/MockAmrConfiguration.java|인계 Reporter Bean 및 MockAmr 생성자 연결, 기존 destroyMethod 유지|
|controller/RobotController.java|ProductionLineService 주입 및 인계 시작 API 추가, 불필요한 import 제거|
|domain/ProductionLine.java|release ID 값 비교, 상태 조회용 occupyingRobotId getter, 오류 메시지 오타|
|service/ProductionLineService.java|Robot·Task 비관적 잠금, Task.robot 검증, 조회 예외 구체화|
|service/RobotService.java|완료 시 Task.robot 검증, 공백 정리; null 방어 및 기존 상태 전이 보존|
|service/TaskService.java|배정 잠금 순서만 Robot→Task로 변경|
|mockamr/MockAmr.java|인계 성공·실패 로그 수정|
|mockamr/MockAmrGateway.java|기존 변경의 줄 끝 공백만 정리|
|src/test/java/com/fleetops/mockamr/MockAmrGatewayTest.java|새 생성자 호환, 기존 배송 HTTP 테스트 유지|
|src/test/java/com/fleetops/integration/RobotArrivalIntegrationTest.java|랜덤 포트 완료 Reporter 추가, 기존 배정·자동 도착 검증 뒤 실제 인계 시작·완료까지 확인|
|src/test/java/com/fleetops/integration/HandoverIntegrationTest.java (신규)|PostgreSQL 기반 13개 인계·롤백·경합·HTTP 테스트|
|src/test/java/com/fleetops/domain/ProductionLineTest.java (신규)|캐시 범위 밖 Long 값 비교 및 잘못된 release 검증 2개|
|src/test/java/com/fleetops/command/ProductionLineOccupiedListenerTest.java (신규)|커밋·롤백·무트랜잭션 이벤트 처리 1개|
|docs/refactoring/002-mock-amr-integration-review.md|기존 보고서를 최신 검증 결과로 덮어씀|

## 5. 수정하지 않은 부분과 이유

- MockAmrRunner: 이미 mock-demo 전용이며 MockAmr을 직접 생성하지 않는다. 서비스 호출만 하므로 새 생성자와 충돌하지 않는다. 별도 데모 실행은 하지 않았고 코드 경로·컴파일 및 Runner 없는 전체 흐름을 검증했다.
- HandoverCompletedReporter: URL·POST·204가 Controller와 일치한다. Bean 연결만 필요했다.
- ProductionLineOccupiedEvent/Listener: 이미 불변 값 이벤트 및 AFTER_COMMIT으로 구현되어 있다. 테스트로 확인하고 본문은 변경하지 않았다.
- Robot·Task 상태 전이, Robot.currentTask 유지: 개발자 V1 설계를 보존했다. 완료 후 currentTask를 지우거나 taskId를 완료 API에 추가하지 않았다.
- ArrivalReporter·기존 배송 스케줄링·scheduler 크기 3·shutdownNow 유지. Bean 간 순환 참조는 없으며 정상 Spring 컨텍스트/실제 Gateway 경로가 실행된다. 테스트용 Primary Reporter는 랜덤 포트 해석을 위한 테스트 범위 설정이다.
- build.gradle, .vscode/settings.json, RobotIntegrationTest 등 작업 전 사용자 변경은 보존했다. 기존 테스트를 삭제하거나 비활성화하지 않았다. 운영 DB 설정은 수정하지 않았다.

## 6. 테스트 시나리오 결과

최종 전체 47개가 통과했다. 관련 신규 테스트 16개도 별도 실행에서 통과했다. DB 테스트는 실제 PostgreSQL 16의 `fleetops_test`를 사용한다. Gateway mock만 쓰는 테스트에서도 상태·롤백 확인은 새 저장소 조회로 수행한다.

|시나리오|결과|검증 근거|
|---|---|---|
|1 정상 요청: OCCUPIED/HANDOVER|PASS|requestCommitsBeforeGateway, DB 조회 및 명령 횟수|
|2 해당 Robot 배정 아님|PASS|wrongRobotRejected, inconsistentTaskOwnerRejected|
|3 WAITING_FOR_HANDOVER 아님|PASS|wrongRobotStateRejected|
|4 이미 점유된 라인|PASS|occupiedLineRejected, 로봇 상태 보존|
|5 트랜잭션 롤백 시 Gateway 금지|PASS|rollbackDoesNotDispatchOrPersist: DB 상태 복원 및 명령 미호출|
|6 완료: RETURNING/COMPLETED/AVAILABLE, 점유자 null|PASS|completeAndDuplicatePolicy 및 실제 HTTP 흐름|
|7 currentTask null|PASS|noCurrentTaskIsDomainError: NPE 대신 구체적 IllegalStateException|
|8 다른 Robot 점유 라인 해제 거부|PASS|foreignOccupantRollsBackAllChanges: 앞선 Robot/Task 변경까지 DB 롤백|
|9 중복 완료 거부 정책|PASS|completeAndDuplicatePolicy: 두 번째 예외 및 최종 상태 보존|
|10 Reporter→Controller→Service 실제 HTTP|PASS|realHttpStartAndScheduledCompletion: 실제 예약·HTTP·DB; 중복 비204 응답도 Reporter가 거부|
|추가: 중복 시작/Task 상태 오류|PASS|duplicateStartRejected, unassignedTaskStatusRejected|
|추가: 동일 라인 동시 요청|PASS|두 스레드/별도 트랜잭션 중 하나만 성공, 패자는 대기 상태 보존, 명령 1회|
|추가: Long 1000 값 비교 및 null/미점유 release|PASS|ProductionLineTest 2개|
|추가: 커밋 전/롤백/무트랜잭션 이벤트|PASS|ProductionLineOccupiedListenerTest; 테스트 매니저 기반이며 DB 검증과 구분|
|기존 배송→도착→신규 인계 전체 실제 Bean 경로|PASS|RobotArrivalIntegrationTest, Gateway 대체 없이 실행|

## 7. 실행 명령과 실제 결과

- `git status --short`: 작업 전 이미 다수 수정 및 인계 클래스 4개 미추적 상태. 이를 유지했다.
- `find .. -name AGENTS.md -print`: 적용할 AGENTS.md 없음.
- 수정 전 `./gradlew test --console=plain`: Gradle 캐시 잠금 파일이 읽기 전용이라 실행 실패. 환경 권한 문제다.
- 권한 승인 후 `./gradlew test --offline --console=plain`: MockAmrConfiguration 생성자 인자 불일치로 compileJava 실패. 운영 코드 연결 버그이며 테스트는 실행되지 않음.
- `docker ps`: 최초 소켓 권한 거부, 승인 후 기존 `fleetops-postgres-test`(postgres:16, localhost:5432) 확인.
- `docker exec fleetops-postgres-test psql -U fleetops -d fleetops_test -c 'SELECT current_database();'`: fleetops_test 조회 성공.
- TEST_DB_PASSWORD는 최초 환경에 없었다. 기존 테스트 컨테이너의 POSTGRES_PASSWORD를 자식 테스트 프로세스의 TEST_DB_PASSWORD로만 전달했다. 인증정보는 출력·저장·운영 설정에 반영하지 않았다.
- 관련 테스트 명령: `./gradlew test --offline --console=plain --tests '*HandoverIntegrationTest' --tests '*ProductionLineTest' --tests '*ProductionLineOccupiedListenerTest'`.
  - 첫 실행: 14개 중 4 PASS / 10 FAIL. 신규 테스트 준비 코드의 JPA 삭제·재생성 오류였다. DB 연결 및 컴파일 실패가 아니다.
  - 픽스처 수정 및 추가 시나리오 후 재실행: **16개, 16 PASS / 0 FAIL / 0 SKIP**, BUILD SUCCESSFUL.
- 전체 테스트: `./gradlew test --offline --console=plain` (동일한 테스트 DB 인증 환경으로 실행). **47개, 47 PASS / 0 FAIL / 0 ERROR / 0 SKIP**, BUILD SUCCESSFUL (43초). PostgreSQL 인계 13개, 기존 DB 통합 10개, 컨텍스트 1개 및 나머지 단위/이벤트/로컬 HTTP 23개가 통과했다. Gradle XML 13개 파일의 수치를 합산했다.
- `git diff --check`: 관련 파일의 줄 끝 공백 정리 후 통과.

운영 설정을 테스트 DB 설정으로 바꾸지 않았다. 기존 test 프로필의 create-drop을 그대로 사용하므로 반드시 전용 테스트 DB에서 실행해야 한다. 테스트 실행에 Docker에서 가져온 인증정보를 사용한 것은 이번 환경에 한정한 실행 방법이며 코드에 컨테이너 의존성을 추가하지 않았다.

## 8. 트랜잭션·락·이벤트 판단

- 시작과 완료는 모두 Spring 프록시의 @Transactional 경계 안에서 실행되며 변경 감지로 저장된다. RuntimeException은 전체 변경을 롤백한다. 점유자 불일치가 마지막 release에서 발생해도 Robot/Task 변경이 DB에 남지 않는 것을 확인했다.
- 인계 시작·완료 및 배정은 Robot→Task→ProductionLine 순서(필요한 대상만)로 PESSIMISTIC_WRITE 잠금을 획득한다. 도착은 Robot만 잠근다. 기존 Task→Robot 역순을 제거했다.
- 두 로봇이 같은 라인을 동시에 요청하는 PostgreSQL 테스트에서 하나만 성공했다. 모든 미래 경로의 교착 부재나 부하 안정성까지 증명한 것은 아니다. 현재 단일 작업/단일 라인 경로의 순서를 검토하고 실제 경합 한 시나리오를 검증했다.
- 이벤트 발행은 트랜잭션 안, 명령 전달은 최외곽 커밋 성공 이후다. rollbackOnly 및 트랜잭션 없는 발행에서 명령이 없음을 확인했다. 이벤트는 엔티티 지연 로딩에 의존하지 않는다.
- AFTER_COMMIT 리스너는 동기적으로 예약만 수행한다. 지연 HTTP는 scheduler에서 실행되고 별도 요청의 트랜잭션에서 완료를 저장한다.
- mock 기반 이벤트 테스트만으로 DB 정합성을 주장하지 않는다. 별도 PostgreSQL 통합 테스트가 실제 커밋·롤백·경합과 DB 최종 상태를 확인한다.

## 9. 남은 위험 및 V2/V3 검토 사항

- 완료 API는 robotId만 사용한다. 현재 Task가 RETURNING/COMPLETED인 동안의 중복은 거부하지만, 향후 동일 로봇이 새 Task의 HANDOVER에 진입한 순간 오래된 완료 보고가 도착하면 새 Task를 잘못 완료할 수 있다. V1 계약을 유지하므로 명령 ID/세대 번호/작업 상관관계 검증은 추가하지 않았다.
- 중복 완료는 성공 처리하는 멱등 API가 아니다. 상태 오류는 IllegalStateException으로 거부되며 기존 전역 예외 매핑이 없어 HTTP 500으로 전달된다. Reporter는 204만 성공으로 인식한다. 업무 오류의 4xx 매핑·응답 표준은 별도 정책으로 검토할 사항이다.
- 커밋과 명령 전달은 원자적이지 않다. AFTER_COMMIT 이후 전송 실패, 프로세스 종료, scheduler 예약 실패, HTTP 실패는 DB 인계 시작을 되돌리지 못한다. 메모리 예약 유실·재시도·복구·outbox·중복 처리·Retry/DLQ는 후속 범위다.
- 동기 HttpClient에 명시적 타임아웃이 없다. 장기 HTTP 지연은 scheduler 스레드를 점유할 수 있다. shutdownNow는 취소/interrupt를 요청하지만 종료 완료 대기를 보장하지 않는다.
- 기본 URL은 localhost와 server.port(기본 8080)다. server.port=0, context path, 프록시 환경은 실제 callback URL을 mock-amr.base-url로 지정해야 한다. 테스트는 랜덤 포트용 Reporter를 사용한다.
- 복귀 완료·IDLE 복원은 구현하지 않았다. 커밋 이후 명령 전송 실패 복구나 비정상 DB 상태 자동 교정도 추가하지 않았다.
- mock-demo 실행 자체, 서버 재시작 복구, 장기 부하, 강제 DB 장애, 모든 잠금 조합은 NOT RUN이다.

## 10. 개발자 리뷰 코드 및 학습 포인트

1. `ProductionLineService.requestHandover`: 양방향 연결 검증, Robot→Task→라인 잠금 순서, 상태 변경과 이벤트 발행의 동일 트랜잭션 경계. TaskService 잠금 순서와 함께 리뷰한다.
2. `RobotService.completeHandover`: currentTask null 방어, Task.robot 검증, 마지막 release 실패가 앞선 변경까지 롤백되는 이유. 프록시 밖 직접 생성 호출은 트랜잭션 보장을 제공하지 않는다.
3. `ProductionLine.release`: Long 값 비교와 점유 상태·점유자 검증. ProductionLineOccupiedListener의 AFTER_COMMIT은 커밋 이후 실행만 보장하고 전송 보장을 제공하지 않는다는 점도 확인한다.

이번 작업은 커밋하지 않았으며 사용자 검토를 기다린다.
