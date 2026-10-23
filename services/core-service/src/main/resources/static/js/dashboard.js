document.addEventListener('DOMContentLoaded', function () {
    const ACCENT = '#2b5cd6';
    const MUTED = '#dcdad4';
    const PERIOD_LABELS = { '1d': '최근 24시간', '7d': '최근 7일', '30d': '최근 30일' };
    const FUNNEL_LABELS = { VISIT: '방문', ENGAGE: '참여 클릭', QUALIFY: '당첨', PURCHASE: '결제' };
    const activityId = document.getElementById('activityId').value;
    let currentPeriod = '7d';
    let trafficChart = null;

    document.querySelectorAll('#periodSeg button').forEach(button => {
        button.addEventListener('click', () => {
            currentPeriod = button.dataset.period;
            document.querySelectorAll('#periodSeg button').forEach(b => b.classList.toggle('on', b === button));
            setText('periodLabel', `${PERIOD_LABELS[currentPeriod]} · 결제 확정 기준`);
            fetchDashboardData();
        });
    });

    fetchDashboardData();
    // Poll every 5 seconds for real-time updates
    setInterval(fetchDashboardData, 5000);

    async function fetchDashboardData() {
        try {
            const response = await fetch(`/api/v1/dashboard/activity/${activityId}?period=${currentPeriod}`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            document.getElementById("behaviorStatus").hidden = data.overview.totalVisits != null && data.trafficTrend != null;
            updateOverview(data.overview, data.previousOverview);
            updateStock(data.realtime);
            renderFunnel(data.funnel || []);
            renderTrafficChart(data.trafficTrend || []);
            setText('lastUpdated', `${new Date().toLocaleTimeString('ko-KR', { hour12: false })} 갱신`);
        } catch (error) {
            console.error('Error fetching dashboard data:', error);
        }
    }

    function updateOverview(overview, previous) {
        if (!overview) return;
        setText('gmv', formatWon(overview.gmv));
        setHtml('gmvSub', `구매 ${formatNumber(overview.purchaseCount)}건${change(overview.gmv, previous?.gmv)}`);
        setText('aov', formatWon(overview.averageOrderValue));
        setText('aovSub', '거래액 ÷ 구매');
        setText('conversionRate', overview.conversionRate == null ? '-' : `${overview.conversionRate.toFixed(1)}%`);
        setText('conversionSub', `방문 ${formatNumber(overview.totalVisits)}명 중 ${formatNumber(overview.purchaseCount)}명`);
        const roas = document.getElementById('roas');
        roas.textContent = `${Math.round(overview.roas || 0)}%`;
        roas.classList.toggle('low', overview.budget > 0 && overview.roas < 100);
        setText('budget', overview.budget ? `예산 ${formatWon(overview.budget)}` : '예산 미설정');
    }

    // Previous period with no traffic (e.g. before the activity opened) has nothing to compare with.
    function change(current, prev) {
        if (!prev) return '';
        const pct = (current - prev) / prev * 100;
        return ` · <span class="${pct >= 0 ? 'up' : 'low'}">${pct >= 0 ? '+' : ''}${pct.toFixed(1)}%</span>`;
    }

    function updateStock(realtime) {
        const activity = realtime?.activity;
        if (!activity) return;
        const total = activity.totalStock || 0;
        const remaining = activity.remainingStock || 0;
        const confirmed = activity.participantCount || 0;
        const soldPct = total ? Math.min(100, confirmed / total * 100) : 0;
        const leftPct = total ? Math.min(100 - soldPct, remaining / total * 100) : 0;

        setText('realtimeParticipants', formatNumber(confirmed));
        setText('realtimeTotal', `/ ${formatNumber(total)}`);
        document.getElementById('soldBar').style.width = `${soldPct}%`;
        document.getElementById('leftBar').style.width = `${leftPct}%`;
        setText('soldLegend', `참여 확정 ${formatNumber(confirmed)}`);
        setText('leftLegend', `남은 수량 ${formatNumber(remaining)}`);
        // Unpaid reservations return to stock when their 5-minute token expires.
        const pending = Math.max(0, total - confirmed - remaining);
        document.getElementById('stockState').innerHTML = remaining === 0
            ? '<span class="sold-out">매진</span>'
            : `${pending ? `결제 대기 ${formatNumber(pending)} · ` : ''}${Math.round(remaining / total * 100)}% 남음`;
    }

    function renderFunnel(steps) {
        const top = steps[0]?.count || 0;
        document.getElementById('funnel').innerHTML = steps.map((step, i) => {
            const prev = i === 0 ? null : steps[i - 1].count;
            const rate = step.count != null && prev ? `${(step.count / prev * 100).toFixed(1)}%` : '';
            const width = step.count != null && top ? Math.max(1, step.count / top * 100) : 0;
            return `<div class="funnel-row">
                <div class="funnel-label">${FUNNEL_LABELS[step.step] || step.step}</div>
                <div class="funnel-bar"><i style="width:${width}%"></i></div>
                <div class="funnel-count num">${formatNumber(step.count)}</div>
                <div class="funnel-rate num">${rate}</div>
            </div>`;
        }).join('');
    }

    function renderTrafficChart(trafficData) {
        const labels = trafficData.map(d => `${new Date(d.timestamp).getHours()}시`);
        const data = trafficData.map(d => d.count);
        const peak = Math.max(0, ...data);
        const peakIndex = data.indexOf(peak);
        const colors = data.map((v, i) => (peak > 0 && i === peakIndex ? ACCENT : MUTED));
        setText('peakLabel', peak > 0 ? `최대 ${labels[peakIndex]} · ${formatNumber(peak)}명` : '');

        if (trafficChart) {
            trafficChart.data.labels = labels;
            trafficChart.data.datasets[0].data = data;
            trafficChart.data.datasets[0].backgroundColor = colors;
            trafficChart.update('none');
            return;
        }
        trafficChart = new Chart(document.getElementById('trafficChart'), {
            type: 'bar',
            data: { labels, datasets: [{ data, backgroundColor: colors, borderRadius: 2, barPercentage: 0.8, categoryPercentage: 0.9 }] },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: ctx => `${formatNumber(ctx.raw)}명` } }
                },
                scales: {
                    x: {
                        grid: { display: false },
                        border: { color: '#e7e6e2' },
                        ticks: { font: { size: 10 }, color: '#93918b', autoSkip: false, maxRotation: 0,
                            callback: (v, i) => (i % 6 === 0 ? labels[i] : '') }
                    },
                    y: { display: false, beginAtZero: true }
                }
            }
        });
    }

    function setText(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
    }

    function setHtml(id, value) {
        const el = document.getElementById(id);
        if (el) el.innerHTML = value;
    }

    function formatNumber(num) {
        if (num === undefined || num === null || Number.isNaN(Number(num))) return '-';
        return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 }).format(num);
    }

    // Exact won below 1억; 억/만 above so large totals stay scannable.
    function formatWon(amount) {
        if (amount === undefined || amount === null) return '-';
        const v = Math.round(Number(amount) || 0);
        if (v < 1e8) return `${v.toLocaleString('ko-KR')}원`;
        const man = Math.round((v % 1e8) / 1e4);
        return `${Math.floor(v / 1e8)}억${man ? ` ${man.toLocaleString('ko-KR')}만` : ''}원`;
    }
});
