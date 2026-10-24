let currentPeriod = "7d";
let campaignStream = null;
let reconnectTimer = null;

document.addEventListener('DOMContentLoaded', function () {
    const campaignId = document.getElementById('campaignId').value;
    if (!campaignId) {
        console.error("Campaign ID not found");
        return;
    }

    // Initial fetch for immediate display
    fetchCampaignData(campaignId);

    // Start SSE for real-time updates
    connectToSse(campaignId);
});

async function fetchCampaignData(campaignId) {
    try {
        const response = await fetch(`/api/v1/dashboard/campaign/${campaignId}?period=${currentPeriod}`);
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        if (data.period === currentPeriod) updateDashboard(data);
    } catch (error) {
        console.error("Error fetching campaign data:", error);
    }
}

function connectToSse(campaignId) {
    clearTimeout(reconnectTimer);
    if (campaignStream) campaignStream.close();
    const eventSource = new EventSource(`/api/v1/dashboard/stream/campaign/${campaignId}?period=${currentPeriod}`);

    campaignStream = eventSource;

    eventSource.addEventListener('dashboard-update', function (event) {
        try {
            const data = JSON.parse(event.data);
            if (data.period === currentPeriod) updateDashboard(data);
        } catch (error) {
            console.error("Error parsing SSE data:", error);
        }
    });

    eventSource.onerror = function (error) {
        console.error("SSE error:", error);
        eventSource.close();
        // Optional: Reconnect logic with backoff
        if (campaignStream === eventSource) {
            reconnectTimer = setTimeout(() => connectToSse(campaignId), 5000);
        }
    };
}

function updateDashboard(data) {
    document.getElementById('behaviorStatus').hidden = data.overview.totalVisits != null && data.heatmap != null;
    document.getElementById('campaignName').textContent = data.campaignName;
    document.getElementById('lastUpdated').textContent = `${new Date().toLocaleTimeString('ko-KR', {hour12:false})} 갱신`;
    updateKpi('totalVisits', formatNumber(data.overview.totalVisits));
    updateKpi('totalPurchases', `${formatNumber(data.overview.purchaseCount)}건`);
    updateKpi('totalGMV', formatWon(data.overview.gmv));
    updateKpi('totalROAS', Number(data.overview.gmv) > 0 ? formatPercent(data.overview.roas) : '매출 없음');
    document.getElementById('totalROAS').classList.toggle('low', Number(data.overview.gmv) > 0 && data.overview.roas < 100);
    renderComparisonChart(data.activities);
    renderHeatmapChart(data.heatmap);
    updateActivityTable(data.activities);
}

function updateKpi(id, value) {
    document.getElementById(id).textContent = value;
}

let comparisonChart = null;
function renderComparisonChart(activities) {
    const labels = activities.map(a => a.activityName);
    const visits = activities.map(a => a.totalVisits);
    const purchases = activities.map(a => a.totalPurchases);
    const peak = Math.max(0, ...purchases);
    const colors = purchases.map(n => peak > 0 && n === peak ? '#2b5cd6' : '#dcdad4');
    if (comparisonChart) {
        comparisonChart.data.labels = labels;
        comparisonChart.data.datasets[0].data = visits;
        comparisonChart.data.datasets[1].data = purchases;
        comparisonChart.data.datasets[1].backgroundColor = colors;
        comparisonChart.update();
        return;
    }
    comparisonChart = new Chart(document.getElementById('comparisonChart'), {
        type:'bar',
        data:{labels, datasets:[
            {label:'방문', data:visits, backgroundColor:'#dcdad4', borderRadius:2},
            {label:'구매', data:purchases, backgroundColor:colors, borderRadius:2}
        ]},
        options:{
            responsive:true, maintainAspectRatio:false,
            plugins:{legend:{display:false}, tooltip:{callbacks:{label:ctx => `${ctx.dataset.label} ${formatNumber(ctx.raw)}건`}}},
            scales:{
                x:{grid:{display:false}, border:{color:'#e7e6e2'}, ticks:{color:'#93918b', font:{size:10}, maxRotation:0}},
                y:{display:false, beginAtZero:true}
            },
            onClick:(event, elements, chart) => {
                if (elements.length) {
                    const activity = activities.find(a => a.activityName === chart.data.labels[elements[0].index]);
                    window.location.href = `/admin/dashboard/${activity.activityId}`;
                }
            },
            onHover:(event, elements) => { event.native.target.style.cursor = elements.length ? 'pointer' : 'default'; }
        }
    });
}

