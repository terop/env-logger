import {
  checkDateInterval,
  dateIntervalErrorMessage,
  isInvalidIsoDate
} from '../data/dates.js';
import { inclusiveDayCount } from '../echarts/axis.js';
import { getDateTime } from '../globals.js';
import { chartState } from '../state.js';
import { transformData } from '../data/parse.js';
import {
  applyDisplayPayload,
  fetchDisplayData,
  handleDisplayDataError
} from '../api/display.js';
import {
  hideAllObsSeries,
  showAllObsSeries,
  showRuuvitagSeriesType
} from '../charts/observation-controller.js';
import {
  plotElectricityPriceMinute,
  refreshElecDataForDateRange,
  refreshElecMinutePriceForDate
} from '../charts/setup.js';
import { fetchMinutePrice, handleElecError } from '../api/electricity.js';
import {
  dateRangeTooLargeMessage,
  hideAlert,
  hideDateRangeError,
  showAlert,
  showDateRangeError,
  toggleLoadingSpinner,
  toggleVisibility,
  scrollToBottom
} from '../ui/dom.js';

const OBS_DATE_ERROR = 'dateRangeError';
const ELEC_DATE_ERROR = 'elecDateRangeError';
const ELEC_MINUTE_DATE_ERROR = 'elecMinuteDateError';

const bindClick = (elementId, handler) => {
  document.getElementById(elementId).addEventListener('click', handler, false);
};

const bindShowHideAll = (plotType) => {
  bindClick(`${plotType}HideAll`, () => hideAllObsSeries(plotType));
  bindClick(`${plotType}ShowAll`, () => showAllObsSeries(plotType));
};

const validateDateInterval = (startDate, endDate, errorElementId) => {
  const result = checkDateInterval(startDate, endDate);
  if (result.ok) {
    hideAlert(errorElementId);
    return true;
  }
  showAlert(errorElementId, dateIntervalErrorMessage(result.error));
  return false;
};

const updateButtonClickHandler = async (event) => {
  const startDate = document.getElementById('startDate').value;
  const endDate = document.getElementById('endDate').value;
  const DateTime = getDateTime();
  let isSpinnerShown = false;

  if (!validateDateInterval(startDate, endDate, OBS_DATE_ERROR)) {
    event.preventDefault();
    return;
  }

  if (inclusiveDayCount(startDate, endDate) > chartState.maxDisplayDays) {
    showDateRangeError(dateRangeTooLargeMessage(chartState.maxDisplayDays));
    event.preventDefault();
    return;
  }

  hideDateRangeError();

  const diff = DateTime.fromISO(endDate).diff(
    DateTime.fromISO(startDate),
    ['days']
  );

  if (diff.days >= 7) {
    isSpinnerShown = true;
    toggleLoadingSpinner();
  }

  try {
    const rData = await fetchDisplayData({
      startDate,
      endDate,
      includeMeta: false
    });
    applyDisplayPayload(rData);
    if (rData['obs-dates']?.current) {
      document.getElementById('startDate').value = rData['obs-dates'].current.start;
      document.getElementById('endDate').value = rData['obs-dates'].current.end;
    }
    transformData();
    chartState.charts.weather?.initOrUpdate({ preserveLegend: true });
    chartState.charts.other?.initOrUpdate({ preserveLegend: true });
    chartState.charts.ruuvitag?.initOrUpdate({ preserveLegend: true });
  } catch (error) {
    handleDisplayDataError(error);
  } finally {
    if (isSpinnerShown) {
      toggleLoadingSpinner();
    }
  }
};

const elecUpdateButtonClickHandler = async (event) => {
  const startDate = document.getElementById('elecStartDate').value;
  const endDate = document.getElementById('elecEndDate').value;

  if (!validateDateInterval(startDate, endDate, ELEC_DATE_ERROR)) {
    event.preventDefault();
    return false;
  }

  await refreshElecDataForDateRange(startDate, endDate);
  return true;
};

const elecMinuteDateUpdateBtnClickHandler = async (event) => {
  const minuteDate = document.getElementById('elecMinuteDate').value;

  if (isInvalidIsoDate(minuteDate)) {
    showAlert(ELEC_MINUTE_DATE_ERROR, 'Electricity price date is invalid');
    event.preventDefault();
    return false;
  }

  hideAlert(ELEC_MINUTE_DATE_ERROR);
  await refreshElecMinutePriceForDate(minuteDate);
  return true;
};

const elecPriceShowFeesChangeHandler = () => {
  refreshElecDataForDateRange(
    document.getElementById('elecStartDate').value,
    document.getElementById('elecEndDate').value
  );
  refreshElecMinutePriceForDate(document.getElementById('elecMinuteDate').value);
};

const updateMinuteElecPrice = async (direction) => {
  const DateTime = getDateTime();
  const dateField = document.getElementById('elecMinuteDate');
  const stepDays = direction === 'forward' ? 1 : -1;
  const newDate = DateTime.fromISO(dateField.value).plus({ days: stepDays });
  const atLimit = direction === 'forward'
    ? DateTime.fromISO(dateField.max) < newDate
    : DateTime.fromISO(dateField.min) > newDate;

  if (atLimit) {
    showAlert(
      ELEC_MINUTE_DATE_ERROR,
      direction === 'forward'
        ? 'You are already at the newest date'
        : 'You are already at the oldest date'
    );
    return;
  }

  hideAlert(ELEC_MINUTE_DATE_ERROR);

  try {
    const elecData = await fetchMinutePrice(newDate.toISODate());
    if (elecData.error) {
      console.error(`Electricity data fetch error: ${elecData.error}`);
      return;
    }
    dateField.value = newDate.toISODate();
    plotElectricityPriceMinute(elecData.prices);
  } catch (error) {
    handleElecError(error, 'Electricity price');
  }
};

export const bindAlwaysEvents = () => {
  bindClick('updateBtn', updateButtonClickHandler);
  bindClick('elecUpdateBtn', elecUpdateButtonClickHandler);
  bindClick('elecMinuteDateUpdateBtn', elecMinuteDateUpdateBtnClickHandler);
  document.getElementById('elecPriceShowFees').addEventListener(
    'change',
    elecPriceShowFeesChangeHandler,
    false
  );
  bindClick('elecMinuteDayBackward', () => updateMinuteElecPrice('backward'));
  bindClick('elecMinuteDayForward', () => updateMinuteElecPrice('forward'));
  bindClick('showImages', () => toggleVisibility('imageDiv'));
  bindShowHideAll('weather');
};

export const bindDataEvents = () => {
  bindClick('showInfoText', () => toggleVisibility('infoText'));
  bindShowHideAll('other');
  bindShowHideAll('ruuvitag');
  bindClick(
    'ruuvitagShowTemperature',
    () => showRuuvitagSeriesType('temperature')
  );
  bindClick(
    'ruuvitagShowHumidity',
    () => showRuuvitagSeriesType('humidity')
  );
  document.getElementById('elecPlotAccordion').addEventListener(
    'shown.bs.collapse',
    () => {
      scrollToBottom(0);
    },
    false
  );
};
