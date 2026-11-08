/*
 * Verifies the real-time pipeline: logs in, opens a STOMP-over-SockJS connection with the JWT,
 * waits for bus location messages on /topic/location, and checks that a connection WITHOUT a token
 * is rejected.
 *
 * Usage (backend running):  node scripts/verify-websocket.cjs [email] [password]
 */
const { Client } = require('@stomp/stompjs');
const SockJS = require('sockjs-client');

const API = process.env.API_URL || 'http://localhost:8080/api';
const WS = process.env.WS_URL || 'http://localhost:8080/ws';
const email = process.argv[2] || 'user@transport.com';
const password = process.argv[3] || 'user123';

function connect(token) {
  return new Promise((resolve) => {
    const received = [];
    const client = new Client({
      webSocketFactory: () => new SockJS(WS),
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 0,
      debug: () => {},
      onConnect: () => {
        client.subscribe('/topic/location', (msg) => received.push(JSON.parse(msg.body)));
        setTimeout(() => { client.deactivate(); resolve({ connected: true, received }); }, 12000);
      },
      onStompError: () => { client.deactivate(); resolve({ connected: false, received }); },
      onWebSocketClose: () => resolve({ connected: false, received }),
    });
    client.activate();
  });
}

(async () => {
  const res = await fetch(`${API}/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }),
  });
  if (!res.ok) { console.error('Login failed', res.status); process.exit(1); }
  const { token } = await res.json();

  const withToken = await connect(token);
  const buses = new Set(withToken.received.map((m) => m.busId));
  console.log(`Authenticated connection: connected=${withToken.connected}, messages=${withToken.received.length}, distinct buses=${buses.size}`);
  if (withToken.received[0]) console.log('Sample message:', JSON.stringify(withToken.received[0]));

  const withoutToken = await connect(null);
  console.log(`Connection without token: connected=${withoutToken.connected} (expected false)`);

  const ok = withToken.connected && withToken.received.length > 0 && !withoutToken.connected;
  console.log(ok ? 'WEBSOCKET CHECK PASSED' : 'WEBSOCKET CHECK FAILED');
  process.exit(ok ? 0 : 1);
})();
