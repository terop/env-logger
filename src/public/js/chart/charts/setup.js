import { WIND_DIRECTION_SERIES } from '../constants.js';
import { chartState } from '../state.js';
import { createChartManager } from '../echarts/chart-manager.js';
import { buildObsEchartsOption, buildWeatherEchartsOption } from './observation.js';
import { updateYAxisForLegendSelection } from './observation-controller.js';
import {
  buildDayElecOption,
  buildHourElecOption,
  buildMinuteElecOption,
  getClosestElecPriceDataIndex,
  refreshHourElecBarColours,
  refreshMinuteElecBarColours
} from './electricity.js';
import {
  clearElecColourRefreshIntervals,
  fetchElecData,
  fetchMinutePrice,
  handleElecError,
  scheduleElecColourRefresh
} from '../api/electricity.js';
import { toggleVisibility } from '../ui/dom.js';
import {
  appendElecLatestPrices,
  appendElecMonthSummary
} from '../ui/info-text.js';
import { createTestbedImageClickHandler } from '../ui/testbed-image.js';
import { getDateTime } from '../globals.js';

export const initObservationCharts = () => {
  chartState.charts.weather = createChartManager({
    elementId: 'weatherPlot',
    buildOption: (legendSelected) => buildWeatherEchartsOption(legendSelected),
    hooks: {
      onInit: (instance) => {
        instance.on(
          'click',
          createTestbedImageClickHandler(
            () => chartState.dataLabels.weather,
            WIND_DIRECTION_SERIES
          )
        );
        instance.on('legendselectchanged', (params) => {
          updateYAxisForLegendSelection('weather', params.selected);
        });
      }
    }
  });

  chartState.charts.other = createChartManager({
    elementId: 'otherPlot',
    buildOption: (legendSelected) => buildObsEchartsOption('other', legendSelected),
    hooks: {
      onInit: (instance) => {
        instance.on(
          'click',
          createTestbedImageClickHandler(() => chartState.dataLabels.other)
        );
        instance.on('legendselectchanged', (params) => {
          updateYAxisForLegendSelection('other', params.selected);
        });
      }
    }
  });

  chartState.charts.ruuvitag = createChartManager({
    elementId: 'ruuvitagPlot',
    buildOption: (legendSelected) =>
      buildObsEchartsOption('ruuvitag', legendSelected),
    hooks: {
      onInit: (instance) => {
        instance.on('legendselectchanged', (params) => {
          updateYAxisForLegendSelection('ruuvitag', params.selected);
        });
      }
    }
  });

  chartState.charts.weather.initOrUpdate();
  chartState.charts.other.initOrUpdate();
  chartState.charts.ruuvitag.initOrUpdate();

  chartState.charts.weather.bindAccordionResize('weatherPlotAccordion');
  chartState.charts.other.bindAccordionResize('otherPlotAccordion');
  chartState.charts.ruuvitag.bindAccordionResize('ruuvitagPlotAccordion');
};

export const initElecCharts = () => {
  chartState.charts.hourElec = createChartManager({
    elementId: 'hourElecDataPlot',
    buildOption: () => ({})
  });
  chartState.charts.dayElec = createChartManager({
    elementId: 'dayElecDataPlot',
    buildOption: () => ({})
  });
  chartState.charts.minuteElec = createChartManager({
    elementId: 'minuteElecDataPlot',
    buildOption: () => ({})
  });

  chartState.charts.hourElec.bindAccordionResize('elecHourPlotAccordion');
  chartState.charts.dayElec.bindAccordionResize('elecDayPlotAccordion');
  chartState.charts.minuteElec.bindAccordionResize('elecMinutePlotAccordion');
};

export const plotElectricityDataHour = (elecData, {
  updateDate = false,
  removeLast = false
} = {}) => {
  const DateTime = getDateTime();
  const { option, summary } = buildHourElecOption(elecData, { removeLast });

  if (updateDate) {
    document.getElementById('elecEndDate').value = summary.lastX
      ? DateTime.fromJSDate(summary.lastX).toISODate()
      : DateTime.now().toISODate();
  }

  document.getElementById('elecInfoBox').innerHTML =
    'Current interval: consumption: ' +
    `${summary.consumptionSum.toFixed(2)} kWh, average price: ` +
    `${summary.averagePrice.toFixed(2)} c / kWh, ` +
    'total cost: <span id="intervalCost"></span> €';

  const manager = chartState.charts.hourElec;
  manager.setOption(option, { notMerge: true });
  manager.resize();
};

