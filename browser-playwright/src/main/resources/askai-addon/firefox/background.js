// AskAI Research add-on, background page. The ONLY place that talks to AskAI: it long-polls the HUD state from the
// local bridge (127.0.0.1, port and token in bridge.json, written by AskAI at every browser start) and posts the
// user's HUD commands back. Nothing runs inside visited pages: no content script, no web-accessible resource, so a
// page has nothing of this add-on to observe.
'use strict';

const PROTOCOL = 1;
const POLL_RETRY_MIN_MS = 500;
const POLL_RETRY_MAX_MS = 5000;

let bridge = null;          // {base, token} once bridge.json was read
let connection = 'starting'; // starting | connected | retrying | no-bridge
let version = 0;
let hud = null;             // the last HUD state from AskAI
const panels = new Set();

async function loadBridge() {
  try {
    const response = await fetch(browser.runtime.getURL('bridge.json'), {cache: 'no-store'});
    if (!response.ok) return null;
    const config = await response.json();
    if (config.protocol !== PROTOCOL || !config.port || !config.token) return null;
    return {base: 'http://127.0.0.1:' + config.port, token: String(config.token)};
  } catch (e) {
    return null;
  }
}

function headers(extra) {
  return Object.assign({'Authorization': 'Bearer ' + bridge.token}, extra || {});
}

async function hello() {
  const response = await fetch(bridge.base + '/v1/hello', {
    method: 'POST',
    headers: headers({'Content-Type': 'application/json'}),
    body: JSON.stringify({protocol: PROTOCOL, addonVersion: browser.runtime.getManifest().version})
  });
  if (!response.ok) throw new Error('hello ' + response.status);
}

async function pollLoop() {
  let retry = POLL_RETRY_MIN_MS;
  let greeted = false;
  for (;;) {
    try {
      if (!greeted) {
        await hello();
        greeted = true;
      }
      const response = await fetch(bridge.base + '/v1/state?after=' + version,
          {headers: headers(), cache: 'no-store'});
      if (response.status === 200) {
        const body = await response.json();
        version = body.version;
        hud = body.state;
      } else if (response.status !== 204) {
        throw new Error('state ' + response.status);
      }
      setConnection('connected');
      retry = POLL_RETRY_MIN_MS;
      if (response.status === 200) publish();
    } catch (e) {
      greeted = false;
      setConnection('retrying');
      await new Promise(resolve => setTimeout(resolve, retry));
      retry = Math.min(POLL_RETRY_MAX_MS, retry * 2);
    }
  }
}

function setConnection(next) {
  if (connection !== next) {
    connection = next;
    publish();
  }
}

async function sendCommand(command) {
  if (!bridge || typeof command !== 'string' || command.length === 0 || command.length > 64) return;
  try {
    await fetch(bridge.base + '/v1/command', {
      method: 'POST',
      headers: headers({'Content-Type': 'text/plain;charset=UTF-8'}),
      body: command
    });
  } catch (e) {
    // The next poll reports the broken connection; a lost click is shown as such by the panel state.
  }
}

/** Toolbar badge: the wait countdown in red while AskAI waits for the user, a pause mark while paused. */
function updateBadge() {
  let text = '';
  let color = '#2b6cb0';
  let title = 'AskAI Research';
  if (connection !== 'connected') {
    text = connection === 'no-bridge' ? '' : '…';
    title += connection === 'no-bridge' ? ' — not started by AskAI' : ' — connecting';
  } else if (hud) {
    if (!hud.terminal && hud.waiting && hud.countdown >= 0) {
      text = String(hud.countdown);
      color = '#b3261e';
    } else if (!hud.terminal && hud.paused) {
      text = 'II';
    }
    title += ' — ' + hud.phase + (hud.status ? ': ' + hud.status : '');
  }
  browser.browserAction.setBadgeText({text});
  browser.browserAction.setBadgeBackgroundColor({color});
  browser.browserAction.setTitle({title});
}

function snapshot() {
  return {type: 'state', connection, hud};
}

function publish() {
  updateBadge();
  const message = snapshot();
  for (const port of panels) {
    try {
      port.postMessage(message);
    } catch (e) {
      panels.delete(port);
    }
  }
}

browser.runtime.onConnect.addListener(port => {
  if (port.name !== 'askai-panel') return;
  panels.add(port);
  port.onDisconnect.addListener(() => panels.delete(port));
  port.onMessage.addListener(message => {
    if (message && message.type === 'command') sendCommand(message.command);
  });
  port.postMessage(snapshot());
});

(async () => {
  bridge = await loadBridge();
  if (!bridge) {
    setConnection('no-bridge');
    return;
  }
  updateBadge();
  pollLoop();
})();