let heatmapChart = null;
function renderHeatmapChart(heatmap) {
    const hours = Array.from({length:24}, (_,i) => i);
    const data = hours.map(h => heatmap ? (heatmap.hourlyTraffic[h] || 0) : null);
    const peak = Math.max(0, ...data);
    const colors = data.map(n => peak > 0 && n === peak ? '#2b5cd6' : '#dcdad4');
    if (heatmapChart) {
        heatmapChart.data.datasets[0].data = data;
        heatmapChart.data.datasets[0].backgroundColor = colors;
        heatmapChart.update();
        return;
    }
    heatmapChart = new Chart(document.getElementById('heatmapChart'), {
        type:'bar',
        data:{labels:hours.map(h => `${h}시`), datasets:[{label:'방문', data, backgroundColor:colors, borderRadius:2, barPercentage:.8, categoryPercentage:.9}]},
        options:{responsive:true, maintainAspectRatio:false,
            plugins:{legend:{display:false}, tooltip:{callbacks:{label:ctx => `${formatNumber(ctx.raw)}명`}}},
            scales:{x:{grid:{display:false}, border:{color:'#e7e6e2'}, ticks:{color:'#93918b',font:{size:10},autoSkip:false,maxRotation:0,callback:(v,i) => i%6 === 0 ? `${i}시` : ''}}, y:{display:false,beginAtZero:true}}
        }
    });
}

function updateActivityTable(activities) {
    const tbody = document.getElementById('activityTableBody');
    if (!activities.length) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty">등록된 활동이 없습니다.</td></tr>';
        return;
    }
    tbody.innerHTML = activities.map(activity => `
        <tr>
            <td><a href="/admin/dashboard/${activity.activityId}">${escapeHtml(activity.activityName)}</a></td>
            <td class="r num">${formatNumber(activity.totalVisits)}</td>
            <td class="r num">${formatNumber(activity.totalEngages)}</td>
            <td class="r num">${formatPercent(activity.engagementRate)}</td>
            <td class="r num">${formatNumber(activity.totalPurchases)}</td>
            <td class="r num">${formatPercent(activity.conversionRate)}</td>
            <td class="r num">${formatWon(activity.gmv)}</td>
        </tr>`).join('');
}

function formatNumber(value) {
    if (value === undefined || value === null) return '-';
    return new Intl.NumberFormat('ko-KR', {maximumFractionDigits:1}).format(value);
}

function formatPercent(value) {
    return value == null ? '-' : `${formatNumber(value)}%`;
}

function formatWon(amount) {
    if (amount === undefined || amount === null) return '-';
    const v = Math.round(Number(amount) || 0);
    if (v < 1e8) return `${v.toLocaleString('ko-KR')}원`;
    const man = Math.round((v % 1e8) / 1e4);
    return `${Math.floor(v / 1e8)}억${man ? ` ${man.toLocaleString('ko-KR')}만` : ''}원`;
}

function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, ch => (
        {'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[ch]));
}

function togglePeriodDropdown() {
    document.getElementById('periodDropdown').classList.toggle('hidden');
}

function changePeriod(period, label) {
    currentPeriod = period;
    document.getElementById('currentPeriodLabel').textContent = label;
    document.getElementById('periodDropdown').classList.add('hidden');
    const campaignId = document.getElementById('campaignId').value;
    fetchCampaignData(campaignId);
    connectToSse(campaignId);
}
