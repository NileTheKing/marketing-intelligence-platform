const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const {randomUUID} = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/shop-checkout.js'), 'utf8');

function screen(storage, send, price = '10000', discount = '1000') {
    function element(extra = {}) {
        return {disabled: false, hidden: true, textContent: '', value: '', dataset: {},
            listeners: {}, addEventListener(type, callback) { this.listeners[type] = callback; }, ...extra};
    }
    const amount = element();
    const controls = {
        'shop-checkout': element({dataset: {userId: '7', csrfToken: 'csrf-token', csrfHeader: 'X-CSRF-TOKEN'}}),
        'coupon-select': element({value: '12', selectedOptions: [{dataset: {discount}}]}),
        'btn-pay': element({querySelector: () => amount}),
        'checkout-message': element(), 'checkout-action-label': element(), 'checkout-new-purchase': element(),
        'product-id': element({value: '3'}), 'original-price': element({value: price}),
        'discount-amount-display': element(), 'total-amount-display': element()
    };
    const visits = [];
    vm.runInNewContext(source, {
        document: {getElementById: id => controls[id], querySelector: query =>
            ({content: query.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'csrf-token'})},
        crypto: {randomUUID}, AbortController, setTimeout, clearTimeout,
        sessionStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value),
            removeItem: key => storage.delete(key)},
        window: {location: {assign: url => visits.push(url)}}, fetch: send
    });
    return {controls, visits, click: () => controls['btn-pay'].listeners.click()};
}

test('response loss and page reload retry the same request and coupon with CSRF', async () => {
    const storage = new Map();
    const calls = [];
    const first = screen(storage, async (url, request) => {
        calls.push({url, request}); throw new Error('response lost');
    });
    await first.click();
    assert.equal(first.visits.length, 0);
    assert.equal(first.controls['coupon-select'].disabled, true);
    const retry = screen(storage, async (url, request) => {
        calls.push({url, request}); return {status: 200, json: async () => ({purchaseId: 42})};
    });
    await retry.click();
    assert.equal(calls[0].request.body, calls[1].request.body);
    const body = JSON.parse(calls[0].request.body);
    assert.equal(body.userCouponId, 12);
    assert.equal(body.finalPrice, undefined);
    assert.equal(calls[1].request.headers['X-CSRF-TOKEN'], 'csrf-token');
    assert.equal(calls[1].request.credentials, 'same-origin');
    assert.deepEqual(retry.visits, ['/payment/success?purchaseId=42']);
    const completed = screen(storage, () => { throw new Error('completed request must not POST'); });
    await completed.click();
    assert.deepEqual(completed.visits, ['/payment/success?purchaseId=42']);
});

test('server failure retains identity and duplicate clicks do not issue parallel purchases', async () => {
    const storage = new Map();
    let finish;
    let count = 0;
    const app = screen(storage, () => { count++; return new Promise(resolve => { finish = resolve; }); });
    const running = app.click();
    await app.click();
    assert.equal(count, 1);
    finish({status: 503});
    await running;
    assert.equal(storage.size, 1);
    assert.equal(app.visits.length, 0);
    assert.equal(app.controls['btn-pay'].disabled, false);
});

test('definite rejection shows an error and does not navigate to success', async () => {
    const storage = new Map();
    const app = screen(storage, async () => ({status: 409, json: async () => ({code: 'COUPON_ALREADY_USED'})}));
    await app.click();
    assert.equal(app.visits.length, 0);
    assert.equal(storage.size, 0);
    assert.equal(app.controls['coupon-select'].disabled, false);
    assert.match(app.controls['checkout-message'].textContent, /구매 조건/);
});


test('request ID conflict preserves the existing identity instead of creating another purchase', async () => {
    const storage = new Map();
    const app = screen(storage, async () => ({status: 409, json: async () => ({code: 'REQUEST_ID_REUSED'})}));
    await app.click();
    assert.equal(storage.size, 1);
    assert.equal(app.controls['coupon-select'].disabled, true);
    assert.equal(app.visits.length, 0);
});


test('price preview is displayed to two decimal places', () => {
    const app = screen(new Map(), () => {}, '85.0085', '0');
    assert.equal(app.controls['total-amount-display'].textContent, '85.01원');
});
