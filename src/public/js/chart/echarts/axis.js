import { getDateTime } from '../globals.js';
import { chartState } from '../state.js';

export const getRawXAxisTickSize = (diffInDays) => {
  const tickOneHour = 3600000;
  let tickSize = tickOneHour;

  if (diffInDays >= 3 && diffInDays < 6) {
    tickSize = 2 * tickOneHour;
  } else if (diffInDays >= 6 && diffInDays < 10) {
    tickSize = 3 * tickOneHour;
  } else if (diffInDays >= 10 && diffInDays < 20) {
    tickSize = 5 * tickOneHour;
  } else if (diffInDays >= 20) {
    tickSize = 6 * tickOneHour;
  }

  return tickSize;
};

export const getRawXAxisConfig = (diffInDays) => ({
  dtick: getRawXAxisTickSize(diffInDays),
  tickformat: '%H',
  tickangle: -45
});

export const getBucketedXAxisConfig = (diffInDays) => {
  const hour = 3600000;
  const day = 86400000;
  const targetTicks = 12;
  const steps = [
    hour, 2 * hour, 3 * hour, 6 * hour, 12 * hour,
    day, 2 * day, 7 * day, 14 * day, 30 * day
  ];
  const spanMs = Math.max(diffInDays, 1 / 24) * day;
  const dtick = steps.find((step) => spanMs / step <= targetTicks) || 30 * day;

  let tickformat = '%H';
  let tickangle = -45;
  if (dtick >= day) {
    tickformat = dtick >= 14 * day ? '%d.%m.%Y' : '%d.%m.';
    tickangle = dtick >= 7 * day ? 0 : -45;
  } else if (dtick >= 6 * hour) {
    tickformat = '%d.%m. %H';
  }

  return { dtick, tickformat, tickangle };
};

export const getXAxisConfig = (diffInDays) =>
  (chartState.displayResolution ? getBucketedXAxisConfig : getRawXAxisConfig)(diffInDays);

export const niceAxisDecimals = (value) => {
  const abs = Math.abs(value);
  if (abs >= 100) {
    return 0;
  }
  if (abs >= 10) {
    return 1;
  }
  return 2;
};

export const niceAxisStep = (range, targetTicks = 5) => {
  if (range <= 0) {
    return 0.1;
  }

  const rough = range / targetTicks;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const normalized = rough / magnitude;
  let niceNormalized;

  if (normalized <= 1) {
    niceNormalized = 1;
  } else if (normalized <= 2) {
    niceNormalized = 2;
  } else if (normalized <= 5) {
    niceNormalized = 5;
  } else {
    niceNormalized = 10;
  }

  return niceNormalized * magnitude;
};

export const snapAxisBound = (value, step, direction) => {
  const snapped = direction === 'min'
    ? Math.floor(value / step + 1e-9) * step
    : Math.ceil(value / step - 1e-9) * step;
  const decimals = niceAxisDecimals(step);
  return Number(snapped.toFixed(decimals));
};

export const formatNiceAxisLabel = (value) => {
  const decimals = niceAxisDecimals(value);
  return Number(value.toFixed(decimals)).toString();
};

export const getNiceYAxisRange = (minValue, maxValue, {
  minPadding = 0,
  maxPadding = 0,
  minFloor = 0
} = {}) => {
  const rawMin = Math.max(minFloor, minValue - minPadding);
  const rawMax = maxValue + maxPadding;
  const interval = niceAxisStep(rawMax - rawMin);
  let min = snapAxisBound(rawMin, interval, 'min');
  let max = snapAxisBound(rawMax, interval, 'max');

  min = Math.max(minFloor, min);
  if (max <= min) {
    max = min + interval;
  }

  return { min, max, interval };
};

export const getElecPriceYAxisRange = (minPrice, maxPrice, {
  minPadding = 0.5,
  maxPadding = 0.5
} = {}) => {
  if (minPrice < 0) {
    return getNiceYAxisRange(minPrice, maxPrice, {
      minPadding,
      maxPadding,
      minFloor: Number.NEGATIVE_INFINITY
    });
  }

  return getNiceYAxisRange(0, maxPrice, {
    minPadding: 0,
    maxPadding,
    minFloor: 0
  });
};

export const getDataExtremeValues = (plotData) => {
  let minValue = Infinity;
  let maxValue = -Infinity;
  let hasValue = false;

  for (let i = 0; i < plotData.length; i++) {
    const series = plotData[i].filter((item) => !Number.isNaN(item) && item !== null);
    if (!series.length) {
      continue;
    }

    hasValue = true;
    const seriesMin = Math.min(...series);
    const seriesMax = Math.max(...series);

    if (seriesMin < minValue) {
      minValue = seriesMin;
    }
    if (seriesMax > maxValue) {
      maxValue = seriesMax;
    }
  }

  if (!hasValue) {
    return null;
  }

  return [minValue, maxValue];
};

export const computeObsAxisPadding = (minValue, maxValue, {
  ratio = 0.05,
  minAbsolute = 0.25
} = {}) => {
  const span = maxValue - minValue;
  if (span === 0) {
    return Math.max(minAbsolute, Math.abs(minValue) * ratio || minAbsolute);
  }
  return Math.max(minAbsolute, span * ratio);
};

export const shouldAnchorObsAxisAtZero = (minValue, maxValue, span) => {
  if (minValue < 0) {
    return false;
  }
  if (minValue === 0) {
    return true;
  }
  return minValue <= Math.max(span * 0.25, maxValue * 0.05);
};

export const getObsYAxisRange = (minValue, maxValue) => {
  const padding = computeObsAxisPadding(minValue, maxValue);
  const span = maxValue - minValue;

  if (span === 0) {
    if (minValue === 0) {
      return getNiceYAxisRange(0, maxValue, {
        minPadding: 0,
        maxPadding: padding,
        minFloor: 0
      });
    }

    return getNiceYAxisRange(minValue, maxValue, {
      minPadding: padding,
      maxPadding: padding,
      minFloor: Number.NEGATIVE_INFINITY
    });
  }

  if (shouldAnchorObsAxisAtZero(minValue, maxValue, span)) {
    return getNiceYAxisRange(0, maxValue, {
      minPadding: 0,
      maxPadding: padding,
      minFloor: 0
    });
  }

  if (minValue < 0) {
    const result = getNiceYAxisRange(minValue, maxValue, {
      minPadding: padding,
      maxPadding: padding,
      minFloor: Number.NEGATIVE_INFINITY
    });

    const minOnlyInterval = niceAxisStep(
      Math.max(Math.abs(minValue) + padding, 0.5)
    );
    const minBound = snapAxisBound(minValue - padding, minOnlyInterval, 'min');
    return {
      ...result,
      min: Math.max(result.min, minBound)
    };
  }

  return getNiceYAxisRange(minValue, maxValue, {
    minPadding: padding,
    maxPadding: padding,
    minFloor: Number.NEGATIVE_INFINITY
  });
};

export const yRangeFromVisibleSeries = (seriesDataList) => {
  const extremes = getDataExtremeValues(seriesDataList);
  if (!extremes) {
    return { yMin: 0, yMax: 1, yInterval: 0.5 };
  }

  const { min, max, interval } = getObsYAxisRange(extremes[0], extremes[1]);
  return { yMin: min, yMax: max, yInterval: interval };
};

export const inclusiveDayCount = (startDate, endDate) => {
  const DateTime = getDateTime();
  return Math.floor(
    DateTime.fromISO(endDate).diff(DateTime.fromISO(startDate), 'days').days
  ) + 1;
};
