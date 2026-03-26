# Axon Portfolio: Architecture Visualizations (Simplified)

이 문서는 포트폴리오의 각 기술적 도전 과제를 시각화한 Mermaid 다이어그램 모음입니다. 각 섹션은 핵심 아키텍처적 병목 현상과 그 해결책을 직관적으로 보여주며, 면접 시 기술적 설명의 근거로 사용됩니다.

---

### **1. Redis 기반 Lock-free 알고리즘 (Concurrency Optimization)**

#### **[BEFORE] DB Row Lock Contention (High Latency)**
```mermaid
sequenceDiagram
    autonumber
    participant App as entry-service
    participant DB as MySQL (Pessimistic Lock)
    
    Note over App, DB: [Problem] Thread Blocking & Connection Starvation
    App->>DB: SELECT ... FOR UPDATE (Row Lock)
    Note right of DB: High Row Contention! <br/>Wait in Queue...
    DB-->>App: OK (Wait time: 1,400ms)
    Note left of App: API Response Time: 1.4s+
```

#### **[AFTER] Redis Lua Atomic Execution (Low Latency)**
```mermaid
sequenceDiagram
    autonumber
    participant App as entry-service
    participant Redis as Redis (Atomic Engine)
    participant DB as MySQL
    
    rect rgb(230, 255, 230)
    Note over App, Redis: [Solution] Single-Threaded Atomic Validation
    App->>Redis: EVAL reservation.lua (UserId)
    Redis->>Redis: 1. SADD (Unique) <br/>2. INCR (Count) <br/>3. Check Limit
    Redis-->>App: OK (1ms)
    end
    
    Note over App, DB: Only successful reservations hit the DB
    App-)DB: INSERT entry (Async/Background)
```

---

### **2. 트랜잭션 전파 제어 및 예외 격리 (Transaction Strategy)**
```mermaid
sequenceDiagram
    autonumber
    participant C as Kafka Consumer
    participant E as EntryService
    participant S as UserSummaryService
    participant P as PurchaseService
    participant D as ProductService (Sync)

    Note over C, S: [Physical TX 1] Propagation: REQUIRED
    C->>E: upsertBatch()
    E-->>C: Entries Saved (OK)
    C->>S: recordPurchaseBatch()
    S-->>C: Summaries Updated (OK)
    Note over C, S: '물리적으로 묶어 커넥션 효율 및 정합성 확보'

    rect rgb(255, 243, 224)
    Note over C, P: [Physical TX 2] Propagation: REQUIRES_NEW
    C->>P: createPurchaseBatch()
    Note right of P: '독립 물리 트랜잭션 시작 (격리)'
    P-->>C: Purchase Records OK
    end

    Note over C, D: [Asynchronous] Deferred Flow
    C-)D: decreaseStock()
    Note right of D: 'Hot Spot 경합 방지를 위해<br/>트랜잭션 지연 후 사후 정산'
```

---

### **3. 지연 재고 동기화 전략 (Deferred Stock Sync)**
```mermaid
flowchart LR
    User(("User Request")) --> App["entry-service"] -->|"Fast INCR"| Redis[("Redis Counter")]
    Sched["StockSync Scheduler"] -->|"Interval Fetch"| Redis
    Sched -->|"Batch Update"| MySQL[("MySQL Master")]
    
    classDef success fill:#efe,stroke:#2a2,stroke-width:2px;
    class MySQL success;
    style Redis fill:#e1f5fe,stroke:#01579b;
```

---

### **4. 수집 시점 의도적 역정규화 (Denormalization Pipeline)**
```mermaid
flowchart LR
    SDK["JS SDK (Enrich)"] -->|"Enrich Context"| Entry["entry-service"]
    Entry -->|"Denormalized Event"| Kafka{{"Kafka"}}
    Kafka -->|"Kafka Connect"| ES[("'ES Flat Index'")]
    Marketer[Marketer] -->|"Instant Single Query"| ES
    
    classDef success fill:#efe,stroke:#2a2,stroke-width:2px;
    class ES success;
```

---

### **5. 비동기 배압 조절 및 서비스 분리 (Service Isolation)**

