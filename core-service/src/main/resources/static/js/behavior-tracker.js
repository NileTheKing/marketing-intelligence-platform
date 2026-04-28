(function (global) {
  'use strict';

  const DEFAULTS = {
    apiBaseUrl: '',
    eventsEndpoint: '/api/v1/events/active',
    collectEndpoint: '/api/v1/behavior/events',
    autoRefreshMs: 5 * 60 * 1000,
    debug: false,
    withCredentials: true,
    cooldownMs: 1500
  };

  const TriggerType = {
    PAGE_VIEW: 'PAGE_VIEW',
    CLICK: 'CLICK',
    SCROLL: 'SCROLL',
    STAY: 'STAY'
  };

  /**
   * Tracker that periodically loads event definitions, registers client-side handlers for configured triggers,
   * and prepares collected behavior events for delivery to the backend.
   */
  function BehaviorTracker() {
    console.log('[AxonBehaviorTracker] constructor');
    this.config = { ...DEFAULTS };
    this.state = {
      events: [],
      lastSentAt: new Map(),
      initialized: false,
      refreshing: false,
      entryTime: Date.now(),
      maxScrollDepth: 0,
      sentDepths: new Set()
    };
  }

  /**
   * 사용자 정의 설정을 적용하고 트래커를 초기화한다.
   */
  BehaviorTracker.prototype.init = async function init(userConfig = {}) {
    console.log('[AxonBehaviorTracker] init', userConfig);
    if (this.state.initialized) {
      this.log('Tracker already initialized, skipping init.');
      return this.state.initialized;
    }

    this.config = { ...DEFAULTS, ...userConfig };
    console.log('[AxonBehaviorTracker] config', this.config);
    this.state.initialized = await this.fetchAndBind();

    if (this.config.autoRefreshMs > 0) {
      setInterval(() => this.refreshEvents(), this.config.autoRefreshMs);
    }

    // 체류 시간 추적을 위해 페이지 이탈 시 이벤트 전송
    window.addEventListener('beforeunload', () => this.handleStayEvent());

    this.log('Behavior tracker initialized with config:', this.config);
    return this.state.initialized;
  };

  /**
   * 이벤트 정의를 조회하고 핸들러를 등록한다.
   */
  BehaviorTracker.prototype.fetchAndBind = async function fetchAndBind() {
    console.log('[AxonBehaviorTracker] fetchAndBind start');
    try {
      const events = await this.fetchActiveEvents();
      this.state.events = events || [];
      this.log('Fetched active events:', events);
      this.registerHandlers();
      return true;
    } catch (error) {
      console.error('[AxonTracker] Failed to initialize tracker', error);
      return false;
    }
  };

  BehaviorTracker.prototype.refreshEvents = async function refreshEvents() {
    console.log('[AxonBehaviorTracker] refreshEvents');
    if (this.state.refreshing) {
      return;
    }
    this.state.refreshing = true;
    try {
      const events = await this.fetchActiveEvents();
      this.state.events = events || [];
      this.log('Refreshed active events:', events);
    } catch (error) {
      console.error('[AxonTracker] Failed to refresh events', error);
    } finally {
      this.state.refreshing = false;
    }
  };

  /**
   * 활성 이벤트 목록을 코어 서비스에서 가져온다.
   */
  BehaviorTracker.prototype.fetchActiveEvents = async function fetchActiveEvents() {
    const url = this.resolveUrl(this.config.eventsEndpoint);
    const headers = await this.buildAuthHeaders();
    
    const response = await fetch(url, {
      method: 'GET',
      headers,
      credentials: this.config.withCredentials ? 'include' : 'same-origin'
    });

    if (!response.ok) {
      throw new Error(`Failed to fetch active events: ${response.status}`);
    }

    return response.json();
  };

  /**
   * 트리거 타입에 따라 감지 핸들러를 등록한다.
   */
  BehaviorTracker.prototype.registerHandlers = function registerHandlers() {
    this.registerPageViewHandlers();
    this.registerClickHandlers();
    this.registerScrollHandlers();
  };

  /**
   * History API를 감시해 PAGE_VIEW 이벤트를 감지한다.
   */
  BehaviorTracker.prototype.registerPageViewHandlers = function registerPageViewHandlers() {
    const check = () => {
        // 페이지 이동 시 상태 초기화
        this.state.entryTime = Date.now();
        this.state.maxScrollDepth = 0;
        this.state.sentDepths.clear();
        this.handlePageView();
    };

    window.addEventListener('load', check, { once: true });
    window.addEventListener('popstate', () => setTimeout(check, 0));

    ['pushState', 'replaceState'].forEach((method) => {
      const original = window.history[method];
      if (typeof original === 'function') {
        window.history[method] = (...args) => {
          const result = original.apply(window.history, args);
          setTimeout(check, 0);
          return result;
        };
      }
    });

    check();
  };

  /**
   * 현재 URL이 등록된 패턴과 일치하는지 확인하고, 해당 이벤트를 전송한다.
   */
  BehaviorTracker.prototype.handlePageView = function handlePageView() {
    const fullPath = window.location.pathname + window.location.search;
    const pathOnly = window.location.pathname;
    const matching = this.state.events.filter(
      (event) =>
        event.triggerType === TriggerType.PAGE_VIEW &&
        (this.matchesUrl(event.triggerPayload, fullPath) || this.matchesUrl(event.triggerPayload, pathOnly))
    );

    matching.forEach((event) => {
      this.sendEvent(event, {
        pageUrl: window.location.href,
        referrer: document.referrer || null
      });
    });
  };

  /**
   * 스크롤 깊이를 감지하고 25% 단위로 이벤트를 전송한다.
   */
  BehaviorTracker.prototype.registerScrollHandlers = function registerScrollHandlers() {
    let throttleTimer;
    window.addEventListener('scroll', () => {
      if (throttleTimer) return;
      
      throttleTimer = setTimeout(() => {
        const winHeight = window.innerHeight;
        const docHeight = document.documentElement.scrollHeight;
        const scrollTop = window.scrollY || document.documentElement.scrollTop;
        const scrollPercent = Math.round((scrollTop + winHeight) / docHeight * 100);

        [25, 50, 75, 100].forEach(depth => {
            if (scrollPercent >= depth && !this.state.sentDepths.has(depth)) {
                this.state.sentDepths.add(depth);
                this.handleScrollDepth(depth);
            }
        });
        
        throttleTimer = null;
      }, 200); // 200ms Throttling
    });
  };

  BehaviorTracker.prototype.handleScrollDepth = function handleScrollDepth(depth) {
    // SCROLL 타입의 이벤트가 정의되어 있는지 확인
    const scrollEvents = this.state.events.filter(e => e.triggerType === TriggerType.SCROLL);
    
    // 만약 백엔드에 정의가 없더라도 기본 행동 로그로 전송 (Synthetic Event)
    const baseEvent = scrollEvents.length > 0 ? scrollEvents[0] : { id: 0, name: 'Scroll Depth', triggerType: 'SCROLL' };
    
    this.sendEvent(baseEvent, {
        depth: depth,
        pageUrl: window.location.href
    });
  };

  /**
   * 체류 시간을 계산하여 전송한다 (이탈 시 호출).
   */
  BehaviorTracker.prototype.handleStayEvent = function handleStayEvent() {
    const stayTimeSec = Math.round((Date.now() - this.state.entryTime) / 1000);
    const stayEvents = this.state.events.filter(e => e.triggerType === TriggerType.STAY);
    
    const baseEvent = stayEvents.length > 0 ? stayEvents[0] : { id: 0, name: 'Stay Duration', triggerType: 'STAY' };
    
    // 이탈 시에는 fetch keepalive 옵션을 사용하여 유실 방지
    this.sendEvent(baseEvent, {
        durationSec: stayTimeSec,
        pageUrl: window.location.href
    });
  };

  /**
   * URL 패턴(와일드카드 지원)을 기반으로 현재 경로가 조건에 맞는지 검사한다.
   */
  BehaviorTracker.prototype.matchesUrl = function matchesUrl(payload, currentPath) {
    if (!payload || !payload.urlPattern) {
      return true;
    }
    const pattern = payload.urlPattern
      .replace(/[.+?^${}()|[\]\\]/g, '\\$&')
      .replace(/\*/g, '.*');
    const regexp = new RegExp(`^${pattern}$`);
    return regexp.test(currentPath);
  };

  /**
   * 캡처 가능한 클릭 이벤트를 등록하고 전송한다.
   */
  BehaviorTracker.prototype.registerClickHandlers = function registerClickHandlers() {
    const clickEvents = this.state.events.filter(
      (event) => event.triggerType === TriggerType.CLICK
    );

    if (clickEvents.length === 0) {
      return;
    }

    document.addEventListener('click', (event) => {
      const { target } = event;
      if (!target) {
        return;
      }

      clickEvents.forEach((definition) => {
        const selector = definition.triggerPayload?.selector;
        if (!selector) {
          return;
        }

        const matchedElement = target.closest(selector);
        if (matchedElement) {
          this.sendEvent(definition, {
            pageUrl: window.location.href,
            referrer: document.referrer || null,
            selector,
            elementText: getSanitizedText(matchedElement),
            elementTag: matchedElement.tagName,
            elementId: matchedElement.id || null
          });
        }
      });
    });
  };

  /**
   * 이벤트 전송 로직
   * - 동일 이벤트의 과도한 전송을 막기 위해 cooldown을 적용한다.
   */
  BehaviorTracker.prototype.sendEvent = async function sendEvent(eventDefinition, properties = {}) {
    console.log('[AxonBehaviorTracker] sendEvent', eventDefinition);
    if (!eventDefinition || !eventDefinition.triggerType) {
      this.log('Skip sending event: invalid definition', eventDefinition);
      return;
    }

    const now = Date.now();
    const lastSent = this.state.lastSentAt.get(eventDefinition.id);
    if (lastSent && now - lastSent < this.config.cooldownMs) {
      this.log('Skip sending event due to cooldown', eventDefinition);
      return;
    }

    this.state.lastSentAt.set(eventDefinition.id, now);

    const requestPayload = {
      eventId: eventDefinition.id,
      eventName: eventDefinition.name,
      triggerType: eventDefinition.triggerType,
      occurredAt: new Date().toISOString(),
      pageUrl: properties.pageUrl || window.location.href,
      referrer: properties.referrer ?? document.referrer ?? null,
      userId: await resolveValue(this.config.userIdProvider),
      sessionId: await resolveValue(this.config.sessionIdProvider),
      properties
    };

    const url = this.resolveUrl(this.config.collectEndpoint);
    const headers = await this.buildAuthHeaders();
    console.log('[AxonBehaviorTracker] headers', headers);
    headers['Content-Type'] = 'application/json';

    try {
      console.log('[AxonBehaviorTracker] sending event', url, requestPayload);
      const response = await fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(requestPayload),
        credentials: this.config.withCredentials ? 'include' : 'same-origin',
        keepalive: true
      });

      if (!response.ok) {
        throw new Error(`Failed to send event: ${response.status}`);
      }

      this.log('Sent behavior event', requestPayload);
    } catch (error) {
      console.error('[AxonTracker] Failed to send behavior event', error);
    }
  };

  /**
   * Authorization 헤더를 구성한다.
   */
  BehaviorTracker.prototype.buildAuthHeaders = async function buildAuthHeaders() {
    console.log('[AxonBehaviorTracker] buildAuthHeaders');
    const headers = {};
    const token = await resolveValue(this.config.tokenProvider);
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    return headers;
  };

  /**
   * 상대 경로를 apiBaseUrl 기준으로 절대 URL로 변환한다.
   */
  BehaviorTracker.prototype.resolveUrl = function resolveUrl(path) {
    console.log('[AxonBehaviorTracker] resolveUrl', path);
    if (!path) {
      return '';
    }
    if (path.startsWith('http://') || path.startsWith('https://')) {
      return path;
    }
    const base = this.config.apiBaseUrl || '';
    if (!base) {
      return path;
    }
    return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`;
  };

  BehaviorTracker.prototype.log = function log(...args) {
    if (this.config.debug) {
      console.log('[AxonTracker]', ...args);
    }
  };

  /**
   * Extracts visible text from a DOM element, trims surrounding whitespace, and truncates it to 200 characters.
   * @param {Element} element - The DOM element to read text from.
   * @returns {string} The trimmed text content of the element, limited to 200 characters.
   */
  function getSanitizedText(element) {
    const text = element.innerText || element.textContent || '';
    return text.trim().slice(0, 200);
  }

  /**
   * Resolve a value or, if given a function, invoke it and return its result.
   * @param {*} candidate - A value or function. If a function, it will be called and its return value used.
   * @returns {*} The candidate value, the result of invoking the candidate when it is a function, or `null` if the candidate is `undefined`.
   */
  async function resolveValue(candidate) {
    console.log('[AxonBehaviorTracker] resolveValue', candidate);
    if (typeof candidate === 'function') {
      return candidate();
    }
    return candidate ?? null;
  }

  global.AxonBehaviorTracker = new BehaviorTracker();
})(window);