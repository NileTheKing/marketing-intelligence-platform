# Axon CDP K8s 인프라 구축 현황 보고서

> Status: legacy (archived 2026-10-21)
> 과거 K8s 배포 또는 Fluent 수집 전환 검토 기록이다. 본문의 현재형 표현과 실행 명령은 당시 기준이며, 배포 완료를 보증하지 않는다. 현재 운영은 Oracle VM + Docker Compose이며 Fluent Bit/Fluentd는 사용하지 않는다.
> 현재 관측: [로그·메트릭·트레이스 구성](../../operations/observability-correlation-v1-plan.md). 행동 로그 경로: [시스템 지도](../../architecture-map.md).


**작성일**: 2025-11-30
**환경**: KT Cloud Kubernetes (공모전 배포 테스트 단계)
**클러스터**: 마스터 1대 (2 vCPU, 4GB RAM) + 워커 2대 (각 4 vCPU, 16GB RAM)

---

## 📊 Executive Summary

### 배포 완료 현황
- ✅ **데이터 파이프라인**: Kafka (3 brokers), Kafka Connect, Elasticsearch
- ✅ **애플리케이션**: Core-Service (2 replicas), Entry-Service (2 replicas)
- ✅ **데이터 저장소**: Redis, MySQL (로컬 Docker)
- ✅ **모니터링 스택**: Prometheus, Grafana, Kibana
- ⏳ **로그 수집**: Fluent Bit (설정 완료, 배포 대기)

### 자원 사용률 (전체 클러스터)
- **총 자원**: 10 vCPU, 36GB RAM
- **워커 노드 자원**: 8 vCPU, 32GB RAM (애플리케이션 사용 가능)
- **사용 중 (Requests)**: 6.85 vCPU (86%), 11.2 GB (35%)
- **사용 중 (Limits)**: 7.625 vCPU (95%), 14 GB (44%)

### 주요 이슈
1. ⚠️ **Core/Entry Service**: 자원 제한 미설정 → 메모리 폭주 위험
2. ⚠️ **Worker02 CPU**: 108% 오버커밋 → 경합 발생 가능
3. ✅ **Spring Batch**: KT Cloud MySQL 호환성 해결 완료

---

## 🏗️ 인프라 아키텍처

### 데이터 파이프라인
```
┌─────────────────────────────────────────────────────────────┐
│  Browser (JS Tracker)                                        │
│       ↓                                                      │
│  Entry-Service (FCFS Logic)                                  │
│       ↓                                                      │
│  Kafka Topic: axon.event.raw                                 │
│       ↓                                                      │
│  Kafka Connect (Elasticsearch Sink)                          │
│       ↓                                                      │
│  Elasticsearch 8.15.0 (SingleNode)                           │
│       ↓                                                      │
│  Kibana (로그 분석 대시보드)                                  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  K8s Pods (로그) → Fluent Bit (미배포)                       │
│                         ↓                                    │
│                    Kafka Topic: k8s.logs (미생성)            │
│                         ↓                                    │
│                    Kafka Connect → Elasticsearch             │
└─────────────────────────────────────────────────────────────┘
```

### 모니터링 스택
```
┌────────────────────────────────────────────────┐
│  Prometheus (메트릭 수집)                       │
│       ↓                                        │
│  Grafana (메트릭 시각화)                        │
│  - CPU/Memory/Network 모니터링                 │
│  - Pod 상태 추적                                │
└────────────────────────────────────────────────┘
```

---

## 📈 자원 사용 현황 상세

### 전체 클러스터 자원
| 구분 | 총 용량 | 사용 중 (Requests) | 사용 중 (Limits) | 가용 |
|------|---------|-------------------|------------------|------|
| **CPU** | 10 vCPU | 6.85 vCPU (68%) | 7.625 vCPU (76%) | ~2.4 vCPU |
| **Memory** | 36 GB | 11.2 GB (31%) | 14 GB (39%) | ~22 GB |

> **Note**: Pod는 Kubernetes가 자동으로 워커 노드에 배치하므로, 특정 노드 배치는 유동적입니다.

