document.addEventListener('DOMContentLoaded', () => {
    const listContainer = document.getElementById('campaign-list-container');
    const detailHeader = document.getElementById('campaign-detail-header');
    const activityContent = document.getElementById('activity-content');
    const noCampaignSelected = document.getElementById('no-campaign-selected');
    const activityTableBody = document.getElementById('activity-table-body');
    const searchInput = document.getElementById('activity-search');
    const statusFilter = document.getElementById('activity-status-filter');
    const typeFilter = document.getElementById('activity-type-filter');

    const TYPE_LABELS = { FIRST_COME_FIRST_SERVE: '선착순', COUPON: '쿠폰', GIVEAWAY: '경품', WEBHOOK: '웹훅' };
    const STATUS = {
        ACTIVE: { label: '진행 중', cls: 'live' },
        PAUSED: { label: '일시정지', cls: 'paused' },
        DRAFT: { label: '작성 중', cls: '' },
        ENDED: { label: '종료', cls: '' }
    };

    let campaignsData = [];
    let selectedCampaignId = null;
    let activeDropdown = null;

    document.addEventListener('click', (e) => {
        if (activeDropdown && !activeDropdown.contains(e.target)) closeDropdown();
    });
    [searchInput, statusFilter, typeFilter].forEach(el => el.addEventListener('input', renderActivities));

    document.getElementById('create-campaign-btn').addEventListener('click', () => {
        window.adminModalHandler?.showCreateCampaignModal?.();
    });
    document.getElementById('create-activity-btn').addEventListener('click', () => {
        if (!selectedCampaignId) return;
        if (window.adminModalHandler?.showCreateActivityModal) {
            window.adminModalHandler.showCreateActivityModal(selectedCampaignId);
        } else {
            window.location.href = `/admin/campaign-activities/new?campaignId=${selectedCampaignId}`;
        }
    });

    fetchCampaigns();

    function fetchCampaigns() {
        fetch('/api/v1/campaigns')
            .then(response => response.json())
            .then(data => {
                // Running first, then upcoming, then ended; newest start first within each group.
                const rank = c => ({ live: 0, soon: 1, '': 2 })[campaignState(c).cls];
                campaignsData = data.sort((a, b) => rank(a) - rank(b) || new Date(b.startAt) - new Date(a.startAt));
                document.getElementById('campaign-count').textContent =
                    `진행 중 ${campaignsData.filter(c => campaignState(c).cls === 'live').length} · 전체 ${campaignsData.length}`;
                renderCampaignList();
                if (campaignsData.length > 0) selectCampaign(campaignsData[0].id);
            })
            .catch(error => {
                console.error('캠페인 목록 조회 오류:', error);
                listContainer.innerHTML = '<div class="empty low">목록을 불러오지 못했습니다.</div>';
            });
    }

    function campaignState(campaign) {
        const now = new Date();
        if (now < new Date(campaign.startAt)) return { label: '예정', cls: 'soon' };
        if (now > new Date(campaign.endAt)) return { label: '종료', cls: '' };
        return { label: '진행 중', cls: 'live' };
    }

    function renderCampaignList() {
        if (campaignsData.length === 0) {
            listContainer.innerHTML = '<div class="empty">등록된 캠페인이 없습니다.</div>';
            return;
        }
        listContainer.innerHTML = '';
        campaignsData.forEach(campaign => {
            const state = campaignState(campaign);
            const item = document.createElement('div');
            item.className = 'c-item';
            item.dataset.id = campaign.id;
            item.innerHTML = `
                <div class="c-name"><span title="${esc(campaign.name)}">${esc(campaign.name)}</span>
                    <button class="icon-btn" title="캠페인 수정"><i class="fa-solid fa-pen"></i></button></div>
                <div class="c-meta"><span class="num">${formatDateShort(campaign.startAt)} – ${formatDateShort(campaign.endAt)}</span>
                    <span class="pill ${state.cls}">${state.label}</span></div>`;
            item.querySelector('.icon-btn').addEventListener('click', (e) => {
                e.stopPropagation();
                window.adminModalHandler?.showEditCampaignModal?.(campaign.id);
            });
            item.addEventListener('click', () => selectCampaign(campaign.id));
            listContainer.appendChild(item);
        });
    }

    function selectCampaign(id) {
        const campaign = campaignsData.find(c => c.id === id);
        if (!campaign) return;
        selectedCampaignId = id;
        document.querySelectorAll('.c-item').forEach(el => el.classList.toggle('active', Number(el.dataset.id) === id));

        noCampaignSelected.hidden = true;
        detailHeader.hidden = false;
        activityContent.hidden = false;

        const state = campaignState(campaign);
        document.getElementById('selected-campaign-name').textContent = campaign.name;
        document.getElementById('selected-campaign-date').textContent = `${formatDate(campaign.startAt)} – ${formatDate(campaign.endAt)}`;
        const statusEl = document.getElementById('selected-campaign-status');
        statusEl.className = `pill ${state.cls}`;
        statusEl.innerHTML = `<i></i>${state.label}`;

        const activities = campaign.campaignActivities || [];
        const counts = {};
        activities.forEach(a => { counts[a.activityType] = (counts[a.activityType] || 0) + 1; });
        const fcfs = activities.filter(a => a.activityType === 'FIRST_COME_FIRST_SERVE');
        const participants = fcfs.reduce((sum, a) => sum + (a.participantCount || 0), 0);
        const capacity = fcfs.reduce((sum, a) => sum + (a.limitCount || 0), 0);
        const active = activities.filter(a => a.status === 'ACTIVE').length;
        const budget = activities.reduce((sum, a) => sum + (a.budget || 0), 0);

        setText('stat-total-activities', activities.length);
        setText('stat-type-mix', Object.entries(counts).map(([t, n]) => `${TYPE_LABELS[t] || t} ${n}`).join(' · ') || '-');
        setText('stat-active-activities', active);
        setText('stat-active-sub', active ? '참여를 받는 중' : '진행 중인 활동 없음');
        setText('stat-total-participants', participants.toLocaleString());
        setText('stat-fill', capacity ? `정원 ${capacity.toLocaleString()}명 중 ${Math.round(participants / capacity * 100)}%` : '선착순 활동 없음');
        setText('stat-budget', budget ? krw(budget) : '-');

        renderActivities();
    }

    function renderActivities() {
        const campaign = campaignsData.find(c => c.id === selectedCampaignId);
        if (!campaign) return;
        const query = searchInput.value.trim().toLowerCase();
        const activities = (campaign.campaignActivities || []).filter(a =>
            (!query || a.name.toLowerCase().includes(query))
            && (!statusFilter.value || a.status === statusFilter.value)
            && (!typeFilter.value || a.activityType === typeFilter.value));

        if (activities.length === 0) {
            activityTableBody.innerHTML = '<tr><td colspan="7" class="empty">조건에 맞는 활동이 없습니다.</td></tr>';
            return;
        }

        activityTableBody.innerHTML = activities.map(activity => {
            const status = STATUS[activity.status] || { label: activity.status, cls: '' };
            const isFcfs = activity.activityType === 'FIRST_COME_FIRST_SERVE';
            const name = isFcfs
                ? `<a href="/admin/dashboard/${activity.id}">${esc(activity.name)}</a>`
                : `<span class="act-name">${esc(activity.name)}</span>`;
            const detail = activity.productName
                ? `${esc(activity.productName)} · ${Number(activity.price).toLocaleString()}원`
                : activity.couponName ? `${esc(activity.couponName)}${activity.holdoutRatePercent ? ` · 홀드아웃 ${activity.holdoutRatePercent}%` : ''}` : '';
            return `<tr>
                <td>${name}<div class="act-sub">${detail}</div></td>
                <td class="tag">${TYPE_LABELS[activity.activityType] || activity.activityType}</td>
                <td><span class="pill ${status.cls}"><i></i>${status.label}</span></td>
                <td class="num tag">${formatDateShort(activity.startDate)} – ${formatDateShort(activity.endDate)}</td>
                <td>${progressCell(activity)}</td>
                <td class="r num">${activity.budget ? krw(activity.budget) : '-'}</td>
                <td><button class="menu-btn" data-id="${activity.id}"><i class="fa-solid fa-ellipsis pointer-events-none"></i></button></td>
            </tr>`;
        }).join('');

        activityTableBody.querySelectorAll('.menu-btn').forEach(btn => {
            const activity = activities.find(a => String(a.id) === btn.dataset.id);
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                openMenu(btn, activity);
            });
        });
    }

    function progressCell(activity) {
        if (activity.activityType !== 'FIRST_COME_FIRST_SERVE' || !activity.limitCount) return '<span class="tag">-</span>';
        const count = activity.participantCount || 0;
        const percent = Math.min(100, Math.round(count / activity.limitCount * 100));
        return `<span class="bar"><i style="width:${percent}%"></i></span>
            <span class="num tag" style="margin-left:6px">${count.toLocaleString()}/${activity.limitCount.toLocaleString()}</span>`;
    }

    function openMenu(anchor, activity) {
        closeDropdown();
        const modal = window.adminModalHandler || {};
        const items = [['수정', '', () => modal.showEditModal?.(activity.id, activity.name)]];
        const status = (label, next, cls = '') => [label, cls, () => modal.showStatusModal?.(activity.id, activity.name, activity.status, next)];
        if (activity.status === 'DRAFT') items.push(status('게시', 'ACTIVE'));
        if (activity.status === 'ACTIVE') items.push(status('일시정지', 'PAUSED'), status('종료', 'ENDED', 'danger'));
        if (activity.status === 'PAUSED') items.push(status('재개', 'ACTIVE'));
        items.push(null, ['삭제', 'danger', () => modal.showDeleteModal?.(activity.id, activity.name)]);

        const dropdown = document.createElement('div');
        dropdown.className = 'dropdown';
        items.forEach(item => {
            if (!item) { dropdown.appendChild(document.createElement('hr')); return; }
            const [label, cls, action] = item;
            const button = document.createElement('button');
            button.textContent = label;
            button.className = cls;
            button.addEventListener('click', () => { action(); closeDropdown(); });
            dropdown.appendChild(button);
        });
        const rect = anchor.getBoundingClientRect();
        dropdown.style.top = `${rect.bottom + 4}px`;
        dropdown.style.left = `${rect.right - 128}px`;
        document.body.appendChild(dropdown);
        activeDropdown = dropdown;
    }

    function closeDropdown() {
        activeDropdown?.remove();
        activeDropdown = null;
    }

    function setText(id, value) {
        document.getElementById(id).textContent = value;
    }

    function krw(value) {
        const n = Number(value);
        if (n >= 100_000_000) return `${(n / 100_000_000).toFixed(2)}억`;
        if (n >= 10_000) return `${Math.round(n / 10_000).toLocaleString()}만`;
        return `${n.toLocaleString()}원`;
    }

    function esc(text) {
        return String(text ?? '').replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]);
    }

    function formatDateShort(dateString) {
        if (!dateString) return '-';
        const date = new Date(dateString);
        return `${date.getMonth() + 1}.${date.getDate()}`;
    }

    function formatDate(dateString) {
        if (!dateString) return '-';
        const date = new Date(dateString);
        const pad = n => String(n).padStart(2, '0');
        return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())}`;
    }
});
