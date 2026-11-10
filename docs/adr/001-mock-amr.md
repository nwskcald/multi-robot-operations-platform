# ADR-001: Mock AMR 구현 방식

**Date:** 2026-11-10  
**Status:** Accepted

## Context

현재는 Robot의 실제 이동보다 작업 배정, 도착 보고, 상태 정합성을 검증하는 게 우선이다.

2D 시뮬레이터나 ROS2를 도입하면 구현 범위가 커지므로, 이동 과정은 단순화하기로 했다.

## Decision

- `travelSeconds`를 기준으로 이동 시간을 시뮬레이션한다.
- `ScheduledExecutorService`로 도착 보고를 예약한다.
- `HttpClient`로 Platform의 도착 보고 API를 호출한다.
- Platform에서 현재 Task와 Robot 상태를 검증한다.

## Trade-offs

- 실제 위치나 경로를 기반으로 도착 여부를 검증하지 못한다.
- 프로세스 재시작 시 예약된 작업이 사라진다.
- 현재는 데모 Runner에서 직접 실행하며, 작업 배정과 자동 연결되지는 않았다.
- 통신 실패에 대한 재시도 및 복구 처리가 없다.

추후 실제 이동 시뮬레이션과 로봇 연동, 통신 장애 복구 기능을 단계적으로 발전시킬 예정이다.