(function (expectedPage) {
  'use strict';
  if (typeof expectedPage !== 'string' || window.location.href !== expectedPage) return JSON.stringify({ status: 'unavailable' });
  const selection = window.getSelection();
  let text = '';
  if (selection && !selection.isCollapsed && selection.anchorNode && selection.focusNode) {
    const anchor = selection.anchorNode.parentElement?.closest('[data-chat-flow-kind="assistant-step"]');
    const focus = selection.focusNode.parentElement?.closest('[data-chat-flow-kind="assistant-step"]');
    if (anchor && anchor === focus && anchor.getClientRects().length > 0) text = selection.toString().trim();
  }
  if (!text) {
    const rows = Array.from(document.querySelectorAll('[data-chat-flow-kind="assistant-step"][data-chat-group-part="response"]'))
      .filter(row => row.getClientRects().length > 0 && !row.closest('[hidden]'));
    const last = rows[rows.length - 1];
    if (!last || last.querySelector('[data-streaming="true"]')) return JSON.stringify({ status: 'unavailable' });
    const clone = last.cloneNode(true);
    clone.querySelectorAll('button, script, style, [hidden], [aria-hidden="true"], [data-message-attachments]').forEach(node => node.remove());
    text = (clone.textContent || '').trim();
  }
  if (!text) return JSON.stringify({ status: 'unavailable' });
  const limit = 2000;
  return JSON.stringify({ status: 'ok', text: text.slice(0, limit), truncated: text.length > limit });
})(/*__VOICE_PAGE__*/null)
