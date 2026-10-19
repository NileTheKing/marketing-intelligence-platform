document.addEventListener('DOMContentLoaded', function () {
    const ACCENT = '#2b5cd6';
    const MUTED = '#dcdad4';
    let globalTrafficChart = null;

    fetchGlobalDashboardData();
    // Poll every 10 seconds for updates
    setInterval(fetchGlobalDashboardData, 10000);

    async function fetchGlobalDashboardData() {
        try {
            const response = await fetch(`/api/v1/dashboard/overview`);
            const data = await response.json();
            updateDashboard(data);
        } catch (error) {
            console.error('Error fetching global dashboard data:', error);
        }
    }

    function updateDashboard(data) {
        updateOverviewCards(data.overview);
        renderGlobalTrafficChart(data.hourlyTraffic);
        updateAutoCoupon(data.unattributedCouponPurchases);
        updateCampaignEfficiencyTable(data.campaignEfficiency);
        updateLastUpdatedTime();
    }

    function updateOverviewCards(overview) {
        const gmv = Number(overview.gmv) || 0;
        const purchases = overview.purchaseCount || 0;
        const visits = overview.totalVisits || 0;
        setText('kpiGmv', formatWon(gmv));
        setText('kpiBudget', `예산 ${formatWon(overview.budget)}`);
        setText('kpiPurchases', `${formatNumber(purchases)}건`);
        setText('kpiAov', purchases ? `객단가 ${formatWon(gmv / purchases)}` : '객단가 -');
        setText('kpiVisits', formatNumber(visits));
        setText('kpiConversion', visits ? `구매 전환 ${formatNumber(purchases / visits * 100)}%` : '구매 전환 -');
        setText('kpiRoas', `${formatNumber(overview.roas)}%`);
    }

    function renderGlobalTrafficChart(hourlyTraffic) {
        const hours = Array.from({ length: 24 }, (_, i) => i);
        const source = (hourlyTraffic && hourlyTraffic.hourlyTraffic) || {};
        const data = hours.map(h => source[h] || 0);
        const peak = Math.max(...data);
        const peakHour = data.indexOf(peak);
        const colors = data.map((v, h) => (peak > 0 && h === peakHour ? ACCENT : MUTED));
        setText('peakLabel', peak > 0 ? `최대 ${peakHour}시 · ${formatNumber(peak)}명` : '');

        if (globalTrafficChart) {
            globalTrafficChart.data.datasets[0].data = data;
            globalTrafficChart.data.datasets[0].backgroundColor = colors;
            globalTrafficChart.update();
            return;
        }
        globalTrafficChart = new Chart(document.getElementById('globalTrafficChart'), {
            type: 'bar',
            data: {
                labels: hours.map(h => `${h}시`),
                datasets: [{ data, backgroundColor: colors, borderRadius: 2, barPercentage: 0.8, categoryPercentage: 0.9 }]
            },
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
                            callback: (v, i) => (i % 6 === 0 ? `${i}시` : '') }
                    },
                    y: { display: false, beginAtZero: true }
                }
            }
        });
    }

    function updateAutoCoupon(aggregate) {
        const count = aggregate ? aggregate.purchaseCount : 0;
        setText('autoCouponGmv', formatWon(aggregate ? aggregate.gmv : 0));
        setText('autoCouponCount', `구매 ${formatNumber(count)}건`);
    }

    function updateCampaignEfficiencyTable(campaigns) {
        const tbody = document.getElementById('campaignEfficiencyTableBody');
        if (!campaigns || !campaigns.length) {
            tbody.innerHTML = '<tr><td colspan="5" class="empty">캠페인이 없습니다.</td></tr>';
            return;
        }
        const sorted = [...campaigns].sort((a, b) => Number(b.gmv) - Number(a.gmv));
        const total = sorted.reduce((sum, c) => sum + (Number(c.gmv) || 0), 0);
        const maxShare = total ? Number(sorted[0].gmv) / total * 100 : 0;

        tbody.innerHTML = sorted.map(campaign => {
            const share = total ? Number(campaign.gmv) / total * 100 : 0;
            const width = maxShare ? share / maxShare * 100 : 0;
            const roasClass = campaign.roas < 100 ? 'low' : '';
            return `<tr>
                <td><a href="/admin/dashboard/campaign/${campaign.campaignId}">${escapeHtml(campaign.campaignName)}</a></td>
                <td class="r num">${formatWon(campaign.budget)}</td>
                <td class="r num">${formatWon(campaign.gmv)}</td>
                <td><span class="bar"><i style="width:${width}%"></i></span>
                    <span class="num" style="color:var(--text-3);font-size:11px;margin-left:8px">${formatNumber(share)}%</span></td>
                <td class="r num"><b class="${roasClass}">${formatNumber(campaign.roas)}%</b></td>
            </tr>`;
        }).join('');
    }

    function updateLastUpdatedTime() {
        const now = new Date();
        const hh = String(now.getHours()).padStart(2, '0');
        const mm = String(now.getMinutes()).padStart(2, '0');
        setText('lastUpdated', `최근 30일 · ${hh}:${mm} 갱신`);
    }

    function setText(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
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

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, ch => (
            { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
    }
});
