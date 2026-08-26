import { elecPriceBarColours } from '../constants.js';
import { getDateTime, getLuxon } from '../globals.js';

export const generateElecBarColours = (prices, xValues, thresholds, {
  isCurrentInterval
}) => prices.map((price, i) => {
  if (isCurrentInterval(xValues[i])) {
    return elecPriceBarColours.currentHour;
  }
  if (price < thresholds.cheap) {
    return elecPriceBarColours.cheap;
  }
  if (price < thresholds.reasonable) {
    return elecPriceBarColours.reasonable;
  }
  return elecPriceBarColours.expensive;
});

export const isCurrentHourInterval = (date, now = getDateTime().now()) => {
  const DateTime = getDateTime();
  const currentDt = DateTime.fromJSDate(date);
  return now.day === currentDt.day && now.hour === currentDt.hour;
};

export const isCurrentQuarterInterval = (date, now = getDateTime().now()) => {
  const DateTime = getDateTime();
  const luxon = getLuxon();
  const parts = luxon.Interval.after(
    DateTime.local(now.year, now.month, now.day, now.hour, 0, 0),
    luxon.Duration.fromObject({ hours: 1 })
  ).divideEqually(4);

  let currentHourQuarter = null;
  for (const part of parts) {
    if (part.contains(now)) {
      currentHourQuarter = part.start;
      break;
    }
  }
  if (!currentHourQuarter) {
    return false;
  }
  return currentHourQuarter.toMillis() === DateTime.fromJSDate(date).toMillis();
};

export const generateElecHourBarChartColours = (xValues, prices, thresholds, now) =>
  generateElecBarColours(prices, xValues, thresholds, {
    isCurrentInterval: (dt) => isCurrentHourInterval(dt, now ?? getDateTime().now())
  });

export const generateElecMinuteChartBarColours = (xValues, prices, thresholds, now) =>
  generateElecBarColours(prices, xValues, thresholds, {
    isCurrentInterval: (dt) => isCurrentQuarterInterval(dt, now ?? getDateTime().now())
  });
