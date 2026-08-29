import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getDataExtremeValues,
  getObsYAxisRange,
  getRawXAxisTickSize,
  inclusiveDayCount
} from '../echarts/axis.js';
import { buildObsBottomLayout } from '../echarts/layout.js';
import { buildDayMarkLines } from '../echarts/mark-lines.js';
import {
  generateElecBarColours,
  isCurrentHourInterval
} from '../charts/elec-colours.js';
import {
  buildDayElecOption,
  buildHourElecOption,
  buildMinuteElecOption
} from '../charts/electricity.js';
import { axisTooltipFormatter } from '../echarts/tooltips.js';
import { elecPriceBarColours } from '../constants.js';
import { DateTime } from 'luxon';
import { AuthLoadError, getJson, HttpError } from '../api/http.js';
import * as http from '../api/http.js';
import { handleElecError } from '../api/electricity.js';
import { showElectricityData } from '../charts/setup.js';
import {
  isAuthRedirectPending,
  resetAuthRedirectPending,
  setAuthRedirectPending
} from '../ui/dom.js';
import { buildWindArrowPoints, windFlowAngle } from '../wind.js';

describe('windFlowAngle', () => {
  // Dart points up at 0°. ECharts rotation is anticlockwise; arrows show flow (to).
  it('points south for a north wind', () => {
    expect(windFlowAngle(0)).toBe(180);
  });

  it('points west for an east wind', () => {
    expect(windFlowAngle(90)).toBe(90);
  });

  it('points north for a south wind', () => {
    expect(windFlowAngle(180)).toBe(0);
  });

  it('points east for a west wind', () => {
    expect(windFlowAngle(270)).toBe(270);
  });

  it('points south-west for a north-east wind', () => {
    expect(windFlowAngle(45)).toBe(135);
  });
});

describe('buildWindArrowPoints', () => {
  it('rotates each arrow with the flow angle', () => {
    const t0 = new Date('2024-01-01T00:00:00Z');
    const t1 = new Date('2024-01-01T00:10:00Z');
    const { points } = buildWindArrowPoints(
      [t0, t1],
      [270, 90],
      [3, 4],
      2
    );
    expect(points).toHaveLength(2);
    expect(points[0].symbolRotate).toBe(270);
    expect(points[0].label).toBe('W (270°)');
    expect(points[1].symbolRotate).toBe(90);
    expect(points[1].label).toBe('E (90°)');
  });
});

describe('getRawXAxisTickSize', () => {
  it('uses 2h ticks for 3–5 day ranges', () => {
    expect(getRawXAxisTickSize(4)).toBe(7_200_000);
  });

  it('uses 1h ticks for short ranges', () => {
    expect(getRawXAxisTickSize(2)).toBe(3_600_000);
  });
});

describe('getObsYAxisRange', () => {
  it('anchors at zero when values are near zero', () => {
    const range = getObsYAxisRange(0, 10);
    expect(range.min).toBe(0);
    expect(range.max).toBeGreaterThan(10);
  });

  it('limits min for mixed-scale negative values', () => {
    const range = getObsYAxisRange(-90, 400);
    expect(range.min).toBeGreaterThan(-200);
    expect(range.min).toBeLessThanOrEqual(-90);
  });
});

describe('getDataExtremeValues', () => {
  it('returns min and max across series', () => {
    expect(getDataExtremeValues([[1, 5, null], [2, 9]])).toEqual([1, 9]);
  });

  it('returns null for empty data', () => {
    expect(getDataExtremeValues([[null, NaN]])).toBeNull();
  });
});

describe('buildDayMarkLines', () => {
  it('skips single-day ranges via xLabels', () => {
    const day = new Date('2024-01-01T00:00:00');
    const marks = [day, new Date('2024-01-01T12:00:00')];
    expect(buildDayMarkLines(marks, 0, 10, {
      xLabels: [day, new Date('2024-01-01T23:00:00')]
    })).toEqual([]);
  });

  it('builds lines skipping the first timestamp', () => {
    const t0 = new Date('2024-01-01T00:00:00');
    const t1 = new Date('2024-01-02T00:00:00');
    const lines = buildDayMarkLines([t0, t1], 0, 5, {
      xLabels: [t0, new Date('2024-01-02T12:00:00')]
    });
    expect(lines).toHaveLength(1);
    expect(lines[0][0].xAxis).toBe(t1.getTime());
  });
});

