import { describe, expect, it } from 'vitest';
import {
  checkDateInterval,
  dateIntervalErrorMessage,
  DATE_INTERVAL_INVALID,
  DATE_INTERVAL_ORDER,
  isInvalidIsoDate
} from '../data/dates.js';

describe('isInvalidIsoDate', () => {
  it('treats empty values as valid (unchecked)', () => {
    expect(isInvalidIsoDate('')).toBe(false);
    expect(isInvalidIsoDate(null)).toBe(false);
    expect(isInvalidIsoDate(undefined)).toBe(false);
  });

  it('accepts ISO dates', () => {
    expect(isInvalidIsoDate('2024-01-01')).toBe(false);
    expect(isInvalidIsoDate('2024-06-15T12:00:00')).toBe(false);
  });

  it('rejects malformed values', () => {
    expect(isInvalidIsoDate('not-a-date')).toBe(true);
    expect(isInvalidIsoDate('2024-13-40')).toBe(true);
  });
});

describe('checkDateInterval', () => {
  it('accepts start before end', () => {
    expect(checkDateInterval('2024-01-01', '2024-01-03')).toEqual({ ok: true });
  });

  it('accepts the same start and end day', () => {
    expect(checkDateInterval('2024-01-01', '2024-01-01')).toEqual({ ok: true });
  });

  it('rejects start after end', () => {
    expect(checkDateInterval('2024-01-03', '2024-01-01')).toEqual({
      ok: false,
      error: DATE_INTERVAL_ORDER
    });
  });

  it('rejects an invalid start or end date', () => {
    expect(checkDateInterval('not-a-date', '2024-01-01')).toEqual({
      ok: false,
      error: DATE_INTERVAL_INVALID
    });
    expect(checkDateInterval('2024-01-01', 'nope')).toEqual({
      ok: false,
      error: DATE_INTERVAL_INVALID
    });
  });

  it('skips empty start/end in the invalid check (same as the form handler)', () => {
    expect(checkDateInterval('', '2024-01-01')).toEqual({ ok: true });
  });
});

describe('dateIntervalErrorMessage', () => {
  it('maps known errors to UI copy', () => {
    expect(dateIntervalErrorMessage(DATE_INTERVAL_INVALID))
      .toBe('Start or end date is invalid');
    expect(dateIntervalErrorMessage(DATE_INTERVAL_ORDER))
      .toBe('Start date must be smaller than the end date');
  });
});
