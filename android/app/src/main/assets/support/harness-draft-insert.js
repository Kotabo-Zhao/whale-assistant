(function (text, expectedPage) {
  'use strict';
  if (typeof text !== 'string' || !text.trim() || text.length > 2000) return 'invalid';
  if (typeof expectedPage !== 'string' || window.location.href !== expectedPage) return 'unavailable';
  const editors = Array.from(document.querySelectorAll('[data-composer-input="true"][contenteditable="true"]'))
    .filter(editor => editor.getAttribute('aria-disabled') !== 'true' && editor.getClientRects().length > 0);
  if (editors.length !== 1 || typeof document.execCommand !== 'function') return 'unavailable';
  const editor = editors[0];
  editor.focus();
  const selection = window.getSelection();
  if (!selection) return 'unavailable';
  const range = document.createRange();
  range.selectNodeContents(editor);
  range.collapse(false);
  selection.removeAllRanges();
  selection.addRange(range);
  return document.execCommand('insertText', false, text) ? 'inserted' : 'unavailable';
})(/*__DRAFT_INPUT__*/null, /*__DRAFT_PAGE__*/null)
