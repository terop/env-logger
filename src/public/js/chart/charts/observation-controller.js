import { DAY_MARK_LINE_SERIES, WIND_DIRECTION_SERIES } from '../constants.js';
import { chartState } from '../state.js';
import { yRangeFromVisibleSeries } from '../echarts/axis.js';
import { buildDayMarkLines } from '../echarts/mark-lines.js';
import { buildObsEchartsOption, buildWeatherEchartsOption } from './observation.js';
import { getSeriesData, getSeriesNames } from './series-meta.js';

export { getSeriesData, getSeriesNames, weatherSeriesNames } from './series-meta.js';

export const updateYAxisForLegendSelection = (plotType, selected) => {
  const manager = chartState.charts[plotType];
  if (!manager) {
    return;
  }

  const seriesNameList = getSeriesNames(plotType);
  const visibleSeriesData = [];

  for (const seriesName of seriesNameList) {
    if (selected[seriesName] === false) {
      continue;
    }
    if (plotType === 'weather' && seriesName === WIND_DIRECTION_SERIES) {
      continue;
    }
    const seriesData = getSeriesData(plotType, seriesName);
    if (seriesData) {
      visibleSeriesData.push(seriesData);
    }
  }

  const { yMin, yMax, yInterval } = yRangeFromVisibleSeries(visibleSeriesData);

  if (plotType === 'weather') {
    const dayMarkLines = buildDayMarkLines(
      chartState.annotationIndices.weather,
      yMin,
      yMax,
      { skipFirst: true, xLabels: chartState.dataLabels.weather }
    );
    manager.setOption({
      yAxis: [
        {},
        { min: yMin, max: yMax, interval: yInterval }
      ],
      series: [
        {
          id: DAY_MARK_LINE_SERIES,
          markLine: { data: dayMarkLines }
        }
      ]
    });
    return;
  }

  const xValues = plotType === 'ruuvitag'
    ? chartState.dataLabels.rt
    : chartState.dataLabels.other;
  const dayMarkLines = buildDayMarkLines(
    chartState.annotationIndices.other,
    yMin,
    yMax,
    { skipFirst: true, xLabels: xValues }
  );
  manager.setOption({
    yAxis: { min: yMin, max: yMax, interval: yInterval },
    series: [
      {
        id: DAY_MARK_LINE_SERIES,
        markLine: { data: dayMarkLines }
      }
    ]
  });
};

export const setObsLegendSelection = (plotType, selected) => {
  const manager = chartState.charts[plotType];
  if (!manager) {
    return;
  }
  if (plotType === 'weather') {
    manager.setOption(buildWeatherEchartsOption(selected), { notMerge: true });
    return;
  }
  manager.setOption(buildObsEchartsOption(plotType, selected), { notMerge: true });
};

export const hideAllObsSeries = (plotType) => {
  const selected = Object.fromEntries(
    getSeriesNames(plotType).map((name) => [name, false])
  );
  setObsLegendSelection(plotType, selected);
};

export const showAllObsSeries = (plotType) => {
  const selected = Object.fromEntries(
    getSeriesNames(plotType).map((name) => [name, true])
  );
  setObsLegendSelection(plotType, selected);
};

export const showRuuvitagSeriesType = (type) => {
  const selected = {};
  for (const seriesName of getSeriesNames('ruuvitag')) {
    selected[seriesName] = seriesName.includes(type);
  }
  setObsLegendSelection('ruuvitag', selected);
};
