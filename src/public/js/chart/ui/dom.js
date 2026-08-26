import { displayResolutionLabels } from '../constants.js';
import { chartState } from '../state.js';

let authRedirectPending = false;

export const hideElement = (elementId) => {
  document.getElementById(elementId).style.display = 'none';
};

export const toggleClassForElement = (elementId, className) => {
  document.getElementById(elementId).classList.toggle(className);
};

export const toggleVisibility = (elementId) => {
  toggleClassForElement(elementId, 'display-none');
};

export const toggleLoadingSpinner = () => {
  document.getElementsByTagName('body')[0].classList.toggle('top-padding');
  toggleClassForElement('bodyDiv', 'top-padding');
  toggleClassForElement('loadingSpinner', 'fg-blur');
  toggleVisibility('loadingSpinner');
  toggleClassForElement('bodyDiv', 'bg-blur');
};

export const dateRangeTooLargeMessage = (maxDays) =>
  `Date range exceeds the maximum of ${maxDays} days`;

export const showDateRangeError = (message) => {
  const note = document.getElementById('dateRangeError');
  note.textContent = message;
  note.classList.remove('display-none');
};

export const hideDateRangeError = () => {
  const note = document.getElementById('dateRangeError');
  note.textContent = '';
  note.classList.add('display-none');
};

export const showSetupError = (message) => {
  showDateRangeError(message);
};

export const showDisplayResolution = (resolution) => {
  chartState.displayResolution = resolution || null;
  const note = document.getElementById('displayResolutionNote');
  const label = resolution && displayResolutionLabels[resolution];

  if (label) {
    note.textContent = label;
    note.classList.remove('display-none');
  } else {
    note.textContent = '';
    note.classList.add('display-none');
  }
};

export const scrollToBottom = (timeout) => {
  window.setTimeout(() => {
    window.scroll(0, document.body.scrollHeight);
  }, timeout);
};

export const isAuthRedirectPending = () => authRedirectPending;

export const resetAuthRedirectPending = () => {
  authRedirectPending = false;
};

export const setAuthRedirectPending = (pending) => {
  authRedirectPending = pending;
};

export const redirectToLogin = () => {
  if (authRedirectPending) {
    return;
  }
  authRedirectPending = true;

  const base =
    globalThis.authSettings?.applicationUrl ??
    globalThis.applicationUrl ??
    '';
  if (globalThis.location) {
    globalThis.location.href = `${base}login`;
  }
};