#### **[BEFORE] Monolithic / Synchronous Hit (Critical DB Risk)**
```mermaid
flowchart LR
    User(("Burst Traffic")) --> App["Monolithic Service"]
    App -->|"Blocking Call"| DB[("MySQL (Direct Hit)")]
    DB --- Risk["'High Connection Pressure!<br/>Single Point of Failure (SPOF)'"]
    style DB fill:#ffebee,stroke:#c62828,stroke-width:2px,stroke-dasharray: 5 5;
    style Risk fill:#fff3e0,stroke:#e65100,stroke-dasharray: 5 5;
```

#### **[AFTER] Asynchronous Back-pressure (Resource Protection)**
```mermaid
flowchart LR
    User(("Burst Traffic")) --> Entry["entry-service"]
    Entry -->|"Async / Buffer"| Kafka{{"Kafka (Shock Absorber)"}}
    subgraph Core_Isolated ["core-service (Isolated)"]
        Kafka -->|"Safe Drain"| Consumer["Consumer Service"]
        Consumer --> DB[("MySQL (Master)")]
    end
    
    classDef success fill:#efe,stroke:#2a2,stroke-width:2px;
    class Kafka,Consumer success;
```

---

### **6. Connection Storm 및 커널 튜닝 (Infrastructure Scaling)**

#### **[BEFORE] Connection Rejected (Kernel Limit)**
```mermaid
flowchart TD
    User["Burst Traffic"] -->|"TCP SYN"| NAT["Static NAT"]
    NAT -->|"Packet Drop"| NewQ["SYN Queue (128)"]
    style NewQ fill:#ffebee,stroke:#c62828,stroke-width:2px,stroke-dasharray: 5 5;
    NewQ -.-x Ingress["Ingress (Ignored)"]
```

#### **[AFTER] Tuned Networking Layer (Secure Accept)**
```mermaid
flowchart TD
    NAT["Static NAT"] -->|"Accept All"| NewQ["net.core.somaxconn (1024)"]
    NewQ --> Ingress["Ingress Nginx"]
    Ingress -->|"Keep-alive: 2000"| App["entry-service"]
    
    classDef success fill:#efe,stroke:#2a2,stroke-width:2px;
    class NewQ,Ingress success;
```

---

### **7. Function Calling 기반 AI 에이전트 (Tool Use Architecture)**
```mermaid
flowchart TD
    User(("User Query")) --> AI["FabriX AI Agent"]
    AI -->|"Parse Parameters"| Tool["Verified Dashboard API"]
    Tool -->|"Fact Dataset"| AI
    AI -->|"100% Verified Answer"| User
    
    classDef success fill:#efe,stroke:#2a2,stroke-width:2px;
    class Tool success;
```

---

### **8. Spring ApplicationEvent 기반 결합도 해소 (Domain Decoupling)**
```mermaid
flowchart LR
    Order["Order Logic"] -->|"Publish Event"| Hub{{"Event Hub"}}
    Hub -- "Success" --> L1["Log Service"]
    Hub -- "Success" --> L2["SMS Service"]
    Hub -- "Success" --> L3["Slack Alert"]
    style Order fill:#e1f5fe,stroke:#01579b;
```

---

### **9. 전략 패턴을 통한 비즈니스 확장성 확보 (Strategy Pattern)**
```mermaid
flowchart TD
    Consumer["CampaignActivityConsumerService"] -->|"Lookup"| Map["Map&lt;Type, Strategy&gt;"]
    Map -->|"Select"| Interface["BatchStrategy (Interface)"]
    Interface -->|"Implemented by"| FCFS["FirstComeFirstServeStrategy"]
    Interface -->|"Implemented by"| CP["CouponStrategy"]
    
    style Interface fill:#fff9c4,stroke:#fbc02d;
```

---

### **10. 보안 망 분리 및 Kafka 클러스터 구축 (Network Topology)**
```mermaid
flowchart TD
    subgraph AWS_VPC ["AWS VPC (Secure Network)"]
        direction TB
        subgraph Public_Subnet ["Public Subnet"]
            NAT["NAT Gateway"]
            IGW["Internet Gateway"]
        end
        subgraph Private_Subnet ["Private Subnet (Isolated)"]
            K1["Kafka Node 1"]
            K2["Kafka Node 2"]
            K3["Kafka Node 3"]
            ZK["ZooKeeper Quorum"]
        end
    end
    
    NAT -.->|"Forward"| Private_Subnet
    IGW <--> Public_Subnet
    
    style AWS_VPC fill:#f5f5f5,stroke:#333;
    style Private_Subnet fill:#e1f5fe,stroke:#01579b;
    style Public_Subnet fill:#fff3e0,stroke:#e65100;
```
