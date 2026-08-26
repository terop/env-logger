import { getDateTime } from '../globals.js';

export const tooltipPointValue = (param) => {
  if (Array.isArray(param.data)) {
    return param.data[1];
  }
  if (param.data && Array.isArray(param.data.value)) {
    return param.data.value[1];
  }
  if (Array.isArray(param.value)) {
    return param.value[1];
  }
  return param.data;
};

export const hasTooltipPointData = (value) =>
  value != null && !Number.isNaN(value);

/**
 * @param {object} options
 * @param {string} options.timeFormat - luxon format string
 * @param {(seriesName: string, y: unknown, param: object) => string|null} [options.formatSeriesLine]
 */
export const axisTooltipFormatter = ({
  timeFormat,
  formatSeriesLine
}) => (params) => {
  if (!params || !params.length) {
    return '';
  }
  const DateTime = getDateTime();
  const ts = DateTime.fromMillis(params[0].axisValue).toFormat(timeFormat);
  let html = `<b>${ts}</b>`;
  for (const p of params) {
    const line = formatSeriesLine
      ? formatSeriesLine(p.seriesName, tooltipPointValue(p), p)
      : null;
    if (line == null) {
      continue;
    }
    html += line;
  }
  return html;
};
