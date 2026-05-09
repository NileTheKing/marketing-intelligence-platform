import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

/**
 * =========================================================================
 * Axon FCFS Load Test Script
 * =========================================================================
 *
 * 다양한 시나리오로 선착순 이벤트 부하 테스트
 *
 * 실행 방법:
 *   # 1. Port-forward (별도 터미널)
 *   kubectl port-forward svc/entry-service 8081:80
 *   kubectl port-forward svc/core-service 8080:8080
 *
 *   # 2. 시나리오 선택 실행
 *   SCENARIO=spike MAX_VUS=8000 k6 run k6-fcfs-load-test.js
 *   SCENARIO=constant VUS_LIST="100,500,1000,2000" k6 run k6-fcfs-load-test.js
 *   SCENARIO=ramp k6 run k6-fcfs-load-test.js
 *   SCENARIO=stress MAX_VUS=10000 k6 run k6-fcfs-load-test.js
 *   SCENARIO=soak VUS=500 DURATION=5m k6 run k6-fcfs-load-test.js
 * =========================================================================
 */

// =========================================================================
// 환경 변수 설정
// =========================================================================
const ENTRY_SERVICE_URL = __ENV.ENTRY_SERVICE_URL || 'http://localhost:8081';
const CORE_SERVICE_URL = __ENV.CORE_SERVICE_URL || 'http://localhost:8080';
const ACTIVITY_ID = parseInt(__ENV.ACTIVITY_ID || '1');
const PRODUCT_ID = parseInt(__ENV.PRODUCT_ID || '1');
const USER_ID_START = parseInt(__ENV.USER_ID_START || '1000');
const USER_ID_END = parseInt(__ENV.USER_ID_END || '9000');
const FCFS_LIMIT_COUNT = parseInt(__ENV.FCFS_LIMIT_COUNT || '200');
const USE_PRODUCTION_API = __ENV.USE_PRODUCTION_API === 'true';
const USE_TOKEN_FILE = __ENV.USE_TOKEN_FILE !== 'false'; // 기본값: true

// 시나리오 선택
const SCENARIO = __ENV.SCENARIO || 'spike';

// JWT 토큰 파일 (미리 발급된 토큰)
const TOKEN_FILE_PATH = __ENV.TOKEN_FILE_PATH || './jwt-tokens.json';
let PRE_GENERATED_TOKENS = {};

// 토큰 파일 로드 시도
if (USE_PRODUCTION_API && USE_TOKEN_FILE) {
  try {
    const tokenFileContent = open(TOKEN_FILE_PATH);
    PRE_GENERATED_TOKENS = JSON.parse(tokenFileContent);
    console.log(`✅ JWT 토큰 파일 로드 완료: ${Object.keys(PRE_GENERATED_TOKENS).length}개`);
  } catch (e) {
    console.warn(`⚠️  JWT 토큰 파일 로드 실패: ${e.message}`);
    console.warn('   실시간 JWT 발급 모드로 전환합니다.');
  }
}

// 시나리오별 파라미터
const MAX_VUS = parseInt(__ENV.MAX_VUS || '5000');
const VUS_LIST = (__ENV.VUS_LIST || '100,500,1000,2000,5000,8000').split(',').map(Number);
const DURATION_PER_STEP = __ENV.DURATION_PER_STEP || '10s';
const SOAK_VUS = parseInt(__ENV.VUS || '500');
const SOAK_DURATION = __ENV.DURATION || '5m';

// =========================================================================
// 커스텀 메트릭 정의
// =========================================================================
const fcfsSuccessRate = new Rate('fcfs_success_rate');
const fcfsSoldOutRate = new Rate('fcfs_sold_out_rate');
const fcfsConflictRate = new Rate('fcfs_conflict_rate');
const fcfsErrorRate = new Rate('fcfs_error_rate');

const fcfsSuccessCount = new Counter('fcfs_success_count');
const fcfsSoldOutCount = new Counter('fcfs_sold_out_count');
const fcfsConflictCount = new Counter('fcfs_conflict_count');
const fcfsErrorCount = new Counter('fcfs_error_count');
const fcfsRetryCount = new Counter('fcfs_retry_count');  // 재결제 시나리오