### 노드별 자원 현황 (현재 시점 스냅샷)

#### Master01 (2 vCPU, 4GB RAM)
- **역할**: Control Plane (K8s 관리)
- **사용 중**: CPU 55%, Memory 6%
- **애플리케이션 Pod**: 없음 (시스템 Pod만)

#### Worker01 (4 vCPU, 16GB RAM)
- **사용 중**: CPU 66% (Requests), 108% (Limits - 오버커밋)
- **Memory**: 24% (Requests), 41% (Limits)
- **현재 배치된 주요 Pod**:
  - Kafka Controller × 2
  - Kafka Connect
  - Prometheus Server
  - Grafana
  - Kibana
  - Core/Entry Service 일부

#### Worker02 (4 vCPU, 16GB RAM)
- **사용 중**: CPU 77% (Requests), 81% (Limits)
- **Memory**: 44% (Requests), 47% (Limits)
- **현재 배치된 주요 Pod**:
  - Elasticsearch (2 vCPU, 6 GB - 가장 큼)
  - Kafka Controller × 1
  - Redis
  - Core/Entry Service 일부

> ⚠️ **중요**: Pod 재시작 또는 재배치 시 노드 간 위치가 변경될 수 있습니다. K8s Scheduler가 자원 상황에 따라 최적 배치를 자동으로 결정합니다.

---

## 🔧 배포된 컴포넌트 상세

### 1. Kafka 클러스터 (Bitnami Kafka 3.9.1)
- **구성**: KRaft 모드 (Zookeeper 없음)
- **Brokers**: 3개 (Controller 역할 겸함)
- **자원**: 각 750m CPU (limit), 1152Mi RAM (limit)
- **Topics**:
  - `axon.event.raw`: 비즈니스 이벤트 (활성)
  - `axon.campaign-activity.command`: FCFS 명령
  - `axon.campaign-activity.log`: 도메인 이벤트
  - `axon.user.login`: 사용자 로그인
  - `k8s.logs`: K8s 로그 (미생성, Fluent Bit 배포 시)

### 2. Kafka Connect (Confluent 7.9.0)
- **Plugins**: Elasticsearch Sink 15.0.0
- **Connectors**:
  - ✅ `elasticsearch-sink-behavior-events`: axon.event.raw → ES
  - ⏳ `elasticsearch-sink-k8s-logs`: k8s.logs → ES (설정 준비됨)
- **자원**: 500m CPU, 1.5 GB RAM
- **상태**: RUNNING (1/1 tasks)

### 3. Elasticsearch 8.15.0 (SingleNode)
- **자원**: 2 vCPU, 6 GB RAM
- **Heap**: 3 GB (JVM -Xms3g -Xmx3g)
- **스토리지**: 30 GB NFS (ktc-nfs-client)
- **보안**: 비활성화 (xpack.security=false)
- **Indices**:
  - `axon.event.raw`: 1 document (테스트 완료)
- **예상 처리량**: ~2000 docs/sec (NFS 환경)

### 4. Prometheus (Community Chart)
- **컴포넌트**:
  - Prometheus Server: 500m CPU, 1 GB RAM
  - Node Exporter × 3 (DaemonSet): 각 노드마다 배포
  - Kube State Metrics: 100m CPU, 128 MB RAM
  - Pushgateway: 자원 미설정 (사용 안 함)
- **스토리지**: 10 GB NFS
- **보관 기간**: 7일
- **NodePort**: 30090

### 5. Grafana (Community Chart)
- **자원**: 500m CPU, 512 MB RAM
- **스토리지**: 5 GB NFS
- **계정**: admin / axon-admin-2025
- **데이터소스**: Prometheus 자동 연결
- **대시보드** (자동 설치):
  - Kubernetes Cluster Monitoring (GrafanaID: 7249)
  - Node Exporter Full (GrafanaID: 1860)
  - Kubernetes Pods (GrafanaID: 6417)
- **NodePort**: 30300

