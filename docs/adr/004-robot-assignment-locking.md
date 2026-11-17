# ADR-004: 단일 Robot 배정 정합성과 DB 잠금 전략

**Date:** 2026-11-17  
**Status:** Accepted  
**Related:** ADR-002 (Mock AMR 명령 전달 구조 개선), ADR-003 (작업별 콜백 식별 및 검증)

## Context

V1에서는 하나의 Robot이 동시에 하나의 Task만 수행할 수 있어야 한다.

Task 배정은 API 요청으로 실행되며, 서로 다른 요청이 동일한 IDLE Robot에 동시에 Task를 배정하려고 시도할 수 있다.

동시 요청에 대한 적절한 제어가 없다면 다음 문제가 발생할 수 있다.

- 서로 다른 Task A/B가 동일 Robot에 중복 배정될 수 있다.
- Robot의 `currentTask`와 Task의 `robot` 참조가 일치하지 않을 수 있다.
- 실패한 배정 요청의 Task 상태 변경이 DB에 남을 수 있다.
- 실제로 확정되지 않은 배정에 대해 운반 명령이 발생할 수 있다.

따라서 단일 Robot 배정 불변식과 DB 트랜잭션 정합성을 보장할 필요가 있다.

## Decision

PostgreSQL의 비관적 행 잠금과 Spring 트랜잭션을 사용한다.

- Service의 업무 처리 경계를 `@Transactional`로 관리한다.
- Repository의 `findByIdForUpdate`에서 `PESSIMISTIC_WRITE` 잠금을 사용한다.
- 필요한 엔티티는 Robot → Task → ProductionLine 순서로 잠금을 획득한다.
- Robot 배정 시 IDLE/ONLINE 상태와 Task의 배정 가능 여부를 검사한다.
- Task와 Robot의 상태 및 참조 관계를 하나의 DB 트랜잭션에서 변경한다.
- 상태 검사에 실패하면 예외를 발생시켜 트랜잭션을 롤백한다.
- 운반 명령은 DB 커밋 이후 `AFTER_COMMIT` 이벤트를 통해 Gateway로 전달한다.
- Redis 분산 락이나 별도의 동시성 제어 시스템은 V1에 도입하지 않는다.

## Consequences

### 장점

- 동일 Robot에 대한 배정 요청을 DB 행 잠금으로 직렬화할 수 있다.
- Robot이 이미 배정된 상태라면 후속 요청을 거부할 수 있다.
- 실패한 배정 요청의 Task 변경을 트랜잭션 롤백으로 되돌릴 수 있다.
- Robot과 Task의 참조 관계를 동일 트랜잭션에서 관리할 수 있다.
- DB 커밋 이전에는 외부 운반 명령을 전달하지 않는다.
- 단일 PostgreSQL 기반 V1 구조에서 추가적인 분산 락 없이 핵심 불변식을 보호할 수 있다.

### 단점 및 한계

- DB 행 잠금 대기로 인해 경쟁 요청의 응답시간이 증가할 수 있다.
- 여러 엔티티를 잠그는 경로에서는 일관된 잠금 순서를 유지해야 한다.
- 명시적인 잠금 timeout과 낙관적 버전 관리(`@Version`)는 도입하지 않았다.
- 정상적인 Service 경로 밖의 직접 DB 수정까지 자동으로 보호하지는 않는다.
- DB 트랜잭션은 외부 명령의 전달 성공이나 실행 완료를 보장하지 않는다.
- 다중 Robot 배정 최적화 및 분산 환경의 운영 정책은 V1 범위 밖이다.

## Verification

실제 PostgreSQL 기반 동시성 통합 테스트를 수행했다.

1. IDLE/ONLINE Robot 1개와 서로 다른 WAITING Task A/B를 준비한다.
2. 두 개의 독립 스레드와 DB 트랜잭션에서 동일 Robot 배정을 동시에 시도한다.
3. `pg_stat_activity`와 `pg_blocking_pids`로 실제 Robot 행 잠금 경합을 확인한다.
4. 먼저 배정에 성공한 트랜잭션을 커밋한 뒤 다른 트랜잭션의 결과를 확인한다.
5. 두 요청 중 정확히 하나만 성공하는지 확인한다.
6. 성공 Task가 ASSIGNED이며 Robot의 `currentTask`와 일치하는지 확인한다.
7. 실패 Task가 WAITING/robot null을 유지하는지 확인한다.
8. Robot이 DELIVERING 상태이며 중복 배정이 없는지 확인한다.
9. 성공한 배정의 운반 Gateway 명령만 정확히 한 번 발생하는지 확인한다.

**검증 테스트**

`SameRobotAssignmentIntegrationTest.concurrentAssignments_sameRobot_onlyOneCommitsAndDispatches`

**검증 결과**

- 성공한 배정: 1건
- 거부된 배정: 1건
- 실패 Task의 부분 변경: 없음
- Robot 최종 상태: DELIVERING
- 실패 Task 최종 상태: WAITING/robot null
- 외부 운반 명령: 1회
- 전체 회귀 테스트: 70 tests / 0 failures / 0 errors / 0 skipped
- 빌드 결과: BUILD SUCCESSFUL

## Deferred

- 여러 Robot 간 자동 작업 할당 및 최적화 정책
- Redis 기반 동시성 제어 및 분산 운영
- 명령 전달의 내구성·재시도·장애 복구
- 장기 부하 및 다양한 경합 조합에 대한 추가 검증