const behaviorEventSuccessRate = new Rate('behavior_event_success_rate');
const behaviorEventCount = new Counter('behavior_event_count');

const reservationDuration = new Trend('reservation_duration');

// =========================================================================
// 시나리오 정의
// =========================================================================

// Scenario 1: Thunder Herd (Spike) - 실전형 ⭐
const spike_scenario = {
  scenarios: {
    thunder_herd: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        // Phase 1: Spike (0-2초) - "정각이다! 클릭!"
        { duration: '2s', target: MAX_VUS },

        // Phase 2: Peak (2-10초) - 최대 부하 유지
        { duration: '8s', target: MAX_VUS },

        // Phase 3: Decline (10-15초) - "마감이네..." (20%로 감소)
        { duration: '5s', target: Math.floor(MAX_VUS * 0.2) },

        // Phase 4: Tail (15-25초) - 재시도 (4%로 감소)
        { duration: '10s', target: Math.floor(MAX_VUS * 0.04) },

        // Phase 5: End
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },

  thresholds: {
    // 409/410 are valid FCFS business outcomes, so use domain metrics
    // instead of generic http_req_failed for the spike scenario.
    'fcfs_success_count': [`count==${FCFS_LIMIT_COUNT}`],
    'fcfs_error_count': ['count==0'],
    'behavior_event_success_rate': ['rate==1'],
    'reservation_duration': ['p(95)<5000'],
  },
};

// Scenario 2: Constant Load (계단식) - 강도 테스트 ⭐
const constant_scenarios = {};
VUS_LIST.forEach((vus, index) => {
  constant_scenarios[`constant_${vus}`] = {
    executor: 'constant-vus',
    vus: vus,
    duration: DURATION_PER_STEP,
    startTime: `${index * parseInt(DURATION_PER_STEP)}s`,
    gracefulStop: '5s',
  };
});

const constant_scenario = {
  scenarios: constant_scenarios,

  thresholds: {
    'http_req_duration': ['p(95)<2000'],
    'http_req_failed': ['rate<0.1'],
  },
};

// Scenario 3: Ramp-up (점진적 증가) - 한계 찾기
const ramp_scenario = {
  scenarios: {
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 100 },
        { duration: '10s', target: 500 },
        { duration: '10s', target: 1000 },
        { duration: '10s', target: 2000 },
        { duration: '10s', target: 5000 },
        { duration: '10s', target: 8000 },
        { duration: '10s', target: 10000 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },

  thresholds: {
    'http_req_failed': ['rate<0.2'],
  },
};

// Scenario 4: Stress Test (극한 부하)
const stress_scenario = {
  scenarios: {
    stress: {
      executor: 'constant-vus',
      vus: MAX_VUS,
      duration: '30s',
    },
  },

  thresholds: {
    'http_req_failed': ['rate<0.3'],
  },
};

// Scenario 5: Soak Test (장시간 안정성)
const soak_scenario = {
  scenarios: {
    soak: {
      executor: 'constant-vus',
      vus: SOAK_VUS,
      duration: SOAK_DURATION,
    },
  },

  thresholds: {
    'http_req_duration': ['p(95)<1000'],
    'http_req_failed': ['rate<0.01'],
  },
};

// =========================================================================
// 시나리오 선택
// =========================================================================
export const options = (() => {
  let selectedScenario;
  let scenarioName;

  switch (SCENARIO) {
    case 'spike':
      selectedScenario = spike_scenario;
      scenarioName = `Thunder Herd (Spike) - MAX_VUS=${MAX_VUS}`;
      break;

    case 'constant':
      selectedScenario = constant_scenario;
      scenarioName = `Constant Load (Step-by-step) - VUs=${VUS_LIST.join('→')}`;
      break;

    case 'ramp':
      selectedScenario = ramp_scenario;
      scenarioName = 'Ramp-up Test (Find Limits)';
      break;

    case 'stress':
      selectedScenario = stress_scenario;
      scenarioName = `Stress Test (Extreme Load) - ${MAX_VUS} VUs`;
      break;

    case 'soak':
      selectedScenario = soak_scenario;
      scenarioName = `Soak Test (Long-term Stability) - ${SOAK_VUS} VUs for ${SOAK_DURATION}`;
      break;

    default:
      selectedScenario = spike_scenario;
      scenarioName = 'Thunder Herd (Spike) - Default';
      console.warn(`⚠️  Unknown scenario '${SCENARIO}', using 'spike'`);
  }

  console.log('📊 Scenario:', scenarioName);
  return selectedScenario;
})();

