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
  hideDateRangeError,
  showDateRangeError,
  toggleLoadingSpinner,
  toggleVisibility
} from '../ui/dom.js';
import { scrollToBottom } from '../ui/dom.js';

const validateDateInterval = (startDate, endDate) => {
  const DateTime = getDateTime();
  if ((startDate && DateTime.fromISO(startDate).invalid) ||
      (endDate && DateTime.fromISO(endDate).invalid)) {
    alert('Error: either the start or end date is invalid');
    return false;
  }
  if (DateTime.fromISO(startDate) > DateTime.fromISO(endDate)) {
    alert('Error: start date must be smaller than the end date');
    return false;
  }
  return true;
};

const updateButtonClickHandler = async (event) => {
  const startDate = document.getElementById('startDate').value;
  const endDate = document.getElementById('endDate').value;
  const DateTime = getDateTime();
  let isSpinnerShown = false;

  if (!validateDateInterval(startDate, endDate)) {
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

  if (!validateDateInterval(startDate, endDate)) {
    event.preventDefault();
    return false;
  }

  await refreshElecDataForDateRange(startDate, endDate);
  return true;
};

const elecMinuteDateUpdateBtnClickHandler = async (event) => {
  const minuteDate = document.getElementById('elecMinuteDate').value;
  const DateTime = getDateTime();

  if (minuteDate && DateTime.fromISO(minuteDate).invalid) {
    alert('Error: electricity price date is invalid');
    event.preventDefault();
    return false;
  }

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

  if (direction === 'forward') {
    const newDate = DateTime.fromISO(dateField.value).plus({ days: 1 });
    if (DateTime.fromISO(dateField.max) >= newDate) {
      try {
        const elecData = await fetchMinutePrice(newDate.toISODate());
        if (elecData.error) {
          console.log(`Electricity data fetch error: ${elecData.error}`);
          return;
        }
        dateField.value = newDate.toISODate();
        plotElectricityPriceMinute(elecData.prices);
      } catch (error) {
        handleElecError(error, 'Electricity price');
      }
    } else {
      alert('You are already at the newest date');
    }
  } else {
    const newDate = DateTime.fromISO(dateField.value).minus({ days: 1 });
    if (DateTime.fromISO(dateField.min) <= newDate) {
      try {
        const elecData = await fetchMinutePrice(newDate.toISODate());
        if (elecData.error) {
          console.log(`Electricity data fetch error: ${elecData.error}`);
          return;
        }
        dateField.value = newDate.toISODate();
        plotElectricityPriceMinute(elecData.prices);
      } catch (error) {
        handleElecError(error, 'Electricity price');
      }
    } else {
      alert('You are already at the oldest date');
    }
  }
};

export const bindAlwaysEvents = () => {
  document.getElementById('updateBtn').addEventListener(
    'click',
    updateButtonClickHandler,
    false
  );

  document.getElementById('elecUpdateBtn').addEventListener(
    'click',
    elecUpdateButtonClickHandler,
    false
  );

  document.getElementById('elecMinuteDateUpdateBtn').addEventListener(
    'click',
    elecMinuteDateUpdateBtnClickHandler,
    false
  );

  document.getElementById('elecPriceShowFees').addEventListener(
    'change',
    elecPriceShowFeesChangeHandler,
    false
  );

  document.getElementById('elecMinuteDayBackward').addEventListener(
    'click',
    () => {
      updateMinuteElecPrice('backward');
    },
    false
  );

  document.getElementById('elecMinuteDayForward').addEventListener(
    'click',
    () => {
      updateMinuteElecPrice('forward');
    },
    false
  );

  document.getElementById('showImages').addEventListener(
    'click',
    () => {
      toggleVisibility('imageDiv');
    },
    false
  );

  document.getElementById('weatherHideAll').addEventListener(
    'click',
    () => {
      hideAllObsSeries('weather');
    },
    false
  );

  document.getElementById('weatherShowAll').addEventListener(
    'click',
    () => {
      showAllObsSeries('weather');
    },
    false
  );
};

export const bindDataEvents = () => {
  document.getElementById('showInfoText').addEventListener(
    'click',
    () => {
      toggleVisibility('infoText');
    },
    false
  );

  document.getElementById('otherHideAll').addEventListener(
    'click',
    () => {
      hideAllObsSeries('other');
    },
    false
  );

  document.getElementById('otherShowAll').addEventListener(
    'click',
    () => {
      showAllObsSeries('other');
    },
    false
  );

  document.getElementById('ruuvitagHideAll').addEventListener(
    'click',
    () => {
      hideAllObsSeries('ruuvitag');
    },
    false
  );

  document.getElementById('ruuvitagShowAll').addEventListener(
    'click',
    () => {
      showAllObsSeries('ruuvitag');
    },
    false
  );

  document.getElementById('ruuvitagShowTemperature').addEventListener(
    'click',
    () => {
      showRuuvitagSeriesType('temperature');
    },
    false
  );

  document.getElementById('ruuvitagShowHumidity').addEventListener(
    'click',
    () => {
      showRuuvitagSeriesType('humidity');
    },
    false
  );

  document.getElementById('elecPlotAccordion').addEventListener(
    'shown.bs.collapse',
    () => {
      scrollToBottom(0);
    },
    false
  );
};
