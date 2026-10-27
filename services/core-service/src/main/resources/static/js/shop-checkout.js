(() => {
    'use strict';
    const root = document.getElementById('shop-checkout');
    if (!root) return;
    const coupon = document.getElementById('coupon-select');
    const pay = document.getElementById('btn-pay');
    const message = document.getElementById('checkout-message');
    const newPurchase = document.getElementById('checkout-new-purchase');
    const productId = Number(document.getElementById('product-id').value);
    const price = Number(document.getElementById('original-price').value);
    const storageKey = `shop-checkout:${root.dataset.userId}:${productId}`;
    let pending = null;
    let busy = false;
    let unavailableCoupon = false;

    function say(text) { message.textContent = text; }
    function showPrice() {
        if (unavailableCoupon) {
            document.getElementById('discount-amount-display').textContent = '-';
            document.getElementById('total-amount-display').textContent = '구매 결과 확인 필요';
            pay.querySelector('.price-text').textContent = '';
            return;
        }
        const option = coupon.selectedOptions[0];
        const discount = option ? Math.min(price, Number(option.dataset.discount || 0)) : 0;
        document.getElementById('discount-amount-display').textContent = `-${discount.toLocaleString('ko-KR', {maximumFractionDigits: 2})}원`;
        const total = `${Math.max(0, price - discount).toLocaleString('ko-KR', {maximumFractionDigits: 2})}원`;
        document.getElementById('total-amount-display').textContent = total;
        pay.querySelector('.price-text').textContent = total;
    }
    function freeze() { coupon.disabled = true; }
    function showCompleted() {
        freeze();
        newPurchase.hidden = false;
        document.getElementById('checkout-action-label').textContent = '구매 내역 보기';
        say('이미 완료된 구매입니다. 버튼을 누르면 구매 내역을 확인합니다.');
    }
    function successUrl(id) { return `/payment/success?purchaseId=${encodeURIComponent(id)}`; }
    try {
        pending = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
        if (pending) {
            if (pending.productId !== productId || typeof pending.requestId !== 'string') {
                throw new Error('Invalid pending purchase');
            }
            coupon.value = pending.userCouponId == null ? '' : String(pending.userCouponId);
            unavailableCoupon = pending.userCouponId != null && coupon.value !== String(pending.userCouponId);
            document.getElementById('checkout-action-label').textContent = '구매 결과 확인';
            freeze();
            if (pending.purchaseId) showCompleted();
            else say('아직 결과를 확인하지 못한 구매가 있습니다. 버튼을 눌러 같은 요청으로 확인해 주세요.');
        }
    } catch (_) {
        pay.disabled = true;
        say('브라우저에서 구매 요청 기록을 읽을 수 없습니다. 이 탭의 저장소 설정을 확인해 주세요.');
        return;
    }
    showPrice();
    coupon.addEventListener('change', showPrice);
    newPurchase.addEventListener('click', () => {
        // Only an explicit new purchase may get a fresh request ID after success.
        try { sessionStorage.removeItem(storageKey); }
        catch (_) { say('구매 기록을 정리할 수 없습니다. 저장소 설정을 확인해 주세요.'); return; }
        pending = null;
        unavailableCoupon = false;
        document.getElementById('checkout-action-label').textContent = '구매 체험하기';
        coupon.disabled = false;
        coupon.value = '';
        newPurchase.hidden = true;
        say('새 구매를 진행할 수 있습니다.');
        showPrice();
    });
    pay.addEventListener('click', async () => {
        if (busy) return;
        if (pending && pending.purchaseId) {
            window.location.assign(successUrl(pending.purchaseId));
            return;
        }
        const csrf = root.dataset.csrfToken;
        const csrfHeader = root.dataset.csrfHeader;
        if (!csrf || !csrfHeader) {
            say('구매 요청을 준비할 수 없습니다. 화면을 새로고침해 주세요.');
            return;
        }
        if (!pending) {
            try {
                pending = {requestId: crypto.randomUUID(), productId,
                    userCouponId: coupon.value ? Number(coupon.value) : null};
                sessionStorage.setItem(storageKey, JSON.stringify(pending));
            } catch (_) {
                pending = null;
                say('구매 요청을 보관할 수 없습니다. 브라우저 저장소 설정을 확인해 주세요.');
                return;
            }
        }
        busy = true;
        pay.disabled = true;
        freeze();
        say('구매 요청을 처리하고 있습니다.');
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 15000);
        try {
            const response = await fetch('/api/v1/shop-purchases', {
                method: 'POST', credentials: 'same-origin', signal: controller.signal,
                headers: {'Content-Type': 'application/json', [csrfHeader]: csrf},
                body: JSON.stringify({requestId: pending.requestId, productId: pending.productId,
                    userCouponId: pending.userCouponId})
            });
            if (response.status === 200 || response.status === 201) {
                const result = await response.json();
                if (!Number.isSafeInteger(result.purchaseId) || result.purchaseId <= 0) throw new Error('Invalid purchase response');
                pending.purchaseId = result.purchaseId;
                // A storage failure after commit must never create a fresh request ID.
                try { sessionStorage.setItem(storageKey, JSON.stringify(pending)); } catch (_) { /* Keep the original pending key. */ }
                window.location.assign(successUrl(result.purchaseId));
                return;
            }
            const error = await response.json().catch(() => ({}));
            if (response.status === 409 && error.code !== 'COUPON_ALREADY_USED') {
                say('이 요청과 다른 구매 기록이 있습니다. 새 구매를 시작하지 말고 기존 구매 내역을 확인해 주세요.');
            } else if ([400, 404, 409].includes(response.status)) {
                sessionStorage.removeItem(storageKey);
                pending = null;
                unavailableCoupon = false;
                document.getElementById('checkout-action-label').textContent = '구매 체험하기';
                coupon.disabled = false;
                say('구매 조건을 확인할 수 없습니다. 상품과 쿠폰을 확인한 뒤 다시 시도해 주세요.');
            } else if (response.status === 401) {
                say('로그인이 필요합니다. 로그인 후 이 화면에서 같은 요청을 다시 시도해 주세요.');
            } else if (response.status === 403) {
                say('요청을 확인할 수 없습니다. 화면을 새로고침한 뒤 다시 시도해 주세요.');
            } else {
                say('구매 결과를 확인하지 못했습니다. 버튼을 누르면 같은 요청으로 다시 확인합니다.');
            }
        } catch (_) {
            say('구매 결과를 확인하지 못했습니다. 버튼을 누르면 같은 요청으로 다시 확인합니다.');
        } finally {
            clearTimeout(timeout);
            busy = false;
            pay.disabled = false;
        }
    });
})();
