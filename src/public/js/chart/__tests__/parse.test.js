import { afterEach, describe, expect, it } from 'vitest';
import { DateTime } from 'luxon';
import {
  buildLabelValues,
  parseOtherData,
  parseRTData,
  parseWeatherData,
  transformData
} from '../data/parse.js';
import { chartState } from '../state.js';

const midnightMillis = (year, month, day) =>
  DateTime.local(year, month, day, 0, 0, 0).toMillis();

const middayMillis = (year, month, day) =>
  DateTime.local(year, month, day, 12, 0, 0).toMillis();

const resetParseState = () => {
  chartState.data = {
    other: null,
    rt: null,
    weather: null,
    weatherObs: null
  };
  chartState.dataSets = { weather: {}, other: {}, rt: {} };
  chartState.dataLabels = { weather: [], other: [], rt: [] };
  chartState.labelValues = { weather: {}, other: {}, rt: {} };
  chartState.annotationIndices = { weather: [], other: [] };
  chartState.names = {
    bleBeacon: null,
    testbedImage: null,
    ruuvitag: null
  };
};

afterEach(resetParseState);

describe('parseWeatherData', () => {
  it('copies series, labels, wind direction, and midnight annotations', () => {
    const midnight = midnightMillis(2024, 6, 2);
    const midday = middayMillis(2024, 6, 2);
    parseWeatherData({
      time: [midnight, midday],
      temperature: [10, 12],
      cloudiness: [20, 40],
      'wind-speed': [3, 5],
      'wind-direction': [180, 90]
    });

    expect(chartState.dataLabels.weather).toEqual([
      new Date(midnight),
      new Date(midday)
    ]);
    expect(chartState.dataSets.weather.temperature).toEqual([10, 12]);
    expect(chartState.dataSets.weather['wind-direction']).toEqual([180, 90]);
    expect(chartState.annotationIndices.weather).toEqual([new Date(midnight)]);
  });
});

describe('parseOtherData', () => {
  it('pads shorter series from the start and records names', () => {
    const t0 = middayMillis(2024, 6, 1);
    const t1 = middayMillis(2024, 6, 2);
    parseOtherData({
      recorded: [t0, t1],
      'beacon-name': [null, 'Kitchen'],
      'tb-image-name': [null, 'img-1'],
      'inside-light': [100],
      'inside-temperature': [21, 22],
      co2: [400, 410],
      'ruuvi-co2': [500, 510],
      'pm-25': [4, 5],
      iaqs: [80, 81],
      'beacon-rssi': [-70, -65],
      'beacon-battery': [90, 88],
      'outside-temperature': [8, 9]
    });

    expect(chartState.dataLabels.other).toEqual([new Date(t0), new Date(t1)]);
    expect(chartState.dataSets.other['inside-light']).toEqual([null, 100]);
    expect(chartState.dataSets.other['inside-temperature']).toEqual([21, 22]);
    expect(chartState.names.bleBeacon).toEqual([null, 'Kitchen']);
    expect(chartState.names.testbedImage).toEqual([null, 'img-1']);
  });

  it('records midnight annotations', () => {
    const midnight = midnightMillis(2024, 6, 3);
    parseOtherData({
      recorded: [midnight],
      'beacon-name': ['x'],
      'tb-image-name': [null],
      'inside-light': [1],
      'inside-temperature': [20],
      co2: [400],
      'ruuvi-co2': [500],
      'pm-25': [3],
      iaqs: [70],
      'beacon-rssi': [-60],
      'beacon-battery': [80],
      'outside-temperature': [5]
    });

    expect(chartState.annotationIndices.other).toEqual([new Date(midnight)]);
  });
});

describe('parseRTData', () => {
  it('aligns missing tags to the same timestamps with nulls', () => {
    const t1 = middayMillis(2024, 6, 1);
    const t2 = middayMillis(2024, 6, 2);
    parseRTData({
      name: ['living', 'living'],
      recorded: [t1, t2],
      temperature: [21, 22],
      humidity: [40, 41]
    }, ['living', 'bedroom']);

    expect(chartState.dataLabels.rt).toEqual([new Date(t1), new Date(t2)]);
    expect(chartState.dataSets.rt.living.temperature).toEqual([21, 22]);
    expect(chartState.dataSets.rt.bedroom.temperature).toEqual([null, null]);
    expect(chartState.dataSets.rt.bedroom.humidity).toEqual([null, null]);
  });

  it('groups interleaved observations that share a timestamp', () => {
    const t1 = middayMillis(2024, 6, 1);
    const t2 = middayMillis(2024, 6, 2);
    parseRTData({
      name: ['living', 'bedroom', 'living', 'bedroom'],
      recorded: [t1, t1, t2, t2],
      temperature: [21, 18, 22, 19],
      humidity: [40, 50, 41, 51]
    }, ['living', 'bedroom']);

    expect(chartState.dataLabels.rt).toEqual([new Date(t1), new Date(t2)]);
    expect(chartState.dataSets.rt.living.temperature).toEqual([21, 22]);
    expect(chartState.dataSets.rt.bedroom.temperature).toEqual([18, 19]);
  });
});

describe('buildLabelValues', () => {
  it('uses the first non-empty beacon name in series labels', () => {
    chartState.names.bleBeacon = [null, 'Kitchen'];
    chartState.names.ruuvitag = ['living'];

    const labels = buildLabelValues();

    expect(labels.other['beacon-rssi']).toBe('Beacon "Kitchen" RSSI');
    expect(labels.other['beacon-battery']).toBe('Beacon "Kitchen" battery level');
    expect(labels.rt.living.temperature).toBe('"living" temperature');
    expect(labels.weather.temperature).toBe('Temperature');
  });

  it('falls back when no beacon name is present', () => {
    chartState.names.bleBeacon = [null, null];
    expect(buildLabelValues().other['beacon-rssi']).toBe('Beacon RSSI');
  });
});

describe('transformData', () => {
  it('resets previous series and rebuilds labels', () => {
    chartState.dataLabels.weather = [new Date()];
    chartState.annotationIndices.weather = [new Date()];
    chartState.data.rt = {
      name: ['living'],
      recorded: [middayMillis(2024, 6, 1)],
      temperature: [21],
      humidity: [40]
    };
    chartState.names.ruuvitag = ['living'];
    chartState.data.weatherObs = {
      time: [middayMillis(2024, 6, 1)],
      temperature: [10],
      cloudiness: [20],
      'wind-speed': [3]
    };
    chartState.data.other = {
      recorded: [middayMillis(2024, 6, 1)],
      'beacon-name': ['Hall'],
      'tb-image-name': [null],
      'inside-light': [80],
      'inside-temperature': [21],
      co2: [400],
      'ruuvi-co2': [500],
      'pm-25': [4],
      iaqs: [80],
      'beacon-rssi': [-70],
      'beacon-battery': [90],
      'outside-temperature': [8]
    };

    const labels = transformData();

    expect(chartState.dataLabels.weather).toHaveLength(1);
    expect(chartState.dataSets.rt.living.temperature).toEqual([21]);
    expect(labels.other['beacon-rssi']).toBe('Beacon "Hall" RSSI');
  });
});
