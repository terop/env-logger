import { DAY_MARK_LINE_SERIES } from '../constants.js';
import { getDateTime } from '../globals.js';

export const sameCalendarDay = (a, b) => a.getDate() === b.getDate()
  && a.getMonth() === b.getMonth()
  && a.getFullYear() === b.getFullYear();

export const isMidnight = (date) => {
  const DateTime = getDateTime();
  const recorded = DateTime.fromJSDate(date);
  return recorded.hour === 0 && recorded.minute === 0;
};

/**
 * Build ECharts markLine segment pairs at day boundaries.
 * @param {Date[]} timestamps - points that should get a vertical line (already filtered)
 * @param {number} yMin
 * @param {number} yMax
 * @param {{ skipFirst?: boolean, xLabels?: Date[] }} [options]
 */
export const buildDayMarkLines = (timestamps, yMin, yMax, {
  skipFirst = true,
  xLabels = null
} = {}) => {
  if (!timestamps?.length) {
    return [];
  }
  if (xLabels?.length >= 2 && sameCalendarDay(xLabels[0], xLabels[xLabels.length - 1])) {
    return [];
  }

  const data = [];
  for (let i = 0; i < timestamps.length; i++) {
    if (skipFirst && i === 0) {
      continue;
    }
    data.push([
      { xAxis: timestamps[i].getTime(), yAxis: yMin },
      { xAxis: timestamps[i].getTime(), yAxis: yMax }
    ]);
  }
  return data;
};

/** Mark lines at midnight hours from a list of x-axis Date values (elec hour chart). */
export const buildHourBoundaryMarkLines = (xValues, yMin, yMax) => {
  const DateTime = getDateTime();
  const data = [];
  for (let i = 1; i < xValues.length - 1; i++) {
    if (DateTime.fromJSDate(xValues[i]).hour === 0) {
      data.push([
        { xAxis: xValues[i].getTime(), yAxis: yMin },
        { xAxis: xValues[i].getTime(), yAxis: yMax }
      ]);
    }
  }
  return data;
};

export const buildDayMarkLineSeries = (markLineData, {
  xAxisIndex = 0,
  yAxisIndex = 0
} = {}) => ({
  id: DAY_MARK_LINE_SERIES,
  name: DAY_MARK_LINE_SERIES,
  type: 'line',
  xAxisIndex,
  yAxisIndex,
  data: [],
  silent: true,
  symbol: 'none',
  lineStyle: { opacity: 0 },
  legendHoverLink: false,
  tooltip: { show: false },
  markLine: {
    silent: true,
    symbol: 'none',
    lineStyle: { color: '#838b93', width: 1, type: 'solid' },
    label: { show: false },
    data: markLineData
  }
});