describe('generateElecBarColours', () => {
  const thresholds = { cheap: 5, reasonable: 10 };
  const now = DateTime.fromISO('2024-06-01T14:30:00');

  it('highlights current hour and applies thresholds', () => {
    const xValues = [
      DateTime.fromISO('2024-06-01T13:00:00').toJSDate(),
      DateTime.fromISO('2024-06-01T14:00:00').toJSDate(),
      DateTime.fromISO('2024-06-01T15:00:00').toJSDate()
    ];
    const colours = generateElecBarColours([3, 7, 12], xValues, thresholds, {
      isCurrentInterval: (dt) => isCurrentHourInterval(dt, now)
    });
    expect(colours).toEqual([
      elecPriceBarColours.cheap,
      elecPriceBarColours.currentHour,
      elecPriceBarColours.expensive
    ]);
  });
});

describe('inclusiveDayCount', () => {
  it('counts inclusive days', () => {
    expect(inclusiveDayCount('2024-01-01', '2024-01-01')).toBe(1);
    expect(inclusiveDayCount('2024-01-01', '2024-01-03')).toBe(3);
  });
});

describe('buildObsBottomLayout', () => {
  it('grows grid bottom with series count', () => {
    const few = buildObsBottomLayout(2);
    const many = buildObsBottomLayout(20);
    expect(many.gridBottom).toBeGreaterThan(few.gridBottom);
  });
});

describe('getJson', () => {
  afterEach(() => {
    delete globalThis.refreshTokensIfNeeded;
    delete globalThis.restoreIdTokenSession;
    delete globalThis.authReady;
    delete globalThis.authLoadOk;
    resetAuthRedirectPending();
  });

  it('returns JSON body on success', async () => {
    globalThis.fetch = async () => ({
      ok: true,
      json: async () => ({ hello: 'world' })
    });
    await expect(getJson('data/display')).resolves.toEqual({ hello: 'world' });
  });

  it('throws HttpError with status and body', async () => {
    globalThis.fetch = async () => ({
      ok: false,
      status: 400,
      json: async () => ({ error: 'date-range-too-large', 'max-days': 90 })
    });
    await expect(getJson('data/display')).rejects.toMatchObject({
      status: 400,
      body: { error: 'date-range-too-large', 'max-days': 90 }
    });
    await expect(getJson('data/display')).rejects.toBeInstanceOf(HttpError);
  });

  it('waits for authReady before fetching', async () => {
    let authResolved = false;
    globalThis.authReady = new Promise((resolve) => {
      setTimeout(() => {
        authResolved = true;
        resolve();
      }, 10);
    });
    globalThis.fetch = async () => ({
      ok: true,
      json: async () => ({ ok: true })
    });
    await expect(getJson('data/display')).resolves.toEqual({ ok: true });
    expect(authResolved).toBe(true);
  });

  it('refreshes and retries once on 401', async () => {
    let calls = 0;
    globalThis.refreshTokensIfNeeded = async (force = false) => {
      if (force) {
        return true;
      }
      return true;
    };
    globalThis.fetch = async () => {
      calls += 1;
      if (calls === 1) {
        return { ok: false, status: 401, json: async () => null };
      }
      return { ok: true, json: async () => ({ ok: true }) };
    };
    await expect(getJson('data/display')).resolves.toEqual({ ok: true });
    expect(calls).toBe(2);
  });

  it('restores id-token session before retry after forced refresh', async () => {
    let calls = 0;
    globalThis.refreshTokensIfNeeded = async () => true;
    globalThis.restoreIdTokenSession = async () => true;
    globalThis.fetch = async () => {
      calls += 1;
      if (calls === 1) {
        return { ok: false, status: 401, json: async () => null };
      }
      return { ok: true, json: async () => ({ ok: true }) };
    };
    await expect(getJson('data/display')).resolves.toEqual({ ok: true });
    expect(calls).toBe(2);
  });

  it('does not retry 401 when refresh fails', async () => {
    let calls = 0;
    globalThis.refreshTokensIfNeeded = async () => false;
    globalThis.fetch = async () => {
      calls += 1;
      return { ok: false, status: 401, json: async () => null };
    };
    await expect(getJson('data/display')).rejects.toMatchObject({ status: 401 });
    expect(calls).toBe(1);
  });

  it('throws AuthLoadError when authReady rejects', async () => {
    let calls = 0;
    globalThis.authReady = Promise.reject(new Error('Failed to load auth.js'));
    globalThis.fetch = async () => {
      calls += 1;
      return { ok: true, json: async () => ({ ok: true }) };
    };
    await expect(getJson('data/display')).rejects.toBeInstanceOf(AuthLoadError);
    expect(calls).toBe(0);
  });

  it('throws HttpError 401 without fetch when redirect is pending', async () => {
    let calls = 0;
    setAuthRedirectPending(true);
    globalThis.fetch = async () => {
      calls += 1;
      return { ok: true, json: async () => ({ ok: true }) };
    };
    await expect(getJson('data/display')).rejects.toMatchObject({ status: 401 });
    expect(calls).toBe(0);
  });
});

