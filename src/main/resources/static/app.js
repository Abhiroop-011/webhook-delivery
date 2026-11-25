const el = id => document.getElementById(id);
const warehouse = new URL(location.origin);
warehouse.port = '8081';
let selected = null;
let refreshing = false;
let pendingSubmission = null;
let previousRows = '';

function newOrder() {
  return 'ORD-' + crypto.randomUUID().slice(0, 8);
}

function node(tag, text, className) {
  const item = document.createElement(tag);
  item.textContent = text;
  if (className) item.className = className;
  return item;
}

function badge(value) {
  const item = node('span', value, 'badge');
  item.dataset.state = value;
  return item;
}

async function json(url, options) {
  const response = await fetch(url, { ...options, cache: 'no-store', signal: AbortSignal.timeout(5000) });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || 'HTTP ' + response.status);
  }
  return response.json();
}

function renderEvents(events) {
  const fingerprint = JSON.stringify([selected, events]);
  if (fingerprint === previousRows) return;
  previousRows = fingerprint;
  const list = el('events');
  list.replaceChildren();
  if (!events.length) list.append(node('li', 'Send an order to see its delivery here.', 'empty'));
  for (const event of events) {
    const button = node('button', '', 'event');
    button.setAttribute('aria-current', String(selected === event.id));
    const title = node('span', event.eventType);
    title.append(node('small', event.id.slice(0, 8) + ' · ' + new Date(event.createdAt).toLocaleTimeString()));
    button.append(title, badge(event.state));
    button.onclick = () => {
      if (selected === event.id) return;
      selectEvent(event.id);
      refresh();
    };
    const li = node('li', '');
    li.append(button);
    list.append(li);
  }
}

function selectEvent(id) {
  selected = id;
  previousRows = '';
  el('selection').textContent = id;
  el('state').replaceChildren();
  el('attempts').replaceChildren();
  el('receipt').textContent = 'Loading delivery history…';
  el('replay').hidden = true;
}

function renderHistory(history) {
  el('selection').textContent = history.event.id;
  el('state').replaceChildren(badge(history.event.state));
  el('replay').hidden = history.event.state !== 'DEAD';
  const list = el('attempts');
  list.replaceChildren();
  if (!history.attempts.length) list.append(node('li', 'Waiting for a worker to claim the event.'));
  for (const attempt of history.attempts) {
    const li = node('li', 'Attempt ' + attempt.number + '  ');
    li.append(badge(attempt.outcome));
    const fields = [new Date(attempt.startedAt).toLocaleTimeString(), attempt.detail];
    if (attempt.latencyMs !== null) fields.push(attempt.latencyMs + ' ms');
    li.append(node('small', fields.filter(Boolean).join(' · ')));
    list.append(li);
  }
}

async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    const events = await json('/events');
    if (!selected && events.length) selected = events[0].id;
    renderEvents(events);
    const id = selected;
    if (id) {
      const [history, receipt] = await Promise.all([
        json('/events/' + id),
        json(warehouse.origin + '/demo/orders/' + id).catch(() => null)
      ]);
      if (id === selected) {
        renderHistory(history);
        el('receipt').textContent = !receipt ? 'Warehouse receipt unavailable'
          : receipt.saved ? 'Warehouse order saved' : 'No warehouse order recorded yet';
      }
    }
    el('connection').textContent = 'Updated ' + new Date().toLocaleTimeString();
    const status = await json(warehouse.origin + '/demo').catch(() => null);
    el('warehouse-status').textContent = status ? status.mode + ' · ' + status.orders + ' total orders saved'
                                               : 'Warehouse status unavailable';
  } catch (error) {
    el('connection').textContent = 'Refresh failed · ' + error.message;
  } finally {
    refreshing = false;
  }
}

el('order').value = newOrder();
el('order').oninput = () => { pendingSubmission = null; };
el('send').onsubmit = async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button');
  button.disabled = true;
  const input = el('order');
  input.disabled = true;
  const orderId = input.value.trim();
  try {
    if (!orderId) throw new Error('Enter an order ID.');
    if (!pendingSubmission) pendingSubmission = { key: crypto.randomUUID(), orderId };
    const reply = await json('/events', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': pendingSubmission.key },
      body: JSON.stringify({ destination: 'warehouse', eventType: 'order.created',
        payload: { orderId: pendingSubmission.orderId } })
    });
    selectEvent(reply.id);
    pendingSubmission = null;
    input.value = newOrder();
    el('feedback').textContent = 'Accepted ' + reply.id;
    await refresh();
  } catch (error) { el('feedback').textContent = error.message; }
  finally { button.disabled = false; input.disabled = false; }
};

el('set-mode').onsubmit = async event => {
  event.preventDefault();
  try {
    const status = await json(warehouse.origin + '/demo/mode/' + el('mode').value, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
    });
    el('warehouse-status').textContent = status.mode + ' · ' + status.orders + ' total orders saved';
  } catch (error) { el('warehouse-status').textContent = error.message; }
};

el('replay').onclick = async () => {
  el('replay').disabled = true;
  try {
    await json('/events/' + selected + '/replay', { method: 'POST' });
    el('feedback').textContent = 'Replay queued.';
    await refresh();
  } catch (error) { el('feedback').textContent = error.message; }
  finally { el('replay').disabled = false; }
};

refresh();
setInterval(refresh, 1200);
