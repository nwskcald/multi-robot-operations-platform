# V1 — Fleet Core & State Consistency

> **Project:** Cloud-native Multi-Robot Autonomy & Operations Platform  
> **Document:** Architecture — Confirmed Baseline  
> **Updated:** 2026-10-31  
> **Status:** 확정된 요구사항 및 운영 정책 정리. 상세 설계는 진행 중.

## 1. 프로젝트 개요

자동차 부품 스마트팩토리에서 AMR의 자재 운송 작업을 관리하는 Fleet Platform을 구현한다.

Client가 자재 운송을 요청하면 Platform은 Task를 생성하고, 가용 AMR에 배정한다. AMR이 목적지 생산라인에 도착하고 자재 인계가 완료되면 Task를 종료한다. AMR은 이후 준비 장소로 복귀하며, 복귀가 끝나기 전까지 새 Task를 배정받지 않는다.

V1의 중심은 로봇의 주행이 아니라 **Robot·Task·ProductionLine의 상태와 데이터 정합성**이다.

## 2. 시스템 구성과 역할

| 구성 요소 | 역할 |
|---|---|
| Client | Platform API를 통해 자재 운송 요청 |
| Fleet Platform | Task 생성·대기·배정·완료, Robot 및 생산라인 상태 관리 |
| Robot 도메인 | Platform 내부에서 AMR의 상태와 작업 배정 규칙 표현 |
| Mock AMR | 실제 AMR 대신 Heartbeat, 도착, 인계 시작·완료 등의 동작을 모의 보고하는 별도 프로그램 |
| Task | 자재를 목적지 생산라인으로 운송하고 인계를 완료하는 작업 |
| ProductionLine | 운송 목적지이며, 한 번에 하나의 인계만 점유 가능 |
| PostgreSQL | Platform의 업무 상태 영속 저장 |

- Platform은 **Java / Spring Boot / PostgreSQL**을 사용한다.
- Mock AMR과 Platform 내부의 `Robot` 도메인은 별개다. Mock AMR은 API로 보고하고, Platform이 업무 상태를 관리한다.
- V1의 인계 시작·완료 보고는 **Mock AMR이 모의 전송**한다. 실제 생산라인 장치가 인계 사실을 검증하는 구조는 아니다.

## 3. 운영 시나리오

### 3.1 정상 운송

1. Client가 자재 운송을 요청한다.
2. Platform이 Task를 생성한다.
3. 가용 AMR에 Task를 배정한다.
4. Mock AMR이 운송 동작을 모의하고 목적지 도착을 보고한다.
5. AMR 도착 시 해당 생산라인의 인계 점유가 시작된다.
6. Mock AMR이 인계 시작·완료를 보고한다.
7. Platform이 **동일 Task의 도착과 인계 완료**를 모두 확인하면 Task를 완료한다.
8. AMR은 준비 장소로 복귀한다. 복귀는 별도 Task가 아니라 Robot의 상태 전이다.
9. 복귀 완료 후 AMR은 다시 배정 가능해진다.

### 3.2 가용 AMR 부족

- 배정 가능한 AMR이 없으면 Task를 대기시킨다.
- 대기 Task는 **생성 순서에 따른 FIFO**로 배정한다.
- Task를 유실하거나 하나의 AMR에 활성 Task를 중복 배정하지 않는다.

### 3.3 동시 운송 요청

- 여러 Client 요청으로 각각의 Task를 생성한다.
- 동시 요청과 배정 중에도 Robot·Task의 중복 배정을 방지한다.

### 3.4 도착·인계 보고의 역순 수신

- 동일 Task의 인계 완료 보고가 도착 보고보다 먼저 수신되면, 유효성을 확인한 뒤 **인계 완료 사실을 기록하고 도착 보고를 기다린다**.
- 두 확인 사실이 모두 있어야 Task를 완료한다.
- 보고 수신 순서가 실제 물리적 인계 순서를 뜻하지는 않는다.

### 3.5 운송 중 통신 장애

- Mock AMR은 주기적으로 Platform에 Heartbeat를 전송한다.
- Platform은 Heartbeat Timeout으로 통신 이상을 감지한다.
- 통신 이상 AMR에는 신규 Task를 배정하지 않는다.
- 해당 AMR의 진행 중 Task는 보류한다. 다른 AMR에 즉시 재배정하지 않는다.
- 통신이 복구되더라도 Task를 자동 재개하지 않는다. **운영자 확인이 필요하다**.