// =========================================================================
// Setup: 테스트 시작 전 초기화
// =========================================================================
export function setup() {
  console.log('='.repeat(70));
  console.log('🚀 Axon FCFS Load Test Starting...');
  console.log('='.repeat(70));
  console.log(`Entry Service: ${ENTRY_SERVICE_URL}`);
  console.log(`Core Service: ${CORE_SERVICE_URL}`);
  console.log(`Activity ID: ${ACTIVITY_ID}`);
  console.log(`User ID Range: ${USER_ID_START} - ${USER_ID_END}`);
  console.log(`Production API: ${USE_PRODUCTION_API ? 'YES (JWT)' : 'NO (Test API)'}`);

  if (USE_PRODUCTION_API) {
    const tokenCount = Object.keys(PRE_GENERATED_TOKENS).length;
    if (tokenCount > 0) {
      console.log(`JWT 모드: 사전 발급 (${tokenCount}개 토큰)`);
    } else {
      console.log(`JWT 모드: 실시간 발급`);
    }
  }

  console.log(`Scenario: ${SCENARIO.toUpperCase()}`);

  if (SCENARIO === 'spike') {
    console.log(`MAX_VUS: ${MAX_VUS}`);
  } else if (SCENARIO === 'constant') {
    console.log(`VUS_LIST: ${VUS_LIST.join(', ')}`);
    console.log(`DURATION_PER_STEP: ${DURATION_PER_STEP}`);
  }

  console.log('='.repeat(70));

  return {
    entryServiceUrl: ENTRY_SERVICE_URL,
    coreServiceUrl: CORE_SERVICE_URL,
    activityId: ACTIVITY_ID,
    productId: PRODUCT_ID,
    userIdStart: USER_ID_START,
    userIdEnd: USER_ID_END,
    useProductionApi: USE_PRODUCTION_API,
    tokens: PRE_GENERATED_TOKENS,
  };
}

// =========================================================================
// 메인 시나리오: 각 VU(Virtual User)가 실행하는 흐름
// =========================================================================
export default function (data) {
  // 순차적 userId 생성 (중복 방지)
  const uniqueIndex = exec.scenario.iterationInTest;
  const userId = data.userIdStart + (uniqueIndex % (data.userIdEnd - data.userIdStart + 1));
  const sessionId = `session-${userId}-${Date.now()}`;

  // ===== Step 1: 페이지 방문 이벤트 =====
  sendBehaviorEvent(data, userId, sessionId, 'PAGE_VIEW');
  sleep(Math.random() * 0.2 + 0.1); // 100-300ms 랜덤 딜레이

  // ===== Step 2: 참여 버튼 클릭 이벤트 =====
  sendBehaviorEvent(data, userId, sessionId, 'CLICK');

  // ===== Step 3: FCFS 예약 시도 =====
  const startTime = Date.now();

  let reservationToken = null;
  if (data.useProductionApi) {
    // 프로덕션 API (JWT 필요)
    reservationToken = reserveWithJWT(data, userId);
  } else {
    // 테스트 API (인증 불필요)
    reserveWithTestAPI(data, userId);
  }

  const duration = Date.now() - startTime;
  reservationDuration.add(duration);

  // ===== Step 4: 결제 승인 (DB 저장) =====
  if (reservationToken) {
    confirmPayment(data, userId, reservationToken);
  }
}

