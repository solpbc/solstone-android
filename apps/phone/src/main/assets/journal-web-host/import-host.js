// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

// Installed at document start in frames on the journal's origin. Only the top journal document
// gets the import host; the app also refuses any message that does not come from the main frame.
((contract, origin, bridgeName) => {
  if (window !== window.top || location.origin !== origin) return;
  const bridge = window[bridgeName];
  if (!bridge) return;
  let sequence = 0;
  const pending = new Map();
  bridge.onmessage = event => {
    let answer;
    try { answer = JSON.parse(event.data); } catch (_) { return; }
    const waiting = pending.get(answer?.seq);
    if (!waiting) return;
    pending.delete(answer.seq);
    waiting(answer.body);
  };
  const post = (message, context) => new Promise(resolve => {
    const seq = ++sequence;
    pending.set(seq, resolve);
    bridge.postMessage(JSON.stringify({seq, message, context}));
  });
  const items = new Map();
  let timer;
  async function update() {
    for (const [item, record] of items) {
      try {
        const status = await post({type:'host.status', item});
        const serial = JSON.stringify(status);
        if (record.last !== serial) {
          record.last = serial;
          window.dispatchEvent(new CustomEvent(contract.event, {detail:{
            ...status, type:contract.status, item, name:record.name, id:record.id,
          }}));
        }
        if (['attention', 'delivered', 'gone'].includes(status.state)) items.delete(item);
      } catch (_) { /* Read the status again while the document is open. */ }
    }
    timer = items.size ? setTimeout(update, 400) : undefined;
  }
  const isObject = value => typeof value === 'object' && value !== null && !Array.isArray(value);
  Object.defineProperty(window, contract.capability, {value:Object.freeze({
    version:contract.version, types:Object.freeze([contract.pick]),
    async send(message) {
      if (!message || typeof message.id !== 'string' || typeof message.type !== 'string') throw new TypeError();
      if (message.type !== contract.pick) return {type:contract.unsupported, id:message.id};
      const request = {type:message.type, id:message.id, area:message.area, multiple:message.multiple,
        accept:Array.isArray(message.accept) ? message.accept.filter(entry => typeof entry === 'string') : null};
      const reply = await post(request, isObject(message.context) ? JSON.stringify(message.context) : null);
      for (const item of reply.items || []) items.set(item.item, {id:message.id, name:item.name});
      if (items.size && !timer) timer = setTimeout(update, 0);
      return reply;
    }
  })});
})(__CONTRACT__, __ORIGIN__, __BRIDGE__);
