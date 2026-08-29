import { chartState } from '../state.js';
import {
  formatNiceAxisLabel,
  getDataExtremeValues,
  getElecPriceYAxisRange,
  getNiceYAxisRange
} from '../echarts/axis.js';
import { buildHourBoundaryMarkLines } from '../echarts/mark-lines.js';
import {
  axisTooltipFormatter,
  hasTooltipPointData
} from '../echarts/tooltips.js';
import { getDateTime } from '../globals.js';
import {
  generateElecHourBarChartColours,
  generateElecMinuteChartBarColours
} from './elec-colours.js';

const arraySum = (array) => array.reduce((acc, curr) => acc + (curr ?? 0), 0);

const arrayAverage = (array) => {
  const values = array.filter((v) => v != null && !Number.isNaN(v));
  return values.length === 0 ? 0 : arraySum(values) / values.length;
};

const EMPTY_Y_AXIS = { min: 0, max: 1, interval: 0.5 };

export const baseElecGrid = {
  left: 60,
  right: 60,
  top: 50,
  bottom: 75
};

export const baseElecLegend = {
  orient: 'horizontal',
  bottom: 0
};

const elecTooltipLine = (seriesName, y, p) => {
  if (!hasTooltipPointData(y)) {
    return null;
  }
  const unit = seriesName === 'Consumption' ? 'kWh' : 'c / kWh';
  return `<br/>${p.marker}${seriesName}: ${y} ${unit}`;
};

export const buildHourElecOption = (elecData, { removeLast = false } = {}) => {
  const DateTime = getDateTime();
  const xValues = [];
  const data = { price: [], consumption: [] };

  for (let i = 0; i < elecData.length - (removeLast ? 1 : 0); i++) {
    const item = elecData[i];
    xValues.push(DateTime.fromISO(item['start-time']).toJSDate());
    data.price.push(item.price);
    data.consumption.push(item.consumption);
  }

  chartState.elec.hourCache = { xValues, prices: data.price };

  const extValuesConsp = getDataExtremeValues([data.consumption]);
  const extValuesPrice = getDataExtremeValues([data.price]);
  // Consumption is left-joined; price-only environments yield null extremes
  const { min: priceMin, max: priceMax, interval: priceInterval } =
    extValuesPrice
      ? getElecPriceYAxisRange(
        extValuesPrice[0],
        extValuesPrice[extValuesPrice.length - 1]
      )
      : EMPTY_Y_AXIS;
  const { min: conspMin, max: conspMax, interval: conspInterval } =
    extValuesConsp
      ? getNiceYAxisRange(
        extValuesConsp[0],
        extValuesConsp[extValuesConsp.length - 1],
        { minPadding: 0.1, maxPadding: 0.1 }
      )
      : EMPTY_Y_AXIS;
  const barColours = generateElecHourBarChartColours(
    xValues,
    data.price,
    chartState.elec.thresholds
  );

  const priceData = xValues.map((dt, i) => ({
    value: [dt.getTime(), data.price[i]],
    itemStyle: { color: barColours[i] }
  }));
  const consumptionData = xValues.map((dt, i) => [
    dt.getTime(),
    data.consumption[i]
  ]);

  return {
    summary: {
      consumptionSum: arraySum(data.consumption),
      averagePrice: arrayAverage(data.price),
      lastX: xValues.length ? xValues[xValues.length - 1] : null
    },
    option: {
      title: {
        text: 'Hourly electricity price and consumption',
        left: 'center'
      },
      legend: baseElecLegend,
      grid: baseElecGrid,
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: axisTooltipFormatter({
          timeFormat: 'dd.MM. HH:mm',
          formatSeriesLine: elecTooltipLine
        })
      },
      xAxis: {
        type: 'time',
        name: 'Time',
        nameLocation: 'middle',
        nameGap: 30,
        axisLabel: {
          hideOverlap: true,
          formatter: (value) =>
            DateTime.fromMillis(value).toFormat('dd.MM. HH:mm')
        }
      },
      yAxis: [
        {
          type: 'value',
          name: 'Price (c / kWh)',
          min: priceMin,
          max: priceMax,
          interval: priceInterval,
          scale: false,
          axisLabel: { formatter: formatNiceAxisLabel }
        },
        {
          type: 'value',
          name: 'Consumption (kWh)',
          min: conspMin,
          max: conspMax,
          interval: conspInterval,
          scale: false,
          axisLabel: { formatter: formatNiceAxisLabel }
        }
      ],
      series: [
        {
          name: 'Price',
          type: 'bar',
          yAxisIndex: 0,
          barMaxWidth: 24,
          data: priceData,
          markLine: {
            silent: true,
            symbol: 'none',
            lineStyle: { color: '#838b93', width: 1, type: 'solid' },
            label: { show: false },
            data: buildHourBoundaryMarkLines(xValues, priceMin, priceMax)
          }
        },
        {
          name: 'Consumption',
          type: 'line',
          yAxisIndex: 1,
          showSymbol: false,
          lineStyle: { color: '#000000', width: 2 },
          itemStyle: { color: '#000000' },
          data: consumptionData
        }
      ]
    }
  };
};

