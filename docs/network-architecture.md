# 🌐 Axon Cloud Network Architecture (KT Cloud K2P)

## 1. External Access Flow (NodePort)
외부 사용자가 도메인(`axon.com`, `nip.io`)을 통해 서비스에 접속하는 경로입니다.

### 📍 Key Components
*   **Public IP:** `210.104.76.202` (Static NAT to Worker 02)
*   **Access URL:** `http://210.104.76.202.nip.io:30080/`
*   **Ingress Controller:** Nginx (NodePort Mode)

### 🌊 Traffic Flow
1.  **User Request**
    *   User accesses `http://210.104.76.202.nip.io:30080/`
    *   DNS resolves to `210.104.76.202`.

2.  **KT Cloud Network (Static NAT)**
    *   **Static NAT:** Public IP `210.104.76.202` is mapped 1:1 to Worker Node 02 (`172.25.0.172`).
    *   **Firewall:** Rule #93 allows TCP traffic on port `30080`.
    *   Traffic is forwarded directly to Worker Node 02's `eth0` interface on port `30080`.

3.  **Worker Node 02 (K8s Service)**
    *   `kube-proxy` listens on port `30080`.
    *   It forwards the packet to the **Ingress Controller Service**.
    *   **Load Balancing:** Due to `externalTrafficPolicy: Cluster`, traffic is distributed to Ingress Pods running on both `worker01` and `worker02`.

4.  **Ingress Controller (Nginx)**
    *   Nginx receives the request.
    *   **Routing:** Based on the path (`/entry`, `/api`), it proxies the request to the corresponding backend ClusterIP Service (`entry-service`, `core-service`).

---

## 2. Network Diagram

```mermaid
graph TD
    User((User))
    
    subgraph "KT Cloud Zone (External)"
        Firewall["Firewall<br/>Allow Port 30080"]
        NAT["Static NAT<br/>210...202 to Worker02"]
        
        subgraph "Worker Nodes"
            Worker1["Worker Node 01<br/>172.25.0.171"]
            Worker2["Worker Node 02<br/>172.25.0.172:30080"]
        end
    end
    
    subgraph "Kubernetes Cluster (Internal)"
        Service["Ingress Service<br/>NodePort: 30080"]
        
        subgraph "Ingress Controllers"
            Ingress1["Nginx Ingress Pod 1"]
            Ingress2["Nginx Ingress Pod 2"]
        end
        
        subgraph "Microservices"
            Entry["entry-service"]
            Core["core-service"]
        end
    end

    %% Flow Details
    User -->|Port 30080| Firewall
    Firewall --> NAT
    NAT -->|Forward to| Worker2
    
    Worker2 -->|Internal Routing| Service
    Service -->|Load Balance| Ingress1
    Service -->|Load Balance| Ingress2
    
    Ingress1 -->|Proxy Pass| Entry
    Ingress1 -->|Proxy Pass| Core
    Ingress2 -->|Proxy Pass| Entry
    Ingress2 -->|Proxy Pass| Core
```

## 3. Internal Communication
*   **App -> Middleware:**
    *   **Redis:** `axon-redis-master` (ClusterIP)
    *   **Kafka:** `axon-kafka` (KRaft, Headless)
    *   **Elasticsearch:** `elasticsearch-master` (Headless)
*   **App -> External:**
    *   **MySQL:** AWS RDS Endpoint (Public Access)
