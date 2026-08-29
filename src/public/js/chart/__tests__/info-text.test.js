import { describe, expect, it } from 'vitest';
import { DateTime } from 'luxon';
import {
  formatElecLatestPricesHtml,
  formatElecMonthSummaryHtml,
  formatForecastHtml,
  formatObservationsHtml,
  formatRuuvitagHtml,
  formatSunHtml,
  formatWeatherHtml
} from '../ui/info-text.js';

const weatherLabels = {
  weather: {
    temperature: 'Temperature',
    'feels-like': 'Feels like',
    cloudiness: 'Cloudiness',
    'wind-speed': 'Wind speed',
    humidity: 'Humidity'
  }
};

describe('formatSunHtml', () => {
  it('returns an empty string without astro data', () => {
    expect(formatSunHtml(null)).toBe('');
  });

  it('formats sunrise and sunset', () => {
    expect(formatSunHtml({ sunrise: '04:12', sunset: '22:40' }))
      .toContain('sunrise 04:12, sunset 22:40');
  });
});

describe('formatWeatherHtml', () => {
  it('returns an empty string without current weather', () => {
    expect(formatWeatherHtml(null, weatherLabels)).toBe('');
  });

  it('includes temperature, feels-like unit, and wind direction', () => {
    const html = formatWeatherHtml({
      time: '2024-06-01T12:00:00',
      temperature: 15,
      'feels-like': 13,
      cloudiness: 40,
      'wind-speed': 5,
      humidity: 70,
      'wind-direction-str': { long: 'south' }
    }, weatherLabels);

    expect(html).toContain('temperature: 15');
    expect(html).toContain('feels like: 13');
    expect(html).toContain('wind: south 5');
    expect(html).toContain('humidity: 70');
    expect(html).not.toContain('fmi-temperature');
  });
});

describe('formatObservationsHtml', () => {
  const labels = {
    'inside-light': 'Inside light',
    'inside-temperature': 'Inside temperature',
    co2: 'Inside CO\u2082',
    'ruuvi-co2': 'Ruuvi Air CO\u2082',
    'pm-25': 'PM 2.5',
    iaqs: 'IAQS',
    'outside-temperature': 'Outside temperature'
  };

  it('includes present values and keeps labels for nulls', () => {
    const html = formatObservationsHtml({
      dataSets: {
        other: {
          'inside-light': [120],
          'inside-temperature': [21],
          co2: [null],
          'ruuvi-co2': [800],
          'pm-25': [5],
          iaqs: [90],
          'beacon-rssi': [-62],
          'beacon-battery': [77],
          'outside-temperature': [8]
        }
      },
      dataLabels: { other: [new Date('2024-06-01T12:00:00')] },
      labelValues: { other: labels },
      names: { bleBeacon: ['Kitchen'] }
    });

    expect(html).toContain('inside light: 120');
    expect(html).toContain('inside temperature: 21');
    expect(html).toContain('inside CO\u2082:');
    expect(html).not.toMatch(/inside CO\u2082: \d/);
    expect(html).toContain('Ruuvi Air CO\u2082: 800');
    expect(html).toContain('beacon "Kitchen": RSSI -62');
    expect(html).toContain('battery level 77');
    expect(html).toContain('outside temperature: 8');
  });
});

describe('formatRuuvitagHtml', () => {
  it('lists temperature and humidity for each tag', () => {
    const html = formatRuuvitagHtml({
      dataSets: {
        rt: {
          living: { temperature: [21], humidity: [40] },
          bedroom: { temperature: [18], humidity: [50] }
        }
      },
      labelValues: {
        rt: {
          living: { temperature: '"living" temperature', humidity: '"living" humidity' },
          bedroom: { temperature: '"bedroom" temperature', humidity: '"bedroom" humidity' }
        }
      }
    });

    expect(html).toContain('"living" temperature: 21');
    expect(html).toContain('"bedroom" humidity: 50');
    expect(html.startsWith('<br>RuuviTags: ')).toBe(true);
    expect(html.endsWith(', ')).toBe(false);
  });
});

describe('formatForecastHtml', () => {
  it('returns an empty string without a forecast', () => {
    expect(formatForecastHtml(null)).toBe('');
  });

  it('formats forecast fields', () => {
    const html = formatForecastHtml({
      time: '2024-06-01T15:00:00',
      temperature: 16,
      'feels-like': 14,
      cloudiness: 30,
      'wind-speed': 4,
      'wind-direction-str': { long: 'west' },
      precipitation: 0.2,
      humidity: 60
    });

    expect(html).toContain('Forecast');
    expect(html).toContain(DateTime.fromISO('2024-06-01T15:00:00').toFormat('dd.MM.yyyy HH:mm'));
    expect(html).toContain('wind: west 4');
    expect(html).toContain('precipitation: 0.2');
  });
});

describe('formatElecLatestPricesHtml', () => {
  it('returns null when the last price is in the past', () => {
    expect(formatElecLatestPricesHtml([
      { 'start-time': '2020-01-01T00:00:00', price: 8 }
    ], () => 0)).toBeNull();
  });

  it('formats the current and next hour', () => {
    const now = DateTime.now();
    const current = now.toISO();
    const next = now.plus({ hours: 1 }).toISO();
    const html = formatElecLatestPricesHtml([
      { 'start-time': current, price: 8.5 },
      { 'start-time': next, price: 9.1 }
    ], () => 0);

    expect(html).toContain('Electricity price: at');
    expect(html).toContain('8.5 c / kWh');
    expect(html).toContain('9.1 c / kWh');
  });
});

describe('formatElecMonthSummaryHtml', () => {
  it('returns empty when month average and consumption are both null', () => {
    expect(formatElecMonthSummaryHtml({
      'month-price-avg': null,
      'month-consumption': null,
      'month-cost': 12
    })).toBe('');
  });

  it('joins the available month fields', () => {
    const html = formatElecMonthSummaryHtml({
      'month-consumption': 120,
      'month-price-avg': 7.5,
      'month-cost': 9
    });
    expect(html).toBe(
      '<br>Current month: consumption: 120 kWh, ' +
      'average price: <span id="elecMonthAvg">7.5</span> c / kWh, ' +
      'total cost: 9 €'
    );
  });
});