describe('handleElecError', () => {
  afterEach(() => {
    resetAuthRedirectPending();
    delete globalThis.location;
  });

  it('returns true and sets redirect pending on 401', () => {
    globalThis.location = { href: '' };
    expect(handleElecError(new HttpError(401, null))).toBe(true);
    expect(isAuthRedirectPending()).toBe(true);
  });

  it('returns false for non-401 errors', () => {
    expect(handleElecError(new HttpError(500, null))).toBe(false);
    expect(isAuthRedirectPending()).toBe(false);
  });
});

describe('showElectricityData', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    resetAuthRedirectPending();
    delete globalThis.location;
    delete globalThis.document;
  });

  it('does not fetch minute prices after 401 from hour/day fetch', async () => {
    globalThis.location = { href: '' };
    globalThis.document = {
      getElementById: () => ({ checked: false, value: '', style: {}, classList: { toggle: () => {} } })
    };
    const getJsonSpy = vi.spyOn(http, 'getJson')
      .mockRejectedValueOnce(new HttpError(401, null));

    await showElectricityData();

    expect(getJsonSpy).toHaveBeenCalledTimes(1);
    expect(getJsonSpy.mock.calls[0][0]).toBe('data/elec-data');
  });
});

describe('buildHourElecOption', () => {
  it('handles price-only data with null consumption', () => {
    const { option, summary } = buildHourElecOption([
      { 'start-time': '2024-06-01T12:00:00', price: 8.5, consumption: null },
      { 'start-time': '2024-06-01T13:00:00', price: 12.0, consumption: null }
    ]);
    expect(summary.consumptionSum).toBe(0);
    expect(option.yAxis[1].min).toBe(0);
    expect(option.yAxis[1].max).toBe(1);
  });
});

describe('buildDayElecOption', () => {
  it('handles price-only data with null consumption', () => {
    const option = buildDayElecOption([
      { date: '2024-06-01', price: 8.5, consumption: null },
      { date: '2024-06-02', price: 12.0, consumption: null }
    ]);
    expect(option.yAxis[1].min).toBe(0);
    expect(option.yAxis[1].max).toBe(1);
  });
});

describe('axisTooltipFormatter', () => {
  it('returns an empty string for missing params', () => {
    const formatter = axisTooltipFormatter({
      timeFormat: 'dd.MM. HH:mm',
      formatSeriesLine: () => '<br/>line'
    });
    expect(formatter(null)).toBe('');
    expect(formatter([])).toBe('');
  });

  it('joins the timestamp with formatted series lines', () => {
    const ts = DateTime.fromISO('2024-06-01T14:00:00').toMillis();
    const formatter = axisTooltipFormatter({
      timeFormat: 'dd.MM. HH:mm',
      formatSeriesLine: (name, y, p) => `<br/>${p.marker}${name}: ${y}`
    });
    expect(formatter([
      { axisValue: ts, marker: '*', seriesName: 'Price', data: [ts, 8.5] }
    ])).toBe(`<b>${DateTime.fromMillis(ts).toFormat('dd.MM. HH:mm')}</b><br/>*Price: 8.5`);
  });
});

describe('elec tooltip formatters', () => {
  const point = (axisValue, name, y) => ({
    axisValue,
    marker: '',
    seriesName: name,
    data: name === 'Price' || name === 'Average price'
      ? { value: [axisValue, y] }
      : [axisValue, y]
  });

  it('uses c/kWh for price and kWh for consumption', () => {
    const ts = DateTime.fromISO('2024-06-01T12:00:00').toMillis();
    const { option } = buildHourElecOption([
      { 'start-time': '2024-06-01T12:00:00', price: 8.5, consumption: 1.2 }
    ]);
    const html = option.tooltip.formatter([
      point(ts, 'Price', 8.5),
      point(ts, 'Consumption', 1.2)
    ]);
    expect(html).toContain('Price: 8.5 c / kWh');
    expect(html).toContain('Consumption: 1.2 kWh');
  });

  it('formats daily average price with a date timestamp', () => {
    const ts = DateTime.fromISO('2024-06-01').toMillis();
    const option = buildDayElecOption([
      { date: '2024-06-01', price: 8.5, consumption: 10 }
    ]);
    const html = option.tooltip.formatter([
      point(ts, 'Average price', 8.5)
    ]);
    expect(html).toContain(DateTime.fromMillis(ts).toFormat('dd.MM.yyyy'));
    expect(html).toContain('Average price: 8.5 c / kWh');
  });

  it('formats minute price tooltips', () => {
    const ts = DateTime.fromISO('2024-06-01T12:15:00').toMillis();
    const option = buildMinuteElecOption([
      { 'start-time': '2024-06-01T12:15:00', price: 7.2 }
    ]);
    const html = option.tooltip.formatter([point(ts, 'Price', 7.2)]);
    expect(html).toContain('Price: 7.2 c / kWh');
  });
});
