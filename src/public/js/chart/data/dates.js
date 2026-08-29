import { getDateTime } from '../globals.js';

export const DATE_INTERVAL_INVALID = 'invalid';
export const DATE_INTERVAL_ORDER = 'order';

export const DATE_INTERVAL_MESSAGES = {
  [DATE_INTERVAL_INVALID]: 'Start or end date is invalid',
  [DATE_INTERVAL_ORDER]: 'Start date must be smaller than the end date'
};

export const dateIntervalErrorMessage = (error) =>
  DATE_INTERVAL_MESSAGES[error] ?? 'Invalid date range';

export const isInvalidIsoDate = (value) =>
  Boolean(value && getDateTime().fromISO(value).invalid);

export const checkDateInterval = (startDate, endDate) => {
  const DateTime = getDateTime();
  if (isInvalidIsoDate(startDate) || isInvalidIsoDate(endDate)) {
    return { ok: false, error: DATE_INTERVAL_INVALID };
  }
  if (DateTime.fromISO(startDate) > DateTime.fromISO(endDate)) {
    return { ok: false, error: DATE_INTERVAL_ORDER };
  }
  return { ok: true };
};
