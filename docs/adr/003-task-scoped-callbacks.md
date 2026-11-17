# ADR-003: 작업별 콜백 식별 및 검증

**Date:** 2026-11-17  
**Status:** Accepted  
**Related:** ADR-001 (Mock AMR 구현 방식), ADR-002 (Mock AMR 명령 전달 구조 개선)

## Context

V1에서는 Robot이 Task를 완료하고 기지로 복귀한 뒤 동일 Robot에 새로운 Task를 배정할 수 있다.

Mock AMR은 일정 시간 후 HTTP 콜백으로 인계 완료와 기지 복귀 완료를 보고한다.

기존 구현은 콜백에서 `robotId`만 전달하고, Platform이 해당 Robot의 `currentTask`를 조회하여 처리했다.

이 방식에서는 다음 문제가 발생할 수 있었다.

- Task A의 오래된 인계 완료 콜백이 Task B의 HANDOVER 중 도착하면 B가 잘못 완료될 수 있다.
- Task A의 오래된 복귀 완료 콜백이 Task B의 RETURNING 중 도착하면 Robot이 조기에 IDLE로 변경될 수 있다.
- Robot의 현재 상태만 검사해서는 콜백이 실제로 어떤 Task에 속하는지 구별할 수 없다.
- 잘못된 완료 처리는 Task·Robot·ProductionLine의 상태 정합성과 후속 명령에 영향을 줄 수 있다.

## Decision

비동기 완료 콜백을 `robotId`뿐 아니라 `taskId`로 식별한다.

- Event → Listener → RobotCommandGateway → Mock AMR → Reporter → HTTP API 전 구간에 `taskId`를 전달한다.
- 인계 완료 API는 `POST /api/robots/{robotId}/tasks/{taskId}/handover-completion`을 사용한다.
- 기지 복귀 완료 API는 `POST /api/robots/{robotId}/tasks/{taskId}/arrival/base`를 사용한다.
- Service는 Robot을 잠근 뒤 `currentTask`와 요청 `taskId`가 일치하는지 확인한다.
- 일치하지 않으면 상태를 변경하기 전에 요청을 거부한다.
- 기존의 Robot 상태, Task 상태, 소유 관계 및 ProductionLine 점유 검증을 유지한다.
- `taskId`가 없는 이전 콜백 URL은 더 이상 지원하지 않는다.

## Consequences

### 장점

- 이전 Task의 지연 콜백이 새로운 Task에 잘못 적용되는 것을 방지한다.
- Robot 재사용 이후에도 콜백이 속한 Task를 명확히 식별할 수 있다.
- 잘못된 콜백으로 인한 조기 Task 완료, 라인 해제 및 Robot IDLE 전환을 차단한다.
- 콜백 처리의 안전성을 Robot 상태뿐 아니라 작업 식별 관계로 검증할 수 있다.

### 단점 및 한계

- Event, Gateway, Mock AMR, Reporter 및 API의 인터페이스를 함께 변경해야 한다.
- 기존 `robotId` 기반 콜백 URL과 호환되지 않는다.
- 현재는 `taskId`로 서로 다른 Task를 구별하지만, 동일 Task의 명령 재실행 세대까지 식별하지는 않는다.
- 통신 실패 후 재시도, 콜백 전달 보장 및 자동 복구는 제공하지 않는다.

## Verification

다음 시나리오를 실제 HTTP·PostgreSQL 통합 테스트로 검증했다.

1. 동일 Robot에서 Task A를 완료하고 Task B를 진행한다.
2. B가 HANDOVER 상태일 때 A의 오래된 인계 완료 콜백을 전송한다.
3. 콜백이 거부되고 Task·Robot·ProductionLine 상태가 유지되는지 확인한다.
4. B의 올바른 인계 완료 콜백은 정상 처리되는지 확인한다.
5. B가 RETURNING 상태일 때 A의 오래된 복귀 완료 콜백을 전송한다.
6. 콜백이 거부되고 Robot의 `currentTask`와 상태가 유지되는지 확인한다.
7. B의 올바른 복귀 완료 콜백으로 Robot이 IDLE이 되고 `currentTask`가 해제되는지 확인한다.
8. 잘못된 콜백으로 추가 Gateway 명령이 발생하지 않는지 확인한다.
9. 이전 콜백 URL이 404를 반환하는지 확인한다.

검증 테스트: `HandoverIntegrationTest.staleCompletionCallbacks_cannotChangeNextTaskOnSameRobot`

해당 회귀 테스트를 포함한 전체 70개 테스트가 통과했다.

## Deferred

- 동일 Task의 명령 재실행을 구분하기 위한 명령 ID 또는 실행 세대
- 콜백 전달 실패에 대한 Retry 및 복구
- 메시지 전달 내구성 및 재전달 정책
- 실제 AMR 연동 시 명령 수락·완료 의미의 구체화
