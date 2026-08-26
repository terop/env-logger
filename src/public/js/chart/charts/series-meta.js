import { WIND_DIRECTION_SERIES, fieldNames } from '../constants.js';
import { chartState } from '../state.js';

export const weatherSeriesNames = () =>
  fieldNames.weather
    .map((key) => chartState.labelValues.weather[key])
    .concat([WIND_DIRECTION_SERIES]);

export const getSeriesNames = (plotType) => {
  if (plotType === 'weather') {
    return weatherSeriesNames();
  }
  if (plotType === 'ruuvitag') {
    const seriesNames = [];
    for (const name of chartState.names.ruuvitag) {
      seriesNames.push(chartState.labelValues.rt[name].temperature);
      seriesNames.push(chartState.labelValues.rt[name].humidity);
    }
    return seriesNames;
  }
  return fieldNames.other.map((key) => chartState.labelValues.other[key]);
};

export const getSeriesData = (plotType, seriesName) => {
  if (plotType === 'weather') {
    for (const key of fieldNames.weather) {
      if (chartState.labelValues.weather[key] === seriesName) {
        return chartState.dataSets.weather[key];
      }
    }
    return null;
  }
  if (plotType === 'ruuvitag') {
    for (const name of chartState.names.ruuvitag) {
      if (chartState.labelValues.rt[name].temperature === seriesName) {
        return chartState.dataSets.rt[name].temperature;
      }
      if (chartState.labelValues.rt[name].humidity === seriesName) {
        return chartState.dataSets.rt[name].humidity;
      }
    }
    return null;
  }
  for (const key of fieldNames.other) {
    if (chartState.labelValues.other[key] === seriesName) {
      return chartState.dataSets.other[key];
    }
  }
  return null;
};
