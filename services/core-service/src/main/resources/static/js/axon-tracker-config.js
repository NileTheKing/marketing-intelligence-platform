/**
 * Axon Behavior Tracker 기본 설정
 * - 각 환경(development/staging/production)에 맞는 값을 템플릿이나 빌드 단계에서 덮어쓰세요.
 */
(function (global) {
  'use strict';

  const DEFAULT_API_BASE = ''; // Use relative path (same origin via Ingress)
  const DEFAULT_COLLECT_ENDPOINT = '/api/v1/behavior-events'; // entry-service via Ingress
  const DEFAULT_DIAGNOSTICS_ENDPOINT = '/api/v1/behavior-events/diagnostics';
  const DEFAULT_SCRIPT_SRC = '/js/behavior-tracker.js';
  const SESSION_STORAGE_KEY = 'axon.behavior.session-id';

  function getOrCreateSessionId() {
    if (global.__AXON_SESSION_ID__) {
      return global.__AXON_SESSION_ID__;
    }

    try {
      const stored = global.sessionStorage.getItem(SESSION_STORAGE_KEY);
      if (stored) {
        return stored;
      }

      const created = global.crypto && typeof global.crypto.randomUUID === 'function'
        ? global.crypto.randomUUID()
        : `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
      global.sessionStorage.setItem(SESSION_STORAGE_KEY, created);
      return created;
    } catch (error) {
      return `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    }
  }

  const overrides = global.__AXON_TRACKER_OVERRIDES__ || {};

  console.log('[AxonTracker] overrides', overrides);

  console.log('[AxonTracker] init config', overrides);
  global.AxonTrackerConfig = Object.assign(
    {
      apiBaseUrl: overrides.apiBaseUrl || global.__AXON_TRACKER_API_BASE__ || DEFAULT_API_BASE,
      collectEndpoint: overrides.collectEndpoint || global.__AXON_TRACKER_COLLECT_ENDPOINT__ || DEFAULT_COLLECT_ENDPOINT,
      diagnosticsEndpoint: overrides.diagnosticsEndpoint || global.__AXON_TRACKER_DIAGNOSTICS_ENDPOINT__ || DEFAULT_DIAGNOSTICS_ENDPOINT,
      tokenProvider: overrides.tokenProvider || (typeof global.__AXON_TRACKER_TOKEN_PROVIDER__ === 'function'
        ? global.__AXON_TRACKER_TOKEN_PROVIDER__
        : () => (global.__AXON_TRACKER_TOKEN__ || null)),
      sessionIdProvider: overrides.sessionIdProvider || (typeof global.__AXON_TRACKER_SESSION_PROVIDER__ === 'function'
        ? global.__AXON_TRACKER_SESSION_PROVIDER__
        : getOrCreateSessionId),
      debug: overrides.debug ?? global.__AXON_TRACKER_DEBUG__ ?? false
    },
    global.AxonTrackerConfig || {}
  );

  const trackerSrc = overrides.scriptSrc || global.__AXON_TRACKER_SCRIPT_SRC__ || DEFAULT_SCRIPT_SRC;
  console.log('[AxonTracker] loading tracker', trackerSrc);

  /**
   * Initializes the global AxonBehaviorTracker using the global AxonTrackerConfig if the tracker is present.
   */
  function initTracker() {
    if (!global.AxonBehaviorTracker) {
      return;
    }
    global.AxonBehaviorTracker.init(global.AxonTrackerConfig);
  }

  if (global.AxonBehaviorTracker) {
    console.log('[AxonTracker] init tracker inline');
    initTracker();
  } else {
    console.log('[AxonTracker] load tracker async');
    const script = document.createElement('script');
    script.src = trackerSrc;
    script.async = true;
    script.onload = function () {
      console.log('[AxonTracker] tracker script loaded');
      initTracker();
    };
    script.onerror = function (error) {
      console.error('[AxonTracker] failed to load tracker', trackerSrc, error);
      console.error('[AxonTracker] Failed to load tracker script:', trackerSrc, error);
    };
    document.head.appendChild(script);
    console.log('[AxonTracker] script appended');
  }
})(window);
