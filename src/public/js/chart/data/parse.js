import { fieldNames } from '../constants.js';
import { getDateTime } from '../globals.js';
import { chartState } from '../state.js';

const padArrayFromStart = (arr, targetLength, value) => {
  const paddingLength = targetLength - arr.length;
  if (paddingLength > 0) {
    const padding = new Array(paddingLength).fill(value);
    return padding.concat(arr);
  }
  return arr;
};

const recordAnnotationIndices = (dataMode, observationTime) => {
  const DateTime = getDateTime();
  const recorded = DateTime.fromMillis(observationTime);
  if (recorded.hour === 0 && recorded.minute === 0) {
    chartState.annotationIndices[dataMode].push(recorded.toJSDate());
  }
};

export const parseRTData = (rtObservations, rtLabels) => {
  for (const label of rtLabels) {
    chartState.dataSets.rt[label] = {
      temperature: [],
      humidity: []
    };
  }

  const observationCount = rtObservations.name.length;
  const tagNames = new Set(rtLabels);
  let missingTags = structuredClone(tagNames);
  let currentTag;
  let currentTs;
  let nextTs;

  for (let i = 0; i < observationCount; i++) {
    currentTs = rtObservations.recorded[i];
    nextTs = rtObservations.recorded[i + 1];

    currentTag = rtObservations.name[i];
    missingTags.delete(currentTag);

    chartState.dataSets.rt[currentTag].temperature.push(rtObservations.temperature[i]);
    chartState.dataSets.rt[currentTag].humidity.push(rtObservations.humidity[i]);

    if (currentTs < nextTs || (i + 1) >= observationCount) {
      chartState.dataLabels.rt.push(new Date(currentTs));

      missingTags.forEach((tagName) => {
        chartState.dataSets.rt[tagName].temperature.push(null);
        chartState.dataSets.rt[tagName].humidity.push(null);
      });

      missingTags = structuredClone(tagNames);
    }
  }
};

export const parseWeatherData = (weatherData) => {
  weatherData.time.forEach((value) => {
    chartState.dataLabels.weather.push(new Date(value));
    recordAnnotationIndices('weather', value);
  });

  fieldNames.weather.forEach((value) => {
    chartState.dataSets.weather[value] = weatherData[value];
  });

  if (weatherData['wind-direction']) {
    chartState.dataSets.weather['wind-direction'] = weatherData['wind-direction'];
  }
};

export const parseOtherData = (otherData) => {
  otherData.recorded.forEach((value) => {
    chartState.dataLabels.other.push(new Date(value));
    recordAnnotationIndices('other', value);
  });

  chartState.names.bleBeacon = otherData['beacon-name'];
  chartState.names.testbedImage = otherData['tb-image-name'];

  fieldNames.other.forEach((value) => {
    chartState.dataSets.other[value] = otherData[value];
    chartState.dataSets.other[value] = padArrayFromStart(
      chartState.dataSets.other[value],
      chartState.dataLabels.other.length,
      null
    );
  });
};

export const buildLabelValues = () => {
  let beaconName = null;
  for (const item of chartState.names.bleBeacon || []) {
    if (item) {
      beaconName = item;
      break;
    }
  }

  chartState.labelValues.other = {
    'inside-light': 'Inside light',
    'inside-temperature': 'Inside temperature',
    'co2': 'Inside CO\u2082',
    'ruuvi-co2': 'Ruuvi Air CO\u2082',
    'pm-25': 'PM 2.5',
    'iaqs': 'IAQS',
    'outside-temperature': 'Outside temperature',
    'beacon-rssi': beaconName
      ? `Beacon "${beaconName}" RSSI`
      : 'Beacon RSSI',
    'beacon-battery': beaconName
      ? `Beacon "${beaconName}" battery level`
      : 'Beacon battery level'
  };

  chartState.labelValues.rt = {};
  for (const name of chartState.names.ruuvitag || []) {
    chartState.labelValues.rt[name] = {
      temperature: `"${name}" temperature`,
      humidity: `"${name}" humidity`
    };
  }

  chartState.labelValues.weather = {
    'temperature': 'Temperature',
    'cloudiness': 'Cloudiness',
    'wind-speed': 'Wind speed',
    'humidity': 'Humidity',
    'feels-like': 'Feels like'
  };

  return chartState.labelValues;
};

/** Transform raw API data into chart-compatible series. Returns labelValues. */
export const transformData = () => {
  chartState.annotationIndices.weather = [];
  chartState.annotationIndices.other = [];

  chartState.dataLabels.weather = [];
  chartState.dataLabels.other = [];
  chartState.dataLabels.rt = [];

  chartState.dataSets.weather = {};
  chartState.dataSets.other = {};
  chartState.dataSets.rt = {};

  parseRTData(chartState.data.rt, chartState.names.ruuvitag);
  parseWeatherData(chartState.data.weatherObs);
  parseOtherData(chartState.data.other);

  return buildLabelValues();
};
