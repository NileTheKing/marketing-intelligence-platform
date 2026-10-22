#!/bin/bash

# Configuration
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="axon_user"
DB_PASS="axon1234"
DB_NAME="axon_db"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🎨 Seeding Professional Event Definitions for Screen Capture..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Seed Event Definitions
# Format: Name | Description | Status | Type | Payload (JSON)
mysql_seed_events() {
    mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" <<EOF
INSERT INTO events (name, description, status, trigger_type, trigger_payload, created_at, updated_at) VALUES 
('Main_Hero_Banner_Click', '메인 페이지 히어로 배너 클릭 유입 추적', 'ACTIVE', 'CLICK', '{"selector": ".hero-banner-link", "campaign": "Spring_2024"}', NOW(), NOW()),
('Product_Detail_View', '전체 상품 상세 페이지 조회 활동 수집', 'ACTIVE', 'PAGE_VIEW', '{"path_pattern": "/products/*", "track_referrer": true}', NOW(), NOW()),
('Add_To_Cart_Global', '전체 도메인 공통 장바구니 담기 버튼 측정', 'ACTIVE', 'CLICK', '{"selector": ".add-to-cart-btn", "goal": "conversion_step_1"}', NOW(), NOW()),
('Checkout_Initiated', '주문서 진입 및 결제 시도 클릭 이벤트', 'ACTIVE', 'CLICK', '{"selector": "#checkout-submit", "priority": "high"}', NOW(), NOW()),
('Footer_Newsletter_Signup', '푸터 영역 뉴스레터 구독 폼 전송 완료', 'ACTIVE', 'FORM_SUBMISSION', '{"form_id": "newsletter-form", "validation": "success_only"}', NOW(), NOW()),
('Scroll_Depth_Content_90', '상세 콘텐츠 90% 이상 도달 (본문 숙독 여부)', 'ACTIVE', 'SCROLL_DEPTH', '{"percentage": 90, "element": "main-article-body"}', NOW(), NOW()),
('Outbound_Social_Link_Click', '소셜 채널(Insta/FB) 공유 버튼 클릭 추적', 'ACTIVE', 'CLICK', '{"selector": ".social-share-btn", "channel": "dynamic"}', NOW(), NOW()),
('Internal_Search_Performed', '사내 검색 결과 페이지 도달 및 검색어 수집', 'ACTIVE', 'PAGE_VIEW', '{"path": "/search", "capture_query": "q"}', NOW(), NOW()),
('Coupon_Apply_Attempt', '프로모션 코드/쿠폰 적용 버튼 클릭 시도', 'ACTIVE', 'CLICK', '{"selector": ".coupon-apply-btn", "event_category": "marketing"}', NOW(), NOW()),
('Cart_Item_Remove', '장바구니 상품 삭제 (부정적 경험 지표)', 'ACTIVE', 'CLICK', '{"selector": ".btn-remove-item"}', NOW(), NOW()),
('Review_Section_Expansion', '상품 리뷰 영역 확장 버튼 클릭 (관심도 측정)', 'ACTIVE', 'CLICK', '{"selector": "#expand-reviews-btn"}', NOW(), NOW()),
('Customer_Support_Chat_Open', 'CS 채팅상단 연결 버튼 클릭 활동', 'ACTIVE', 'CLICK', '{"selector": ".chat-floating-icon"}', NOW(), NOW());
EOF
}

mysql_seed_events

echo " ✅ 12 Professional Event Definitions Seeded into 'events' table."
echo " 📸 You can now capture the 'Event Management' screen at /admin/dashboard/events (or event-board)."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
