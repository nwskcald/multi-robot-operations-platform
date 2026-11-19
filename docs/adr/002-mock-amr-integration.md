# ADR-002: Mock AMR 명령 전달 구조 개선

**Date:** 2026-11-14  
**Status:** Accepted  
**Related:** ADR-001 (Mock AMR 구현 방식), ADR-003 (작업별 콜백 식별 및 검증), ADR-004 (단일 Robot 배정 정합성과 DB 잠금 전략)

## Context

초기 V1에서는 `MockAmrRunner`가 Robot 및 Task 생성, 배정, 이동 시뮬레이션을 순서대로 실행했다. Mock AMR은 일정 시간 후 HTTP로 생산라인 도착을 보고하고, Platform은 상태를 변경했다.

이 구조로 도착 보고를 검증할 수 있었지만 다음 문제가 있었다.

- 일반 API를 통한 Task 배정은 Mock AMR의 이동 실행으로 이어지지 않았다.
- 이동 시뮬레이션이 데모 Runner의 실행 흐름에 의존했다.
- 자재 인계 및 복귀 명령을 추가하면 같은 연결 문제가 반복될 수 있었다.
- Platform의 업무 상태 변경과 로봇 명령 전달 사이에 명확한 책임 경계가 부족했다.

## Decision

Platform의 로봇 명령 전달 책임을 `RobotCommandGateway` 인터페이스로 분리한다.

- `RobotCommandGateway`는 `deliver`, `startHandover`, `startReturn` 명령을 제공한다. 각 명령에 `robotId`, `taskId`, 해당 시뮬레이션 시간을 전달한다.
- V1에서는 `MockAmrGateway`가 동일 프로세스의 `MockAmr`을 호출하는 구현체 역할을 맡는다.
- Service는 업무 상태를 트랜잭션에서 변경하고 값 기반 Spring Event를 발행한다.
- `@TransactionalEventListener(phase = AFTER_COMMIT)` Listener가 DB 커밋 이후 Gateway 명령을 실행한다. 롤백된 업무에 대해서는 명령을 보내지 않는다.
- 작업 배정 이후 운반 명령이 발생하고, Mock AMR은 지연된 실제 HTTP 요청으로 생산라인 도착을 보고한다.
- 생산라인 도착만으로 인계를 시작하지 않는다. Client의 별도 인계 요청이 성공적으로 커밋되면 인계 명령을 전달한다.
- 인계 완료 보고가 처리되면 Task는 COMPLETED, Robot은 RETURNING, ProductionLine은 AVAILABLE로 전이한다. 커밋 이후 복귀 명령이 전달된다.
- 복귀 완료 HTTP 보고 후 Robot은 IDLE이 되고 `currentTask`가 해제된다. 완료 Task의 `robot` 이력은 유지한다.
- 완료·복귀 보고에는 `taskId`가 포함되며, Platform은 현재 Task와의 일치를 검증한다. 상세 안전성 결정은 ADR-003에 기록한다.
- `MockAmrRunner`는 필수 실행 경로에서 제외하고 `mock-demo` 프로필의 데모 구성으로 유지한다.
- Kafka, 메시지 재시도, 별도 시뮬레이터 프로세스는 V1에 도입하지 않는다.

## Consequences

### 장점

- 일반 REST API로 배정한 Task도 Mock AMR의 이동 시뮬레이션과 연결된다.
- 운반·인계·복귀에 동일한 Gateway 및 이벤트 경계를 적용할 수 있다.
- 비즈니스 트랜잭션이 실패하면 외부 명령 전달을 시작하지 않는다.
- Mock AMR과 별개로 향후 로봇 통신 구현을 교체하기 쉬운 구조가 된다.
- Client의 명시적 인계 요청과 Mock AMR의 완료 보고 책임이 구분된다.

### 단점 및 한계

- Gateway·Event·Listener 계층이 추가되어 구성요소가 늘어난다.
- `AFTER_COMMIT`은 DB 커밋과 외부 명령 실행을 원자적으로 묶지 않는다. 커밋 직후 프로세스가 종료되거나 명령 전달에 실패할 수 있다.
- 이벤트를 영속 저장하지 않으며 Outbox, 수신 확인, 자동 재시도·복구가 없다.
- Mock AMR과 예약 스케줄러는 여전히 Platform 프로세스에 종속된다.
- Reporter의 HTTP 요청에는 명시적 타임아웃이 없으며 실패 시 자동 재전송하지 않는다.
- 단일 Robot 중심 검증 결과만으로 실제 다중 Robot 운영의 처리량·장애 내성을 보장할 수 없다.

## Verification

기존 데모 Runner에 의존하지 않는 다음 API 기반 흐름을 통합 테스트로 확인했다.

1. Client가 Robot과 Task를 생성하고 특정 Robot에 Task를 배정한다.
2. 배정 트랜잭션 커밋 후 Listener가 Gateway를 통해 운반 명령을 실행한다.
3. Mock AMR이 생산라인 도착을 HTTP로 보고하고 Robot은 WAITING_FOR_HANDOVER가 된다. Task는 ASSIGNED, 라인은 AVAILABLE로 유지된다.
4. Client가 별도 인계 요청을 전송한다. 라인 점유·Robot HANDOVER 전이가 커밋된 뒤 인계 명령이 실행된다.
5. 인계 완료 HTTP 보고로 Task는 COMPLETED, 라인은 AVAILABLE, Robot은 RETURNING이 된다. 커밋 후 복귀 명령이 실행된다.
6. 기지 복귀 완료 HTTP 보고로 Robot이 IDLE/currentTask null이 되며 새 Task에 재사용 가능해진다.

**대표 검증**

- `FleetV1UseCaseIntegrationTest.realHttpCycle_andRobotReuse`: LINE_A/B/C 전체 사이클과 LINE_A Robot 재사용.
- `HandoverIntegrationTest.staleCompletionCallbacks_cannotChangeNextTaskOnSameRobot`: 과거 Task의 지연 완료·복귀 보고 거부, 현재 업무 상태 보호.
- `SameRobotAssignmentIntegrationTest.concurrentAssignments_sameRobot_onlyOneCommitsAndDispatches`: 동일 Robot 경합에서 한 배정만 커밋되고 Gateway 운반 명령이 한 번만 전달되는지 검증(동시성 상세는 ADR-004).
- 기존 회귀 테스트 실행 기록: **70 tests / 0 failures / 0 errors / 0 skipped**, BUILD SUCCESSFUL. 이 문서 개정으로 테스트를 새로 실행하지는 않았다.

## Deferred

- 명령·이벤트 전달의 내구성, Retry/DLQ 및 복구
- 시뮬레이터 프로세스 분리와 실제 로봇 통신 구현체
- 다중 Robot 자동 배정·운영 및 부하 검증