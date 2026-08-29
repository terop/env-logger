import { chartState } from '../state.js';
import { AuthLoadError, getJson, HttpError } from './http.js';
import {
  dateRangeTooLargeMessage,
  hideDateRangeError,
  redirectToLogin,
  showDateRangeError,
  showDisplayResolution,
  showSetupError
} from '../ui/dom.js';

export const handleDisplayDataError = (error) => {
  if (error instanceof AuthLoadError) {
    showSetupError('Authentication setup failed. Reload the page.');
    return;
  }

  const status = error instanceof HttpError
    ? error.status
    : error?.status;

  if (status === 401) {
    redirectToLogin();
    return;
  }

  if (status === 400) {
    const data = error instanceof HttpError ? error.body : error?.body;
    if (typeof data === 'object' && data?.error === 'date-range-too-large') {
      showDateRangeError(
        dateRangeTooLargeMessage(data['max-days'] ?? chartState.maxDisplayDays)
      );
      return;
    }
    if (typeof data === 'string' && data.includes('Date range')) {
      showDateRangeError(dateRangeTooLargeMessage(chartState.maxDisplayDays));
      return;
    }
  }

  showDateRangeError('Failed to load chart data');
  console.error(`Display data fetch error: ${error}`);
};

export const applyDisplayPayload = (rData) => {
  if (rData['tb-image-basepath'] != null) {
    chartState.testbedImageBasepath = rData['tb-image-basepath'];
  }
  if (rData['weather-data'] != null) {
    chartState.data.weather = rData['weather-data'];
  }
  if (rData['weather-obs-data'] != null) {
    chartState.data.weatherObs = rData['weather-obs-data'];
  }
  if (rData['obs-data'] != null) {
    chartState.data.other = rData['obs-data'];
  }
  if (rData['rt-data'] != null) {
    chartState.data.rt = rData['rt-data'];
  }
  if (rData['rt-names']) {
    chartState.names.ruuvitag = rData['rt-names'];
  }
  if (rData['max-display-days'] != null) {
    chartState.maxDisplayDays = rData['max-display-days'];
  }
  showDisplayResolution(rData['display-resolution']);
};

export const fetchDisplayData = (params = {}) => getJson('data/display', params);

export const syncObsDateInputs = (rData) => {
  hideDateRangeError();
  if (rData['obs-dates']?.['min-max']) {
    const intMinMax = rData['obs-dates']['min-max'];
    document.getElementById('startDate').min = intMinMax.start;
    document.getElementById('startDate').max = intMinMax.end;
    document.getElementById('endDate').min = intMinMax.start;
    document.getElementById('endDate').max = intMinMax.end;
  }
  if (rData['obs-dates']?.current) {
    document.getElementById('startDate').value = rData['obs-dates'].current.start;
    document.getElementById('endDate').value = rData['obs-dates'].current.end;
  }
};
