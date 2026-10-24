const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/campaign-activity-module.js'), 'utf8');
const context = {};
context.window = context;
vm.runInNewContext(source, context);
const activityModule = context.CampaignActivityModule;
const serialize = (...args) => JSON.parse(JSON.stringify(activityModule.serializeCouponDelivery(...args)));

test('activity editor calls existing shared helper exports', () => {
    const editor = fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/js/admin_create_campaignActivitys.js'), 'utf8');
    for (const [, name] of editor.matchAll(/Helpers\.(\w+)\s*\(/g)) {
        assert.equal(typeof activityModule.Helpers[name], 'function', `Helpers.${name} is not exported`);
    }
    const [, bindings] = editor.match(/const\s*\{([^}]+)\}\s*=\s*CampaignActivityModule/);
    for (const name of bindings.split(',').map(value => value.trim())) {
        assert.notEqual(activityModule[name], undefined, `${name} is not exported`);
    }
});

test('serializes only the selected coupon delivery condition', () => {
    assert.deepEqual(serialize('COUPON', 'DIRECT', '', '', 'REENGAGEMENT', ' note ', '10'), {
        couponDeliveryMode: 'DIRECT', delayMinutes: null, dormantDays: null,
        purpose: 'REENGAGEMENT', operatorMemo: 'note', holdoutRatePercent: 10
    });
    assert.deepEqual(serialize('COUPON', 'ABANDONED_CART', '30', '', 'OTHER', 'memo', '15'), {
        couponDeliveryMode: 'ABANDONED_CART', delayMinutes: 30, dormantDays: null,
        purpose: 'OTHER', operatorMemo: 'memo', holdoutRatePercent: 15
    });
    assert.deepEqual(serialize('COUPON', 'DORMANT_CUSTOMER', '', '90', 'OTHER', 'memo'), {
        couponDeliveryMode: 'DORMANT_CUSTOMER', delayMinutes: null, dormantDays: 90,
        purpose: 'OTHER', operatorMemo: 'memo', holdoutRatePercent: null
    });
    assert.deepEqual(serialize('FIRST_COME_FIRST_SERVE', 'DIRECT', '', '', '', ''), {});
});

test('loads coupon edit configuration without changing immutable mode', () => {
    const controls = Object.fromEntries(['deliveryMode', 'delayMinutes', 'dormantDays', 'purpose', 'operatorMemo', 'holdoutRatePercent']
        .map(name => [name, { value: '', disabled: false }]));
    activityModule.applyCouponEdit({
        couponDeliveryMode: 'ABANDONED_CART', delayMinutes: 45, dormantDays: null,
        purpose: 'REENGAGEMENT', operatorMemo: 'existing memo', holdoutRatePercent: 15
    }, controls);
    assert.equal(controls.deliveryMode.value, 'ABANDONED_CART');
    assert.equal(controls.deliveryMode.disabled, true);
    assert.equal(controls.delayMinutes.value, 45);
    assert.equal(controls.dormantDays.value, '');
    assert.equal(controls.purpose.value, 'REENGAGEMENT');
    assert.equal(controls.operatorMemo.value, 'existing memo');
    assert.equal(controls.holdoutRatePercent.value, 15);
});