### 6. Kibana 8.15.0
- **자원**: 500m CPU, 1 GB RAM
- **연결**: Elasticsearch (http://elasticsearch-master:9200)
- **보안**: 비활성화
- **NodePort**: 30561
- **Index Patterns**: `axon.event.raw` (설정 필요)

### 7. Redis (Bitnami)
- **자원**: 150m CPU, 192 MB RAM
- **용도**: FCFS 참가자 추적 (Sets), 캐싱
- **스토리지**: Persistent

### 8. Core-Service (2 replicas)
- **자원**: ⚠️ **미설정** (무제한)
- **노드**: Worker01, Worker02 분산
- **위험**: 메모리 폭주 시 노드 전체 영향

### 9. Entry-Service (2 replicas)
- **자원**: ⚠️ **미설정** (무제한)
- **노드**: Worker01, Worker02 분산
- **위험**: FCFS 스파이크 시 CPU 독점 가능

---

## ⚠️ 주요 이슈 및 권장 조치

### 1. Core/Entry Service 자원 제한 미설정 (P0 - 긴급)

**문제점:**
```yaml
core-service:
  resources: <none>  # 무제한!

entry-service:
  resources: <none>  # 무제한!
```

**위험:**
- FCFS 이벤트 시 메모리 폭주 → 노드 전체 OOMKilled
- CPU 독점으로 ES/Kafka 성능 저하

**권장 조치:**
```yaml
resources:
  requests:
    cpu: "500m"
    memory: "512Mi"
  limits:
    cpu: "1000m"
    memory: "1Gi"
```

**적용 방법:**
```bash
# Deployment 수정
kubectl edit deployment core-service
kubectl edit deployment entry-service
```

### 2. Worker01 CPU 오버커밋 (P1 - 중요)

**현황:**
- Limits: 4.35 vCPU / 4 vCPU = 108%
- 여러 Pod가 동시에 CPU 최대치 사용 시 경합 발생

**영향:**
- Prometheus/Grafana 쿼리 지연
- Kafka Connect 처리 속도 저하

**조치 옵션:**
1. **Pod 재배치**: Prometheus/Grafana를 Worker02로 이동
2. **Limits 조정**: 일부 Pod의 CPU Limit 감소
3. **허용**: 실제로는 모든 Pod가 동시 최대 사용하지 않음 (현재 권장)

### 3. Elasticsearch 단일 장애점 (P2 - 보통)

**현황:**
- SingleNode, Replication 없음
- ES 다운 시 → 전체 로그/대시보드 중단

**완화 조치 (이미 구현):**
- Kafka 버퍼링: ES 재시작 후 자동 복구
- 짧은 재시작 시간: K8s가 30초 내 자동 재시작

**장기 조치 (프로덕션 시):**
- ES 클러스터 구성 (3 nodes)
- Replication Factor: 2

### 4. Spring Batch 스키마 호환성 (✅ 해결됨)

**문제:**
- KT Cloud MySQL: `sql_require_primary_key=ON` 정책
- Spring Batch 기본 스키마: PRIMARY KEY 없음

**해결:**
- 커스텀 스키마 생성: `services/core-service/src/main/resources/org/springframework/batch/core/schema-mysql.sql`
- `BATCH_JOB_EXECUTION_PARAMS`에 복합 PRIMARY KEY 추가
- `BatchInitialTableConfig`: INFORMATION_SCHEMA로 테이블 존재 확인

---

## 🚀 배포 대기 중 컴포넌트

### Fluent Bit DaemonSet

**목적**: K8s 컨테이너 로그 수집 → Kafka → Elasticsearch

**설정 완료:**
- Values: `/Users/dem/Project/Axon/archive/k8s/helm/fluentbit-values.yaml`
- Output: Kafka (axon-kafka:9092)
- Topic: `k8s.logs`

**배포 대기 이유:**
- 팀 회의 필요: Kafka Topic 추가 생성 결정

**예상 자원 증가:**
- 노드당: 200m CPU, 512 MB RAM
- 총 (2 노드): 400m CPU, 1 GB RAM

**배포 후 추가 작업:**
1. Kafka Topic `k8s.logs` 생성
2. Kafka Connect Sink Connector 추가 (`elasticsearch-sink-k8s-logs`)
3. Kibana Index Pattern 추가

---

## 📊 성능 예측 및 병목 분석

### FCFS 이벤트 시나리오

**예상 트래픽:**
```
평상시:
  - 비즈니스 이벤트: 100-500 docs/sec
  - K8s 로그: 100-200 docs/sec

FCFS 스파이크 (10분):
  - 비즈니스 이벤트: 5,000 docs/sec
  - K8s 로그: 1,000 docs/sec
  - 총: 6,000 docs/sec
```

**처리 능력:**
```
Kafka:
  - 처리량: 50,000+ msgs/sec (충분)
  - 버퍼: 디스크 기반 (수 GB)

Elasticsearch (병목):
  - NFS 환경: ~2,000 docs/sec
  - 스파이크 시: 6,000 유입 → 4,000 큐 대기
  - 큐 처리 시간: 약 15-20분
```

**결론:**
- ✅ Kafka가 버퍼 역할 수행
- ✅ ES 다운 없이 안정적 처리
- ⚠️ 대시보드 최종 수치 확정: 이벤트 종료 후 15-20분 소요
- 💡 공모전 데모 시: "실시간 집계 중" 안내 가능

---

## 🔐 보안 및 접근 제어

### 외부 접근 포트 (NodePort)

| 서비스 | 포트 | 인증 | 공개 여부 |
|--------|------|------|----------|
| Prometheus | 30090 | 없음 | ⚠️ 내부 전용 권장 |
| Grafana | 30300 | admin/axon-admin-2025 | ✅ 데모용 공개 가능 |
| Kibana | 30561 | 없음 | ⚠️ 내부 전용 권장 |
| Elasticsearch | 미노출 | 비활성화 | ✅ ClusterIP만 |

### 보안 설정 현황

**비활성화 (개발/공모전 단계):**
- Elasticsearch xpack.security
- Kibana 인증
- Prometheus 인증

**프로덕션 전환 시 필수:**
1. ES/Kibana: Basic Auth 활성화
2. Prometheus: OAuth2 Proxy
3. Grafana: OAuth2 연동
4. TLS/HTTPS 적용

---

## 📝 운영 가이드

### 접속 정보

```bash
# Prometheus
http://<워커노드IP>:30090

# Grafana
http://<워커노드IP>:30300
계정: admin / axon-admin-2025

# Kibana
http://<워커노드IP>:30561
```

### 주요 모니터링 명령어

```bash
# 전체 Pod 상태
kubectl get pods -o wide

# 노드 자원 사용 (Metrics Server 필요)
kubectl top nodes
kubectl top pods

# Kafka Connect Connector 확인
kubectl exec kafka-connect-<POD> -- curl -s http://localhost:8083/connectors
kubectl exec kafka-connect-<POD> -- curl -s http://localhost:8083/connectors/elasticsearch-sink-behavior-events/status

# Elasticsearch 상태
kubectl exec elasticsearch-0 -- curl -s 'http://localhost:9200/_cluster/health?pretty'
kubectl exec elasticsearch-0 -- curl -s 'http://localhost:9200/_cat/indices?v'

# Kafka Topic 확인
kubectl run kafka-list-topics --rm -i --restart=Never \
  --image=confluentinc/cp-kafka:7.9.0 -- bash -c \
  "kafka-topics --bootstrap-server axon-kafka:9092 --list"
```

### 로그 확인

```bash
# 애플리케이션 로그
kubectl logs -f core-service-<POD>
kubectl logs -f entry-service-<POD>

# 인프라 로그
kubectl logs -f kafka-connect-<POD>
kubectl logs -f elasticsearch-0
kubectl logs -f kibana-<POD>

# Prometheus 로그
kubectl logs -f prometheus-server-<POD> -c prometheus-server
```

### 재시작

```bash
# Deployment 재시작
kubectl rollout restart deployment core-service
kubectl rollout restart deployment entry-service
kubectl rollout restart deployment kafka-connect
kubectl rollout restart deployment kibana

# StatefulSet 재시작 (순서대로 재시작됨)
kubectl rollout restart statefulset elasticsearch
kubectl rollout restart statefulset axon-kafka-controller
```

---

## 📋 체크리스트

### 배포 전 확인사항
- [x] Kafka 클러스터 정상 작동
- [x] Elasticsearch 정상 작동
- [x] Kafka Connect Sink Connector 설정
- [x] Prometheus/Grafana 설치
- [x] Kibana 설치
- [ ] Core/Entry Service 자원 제한 설정
- [ ] Fluent Bit 배포 (팀 결정 대기)

### 데모 전 확인사항
- [ ] Grafana 대시보드 커스터마이징
- [ ] Kibana Index Pattern 생성
- [ ] FCFS 테스트 이벤트 실행
- [ ] 대시보드 실시간 업데이트 확인
- [ ] 자원 사용률 모니터링 (Worker02 주의)

### 프로덕션 전환 시
- [ ] Elasticsearch 클러스터링 (3 nodes)
- [ ] 보안 설정 활성화 (TLS, Auth)
- [ ] 백업 전략 수립
- [ ] Alert 설정 (Prometheus + Grafana)
- [ ] 로그 보관 정책 (ES index lifecycle)
- [ ] 자원 제한 재검토 및 최적화

---

## 📚 관련 문서

### 배포 가이드
- Kafka Connect + ES Sink: `/Users/dem/Project/Axon/helm/FLUENT_BIT_DEPLOYMENT.md`
- Monitoring Stack: `/Users/dem/Project/Axon/helm/MONITORING_DEPLOYMENT.md`

### 설정 파일
- Kafka Connect: `/Users/dem/Project/Axon/archive/k8s/helm/kafka-connect.yaml`
- Elasticsearch: `/Users/dem/Project/Axon/services/core-service/k8s/elasticsearch.yaml`
- Prometheus: `/Users/dem/Project/Axon/archive/k8s/helm/prometheus-values.yaml`
- Grafana: `/Users/dem/Project/Axon/archive/k8s/helm/grafana-values.yaml`
- Kibana: `/Users/dem/Project/Axon/archive/k8s/helm/kibana.yaml`
- Fluent Bit: `/Users/dem/Project/Axon/archive/k8s/helm/fluentbit-values.yaml`

### 프로젝트 문서
- 성능 개선 계획: `docs/archive/performance-improvement-plan.md`
- 개발 로그: `docs/archive/development-log-2025-11.md`

---

## 💡 결론 및 다음 단계

### 현재 상태
✅ **핵심 인프라 배포 완료**
- 데이터 파이프라인: Kafka → Kafka Connect → Elasticsearch 정상 작동
- 모니터링 스택: Prometheus + Grafana 활성화
- 로그 분석: Kibana 접근 가능

⚠️ **개선 필요**
- Core/Entry Service 자원 제한 설정
- Worker01 CPU 오버커밋 모니터링
- Fluent Bit 배포 결정

### 단기 우선순위 (1주 내)
1. **P0**: Core/Entry Service 자원 제한 추가
2. **P1**: Grafana 커스텀 대시보드 작성 (FCFS 메트릭)
3. **P1**: Kibana Index Pattern 설정
4. **P2**: Fluent Bit 배포 여부 결정 및 실행

### 공모전 성공 기준
- ✅ FCFS 이벤트 안정적 처리 (Kafka 버퍼링)
- ✅ 실시간 대시보드 (Grafana + Kibana)
- ✅ 서버 다운 없이 데모 완료
- ⚠️ 15-20분 데이터 지연 허용 (설명 가능)

**종합 평가**: 공모전 데모에 필요한 인프라는 충분히 구축되었으며, 자원 제한 설정 및 모니터링 강화를 통해 안정성을 확보할 수 있습니다.

---

**작성자**: Claude Code
**최종 업데이트**: 2025-11-30 03:50 KST