// =========================================================================
// 행동 이벤트 전송
// =========================================================================
function sendBehaviorEvent(data, userId, sessionId, triggerType) {
  const eventName = triggerType;
  const payload = JSON.stringify({
    eventName: eventName,
    triggerType: triggerType,
    occurredAt: new Date().toISOString(),
    userId: userId,
    sessionId: sessionId,
    pageUrl: `/campaign-activity/${data.activityId}/view`,
    referrer: '',
    properties: {
      activityId: data.activityId,
    },
  });

  // JWT 토큰이 있으면 헤더에 추가 (data.tokens 사용)
  const headers = { 'Content-Type': 'application/json' };
  if (data.tokens && data.tokens[userId]) {
      headers['Authorization'] = `Bearer ${data.tokens[userId]}`;
  }

  const res = http.post(
    `${data.entryServiceUrl}/entry/api/v1/behavior/events`,
    payload,
    {
      headers: headers,
      tags: { name: 'behavior_event' },
    }
  );

  const success = check(res, {
    'behavior event status 200 or 201': (r) => r.status === 200 || r.status === 201 || r.status === 202,
  });

  if (!success) {
      // console.warn(`Behavior Event Failed: ${res.status}`); // 로깅 줄이기
  }

  behaviorEventSuccessRate.add(success);
  behaviorEventCount.add(1);
}

// =========================================================================
// FCFS 예약 (테스트 API)
// =========================================================================
function reserveWithTestAPI(data, userId) {
  // ... (생략) ...
}

// =========================================================================
// FCFS 예약 (프로덕션 API - JWT 필요)
// =========================================================================
function reserveWithJWT(data, userId) {
  let token;

  // Step 1: JWT 토큰 가져오기
  if (data.tokens && data.tokens[userId]) {
    token = data.tokens[userId];
  } else {
    const tokenRes = http.get(
      `${data.coreServiceUrl}/test/auth/token?userId=${userId}`,
      { tags: { name: 'jwt_token_realtime' } }
    );
    if (tokenRes.status !== 200) return null;
    token = tokenRes.body;
  }

  // Step 2: FCFS 예약 (JWT 사용)
  const payload = JSON.stringify({
    campaignActivityId: data.activityId,
    productId: data.productId,
    quantity: 1 // 수량 명시
  });

  const res = http.post(
    `${data.entryServiceUrl}/entry/api/v1/entries`,
    payload,
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      tags: { name: 'fcfs_reservation_jwt' },
      timeout: '10s',
    }
  );

  handleReservationResponse(res, userId);

  if (res.status === 200) {
      try {
          const json = res.json();
          if (json.reservationToken) {
              console.log(`Got token for user ${userId}: ${json.reservationToken.substring(0, 10)}...`);
              return json.reservationToken;
          } else {
              console.error(`No reservationToken in response for user ${userId}:`, json);
          }
      } catch(e) {
          console.error(`Failed to parse reservation token for user ${userId}`, e);
      }
  }
  return null;
}

// =========================================================================
// 결제 승인 (DB 저장) - Prepare -> Confirm
// =========================================================================
function confirmPayment(data, userId, reservationToken) {
  let token = data.tokens && data.tokens[userId] ? data.tokens[userId] : null;

  // 토큰 없으면 실시간 발급
  if (!token) {
    const tokenRes = http.get(
      `${data.coreServiceUrl}/test/auth/token?userId=${userId}`,
      { tags: { name: 'jwt_token_realtime_payment' } }
    );
    if (tokenRes.status !== 200) {
      console.error(`Failed to get JWT token for payment (user ${userId}): ${tokenRes.status}`);
      return;
    }
    token = tokenRes.body;
  }

  // 1. Payment Prepare (2차 토큰 발급)
  const preparePayload = JSON.stringify({
    reservationToken: reservationToken
  });

  const prepareRes = http.post(
    `${data.entryServiceUrl}/entry/api/v1/payments/prepare`,
    preparePayload,
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      tags: { name: 'payment_prepare' },
      timeout: '10s',
    }
  );

  if (prepareRes.status !== 200) {
      console.error(`Payment prepare failed for user ${userId}: ${prepareRes.status}`, prepareRes.body);
      return;
  }

  let approvalToken = null;
  try {
      const json = prepareRes.json();
      if (json && json.approvalToken) {
          approvalToken = json.approvalToken;
      } else if (json && json.ApprovalToken) { // Fallback for old API
          approvalToken = json.ApprovalToken;
      }
  } catch (e) {
      console.error(`Failed to parse prepare response for user ${userId}`, e);
      return;
  }

  if (!approvalToken) {
      console.error(`No approvalToken for user ${userId}`);
      return;
  }

  // 2. Payment Confirm (최종 승인)
  const confirmPayload = JSON.stringify({
    reservationToken: approvalToken // 2차 토큰 사용
  });

  const confirmRes = http.post(
    `${data.entryServiceUrl}/entry/api/v1/payments/confirm`,
    confirmPayload,
    {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      tags: { name: 'payment_confirm' },
      timeout: '10s',
    }
  );

  if (confirmRes.status !== 200) {
      console.error(`Payment confirm failed for user ${userId}: ${confirmRes.status}`, confirmRes.body);
  }

  check(confirmRes, {
    'payment confirm success (200)': (r) => r.status === 200,
  });
}

