# ADR-002: Mock AMR 명령 전달 구조 개선

- **Status:** Proposed
- **Date:** 2026-11-14
- **Related:** ADR-001 (Mock AMR 시뮬레이션 도입)

## Context

V1에서는 MockAmrRunner가 Robot 및 Task 생성, 작업 배정, Mock AMR 이동 시뮬레이션을 순서대로 실행한다.

Mock AMR은 일정 시간 후 HTTP API로 도착을 보고하고, Platform은 Robot 상태를 변경한다.

이 방식으로 도착 보고와 상태 전이를 검증했지만, 다음 한계가 발견되었다.

- 일반 API를 통한 Task 배정은 Mock AMR의 이동 실행으로 이어지지 않는다.
- 이동 시뮬레이션이 MockAmrRunner의 데모 시나리오에 의존한다.
- 자재 인계 및 복귀 명령을 추가할 때 동일한 연결 문제가 반복된다.
- Platform의 비즈니스 로직과 로봇 명령 전달 사이에 명확한 경계가 없다.

## Decision

RobotCommandGateway 인터페이스를 도입하여 Platform의 로봇 명령 전달 책임을 분리한다.

- Task 배정이 DB에 커밋된 이후 Gateway를 통해 이동 명령을 전달한다.
- V1에서는 Mock AMR 기반 Gateway 구현체를 사용한다.
- Mock AMR은 이동 완료 후 기존 HTTP API로 도착을 보고한다.
- MockAmrRunner는 필수 실행 경로에서 제외한다.
- Kafka, 메시지 재시도, 별도 시뮬레이터 프로세스는 이번 변경 범위에서 제외한다.

## Consequences

### 장점

- 일반적인 Task 배정도 실제 이동 시뮬레이션으로 연결된다.
- 자재 인계 및 복귀 명령에 동일한 구조를 재사용할 수 있다.
- 향후 실제 로봇 통신 구현체로 교체하기 쉬워진다.
- 플랫폼 비즈니스 로직과 로봇 통신 구현의 결합도가 감소한다.

### 단점 및 한계

- Gateway와 이벤트 처리 구성요소가 추가되어 구조가 복잡해진다.
- DB 커밋 후 명령 전달에 실패하면 상태 불일치가 발생할 수 있다.
- Mock AMR은 여전히 플랫폼 프로세스에 종속된다.
- 프로세스 종료 시 메모리에 예약된 시뮬레이션 작업이 유실될 수 있다.

## Verification

MockAmrRunner 없이 다음 흐름이 실행되어야 한다.

1. API를 통해 Task를 생성하고 Robot에 배정한다.
2. DB 커밋 이후 Mock AMR 이동 시뮬레이션이 시작된다.
3. travelSeconds 경과 후 Mock AMR이 도착 API를 호출한다.
4. Robot 상태가 DELIVERING에서 WAITING_FOR_HANDOVER로 변경된다.
5. Task 상태는 ASSIGNED로 유지된다.

## Deferred

- 명령 전달 실패 시 Retry 및 복구
- 메시지 기반 명령 전달
- 시뮬레이터 프로세스 분리
- 다중 로봇 명령 처리 및 경합 검증