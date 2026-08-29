import { chartState } from '../state.js';
import { getJson, HttpError } from './http.js';
import { redirectToLogin } from '../ui/dom.js';

const addFeesChecked = () =>
  document.getElementById('elecPriceShowFees')?.checked ?? false;

export const handleElecError = (error, label = 'Electricity data') => {
  const status = error instanceof HttpError ? error.status : error?.status;
  if (status === 401) {
    redirectToLogin();
    return true;
  }
  console.error(`${label} fetch error: ${error}`);
  return false;
};

export const fetchElecData = (params = {}) =>
  getJson('data/elec-data', {
    addFees: addFeesChecked(),
    ...params
  });

export const getCachedMinutePrices = (date) => {
  const cached = chartState.elec.minuteCache[date];
  if (cached !== undefined && cached.addFees === addFeesChecked()) {
    return cached.prices;
  }
  return null;
};

export const setCachedMinutePrices = (date, prices) => {
  chartState.elec.minuteCache[date] = {
    prices,
    addFees: addFeesChecked()
  };
};

/**
 * Fetch 15-minute electricity prices for a date, using cache when valid.
 * @returns {Promise<object|null>} API payload or cached shape { prices, ... }
 */
export const fetchMinutePrice = async (date, { getDate = false } = {}) => {
  const cached = getCachedMinutePrices(date);
  if (cached && !getDate) {
    return { prices: cached };
  }

  const elecData = await getJson('data/elec-price-minute', {
    date,
    getDate: getDate || undefined,
    addFees: addFeesChecked()
  });

  if (elecData.prices) {
    setCachedMinutePrices(date, elecData.prices);
  }
  return elecData;
};

export const clearElecColourRefreshIntervals = () => {
  for (const id of chartState.elec.colourRefreshIntervals) {
    clearInterval(id);
  }
  chartState.elec.colourRefreshIntervals = [];
};

export const scheduleElecColourRefresh = (fn, ms = 120000) => {
  const id = setInterval(fn, ms);
  chartState.elec.colourRefreshIntervals.push(id);
  return id;
};