// =========================================================================
// 예약 응답 처리
// =========================================================================
function handleReservationResponse(res, userId) {
  if (res.status === 200) {
    // ✅ 성공 (reservationToken 받음)
    // isRetry 필드 확인해서 신규/재시도 구분
    let isRetry = false;
    try {
      const json = res.json();
      isRetry = json.isRetry === true;
    } catch(e) {
      // 파싱 실패 시 신규로 간주
    }

    if (isRetry) {
      // 재결제 시나리오 (토큰 재사용)
      fcfsRetryCount.add(1);
      // console.log(`🔄 User ${userId}: Retry with existing token`);
    } else {
      // 신규 예약 성공
      fcfsSuccessRate.add(1);
      fcfsSuccessCount.add(1);
      // console.log(`✅ User ${userId}: New reservation success`);
    }

  } else if (res.status === 410) {
    // 마감 (SOLD_OUT)
    fcfsSoldOutRate.add(1);
    fcfsSoldOutCount.add(1);

  } else if (res.status === 409) {
    // ❌ 중복 참여 (분산 락 실패!)
    fcfsConflictRate.add(1);
    fcfsConflictCount.add(1);
    console.warn(`⚠️  CONFLICT detected! Status: ${res.status}, Body: ${res.body}`);

  } else {
    // 기타 에러
    fcfsErrorRate.add(1);
    fcfsErrorCount.add(1);
    console.error(`Error: ${res.status}, Body: ${res.body}`);
  }

  check(res, {
    'reservation valid business outcome (200/409/410)': (r) => [200, 409, 410].includes(r.status),
  });
}

// =========================================================================
// Teardown: 테스트 종료 후 요약
// =========================================================================
export function teardown(data) {
  console.log('='.repeat(70));
  console.log('🏁 Axon FCFS Load Test Completed!');
  console.log('='.repeat(70));
  console.log('📊 Final Metrics Summary:');
  console.log('   - Check k6 output above for detailed metrics');
  console.log('   - fcfs_success_count: 신규 예약 성공 (should = limitCount)');
  console.log('   - fcfs_retry_count: 재결제 시나리오 (토큰 재사용)');
  console.log('   - fcfs_conflict_count: 중복 참여 차단 (business rejection)');
  console.log('   - fcfs_error_count: 알 수 없는 예약 에러 (should = 0)');
  console.log('='.repeat(70));
  console.log('🔍 Next Steps:');
  console.log('   1. Verify Redis counter:');
  console.log(`      kubectl exec -it axon-redis-master-0 -- redis-cli GET "campaignActivity:${data.activityId}:counter"`);
  console.log('   2. Verify MySQL entries:');
  console.log(`      SELECT COUNT(*) FROM campaign_activity_entries WHERE campaign_activity_id = ${data.activityId};`);
  console.log('   3. Check Elasticsearch events:');
  console.log(`      curl "http://localhost:9200/behavior-events/_count?q=properties.activityId:${data.activityId}"`);
  console.log('='.repeat(70));
}
