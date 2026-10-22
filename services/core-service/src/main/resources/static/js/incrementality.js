document.addEventListener('DOMContentLoaded', function () {
    const runId = document.getElementById('runId').value;
    let windowDays = 7;

    document.getElementById('runPicker').addEventListener('change', (e) => {
        window.location.href = `/admin/campaign-runs/${e.target.value}/incrementality`;
    });
    document.querySelectorAll('#windowSeg button').forEach(button => {
        button.addEventListener('click', () => {
            windowDays = Number(button.dataset.window);
            document.querySelectorAll('#windowSeg button').forEach(b => b.classList.toggle('on', b === button));
            load();
        });
    });

    load();

    async function load() {
        try {
            const response = await fetch(`/api/v1/dashboard/campaign-runs/${runId}/incrementality?windowDays=${windowDays}`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            render(await response.json());
        } catch (error) {
            console.error('Error fetching incrementality:', error);
            setText('observedLabel', '결과를 불러오지 못했습니다.');
        }
    }

    function render(data) {
        const t = data.treatment;
        const h = data.holdout;
        const inc = Number(data.incrementalRevenue);
        const partial = data.observedDays < data.windowDays ? ` · ${data.windowDays}일 중 ${data.observedDays}일 경과` : '';
        setText('observedLabel', `발송 후 ${data.windowDays}일 · ${formatDate(data.observedFrom)} – ${formatDate(data.observedUntil)}${partial}`);

        const incEl = document.getElementById('incremental');
        incEl.textContent = `${inc >= 0 ? '+' : '−'}${formatWon(Math.abs(inc))}`;
        incEl.className = `hero-val num ${inc >= 0 ? 'pos' : 'neg'}`;
        setText('formula', `(${formatWon(t.revenuePerUser)} − ${formatWon(h.revenuePerUser)}) × ${formatNumber(t.targetCount)}명`);

        const p = twoProportionPValue(t.purchasingUserCount, t.targetCount, h.purchasingUserCount, h.targetCount);
        const verdict = document.getElementById('verdict');
        if (p >= 0.05) {
            verdict.className = 'verdict weak';
            verdict.textContent = `차이가 우연일 수 있음 · p=${p.toFixed(2)}`;
        } else {
            verdict.className = `verdict ${inc >= 0 ? 'pos' : 'neg'}`;
            verdict.textContent = `${inc >= 0 ? '효과 있음' : '역효과'} · p=${p < 0.001 ? '<0.001' : p.toFixed(3)}`;
        }

        const maxRpu = Math.max(t.revenuePerUser, h.revenuePerUser) || 1;
        const maxCvr = Math.max(t.purchaseConversionRate, h.purchaseConversionRate) || 1;
        bar('rpuTBar', t.revenuePerUser / maxRpu); setText('rpuT', formatWon(t.revenuePerUser));
        bar('rpuHBar', h.revenuePerUser / maxRpu); setText('rpuH', formatWon(h.revenuePerUser));
        bar('cvrTBar', t.purchaseConversionRate / maxCvr); setText('cvrT', `${t.purchaseConversionRate.toFixed(2)}%`);
        bar('cvrHBar', h.purchaseConversionRate / maxCvr); setText('cvrH', `${h.purchaseConversionRate.toFixed(2)}%`);

        const rows = [
            ['대상 고객', t.targetCount, h.targetCount, formatNumber, null],
            ['구매 고객', t.purchasingUserCount, h.purchasingUserCount, formatNumber, null],
            ['구매 전환율', t.purchaseConversionRate, h.purchaseConversionRate, v => `${v.toFixed(2)}%`, 'pp'],
            ['주문', t.orderCount, h.orderCount, formatNumber, null],
            ['매출', t.revenue, h.revenue, formatWon, null],
            ['1인당 매출', t.revenuePerUser, h.revenuePerUser, formatWon, 'pct'],
            ['쿠폰 사용', t.usedCouponCount, h.usedCouponCount, formatNumber, null],
            ['쿠폰 사용 매출', t.couponAttributedRevenue, h.couponAttributedRevenue, formatWon, null]
        ];
        document.getElementById('compareBody').innerHTML = rows.map(([label, tv, hv, fmt, mode]) =>
            `<tr><td>${label}</td><td class="r num">${fmt(Number(tv))}</td><td class="r num">${fmt(Number(hv))}</td>
                <td class="r num diff">${diff(Number(tv), Number(hv), mode)}</td></tr>`).join('');
    }

    // Only per-user rates are comparable: the groups differ in size by design.
    function diff(tv, hv, mode) {
        if (mode === 'pp') return signed(tv - hv, `${Math.abs(tv - hv).toFixed(2)}%p`);
        if (mode === 'pct' && hv) return signed(tv - hv, `${Math.abs((tv - hv) / hv * 100).toFixed(1)}%`);
        return '<span style="color:var(--text-3)">-</span>';
    }

    function signed(delta, text) {
        return `<span class="${delta >= 0 ? 'pos-t' : 'low'}">${delta >= 0 ? '+' : '−'}${text}</span>`;
    }

    /** Two-sided p-value of a pooled two-proportion z-test on purchase conversion. */
    function twoProportionPValue(x1, n1, x2, n2) {
        if (!n1 || !n2) return 1;
        const pooled = (x1 + x2) / (n1 + n2);
        const se = Math.sqrt(pooled * (1 - pooled) * (1 / n1 + 1 / n2));
        if (!se) return 1;
        const z = Math.abs(x1 / n1 - x2 / n2) / se;
        return 2 * (1 - normalCdf(z));
    }

    // Abramowitz-Stegun 26.2.17 approximation, accurate to ~1e-7.
    function normalCdf(z) {
        const t = 1 / (1 + 0.2316419 * z);
        const poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return 1 - Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI) * poly;
    }

    function bar(id, ratio) {
        document.getElementById(id).style.width = `${Math.max(0, Math.min(1, ratio)) * 100}%`;
    }

    function setText(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
    }

    function formatNumber(num) {
        return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 }).format(num || 0);
    }

    // Exact won below 1억; 억/만 above so large totals stay scannable.
    function formatWon(amount) {
        const v = Math.round(Number(amount) || 0);
        if (v < 1e8) return `${v.toLocaleString('ko-KR')}원`;
        const man = Math.round((v % 1e8) / 1e4);
        return `${Math.floor(v / 1e8)}억${man ? ` ${man.toLocaleString('ko-KR')}만` : ''}원`;
    }

    function formatDate(value) {
        const d = new Date(value);
        return `${d.getMonth() + 1}.${d.getDate()}`;
    }
});
