(() => {
    const RFM_LABELS = { VIP: ['VIP', 'vip'], LOYAL: ['충성 고객', ''], AT_RISK: ['이탈 위험', 'risk'], DORMANT: ['휴면', ''] };
    const EVENT_LABELS = {
        PAGE_VIEW: '페이지 조회',
        CLICK: '클릭',
        SCROLL: '스크롤',
        STAY: '체류',
        CART_ITEM_ADDED: '장바구니 담기',
        CART_ITEM_REMOVED: '장바구니 삭제',
        APPROVED: '선착순 당첨',
        PURCHASE: '구매',
    };

    const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, ch => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));

    // Exact won below 1억; 억/만 only when the full number gets too long to scan.
    const krw = value => {
        const v = Number(value) || 0;
        if (v < 1e8) return `${v.toLocaleString('ko-KR')}원`;
        const man = Math.round((v % 1e8) / 1e4);
        return `${Math.floor(v / 1e8)}억${man ? ` ${man.toLocaleString('ko-KR')}만` : ''}원`;
    };

    const formatDate = value => {
        if (!value) return '-';
        const d = new Date(value);
        return `${d.getMonth() + 1}월 ${d.getDate()}일`;
    };
    const formatTime = d => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    const dayKey = d => `${d.getFullYear()}년 ${d.getMonth() + 1}월 ${d.getDate()}일`;

    const eventDetail = event => {
        const p = event.properties || {};
        const parts = [];
        if (p.productId != null) parts.push(`상품 #${p.productId}`);
        if (p.depth != null) parts.push(`${p.depth}% 도달`);
        if (p.durationSec != null) parts.push(`${p.durationSec}초 체류`);
        if (event.pageUrl) parts.push(event.pageUrl);
        return parts.join(' · ');
    };
    const dotClass = triggerType => triggerType === 'PURCHASE' || triggerType === 'APPROVED'
        ? 'buy' : (triggerType && triggerType.startsWith('CART_') ? 'cart' : '');

    function renderHeader(profile) {
        document.getElementById('customerName').textContent = profile.name;
        document.getElementById('customerSub').textContent =
            [profile.email, profile.grade && `등급 ${profile.grade}`].filter(Boolean).join(' · ');
        const rfm = RFM_LABELS[profile.rfmSegment];
        if (rfm) {
            const badge = document.getElementById('rfmBadge');
            badge.textContent = rfm[0];
            if (rfm[1]) badge.classList.add(rfm[1]);
            badge.hidden = false;
        }
        document.getElementById('kpiRevenue').textContent = krw(profile.confirmedRevenue);
        document.getElementById('kpiPurchases').textContent = `${profile.confirmedPurchaseCount.toLocaleString('ko-KR')}건`;
        document.getElementById('kpiCoupons').textContent = `${profile.usedCouponCount} / ${profile.issuedCouponCount}`;
        document.getElementById('kpiLastPurchase').textContent = formatDate(profile.lastPurchaseAt);
        document.getElementById('kpiLastLogin').textContent = formatDate(profile.lastLoginAt);
    }

    function renderPurchases(purchases) {
        const el = document.getElementById('purchaseList');
        if (!purchases.length) {
            el.innerHTML = '<div class="empty">구매 기록이 없습니다.</div>';
            return;
        }
        el.innerHTML = purchases.map(p => {
            const cancelled = p.status === 'CANCELLED';
            const coupon = p.couponName ? `<span class="chip">${escapeHtml(p.couponName)}</span>` : '';
            return `<div class="list-row">
                <div><div class="row-main">${escapeHtml(p.productName || `상품 #${p.purchaseId}`)}${coupon}</div>
                <div class="row-meta">${formatDate(p.purchasedAt)}${cancelled ? ' · 취소됨' : ''}</div></div>
                <div class="row-right num ${cancelled ? 'cancelled' : ''}">${krw(p.amount)}</div>
            </div>`;
        }).join('');
    }

    function renderTimeline(events) {
        const el = document.getElementById('timeline');
        if (!events.length) {
            el.innerHTML = '<div class="empty">수집된 행동이 없습니다.</div>';
            return;
        }
        let currentDay = null;
        el.innerHTML = events.map(event => {
            const at = event.occurredAt ? new Date(event.occurredAt) : null;
            const day = at ? dayKey(at) : '시각 미상';
            const header = day !== currentDay ? `<div class="day">${day}</div>` : '';
            currentDay = day;
            const label = EVENT_LABELS[event.triggerType] || event.eventName || event.triggerType;
            return `${header}<div class="event">
                <time class="num">${at ? formatTime(at) : '-'}</time>
                <span class="dot ${dotClass(event.triggerType)}"></span>
                <div><div class="ev-name">${escapeHtml(label)}</div>
                <div class="ev-detail">${escapeHtml(eventDetail(event))}</div></div>
            </div>`;
        }).join('');
    }

    async function load() {
        const response = await fetch(`/api/v1/dashboard/customers/${window.CUSTOMER_USER_ID}`);
        if (!response.ok) {
            document.getElementById('customerName').textContent =
                response.status === 404 ? '고객을 찾을 수 없습니다' : '고객 정보를 불러오지 못했습니다';
            return;
        }
        const profile = await response.json();
        renderHeader(profile);
        renderPurchases(profile.recentPurchases);
        renderTimeline(profile.recentEvents);
    }

    load();
})();