export const plotElectricityDataDay = (elecData, { removeLast = false } = {}) => {
  const option = buildDayElecOption(elecData, { removeLast });
  const manager = chartState.charts.dayElec;
  manager.setOption(option, { notMerge: true });
  manager.resize();
};

export const plotElectricityPriceMinute = (priceData) => {
  const option = buildMinuteElecOption(priceData);
  const manager = chartState.charts.minuteElec;
  manager.setOption(option, { notMerge: true });
  manager.resize();
};

export const showElectricityData = async () => {
  clearElecColourRefreshIntervals();

  const DateTime = getDateTime();
  const currentDate = DateTime.now().toISODate();
  const dateField = document.getElementById('elecMinuteDate');
  dateField.value = currentDate;

  try {
    const elecData = await fetchElecData();

    if (!elecData) {
      return;
    }

    if (elecData.error) {
      if (elecData.error !== 'not-enabled') {
        console.log(`Electricity data fetch error: ${elecData.error}`);
      }
      document.getElementById('elecDataDiv').classList.toggle('display-none');
      return;
    }

    if (!elecData['data-hour'] || !elecData['data-day'][0]) {
      toggleVisibility('elecDataDiv');
      return;
    }

    // Start minute-price fetch early to overlap network time with chart updates
    const minutePricePromise = fetchMinutePrice(currentDate, { getDate: true });

    if (elecData.dates.max) {
      const dateMax = elecData.dates.max;
      document.getElementById('elecStartDate').max = dateMax;
      document.getElementById('elecEndDate').max = dateMax;
      document.getElementById('elecMinuteDate').max = dateMax;
    }

    if (elecData.dates.min) {
      const dateMin = elecData.dates.min;
      document.getElementById('elecStartDate').min = dateMin;
      document.getElementById('elecEndDate').min = dateMin;
    }

    if (elecData.dates.current.start) {
      document.getElementById('elecStartDate').value = elecData.dates.current.start;
    }

    if (elecData['price-thresholds']) {
      chartState.elec.thresholds = elecData['price-thresholds'];
    }

    appendElecLatestPrices(elecData['data-hour'], getClosestElecPriceDataIndex);
    plotElectricityDataHour(elecData['data-hour'], {
      updateDate: true,
      removeLast: true
    });
    plotElectricityDataDay(elecData['data-day'], { removeLast: true });

    document.getElementById('intervalCost').innerText =
      elecData['interval-cost'] !== null ? elecData['interval-cost'] : 0;

    appendElecMonthSummary(elecData);

    if (DateTime.fromISO(document.getElementById('elecEndDate').value) >=
        DateTime.fromISO(DateTime.now().toISODate())) {
      scheduleElecColourRefresh(refreshHourElecBarColours);
    }

    const minuteData = await minutePricePromise;
    if (!minuteData.prices) {
      document.getElementById('elecMinuteAccordion').style.display = 'none';
      return;
    }

    plotElectricityPriceMinute(minuteData.prices);
    dateField.min = minuteData['date-min'];
    scheduleElecColourRefresh(refreshMinuteElecBarColours);
  } catch (error) {
    if (handleElecError(error)) {
      return;
    }
    console.log(`Electricity setup error: ${error}`);
  }
};

export const refreshElecDataForDateRange = async (startDate, endDate) => {
  try {
    const elecData = await fetchElecData({ startDate, endDate });

    if (!elecData) {
      return;
    }

    if (elecData.error) {
      if (elecData.error !== 'not-enabled') {
        console.log(`Electricity data fetch error: ${elecData.error}`);
      }
      return;
    }

    document.getElementById('elecStartDate').value = elecData.dates.current.start;
    document.getElementById('elecEndDate').value = elecData.dates.current.end;

    plotElectricityDataHour(elecData['data-hour']);
    plotElectricityDataDay(elecData['data-day']);

    if (elecData['month-price-avg']) {
      const monthAvg = document.getElementById('elecMonthAvg');
      if (monthAvg) {
        monthAvg.innerText = elecData['month-price-avg'];
      }
    }
    document.getElementById('intervalCost').innerText =
      elecData['interval-cost'] !== null ? elecData['interval-cost'] : 0;
  } catch (error) {
    handleElecError(error);
  }
};

export const refreshElecMinutePriceForDate = async (minuteDate) => {
  try {
    const elecData = await fetchMinutePrice(minuteDate);
    if (elecData.error) {
      console.log(`Electricity data fetch error: ${elecData.error}`);
      return;
    }
    plotElectricityPriceMinute(elecData.prices);
  } catch (error) {
    handleElecError(error, 'Electricity price');
  }
};