export const buildDayElecOption = (elecData, { removeLast = false } = {}) => {
  const DateTime = getDateTime();
  const xValues = [];
  const data = { price: [], consumption: [] };

  for (let i = 0; i < elecData.length - (removeLast ? 1 : 0); i++) {
    const item = elecData[i];
    if (!item) {
      continue;
    }
    xValues.push(DateTime.fromISO(item.date).toJSDate());
    data.price.push(item.price);
    data.consumption.push(item.consumption);
  }

  const priceData = xValues.map((dt, i) => [dt.getTime(), data.price[i]]);
  const consumptionData = xValues.map((dt, i) => [
    dt.getTime(),
    data.consumption[i]
  ]);

  const extValuesConsp = getDataExtremeValues([data.consumption]);
  const conspMaxValue = extValuesConsp
    ? extValuesConsp[extValuesConsp.length - 1]
    : 0;
  const {
    min: conspMin,
    max: conspMax,
    interval: conspInterval
  } = conspMaxValue <= 0
    ? EMPTY_Y_AXIS
    : getNiceYAxisRange(0, conspMaxValue, {
      maxPadding: conspMaxValue * 0.1,
      minFloor: 0
    });

  return {
    title: {
      text: 'Daily electricity price and consumption',
      left: 'center'
    },
    legend: baseElecLegend,
    grid: baseElecGrid,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: axisTooltipFormatter({
        timeFormat: 'dd.MM.yyyy',
        formatSeriesLine: elecTooltipLine
      })
    },
    xAxis: {
      type: 'time',
      name: 'Date',
      nameLocation: 'middle',
      nameGap: 30,
      minInterval: 24 * 3600 * 1000,
      axisLabel: {
        hideOverlap: true,
        formatter: (value) =>
          DateTime.fromMillis(value).toFormat('dd.MM.yyyy')
      }
    },
    yAxis: [
      {
        type: 'value',
        name: 'Average price (c / kWh)'
      },
      {
        type: 'value',
        name: 'Consumption (kWh)',
        min: conspMin,
        max: conspMax,
        interval: conspInterval,
        scale: false,
        axisLabel: { formatter: formatNiceAxisLabel }
      }
    ],
    series: [
      {
        name: 'Average price',
        type: 'line',
        yAxisIndex: 0,
        showSymbol: false,
        lineStyle: { width: 2 },
        data: priceData
      },
      {
        name: 'Consumption',
        type: 'bar',
        yAxisIndex: 1,
        barMaxWidth: 24,
        data: consumptionData,
        label: {
          show: true,
          position: 'top',
          formatter: (params) => {
            const y = Array.isArray(params.value) ? params.value[1] : params.value;
            if (y == null || Number.isNaN(y)) {
              return '';
            }
            return `${y} kWh`;
          }
        }
      }
    ]
  };
};

