import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const WS_URL = import.meta.env.VITE_WS_URL || 'http://localhost:8080/ws';

/**
 * One shared STOMP connection for the whole app.
 *
 * - The JWT is sent in the CONNECT frame; the backend rejects connections without it.
 * - Topic subscriptions are (re)created inside onConnect, so they only happen once the
 *   connection is established and are restored automatically after a reconnect.
 * - Pages register listeners with subscribe(topic, fn) and get an unsubscribe function back;
 *   the connection closes when the last listener leaves.
 *
 * Status values: 'idle' | 'connecting' | 'live' | 'reconnecting' | 'unauthorized'
 */
class WebSocketService {
  constructor() {
    this.client = null;
    this.status = 'idle';
    this.listeners = new Map(); // topic -> Set<fn>
    this.stompSubs = new Map(); // topic -> StompSubscription
    this.statusListeners = new Set();
  }

  setStatus(status) {
    this.status = status;
    this.statusListeners.forEach((fn) => fn(status));
  }

  onStatus(fn) {
    this.statusListeners.add(fn);
    fn(this.status);
    return () => this.statusListeners.delete(fn);
  }

  ensureConnected() {
    if (this.client) return;
    const token = localStorage.getItem('token');
    if (!token) {
      this.setStatus('unauthorized');
      return;
    }
    this.setStatus('connecting');
    this.client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 4000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      debug: () => {},
      onConnect: () => {
        this.stompSubs.clear();
        this.listeners.forEach((_, topic) => this.subscribeOnServer(topic));
        this.setStatus('live');
      },
      onStompError: (frame) => {
        // The backend sends an ERROR frame when the token is missing/expired.
        const unauthorized = (frame.headers?.message || '').toLowerCase().includes('unauthorized');
        if (unauthorized) {
          this.setStatus('unauthorized');
          this.disconnect(false);
        }
      },
      onWebSocketClose: () => {
        if (this.client && this.status !== 'unauthorized') this.setStatus('reconnecting');
      },
    });
    this.client.activate();
  }

  subscribeOnServer(topic) {
    if (!this.client?.connected || this.stompSubs.has(topic)) return;
    const sub = this.client.subscribe(topic, (message) => {
      let body;
      try {
        body = JSON.parse(message.body);
      } catch {
        return;
      }
      this.listeners.get(topic)?.forEach((fn) => fn(body));
    });
    this.stompSubs.set(topic, sub);
  }

  /** Listen to a topic; returns a cleanup function. */
  subscribe(topic, fn) {
    if (!this.listeners.has(topic)) this.listeners.set(topic, new Set());
    this.listeners.get(topic).add(fn);
    this.ensureConnected();
    this.subscribeOnServer(topic);

    return () => {
      const set = this.listeners.get(topic);
      if (!set) return;
      set.delete(fn);
      if (set.size === 0) {
        this.listeners.delete(topic);
        this.stompSubs.get(topic)?.unsubscribe();
        this.stompSubs.delete(topic);
      }
      if (this.listeners.size === 0) this.disconnect();
    };
  }

  disconnect(resetStatus = true) {
    const client = this.client;
    this.client = null;
    this.stompSubs.clear();
    if (client) client.deactivate();
    if (resetStatus) this.setStatus('idle');
  }

  /** Call on logout so the next user connects with their own token. */
  reset() {
    this.listeners.clear();
    this.disconnect();
  }
}

const websocketService = new WebSocketService();
export default websocketService;
export const LOCATION_TOPIC = '/topic/location';
export const CROWD_TOPIC = '/topic/crowd';
export const OCCUPANCY_TOPIC = '/topic/occupancy';
