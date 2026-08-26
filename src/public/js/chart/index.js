import { transformData } from './data/parse.js';
import {
  applyDisplayPayload,
  fetchDisplayData,
  handleDisplayDataError,
  syncObsDateInputs
} from './api/display.js';
import {
  initElecCharts,
  initObservationCharts,
  showElectricityData
} from './charts/setup.js';
import { bindAlwaysEvents, bindDataEvents } from './events/bind-events.js';
import { hideElement, showSetupError } from './ui/dom.js';
import { showLastObservation } from './ui/info-text.js';
import { chartState } from './state.js';

export const initPage = () => {
  bindAlwaysEvents();

  if (!chartState.data.other?.recorded?.length) {
    document.getElementById('noDataError').style.display = 'block';
    hideElement('imageButtonDiv');
    hideElement('latestCheckboxDiv');
    hideElement('plotAccordion');
    hideElement('elecDataDiv');
    return;
  }

  transformData();
  showLastObservation();
  initObservationCharts();
  initElecCharts();
  showElectricityData();
  bindDataEvents();
};

export const bootstrap = async () => {
  try {
    if (globalThis.authReady) {
      await globalThis.authReady;
    }
  } catch {
    showSetupError('Authentication setup failed. Reload the page.');
    return;
  }

  try {
    const rData = await fetchDisplayData();
    applyDisplayPayload(rData);
    syncObsDateInputs(rData);
    initPage();
  } catch (error) {
    handleDisplayDataError(error);
  }
};

bootstrap();

setInterval(() => {
  if (typeof globalThis.refreshTokensIfNeeded === 'function') {
    globalThis.refreshTokensIfNeeded();
  }
}, 30000);