export const buildMinuteElecOption = (priceData) => {
  const DateTime = getDateTime();
  const xValues = [];
  const data = { price: [] };

  for (const item of priceData) {
    xValues.push(DateTime.fromISO(item['start-time']).toJSDate());
    data.price.push(item.price);
  }

  chartState.elec.minutePriceCache = { xValues, prices: data.price };

  const extValuesPrice = getDataExtremeValues([data.price]);
  const { min: priceMin, max: priceMax, interval: priceInterval } =
    extValuesPrice
      ? getElecPriceYAxisRange(
        extValuesPrice[0],
        extValuesPrice[extValuesPrice.length - 1],
        { maxPadding: 0.4 }
      )
      : EMPTY_Y_AXIS;
  const barColours = generateElecMinuteChartBarColours(
    xValues,
    data.price,
    chartState.elec.thresholds
  );
  const priceSeriesData = xValues.map((dt, i) => ({
    value: [dt.getTime(), data.price[i]],
    itemStyle: { color: barColours[i] }
  }));

  return {
    title: {
      text: 'Electricity price (15 minute resolution)',
      left: 'center'
    },
    legend: baseElecLegend,
    grid: {
      left: 60,
      right: 40,
      top: 50,
      bottom: 75
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: axisTooltipFormatter({
        timeFormat: 'dd.MM. HH:mm',
        formatSeriesLine: elecTooltipLine
      })
    },
    xAxis: {
      type: 'time',
      name: 'Time',
      nameLocation: 'middle',
      nameGap: 30,
      axisLabel: {
        hideOverlap: true,
        formatter: (value) =>
          DateTime.fromMillis(value).toFormat('dd.MM. HH:mm')
      }
    },
    yAxis: {
      type: 'value',
      name: 'Price (c / kWh)',
      min: priceMin,
      max: priceMax,
      interval: priceInterval,
      scale: false,
      axisLabel: { formatter: formatNiceAxisLabel }
    },
    series: [
      {
        name: 'Price',
        type: 'bar',
        barMaxWidth: 24,
        data: priceSeriesData
      }
    ]
  };
};

export const refreshHourElecBarColours = () => {
  const manager = chartState.charts.hourElec;
  const cache = chartState.elec.hourCache;
  if (!manager?.getInstance() || !cache.xValues.length) {
    return;
  }
  const colours = generateElecHourBarChartColours(
    cache.xValues,
    cache.prices,
    chartState.elec.thresholds
  );
  const priceData = cache.xValues.map((dt, i) => ({
    value: [dt.getTime(), cache.prices[i]],
    itemStyle: { color: colours[i] }
  }));
  manager.setOption({ series: [{ data: priceData }] });
};

export const refreshMinuteElecBarColours = () => {
  const manager = chartState.charts.minuteElec;
  const cache = chartState.elec.minutePriceCache;
  if (!manager?.getInstance() || !cache.xValues.length) {
    return;
  }
  const colours = generateElecMinuteChartBarColours(
    cache.xValues,
    cache.prices,
    chartState.elec.thresholds
  );
  const priceData = cache.xValues.map((dt, i) => ({
    value: [dt.getTime(), cache.prices[i]],
    itemStyle: { color: colours[i] }
  }));
  manager.setOption({ series: [{ data: priceData }] });
};

export const getClosestElecPriceDataIndex = (xValues) => {
  const DateTime = getDateTime();
  const now = DateTime.now();

  let smallest = Infinity;
  let smallestIdx = -1;

  for (let i = xValues.length - 1; i >= 0; i--) {
    const diff = Math.abs(DateTime.fromJSDate(xValues[i]).diff(now).milliseconds);
    if (diff < smallest) {
      smallest = diff;
      smallestIdx = i;
    }
    if (diff > smallest) {
      break;
    }
  }

  if (now.hour < DateTime.fromJSDate(xValues[smallestIdx]).hour) {
    smallestIdx -= 1;
  }

  return smallestIdx;
};
