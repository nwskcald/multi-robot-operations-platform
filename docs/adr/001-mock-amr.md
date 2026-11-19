# ADR-001: Mock AMR 구현 방식

**Date:** 2026-11-10  
**Status:** Accepted  
**Related:** ADR-002 (Mock AMR 명령 전달 구조 개선), ADR-003 (작업별 콜백 식별 및 검증)

## Context

V1에서는 실제 Robot의 주행보다 작업 배정, 도착·인계·복귀 보고, 그리고 Robot·Task·ProductionLine 상태 정합성을 검증하는 것이 우선이다.

2D 경로 탐색이나 ROS2 기반 시뮬레이터를 먼저 도입하면 V1의 구현·검증 범위가 커진다. 따라서 실제 위치·센서·주행 경로 대신 정해진 시간 경과와 HTTP 콜백으로 Robot의 동작을 모사하기로 했다.

## Decision

- `ProductionLineType`에 정의된 `travelSeconds`와 `handoverSeconds`를 사용해 이동 및 인계에 걸리는 시간을 모사한다. 복귀에도 `travelSeconds`를 사용한다.
- 같은 Spring Boot 프로세스의 `MockAmr`이 `ScheduledExecutorService`로 생산라인 도착, 인계 완료, 기지 복귀 보고를 예약한다.
- 각 Reporter는 Java `HttpClient`로 Platform의 실제 HTTP API를 호출한다. 완료·복귀 콜백은 `robotId`와 `taskId`를 함께 전달한다.
- Platform은 각 HTTP 요청을 별도 업무 처리로 받아 현재 Task, Robot 상태와 소유 관계 등을 검증한 뒤 DB 상태를 변경한다.
- Mock AMR은 실행 중 상태의 근거가 아니라 외부 Robot 통신을 흉내 내는 구성요소로 둔다. 최종 업무 상태는 Platform의 도메인과 PostgreSQL에서 관리한다.
- 일반 API의 작업 배정·인계 요청과 Mock AMR 명령 사이 연결은 `RobotCommandGateway` 및 커밋 후 이벤트로 처리한다(ADR-002).
- `MockAmrRunner`는 `mock-demo` 프로필에서만 초기 LINE_A·Robot·Task 준비와 배정을 수행하는 데모 진입점이다. 기본 실행이나 일반 API 흐름의 필수 구성요소는 아니다.

## Trade-offs

### 장점

- 실제 로봇 하드웨어나 별도 시뮬레이터 없이 운반 → 인계 → 복귀 사이클을 HTTP 경계까지 검증할 수 있다.
- 지연 보고를 통해 상태 전이, Robot 재사용, 잘못된 콜백 거부를 재현할 수 있다.
- 모의 통신 구현을 Platform의 업무 규칙과 분리하여 후속 연동의 출발점으로 활용할 수 있다.

### 단점 및 한계

- 설정된 시간만 경과할 뿐 실제 좌표, 경로, 충돌, 이동 속도, 센서, 배터리 상태를 반영하지 않는다.
- Mock AMR 스케줄러는 메모리 기반이므로 프로세스 종료·재시작 시 예약 작업이 유실될 수 있다.
- Reporter의 HTTP 호출에는 명시적인 연결·요청 타임아웃이 없고, 실패 보고의 자동 재시도·복구도 없다.
- Mock AMR은 Platform과 동일 프로세스에서 실행되므로 실제 Robot 네트워크 및 독립 장애를 재현하지 못한다.
- 지연 콜백의 작업 식별은 `taskId` 기준이며, 동일 Task의 명령 재실행 세대를 별도로 식별하지는 않는다(ADR-003).

## Verification

- `FleetV1UseCaseIntegrationTest.realHttpCycle_andRobotReuse`: 실제 HTTP Reporter와 PostgreSQL을 거쳐 LINE_A/B/C의 운반·인계·복귀, LINE_A Robot 재사용 및 최종 상태를 확인한다.
- `HandoverIntegrationTest.staleCompletionCallbacks_cannotChangeNextTaskOnSameRobot`: 이전 Task의 늦은 완료 콜백이 다음 Task에 적용되지 않는지 확인한다.
- 기존 V1 회귀 테스트 실행 기록: **70 tests / 0 failures / 0 errors / 0 skipped**, BUILD SUCCESSFUL. 이 문서 개정으로 테스트를 새로 실행한 것은 아니다.

## Deferred

- 2D 위치·경로 및 다중 Robot 시뮬레이션
- ROS2/Gazebo 및 실제 AMR 연동
- 통신 타임아웃·재시도·복구와 예약의 내구성 확보