### 3.6 복귀 중 통신 장애

- 준비 장소로 복귀 중 Heartbeat가 끊겨도 AMR을 통신 이상으로 처리하고 신규 배정을 금지한다.
- **이미 완료된 Task는 보류 상태로 되돌리지 않는다.**

## 4. 핵심 운영 규칙 및 불변식

| ID | 규칙 |
|---|---|
| INV-01 | 하나의 AMR은 동시에 최대 하나의 활성 Task만 수행한다. |
| INV-02 | 하나의 활성 Task는 동시에 최대 하나의 AMR에만 배정된다. |
| INV-03 | 하나의 생산라인은 동시에 최대 하나의 Task에 대한 인계만 점유한다. |
| INV-04 | 생산라인 점유는 배정 AMR의 **목적지 도착 시점**에 시작한다. |
| INV-05 | Task는 해당 Task의 **AMR 도착 확인과 인계 완료 확인**이 모두 있어야 완료한다. |
| INV-06 | Task 완료 후 준비 장소로 복귀 중인 AMR에는 새 Task를 배정하지 않는다. |
| INV-07 | 통신 이상 AMR에는 새 Task를 배정하지 않는다. |
| INV-08 | 통신 장애로 보류된 Task는 운영자 확인 없이 자동 재개하지 않는다. |
| INV-09 | 이미 완료된 Task는 AMR의 후속 복귀 장애 때문에 보류 상태로 되돌리지 않는다. |

## 5. V1 범위

### 포함

- Robot 등록·조회·상태 관리와 Task 생성·조회·배정·완료
- 생산라인 인계 점유 및 Task 완료 조건 검증
- 가용 AMR 부족 시 Task 대기 및 FIFO 배정
- Task·Robot·ProductionLine의 상태 정합성
- 트랜잭션, 중복 배정 방지, 동시성 검증
- Mock AMR의 HTTP API 기반 보고와 Heartbeat
- Heartbeat Timeout, 통신 장애 시 Task 보류, 운영자 확인 후 복구
- 정상·예외·동시성 테스트 및 API/정합성 기준선 측정

### 제외

- 실제 AMR 하드웨어 제어
- ROS2 / Nav2 / Gazebo 연동
- 지도, 경로 탐색, 충돌 회피, 2D·3D 주행 시뮬레이션
- 배터리·충전 관리
- 제조 공정 및 생산 스케줄 관리
- Kafka 기반 분산 이벤트 오케스트레이션

## 6. 검증 기준

- 허용되지 않은 상태 전이는 거절한다.
- 동시 요청에서도 Robot과 Task의 중복 배정이 발생하지 않아야 한다.
- 도착 보고 또는 인계 완료 보고 중 하나만으로 Task가 완료되지 않아야 한다.
- 장애·실패 처리 후에도 DB의 Robot·Task·ProductionLine 관계가 일관되어야 한다.
- 통신 이상 AMR에는 새 Task가 배정되지 않아야 한다.
- 주요 API 응답시간과 정합성 테스트 결과를 측정·기록한다.

## 7. 현재 미확정 사항

다음 항목은 **결정된 설계가 아니므로 이 문서에서 구현 방식을 정하지 않는다**.

- Robot·Task·ProductionLine의 구체적인 상태 Enum 및 DB 필드
- Mock AMR의 Task 수신 방식(Polling / Push)
- Heartbeat 주기와 Timeout 수치
- FIFO 구현 자료구조, 동시 생성 순서 식별 및 엄격한 순서 보장 방식
- 이벤트 중복 수신 시 구체적인 멱등성 처리 방식
- 생산라인 점유 해제 시점과 점유 중 AMR 장애 대응
- Heartbeat Timeout과 완료 요청이 경합할 때의 트랜잭션·잠금 방식
- 운영자 확인 API 및 보류 Task 재개·취소 조건
- 자재 종류, 생산라인 유형 및 실제 개수
- 하드웨어 장애의 V1 포함 여부 및 판정 방식

---

> 이 문서는 현재까지 합의한 V1 운영 규칙의 기준선이다. 상세 설계는 정상 운송 흐름부터 단계적으로 확정하고, 중요한 변경은 별도로 기록한다.
