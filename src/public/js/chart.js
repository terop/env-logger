/* global applicationUrl,axios,echarts,luxon,refreshTokensIfNeeded */

const DateTime = luxon.DateTime;

// Data field names
const fieldNames = {
  weather: ['temperature', 'cloudiness', 'wind-speed'],
  other: ['inside-light', 'inside-temperature', 'co2', 'ruuvi-co2', 'pm-25',
          'iaqs', 'beacon-rssi', 'beacon-battery',  'outside-temperature']
};

let labelValues = {
  weather: {},
  other: {},
  rt: {}
};
let dataSets = {
  weather: {},
  other: {},
  rt: {}
};
const data = {
  other: null,
  rt: null,
  weather: null,
  weatherObs: null
};
const dataLabels = {
  weather: [],
  other: [],
  rt: []
};
const annotationIndices = {
  weather: [],
  other: []
};
const names = {
  bleBeacon: null,
  testbedImage: null,
  ruuvitag: null
};
const elecPriceBarColours = {
  cheap: '#00cd01',
  reasonable: '#f3e600',
  expensive: '#f44336',
  currentHour: '#60a5fa'
};
let elecMinutePriceData = {};
let elecPriceThresholds = {};
let testbedImageBasepath = '';
let maxDisplayDays = 90;
let weatherChart = null;
let otherChart = null;
let ruuvitagChart = null;
let hourElecChart = null;
let dayElecChart = null;
let minuteElecChart = null;
let hourElecPriceCache = { xValues: [], prices: [] };
let minuteElecPriceCache = { xValues: [], prices: [] };

const WIND_DIRECTION_SERIES = 'Wind direction';
const DAY_MARK_LINE_SERIES = '__dayMarkLines__';

const buildDayMarkLineSeries = (markLineData, {
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

// Observation charts only (Weather / Other / RuuviTag)
const buildXyDataZoom = ({
  xAxisIndex = 0,
  yAxisIndex = 0,
  sliderBottom = 36
} = {}) => [
  {
    type: 'inside',
    xAxisIndex,
    filterMode: 'none'
  },
  {
    type: 'inside',
    yAxisIndex,
    filterMode: 'none'
  },
  {
    type: 'slider',
    xAxisIndex,
    height: 22,
    bottom: sliderBottom,
    filterMode: 'none'
  }
];

// Bottom stack: multi-row plain legend, then zoom slider, then plot.
const buildObsBottomLayout = (seriesCount, {
  chartWidth = 1300,
  avgItemWidth = 170
} = {}) => {
  const usableWidth = chartWidth * 0.9;
  const itemsPerRow = Math.max(1, Math.floor(usableWidth / avgItemWidth));
  const legendRows = Math.max(1, Math.ceil(seriesCount / itemsPerRow));
  const legendRowHeight = 22;
  const legendBottom = 4;
  const legendHeight = legendRows * legendRowHeight;
  const sliderHeight = 22;
  const sliderGap = 10;
  const sliderBottom = legendBottom + legendHeight + sliderGap;
  // Room for tick labels plus x-axis name (nameGap: 30).
  const axisLabelGap = 50;
  const gridBottom = sliderBottom + sliderHeight + axisLabelGap;

  return {
    legend: {
      type: 'plain',
      orient: 'horizontal',
      left: 'center',
      bottom: legendBottom,
      width: '90%'
    },
    sliderBottom,
    gridBottom
  };
};

const tooltipPointValue = (param) => {
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

const hasTooltipPointData = (value) =>
  value != null && !Number.isNaN(value);

const degreesToCompassShort = (deg) => {
  if (deg == null || Number.isNaN(deg)) {
    return '?';
  }
  if (deg >= 0 && deg < 25) {
    return 'N';
  }
  if (deg >= 25 && deg < 65) {
    return 'NE';
  }
  if (deg >= 65 && deg < 115) {
    return 'E';
  }
  if (deg >= 115 && deg < 155) {
    return 'SE';
  }
  if (deg >= 155 && deg < 205) {
    return 'S';
  }
  if (deg >= 205 && deg < 245) {
    return 'SW';
  }
  if (deg >= 245 && deg < 295) {
    return 'W';
  }
  if (deg >= 295 && deg < 335) {
    return 'NW';
  }
  if (deg >= 335 && deg <= 360) {
    return 'N';
  }
  return '?';
};

const windFlowAngle = (fromDeg) => (fromDeg + 180) % 360;

const buildWindArrowPoints = (xValues, windDirections, windSpeeds, pointCount) => {
  const calmWindSpeed = 0.5;
  const windArrowY = 0.5;
  const windArrowMaxCount = 90;

  if (!windDirections || !windSpeeds || !xValues.length) {
    return { points: [], symbolSize: 14 };
  }

  const arrowStep = Math.max(1, Math.ceil(pointCount / windArrowMaxCount));
  const symbolSize = arrowStep === 1 ? 14 : arrowStep === 2 ? 12 : 10;
  const points = [];

  for (let i = 0; i < xValues.length; i++) {
    const dir = windDirections[i];
    const speed = windSpeeds[i];
    if (i % arrowStep !== 0
        || dir == null
        || speed == null
        || speed < calmWindSpeed) {
      continue;
    }

    points.push({
      value: [xValues[i].getTime(), windArrowY],
      symbolRotate: windFlowAngle(dir),
      label: `${degreesToCompassShort(dir)} (${dir}\u00b0)`
    });
  }

  return { points, symbolSize };
};

const getAxiosErrorStatus = (error) =>
  error?.response?.status ?? error?.status;

const inclusiveDayCount = (startDate, endDate) =>
  Math.floor(DateTime.fromISO(endDate).diff(DateTime.fromISO(startDate), 'days').days) + 1;

const dateRangeTooLargeMessage = (maxDays) =>
  `Date range exceeds the maximum of ${maxDays} days`;

const showDateRangeError = (message) => {
  const note = document.getElementById('dateRangeError');
  note.textContent = message;
  note.classList.remove('display-none');
};

const hideDateRangeError = () => {
  const note = document.getElementById('dateRangeError');
  note.textContent = '';
  note.classList.add('display-none');
};

const handleDisplayDataError = (error) => {
  const status = getAxiosErrorStatus(error);

  if (status === 401) {
    redirectToLogin();
    return;
  }

  if (status === 400) {
    const data = error?.response?.data;
    if (typeof data === 'object' && data?.error === 'date-range-too-large') {
      showDateRangeError(dateRangeTooLargeMessage(data['max-days'] ?? maxDisplayDays));
      return;
    }
    if (typeof data === 'string' && data.includes('Date range')) {
      showDateRangeError(dateRangeTooLargeMessage(maxDisplayDays));
      return;
    }
  }

  showDateRangeError('Failed to load chart data');
  console.log(`Display data fetch error: ${error}`);
};

let displayResolution = null;

const redirectToLogin = () => {
  window.location.href = `${applicationUrl}login`;
};

const displayResolutionLabels = {
  '10min': 'Showing 10 minute averages',
  '30min': 'Showing 30 minute averages',
  'hourly': 'Showing hourly averages',
  '2hourly': 'Showing 2-hour averages',
  '3hourly': 'Showing 3-hour averages'
};

const showDisplayResolution = (resolution) => {
  displayResolution = resolution || null;
  const note = document.getElementById('displayResolutionNote');
  const label = resolution && displayResolutionLabels[resolution];

  if (label) {
    note.textContent = label;
    note.classList.remove('display-none');
  } else {
    note.textContent = '';
    note.classList.add('display-none');
  }
};

const getRawXAxisTickSize = (diffInDays) => {
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

const getRawXAxisConfig = (diffInDays) => ({
  dtick: getRawXAxisTickSize(diffInDays),
  tickformat: '%H',
  tickangle: -45
});

const getBucketedXAxisConfig = (diffInDays) => {
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

const getXAxisConfig = (diffInDays) =>
  (displayResolution ? getBucketedXAxisConfig : getRawXAxisConfig)(diffInDays);

const niceAxisDecimals = (value) => {
  const abs = Math.abs(value);
  if (abs >= 100) {
    return 0;
  }
  if (abs >= 10) {
    return 1;
  }
  return 2;
};

const niceAxisStep = (range, targetTicks = 5) => {
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

const snapAxisBound = (value, step, direction) => {
  const snapped = direction === 'min'
    ? Math.floor(value / step + 1e-9) * step
    : Math.ceil(value / step - 1e-9) * step;
  const decimals = niceAxisDecimals(step);
  return Number(snapped.toFixed(decimals));
};

const formatNiceAxisLabel = (value) => {
  const decimals = niceAxisDecimals(value);
  return Number(value.toFixed(decimals)).toString();
};

const getNiceYAxisRange = (minValue, maxValue, {
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

// Price axes start at 0, or at the (padded) minimum when prices are negative.
const getElecPriceYAxisRange = (minPrice, maxPrice, {
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

const getDataExtremeValues = (plotData) => {
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

const computeObsAxisPadding = (minValue, maxValue, {
  ratio = 0.05,
  minAbsolute = 0.25
} = {}) => {
  const span = maxValue - minValue;
  if (span === 0) {
    return Math.max(minAbsolute, Math.abs(minValue) * ratio || minAbsolute);
  }
  return Math.max(minAbsolute, span * ratio);
};

const shouldAnchorObsAxisAtZero = (minValue, maxValue, span) => {
  if (minValue < 0) {
    return false;
  }
  if (minValue === 0) {
    return true;
  }
  return minValue <= Math.max(span * 0.25, maxValue * 0.05);
};

const getObsYAxisRange = (minValue, maxValue) => {
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

    // Mixed-scale charts (e.g. RSSI near -100 dBm with CO2/light in hundreds)
    // can snap the axis minimum far below the data when the full-span interval
    // is coarse. Limit the min bound using a step sized for the negative side.
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

const yRangeFromVisibleSeries = (seriesDataList) => {
  const extremes = getDataExtremeValues(seriesDataList);
  if (!extremes) {
    return { yMin: 0, yMax: 1, yInterval: 0.5 };
  }

  const { min, max, interval } = getObsYAxisRange(extremes[0], extremes[1]);
  return { yMin: min, yMax: max, yInterval: interval };
};

const loadPage = () => {
  // Parse RuuviTag observations
  // rtObservations - observations as JSON
  // rtLabels - RuuviTag labels
  const parseRTData = (rtObservations, rtLabels) => {
    for (const label of rtLabels) {
      dataSets.rt[label] = {
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

      dataSets.rt[currentTag].temperature.push(rtObservations.temperature[i]);
      dataSets.rt[currentTag].humidity.push(rtObservations.humidity[i]);

      if (currentTs < nextTs || (i + 1) >= observationCount) {
        dataLabels.rt.push(new Date(currentTs));

        missingTags.forEach((tagName) => {
          dataSets.rt[tagName].temperature.push(null);
          dataSets.rt[tagName].humidity.push(null);
        });

        missingTags = structuredClone(tagNames);
      }
    }
  };

  const recordAnnotationIndices = (dataMode, observationTime) => {
    const recorded = DateTime.fromMillis(observationTime);
    if (recorded.hour === 0 && recorded.minute === 0) {
      annotationIndices[dataMode].push(recorded.toJSDate());
    }
  };

  const padArrayFromStart = (arr, targetLength, value) => {
    const paddingLength = targetLength - arr.length;
    if (paddingLength > 0) {
      const padding = new Array(paddingLength).fill(value);
      return padding.concat(arr);
    }
    return arr;
  };

  const parseWeatherData = (weatherData) => {
    weatherData.time.forEach((value) => {
      dataLabels.weather.push(new Date(value));

      recordAnnotationIndices('weather', value);
    });

    fieldNames.weather.forEach((value) => {
      dataSets.weather[value] = weatherData[value];
    });

    if (weatherData['wind-direction']) {
      dataSets.weather['wind-direction'] = weatherData['wind-direction'];
    }
  };

  const parseOtherData = (otherData) => {
    otherData.recorded.forEach((value) => {
      dataLabels.other.push(new Date(value));

      recordAnnotationIndices('other', value);
    });

    names.bleBeacon = otherData['beacon-name'];
    names.testbedImage = otherData['tb-image-name'];

    fieldNames.other.forEach((value) => {
      dataSets.other[value] = otherData[value];

      if (['ruuvi-co2', 'pm-25', 'iaqs'].includes(value)) {
        const paddingLength = dataLabels.other.length - dataSets.other[value].length;
        if (paddingLength > 0) {
          dataSets.other[value] = padArrayFromStart(dataSets.other[value],
                                                    dataLabels.other.length, null);
        }
      } else {
        dataSets.other[value] = padArrayFromStart(dataSets.other[value],
                                                  dataLabels.other.length, null);
      }
    });
  };

  // Transform data to chart-compatible format. Returns the data series labels.
  const transformData = () => {
    annotationIndices.weather = [];
    annotationIndices.other = [];

    dataLabels.weather = [];
    dataLabels.other = [];
    dataLabels.rt = [];

    parseRTData(data.rt, names.ruuvitag);
    parseWeatherData(data.weatherObs);
    parseOtherData(data.other);

    let beaconName = null;
    for (const item of names.bleBeacon) {
      if (item) {
        beaconName = item;
        break;
      }
    }

    labelValues.other = {
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
    for (const name of names.ruuvitag) {
      labelValues.rt[name] = {
        temperature: `"${name}" temperature`,
        humidity: `"${name}" humidity`
      };
    }
    labelValues.weather = {
      'temperature': 'Temperature',
      'cloudiness': 'Cloudiness',
      'wind-speed': 'Wind speed',
      'humidity': 'Humidity',
      'feels-like': 'Feels like'
    };

    return labelValues;
  };

  const hideElement = (elementId) => {
    document.getElementById(elementId).style.display = 'none';
  };

  const getElecMinutePriceData = (date) => {
    return (elecMinutePriceData[date] !== undefined &&
            elecMinutePriceData[date].addFees ===
            document.getElementById('elecPriceShowFees').checked) ?
      elecMinutePriceData[date].prices : null;
  };

  if (data.other.recorded.length === 0) {
    document.getElementById('noDataError').style.display = 'block';
    hideElement('imageButtonDiv');
    hideElement('latestCheckboxDiv');
    hideElement('plotAccordion');
    hideElement('elecDataDiv');
  } else {
    labelValues = transformData();

    // Add unit suffix
    const addUnitSuffix = (keyName) => {
      keyName = keyName.toLowerCase();
      return `${keyName.includes('temperature') ? ' \u2103' : ''}` +
        `${keyName.includes('wind') ? ' m/s' : ''}` +
        `${keyName.includes('humidity') ? ' %H' : ''}` +
        `${keyName.includes('rssi') ? ' dBm' : ''}` +
        `${keyName.includes('battery') ? ' %' : ''}` +
        `${keyName.includes('precipitation') ? ' mm' : ''}` +
        `${keyName.includes('light') ? ' lux' : ''}` +
        `${keyName.includes('co2') || keyName.includes('co\u2082') ? ' ppm' : ''}` +
        `${keyName.includes('pm 2') || keyName.includes('pm-2') ? ' \u00b5g/m\u00b3' : ''}`;
    };

    // Change the first letter to lowercase
    const lowerFL = (str) => {
      return str.charAt(0).toLowerCase() + str.slice(1);
    };


    var scrollToBottom = (timeout) => {
      window.setTimeout(() => {
        window.scroll(0, document.body.scrollHeight);
      }, timeout);
    };

    // Show last observation and some other data for quick viewing
    const showLastObservation = () => {
      let observationText = '';
      const weatherKeys = ['temperature', 'feels-like', 'cloudiness', 'wind-speed', 'humidity'];

      if (!data.weather) {
        console.log('Error: no weather data');
        return;
      }

      if (data.weather.ast) {
        observationText += `<span class="weight-bold">Sun</span>: sunrise ${data.weather.ast.sunrise}, sunset ${data.weather.ast.sunset}<br>`;
      }

      const wd = data.weather.fmi.current;
      if (wd) {
        observationText += '<span class="weight-bold">Weather</span>';
        observationText += ` at ${DateTime.now().setLocale('fi').toLocaleString()}` +
          ` ${DateTime.fromISO(wd.time).toLocaleString(DateTime.TIME_SIMPLE)}: `;
        for (const key of weatherKeys) {
          switch (key) {
          case 'wind-speed':
            observationText += `wind: ${wd['wind-direction-str'].long} ` +
              `${wd[key]} ${addUnitSuffix(key)}, `;
            break;
          case 'fmi-temperature':
            observationText += `${lowerFL(labelValues.weather[key])}: ` +
              `${wd.temperature} ${addUnitSuffix(key)}, `;
            break;
          default:
            observationText += `${lowerFL(labelValues.weather[key])}: ${wd[key]}` +
              `${key === 'feels-like' ? addUnitSuffix('temperature') : addUnitSuffix(key)}, `;
          }
        }
      }

      if (wd) {
        observationText = observationText.slice(0, -2) + '<br>';
      }

      let obsIndex = dataSets.other['inside-light'].length - 1;

      observationText += `<span class="weight-bold">Observations</span> at ` +
        `${DateTime.fromJSDate(dataLabels.other[obsIndex]).toLocaleString(DateTime.TIME_SIMPLE)}: ` +
        `${lowerFL(labelValues.other['inside-light'])}: ${dataSets.other['inside-light'][obsIndex]}` +
        `${addUnitSuffix('inside-light')}, `;
      observationText += `${lowerFL(labelValues.other['inside-temperature'])}:`;
      if (dataSets.other['inside-temperature'][obsIndex] !== null) {
        observationText += ` ${dataSets.other['inside-temperature'][obsIndex]}` +
          `${addUnitSuffix('temperature')}, `;
      }
      observationText += `${lowerFL(labelValues.other['co2'])}:`;
      if (dataSets.other['co2'][obsIndex] !== null) {
        observationText += ` ${dataSets.other['co2'][obsIndex]}` +
          `${addUnitSuffix('co2')}, `;
      }
      observationText += `${labelValues.other['ruuvi-co2']}:`;
      if (dataSets.other['ruuvi-co2'][obsIndex] !== null) {
        observationText += ` ${dataSets.other['ruuvi-co2'][obsIndex]}` +
          `${addUnitSuffix('ruuvi-co2')}, `;
      }
      observationText += `${labelValues.other['pm-25']}:`;
      if (dataSets.other['pm-25'][obsIndex] !== null) {
        observationText += ` ${dataSets.other['pm-25'][obsIndex]}` +
          `${addUnitSuffix('pm-25')},`;
      }
      observationText += `<br>${labelValues.other['iaqs']}:`;
      if (dataSets.other['iaqs'][obsIndex] !== null) {
        observationText += ` ${dataSets.other['iaqs'][obsIndex]}` +
          `${addUnitSuffix('iaqs')}, `;
      }

      if (dataSets.other['beacon-rssi'][obsIndex] !== null) {
        observationText += `beacon "${names.bleBeacon[obsIndex]}": RSSI`;
        observationText += ` ${dataSets.other['beacon-rssi'][obsIndex]}${addUnitSuffix('beacon-rssi')}`;

        const battery = dataSets.other['beacon-battery'][obsIndex];
        const batteryText = battery ? `${battery} ${addUnitSuffix('beacon-battery')}` : 'NA';
        observationText += `; battery level ${batteryText}, `;
      }
      observationText += `${lowerFL(labelValues.other['outside-temperature'])}:`;
      if (dataSets.other['outside-temperature'][obsIndex] !== null) {
        observationText += ` ${dataSets.other['outside-temperature'][obsIndex]}` +
          `${addUnitSuffix('temperature')}`;
      }

      observationText += '<br>RuuviTags: ';

      let itemsAdded = 0;
      if (dataSets.rt) {
        obsIndex = dataSets.rt[Object.keys(dataSets.rt)[0]].temperature.length - 1;
        for (const tag in labelValues.rt) {
          if ((itemsAdded > 0 && itemsAdded % 4) === 0) {
            observationText += '<br>';
          }

          observationText += `${labelValues.rt[tag].temperature}: ` +
            `${dataSets.rt[tag].temperature[obsIndex]}` +
            `${addUnitSuffix('temperature')}, ` +
            `${labelValues.rt[tag].humidity}: ${dataSets.rt[tag].humidity[obsIndex]}` +
            `${addUnitSuffix('humidity')}, `;
          itemsAdded += 2;
        }
        observationText = observationText.slice(0, -2);
      }

      const forecast = data.weather.fmi.forecast;
      if (forecast) {
        observationText +=
          '<br><span class="weight-bold">Forecast</span> for ' +
          DateTime.fromISO(forecast.time).toFormat('dd.MM.yyyy HH:mm') +
          `: temperature: ${forecast.temperature} ${addUnitSuffix('temperature')}, ` +
          `feels like: ${forecast['feels-like']} ${addUnitSuffix('temperature')}, ` +
          `cloudiness: ${forecast.cloudiness} %, ` +
          `wind: ${forecast['wind-direction-str'].long} ${forecast['wind-speed']} ${addUnitSuffix('wind')}, ` +
          `precipitation: ${forecast.precipitation} ${addUnitSuffix('precipitation')}, ` +
          `humidity: ${forecast.humidity} ${addUnitSuffix('humidity')}`;
      }

      document.getElementById('infoText').innerHTML = observationText;
      document.getElementById('infoText').classList.remove('display-none');
    };
    showLastObservation();

    // Show the hourly electricity price and consumption data in a chart
    var plotElectricityDataHour = (elecData, updateDate = false,
                                   removeLast = false) => {
      const buildHourElecDayMarkLines = (xValues, yMin, yMax) => {
        const data = [];
        // Skip first and last data points as lines are not needed there
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

      const arraySum = (array) => {
        return array.reduce((acc, curr) => acc + curr, 0);
      };

      const arrayAverage = (array) => {
        return array.length === 0 ? 0 : arraySum(array) / array.length;
      };

      const xValues = [];
      const data = {
        price: [],
        consumption: []
      };

      for (let i = 0; i < elecData.length - (removeLast ? 1 : 0); i++) {
        const item = elecData[i];
        xValues.push(DateTime.fromISO(item['start-time']).toJSDate());
        data.price.push(item.price);
        data.consumption.push(item.consumption);
      }

      hourElecPriceCache = { xValues, prices: data.price };

      if (updateDate) {
        document.getElementById('elecEndDate').value = xValues.length
          ? DateTime.fromJSDate(xValues[xValues.length - 1]).toISODate()
          : DateTime.now().toISODate();
      }

      document.getElementById('elecInfoBox').innerHTML = 'Current interval: consumption: ' +
        `${arraySum(data.consumption).toFixed(2)} kWh, average price: ` +
        `${arrayAverage(data.price).toFixed(2)} c / kWh, ` +
        'total cost: <span id="intervalCost"></span> €';

      const extValuesConsp = getDataExtremeValues([data.consumption]);
      const extValuesPrice = getDataExtremeValues([data.price]);
      const { min: priceMin, max: priceMax, interval: priceInterval } =
        getElecPriceYAxisRange(
          extValuesPrice[0],
          extValuesPrice[extValuesPrice.length - 1]
        );
      const { min: conspMin, max: conspMax, interval: conspInterval } =
        getNiceYAxisRange(
          extValuesConsp[0],
          extValuesConsp[extValuesConsp.length - 1],
          { minPadding: 0.1, maxPadding: 0.1 }
        );
      const barColours = generateElecHourBarChartColours(xValues, data.price);

      const priceData = xValues.map((dt, i) => ({
        value: [dt.getTime(), data.price[i]],
        itemStyle: { color: barColours[i] }
      }));
      const consumptionData = xValues.map((dt, i) => [
        dt.getTime(),
        data.consumption[i]
      ]);

      const option = {
        title: {
          text: 'Hourly electricity price and consumption',
          left: 'center'
        },
        legend: {
          orient: 'horizontal',
          bottom: 0
        },
        grid: {
          left: 60,
          right: 60,
          top: 50,
          bottom: 60
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' },
          formatter: (params) => {
            if (!params || !params.length) {
              return '';
            }
            const ts = DateTime.fromMillis(params[0].axisValue)
              .toFormat('dd.MM. HH:mm');
            let html = `<b>${ts}</b>`;
            for (const p of params) {
              const y = tooltipPointValue(p);
              if (!hasTooltipPointData(y)) {
                continue;
              }
              if (p.seriesName === 'Price') {
                html += `<br/>${p.marker}${p.seriesName}: ${y} c / kWh`;
              } else {
                html += `<br/>${p.marker}${p.seriesName}: ${y} kWh`;
              }
            }
            return html;
          }
        },
        xAxis: {
          type: 'time',
          name: 'Time',
          nameLocation: 'middle',
          nameGap: 30,
          axisLabel: {
            hideOverlap: true,
            formatter: (value) =>
              DateTime.fromMillis(value).toFormat('dd.MM. HH:mm')
          }
        },
        yAxis: [
          {
            type: 'value',
            name: 'Price (c / kWh)',
            min: priceMin,
            max: priceMax,
            interval: priceInterval,
            scale: false,
            axisLabel: {
              formatter: formatNiceAxisLabel
            }
          },
          {
            type: 'value',
            name: 'Consumption (kWh)',
            min: conspMin,
            max: conspMax,
            interval: conspInterval,
            scale: false,
            axisLabel: {
              formatter: formatNiceAxisLabel
            }
          }
        ],
        series: [
          {
            name: 'Price',
            type: 'bar',
            yAxisIndex: 0,
            barMaxWidth: 24,
            data: priceData,
            markLine: {
              silent: true,
              symbol: 'none',
              lineStyle: { color: '#838b93', width: 1, type: 'solid' },
              label: { show: false },
              data: buildHourElecDayMarkLines(xValues, priceMin, priceMax)
            }
          },
          {
            name: 'Consumption',
            type: 'line',
            yAxisIndex: 1,
            showSymbol: false,
            lineStyle: { color: '#000000', width: 2 },
            itemStyle: { color: '#000000' },
            data: consumptionData
          }
        ]
      };

      const el = document.getElementById('hourElecDataPlot');
      if (!hourElecChart) {
        hourElecChart = echarts.init(el);
      }
      hourElecChart.setOption(option, { notMerge: true });
      hourElecChart.resize();
    };

    var refreshHourElecBarColours = () => {
      if (!hourElecChart || !hourElecPriceCache.xValues.length) {
        return;
      }
      const colours = generateElecHourBarChartColours(
        hourElecPriceCache.xValues,
        hourElecPriceCache.prices
      );
      const priceData = hourElecPriceCache.xValues.map((dt, i) => ({
        value: [dt.getTime(), hourElecPriceCache.prices[i]],
        itemStyle: { color: colours[i] }
      }));
      hourElecChart.setOption({
        series: [{ data: priceData }]
      });
    };

    // Show the daily electricity price and consumption data in a chart
    var plotElectricityDataDay = (elecData, removeLast = false) => {
      const xValues = [];
      const data = {
        price: [],
        consumption: []
      };

      for (let i = 0; i < elecData.length - (removeLast ? 1 : 0); i++) {
        const item = elecData[i];
        if (!item) {
          continue;
        }
        xValues.push(DateTime.fromISO(item.date).toJSDate());
        data.price.push(item.price);
        data.consumption.push(item.consumption);
      }

      const priceData = xValues.map((dt, i) => [dt.getTime(), data.price[i]]);
      const consumptionData = xValues.map((dt, i) => [
        dt.getTime(),
        data.consumption[i]
      ]);

      const extValuesConsp = getDataExtremeValues([data.consumption]);
      const conspMaxValue = extValuesConsp[extValuesConsp.length - 1];
      // Extra headroom so bar value labels stay inside the plot area
      const {
        min: conspMin,
        max: conspMax,
        interval: conspInterval
      } = conspMaxValue <= 0
        ? { min: 0, max: 1, interval: 0.5 }
        : getNiceYAxisRange(0, conspMaxValue, {
          maxPadding: conspMaxValue * 0.1,
          minFloor: 0
        });

      const option = {
        title: {
          text: 'Daily electricity price and consumption',
          left: 'center'
        },
        legend: {
          orient: 'horizontal',
          bottom: 0
        },
        grid: {
          left: 60,
          right: 60,
          top: 50,
          bottom: 60
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' },
          formatter: (params) => {
            if (!params || !params.length) {
              return '';
            }
            const ts = DateTime.fromMillis(params[0].axisValue)
              .toFormat('dd.MM.yyyy');
            let html = `<b>${ts}</b>`;
            for (const p of params) {
              const y = tooltipPointValue(p);
              if (!hasTooltipPointData(y)) {
                continue;
              }
              if (p.seriesName === 'Average price') {
                html += `<br/>${p.marker}${p.seriesName}: ${y} c / kWh`;
              } else {
                html += `<br/>${p.marker}${p.seriesName}: ${y} kWh`;
              }
            }
            return html;
          }
        },
        xAxis: {
          type: 'time',
          name: 'Date',
          nameLocation: 'middle',
          nameGap: 30,
          minInterval: 24 * 3600 * 1000,
          axisLabel: {
            hideOverlap: true,
            formatter: (value) =>
              DateTime.fromMillis(value).toFormat('dd.MM.yyyy')
          }
        },
        yAxis: [
          {
            type: 'value',
            name: 'Average price (c / kWh)'
          },
          {
            type: 'value',
            name: 'Consumption (kWh)',
            min: conspMin,
            max: conspMax,
            interval: conspInterval,
            scale: false,
            axisLabel: {
              formatter: formatNiceAxisLabel
            }
          }
        ],
        series: [
          {
            name: 'Average price',
            type: 'line',
            yAxisIndex: 0,
            showSymbol: false,
            lineStyle: { width: 2 },
            data: priceData
          },
          {
            name: 'Consumption',
            type: 'bar',
            yAxisIndex: 1,
            barMaxWidth: 24,
            data: consumptionData,
            label: {
              show: true,
              position: 'top',
              formatter: (params) => {
                const y = Array.isArray(params.value) ? params.value[1] : params.value;
                if (y == null || Number.isNaN(y)) {
                  return '';
                }
                return `${y} kWh`;
              }
            }
          }
        ]
      };

      const el = document.getElementById('dayElecDataPlot');
      if (!dayElecChart) {
        dayElecChart = echarts.init(el);
      }
      dayElecChart.setOption(option, { notMerge: true });
      dayElecChart.resize();
    };

    // Highlight the current hour's values in the hourly electricity
    // price data chart
    const generateElecHourBarChartColours = (xValues, prices) => {
      const now = DateTime.now();
      let colours = [];
      let currentDt;

      for (let i = 0; i < xValues.length; i++) {
        currentDt = DateTime.fromJSDate(xValues[i]);

        if (now.day === currentDt.day && now.hour === currentDt.hour) {
          colours.push(elecPriceBarColours.currentHour);
        } else {
          if (prices[i] < elecPriceThresholds.cheap) {
            colours.push(elecPriceBarColours.cheap);
          } else if (prices[i] < elecPriceThresholds.reasonable) {
            colours.push(elecPriceBarColours.reasonable);
          } else {
            colours.push(elecPriceBarColours.expensive);
          }
        }
      }

      return colours;
    };


    // Highlight the current quarter's values in the 15 minute electricity
    // price data chart
    const generateElecMinuteChartBarColours = (xValues, prices) => {
      let colours = [];

      const now = DateTime.now();
      const parts = luxon.Interval.after(DateTime.local(now.year, now.month, now.day, now.hour, 0, 0),
                                         luxon.Duration.fromObject({'hours': 1})).divideEqually(4);
      let currentHourQuarter;

      for (const part of parts) {
        if (part.contains(now)) {
          currentHourQuarter = part.start;
          break;
        }
      }

      for (let i = 0; i < xValues.length; i++) {
        if (currentHourQuarter.toMillis() === DateTime.fromJSDate(xValues[i]).toMillis()) {
          colours.push(elecPriceBarColours.currentHour);
        } else {
          if (prices[i] < elecPriceThresholds.cheap) {
            colours.push(elecPriceBarColours.cheap);
          } else if (prices[i] < elecPriceThresholds.reasonable) {
            colours.push(elecPriceBarColours.reasonable);
          } else {
            colours.push(elecPriceBarColours.expensive);
          }
        }
      }

      return colours;
    };

    // Show the 15 minute electricity price data in a chart
    var plotElectricityPriceMinute = (priceData) => {
      const xValues = [];
      const data = {
        price: []
      };

      for (const item of priceData) {
        xValues.push(DateTime.fromISO(item['start-time']).toJSDate());
        data.price.push(item.price);
      }

      minuteElecPriceCache = { xValues, prices: data.price };

      const extValuesPrice = getDataExtremeValues([data.price]);
      const { min: priceMin, max: priceMax, interval: priceInterval } =
        getElecPriceYAxisRange(
          extValuesPrice[0],
          extValuesPrice[extValuesPrice.length - 1],
          { maxPadding: 0.4 }
        );
      const barColours = generateElecMinuteChartBarColours(xValues, data.price);
      const priceSeriesData = xValues.map((dt, i) => ({
        value: [dt.getTime(), data.price[i]],
        itemStyle: { color: barColours[i] }
      }));

      const option = {
        title: {
          text: 'Electricity price (15 minute resolution)',
          left: 'center'
        },
        legend: {
          orient: 'horizontal',
          bottom: 0
        },
        grid: {
          left: 60,
          right: 40,
          top: 50,
          bottom: 60
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' },
          formatter: (params) => {
            if (!params || !params.length) {
              return '';
            }
            const ts = DateTime.fromMillis(params[0].axisValue)
              .toFormat('dd.MM. HH:mm');
            let html = `<b>${ts}</b>`;
            for (const p of params) {
              const y = tooltipPointValue(p);
              if (!hasTooltipPointData(y)) {
                continue;
              }
              html += `<br/>${p.marker}${p.seriesName}: ${y} c / kWh`;
            }
            return html;
          }
        },
        xAxis: {
          type: 'time',
          name: 'Time',
          nameLocation: 'middle',
          nameGap: 30,
          axisLabel: {
            hideOverlap: true,
            formatter: (value) =>
              DateTime.fromMillis(value).toFormat('dd.MM. HH:mm')
          }
        },
        yAxis: {
          type: 'value',
          name: 'Price (c / kWh)',
          min: priceMin,
          max: priceMax,
          interval: priceInterval,
          scale: false,
          axisLabel: {
            formatter: formatNiceAxisLabel
          }
        },
        series: [
          {
            name: 'Price',
            type: 'bar',
            barMaxWidth: 24,
            data: priceSeriesData
          }
        ]
      };

      const el = document.getElementById('minuteElecDataPlot');
      if (!minuteElecChart) {
        minuteElecChart = echarts.init(el);
      }
      minuteElecChart.setOption(option, { notMerge: true });
      minuteElecChart.resize();
    };

    var refreshMinuteElecBarColours = () => {
      if (!minuteElecChart || !minuteElecPriceCache.xValues.length) {
        return;
      }
      const colours = generateElecMinuteChartBarColours(
        minuteElecPriceCache.xValues,
        minuteElecPriceCache.prices
      );
      const priceData = minuteElecPriceCache.xValues.map((dt, i) => ({
        value: [dt.getTime(), minuteElecPriceCache.prices[i]],
        itemStyle: { color: colours[i] }
      }));
      minuteElecChart.setOption({
        series: [{ data: priceData }]
      });
    };


    // Determine the index of electricity price data value which is closest to the current hour
    const getClosestElecPriceDataIndex = (xValues) => {
      const now = DateTime.now();

      let smallest = Infinity;
      let smallestIdx = -1;

      for (let i = xValues.length - 1; i >= 0; i--) {
        const diff = Math.abs(DateTime.fromJSDate(xValues[i]).diff(now).milliseconds);
        if (diff < smallest) {
          smallest = diff;
          smallestIdx = i;
        }
        if (diff > smallest) {
          break;
        }
      }

      // Special case handling for the situation when the next hour is closer than the current
      if (now.hour < DateTime.fromJSDate(xValues[smallestIdx]).hour) {
        smallestIdx -= 1;
      }

      return smallestIdx;
    };

    // Fetch and display current electricity data
    var showElectricityData = () => {
      // Displays the latest price as text
      const showLatestPrice = (priceData) => {
        const now = DateTime.now();

        if (now > DateTime.fromISO(priceData[priceData.length - 1]['start-time'])) {
          console.log('No recent electricity price data to show');
          return;
        }

        const currentIdx = getClosestElecPriceDataIndex(priceData.map(item => new Date(item['start-time'])));

        const currentHourData = priceData[currentIdx];
        if (currentHourData) {
          const currentPriceTime = DateTime.fromISO(currentHourData['start-time']).toFormat('HH:mm');
          document.getElementById('infoText').innerHTML += '<br><br>Electricity price: at ' +
            `${currentPriceTime}: ${currentHourData.price} c / kWh`;
        }

        const nextHourData = priceData[currentIdx + 1];
        if (nextHourData) {
          const nextPriceTime = DateTime.fromISO(nextHourData['start-time']).toFormat('HH:mm');
          document.getElementById('infoText').innerHTML += ', at ' +
            `${nextPriceTime}: ${nextHourData.price} c / kWh`;
        }
      };

      axios.get('data/elec-data',
               {
                 params: {
                   addFees: document.getElementById('elecPriceShowFees').checked
                 }
               })
        .then(resp => {
          const elecData = resp.data;

          if (elecData) {
            if (elecData.error) {
              if (elecData.error !== 'not-enabled') {
                console.log(`Electricity data fetch error: ${elecData.error}`);
              }
              toggleClassForElement('elecDataDiv', 'display-none');

              return;
            }

            if (!elecData['data-hour'] || !elecData['data-day'][0]) {
              toggleVisibility('elecDataDiv');
              return;
            }

            if (elecData.dates.max) {
              const dateMax = elecData.dates.max;

              document.getElementById('elecStartDate').max = dateMax;
              document.getElementById('elecEndDate').max = dateMax;
              document.getElementById('elecMinuteDate').max = dateMax;
            }

            if (elecData.dates.min) {
              const dateMin = elecData.dates.min;

              document.getElementById('elecStartDate').min = dateMin;
              document.getElementById('elecEndDate').min = dateMin;
            }

            if (elecData.dates.current.start) {
              document.getElementById('elecStartDate').value = elecData.dates.current.start;
            }

            if (elecData['price-thresholds']) {
              elecPriceThresholds = elecData['price-thresholds'];
            }

            showLatestPrice(elecData['data-hour']);
            plotElectricityDataHour(elecData['data-hour'], true, true);

            plotElectricityDataDay(elecData['data-day'], true);

            document.getElementById('intervalCost').innerText =
              elecData['interval-cost'] !== null ? elecData['interval-cost'] : 0;

            if (elecData['month-price-avg'] !== null || elecData['month-consumption'] !== null) {
              let elecText = '<br>Current month: ';

              if (elecData['month-consumption'] !== null) {
                elecText += `consumption: ${elecData['month-consumption']} kWh`;
              }
              if (elecData['month-price-avg'] !== null) {
                if (!elecText.endsWith(' ')) {
                  elecText += ', ';
                }
                elecText += `average price: <span id="elecMonthAvg">${elecData['month-price-avg']}</span> c / kWh`;
              }
              if (elecData['month-cost'] !== null) {
                if (!elecText.endsWith(' ')) {
                  elecText += ', ';
                }
                elecText += `total cost: ${elecData['month-cost']} €`;
              }
              document.getElementById('infoText').innerHTML += elecText;
            }

            if (DateTime.fromISO(document.getElementById('elecEndDate').value) >=
                DateTime.fromISO(DateTime.now().toISODate())) {
              // Regularly update the current hour bar colour to match the current hour
              setInterval(() => {
                refreshHourElecBarColours();
              }, 120000);
            }
          }
        }).catch(error => {
          if (error.status === 401) {
            redirectToLogin();
          } else {
            console.log(`Electricity data fetch error: ${error}`);
          }
        });

      const currentDate = DateTime.now().toISODate();
      let dateField = document.getElementById('elecMinuteDate');
      dateField.value = currentDate;
      axios.get('data/elec-price-minute',
                {
                  params: {
                    date: currentDate,
                    getDate: true,
                    addFees: document.getElementById('elecPriceShowFees').checked
                  }
                })
        .then(resp => {
          const elecData = resp.data;

          if (!elecData.prices) {
            document.getElementById('elecMinuteAccordion').style.display = 'none';
            return;
          }

          elecMinutePriceData[currentDate] = {prices: elecData.prices,
                                              addFees: document.getElementById('elecPriceShowFees').checked};

          // Delay plot to allow electricity price thresholds to complete loading
          setTimeout(() => {
            plotElectricityPriceMinute(elecData.prices);
          }, 700);
          dateField.min = elecData['date-min'];

          setInterval(() => {
            refreshMinuteElecBarColours();
          }, 120000);
        })
        .catch(error => {
          if (error.status === 401) {
            redirectToLogin();
          } else {
            console.log(`Electricity price fetch error: ${error}`);
          }
        });
    };


    const showTestbedImage = (pointDt) => {
      const pattern = /testbed-(.+).png/;
      const imageCountIdx = names.testbedImage.length - 1;
      const refDt = DateTime.fromISO(pointDt.replace(' ', 'T'));
      let smallest = 100000;
      let smallestIdx = imageCountIdx;

      for (let i = imageCountIdx; i >= 0; i--) {
        const match = pattern.exec(names.testbedImage[i]);
        if (match) {
          const diff = Math.abs(refDt.diff(DateTime.fromISO(match[1]), 'minutes').minutes);
          if (diff <= smallest) {
            smallest = diff;
            smallestIdx = i;
          } else {
            break;
          }
        }
      }

      const imageName = names.testbedImage[smallestIdx];
      const datePattern = /testbed-([\d-]+)T.+/;
      const result = datePattern.exec(imageName);
      if (result) {
        document.getElementById('testbedImage').src =
          `${testbedImageBasepath}${result[1]}/${imageName}`;
        // Scroll page to bottom after loading the image for improved viewing
        scrollToBottom(500);
      }
    };

    const weatherSeriesNames = () =>
      fieldNames.weather.map((key) => labelValues.weather[key]).concat([WIND_DIRECTION_SERIES]);

    const buildWeatherDayMarkLines = (yMin, yMax) => {
      const labels = dataLabels.weather;
      if (!annotationIndices.weather.length || !labels.length) {
        return [];
      }

      const oneDay = labels[0].getDate() === labels[labels.length - 1].getDate();
      if (oneDay) {
        return [];
      }

      const data = [];
      for (let i = 0; i < annotationIndices.weather.length; i++) {
        if (i === 0) {
          continue;
        }
        data.push([
          { xAxis: annotationIndices.weather[i].getTime(), yAxis: yMin },
          { xAxis: annotationIndices.weather[i].getTime(), yAxis: yMax }
        ]);
      }
      return data;
    };

    const buildWeatherEchartsOption = (legendSelected = null) => {
      // Arrows sit centered in a dedicated top grid band (never over value series).
      // Narrow dart pointing up; ECharts rotates with symbolRotate (degrees, clockwise).
      const windArrowBandTop = 48;
      const windArrowBandHeight = 56;
      const windArrowPath = 'path://M0,-14 L1.4,6 L0,2 L-1.4,6 Z';

      const xValues = dataLabels.weather;
      const pointCount = xValues.length;
      const showMarkers = pointCount <= 500;
      // const showMarkers = false;
      const selected = legendSelected || Object.fromEntries(
        weatherSeriesNames().map((name) => [name, true])
      );

      const visibleSeriesData = [];
      for (const key of fieldNames.weather) {
        const name = labelValues.weather[key];
        if (selected[name] !== false) {
          visibleSeriesData.push(dataSets.weather[key]);
        }
      }

      const { yMin, yMax, yInterval } = yRangeFromVisibleSeries(visibleSeriesData);

      const xMin = xValues[0].getTime();
      const xMax = xValues[xValues.length - 1].getTime();
      const mainGridTop = windArrowBandTop + windArrowBandHeight + 8;

      const series = [
        buildDayMarkLineSeries(buildWeatherDayMarkLines(yMin, yMax), {
          xAxisIndex: 1,
          yAxisIndex: 1
        }),
        ...fieldNames.weather.map((key) => {
        const name = labelValues.weather[key];
        return {
          name,
          type: 'line',
          xAxisIndex: 1,
          yAxisIndex: 1,
          showSymbol: showMarkers,
          symbolSize: 2, // showMarkers ? 2 : 4,
          triggerLineEvent: true,
          data: xValues.map((dt, i) => [dt.getTime(), dataSets.weather[key][i]])
        };
      })
      ];

      const { points: windPoints, symbolSize } = buildWindArrowPoints(
        xValues,
        dataSets.weather['wind-direction'],
        dataSets.weather['wind-speed'],
        pointCount
      );

      if (windPoints.length) {
        series.push({
          name: WIND_DIRECTION_SERIES,
          type: 'scatter',
          xAxisIndex: 0,
          yAxisIndex: 0,
          symbol: windArrowPath,
          symbolSize,
          // Keep arrow tips visible at band / chart edges.
          clip: false,
          itemStyle: { color: '#838383' },
          data: windPoints,
          z: 10
        });
      }

      const weatherNames = weatherSeriesNames();
      const bottomLayout = buildObsBottomLayout(weatherNames.length);

      return {
        title: {
          text: 'FMI weather observations',
          left: 'center'
        },
        legend: {
          ...bottomLayout.legend,
          selected,
          symbolRotate: 0,
          symbolKeepAspect: true,
          itemWidth: 12,
          itemHeight: 12,
          data: weatherNames.map((name) => (
            name === WIND_DIRECTION_SERIES
              ? { name, icon: 'triangle' }
              : name
          ))
        },
        // Separate top band for wind arrows so they never overlap value series.
        grid: [
          {
            left: 60,
            right: 30,
            top: windArrowBandTop,
            height: windArrowBandHeight
          },
          {
            left: 60,
            right: 30,
            top: mainGridTop,
            bottom: bottomLayout.gridBottom
          }
        ],
        dataZoom: buildXyDataZoom({
          xAxisIndex: [0, 1],
          yAxisIndex: 1,
          sliderBottom: bottomLayout.sliderBottom
        }),
        axisPointer: {
          link: [{ xAxisIndex: 'all' }]
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'line' },
          formatter: (params) => {
            if (!params || !params.length) {
              return '';
            }
            const ts = DateTime.fromMillis(params[0].axisValue)
              .toFormat('dd.MM. HH:mm:ss');
            let html = `<b>${ts}</b>`;
            for (const p of params) {
              if (p.seriesName === WIND_DIRECTION_SERIES) {
                const label = p.data && p.data.label ? p.data.label : '';
                if (!label) {
                  continue;
                }
                html += `<br/>${p.marker}${p.seriesName}: ${label}`;
              } else {
                const y = tooltipPointValue(p);
                if (!hasTooltipPointData(y)) {
                  continue;
                }
                const unit = addUnitSuffix(p.seriesName);
                html += `<br/>${p.marker}${p.seriesName}: ${y}${unit}`;
              }
            }
            return html;
          }
        },
        xAxis: [
          {
            type: 'time',
            gridIndex: 0,
            min: xMin,
            max: xMax,
            show: false
          },
          {
            type: 'time',
            gridIndex: 1,
            name: 'Time',
            nameLocation: 'middle',
            nameGap: 30,
            min: xMin,
            max: xMax,
            axisLabel: {
              hideOverlap: true,
              formatter: (value) =>
                DateTime.fromMillis(value).toFormat('dd.MM. HH:mm')
            }
          }
        ],
        yAxis: [
          {
            type: 'value',
            gridIndex: 0,
            min: 0,
            max: 1,
            show: false
          },
          {
            type: 'value',
            gridIndex: 1,
            name: 'Value',
            min: yMin,
            max: yMax,
            interval: yInterval,
            scale: false,
            axisLabel: {
              formatter: formatNiceAxisLabel
            }
          }
        ],
        series
      };
    };

    var updateWeatherYAxisForSelection = (selected) => {
      if (!weatherChart) {
        return;
      }

      const visibleSeriesData = [];
      for (const key of fieldNames.weather) {
        const name = labelValues.weather[key];
        if (selected[name] !== false) {
          visibleSeriesData.push(dataSets.weather[key]);
        }
      }

      const { yMin, yMax, yInterval } = yRangeFromVisibleSeries(visibleSeriesData);

      weatherChart.setOption({
        yAxis: [
          {},
          { min: yMin, max: yMax, interval: yInterval }
        ],
        series: [
          {
            id: DAY_MARK_LINE_SERIES,
            markLine: {
              data: buildWeatherDayMarkLines(yMin, yMax)
            }
          }
        ]
      });
    };

    var initOrUpdateWeatherChart = (preserveLegend = false) => {
      let legendSelected = null;
      if (preserveLegend && weatherChart) {
        const option = weatherChart.getOption();
        legendSelected = option.legend && option.legend[0]
          ? option.legend[0].selected
          : null;
      }

      const el = document.getElementById('weatherPlot');
      if (!weatherChart) {
        weatherChart = echarts.init(el);
        weatherChart.on('click', (params) => {
          let ts = null;
          if (Array.isArray(params.value)) {
            ts = params.value[0];
          } else if (params.data && Array.isArray(params.data.value)) {
            ts = params.data.value[0];
          } else if (params.seriesName !== WIND_DIRECTION_SERIES
                     && params.dataIndex != null
                     && dataLabels.weather[params.dataIndex]) {
            ts = dataLabels.weather[params.dataIndex].getTime();
          }

          if (ts == null || Number.isNaN(ts)) {
            return;
          }

          document.getElementById('showImages').checked = true;
          document.getElementById('imageDiv').classList.remove('display-none');
          const pointDt = DateTime.fromMillis(ts).toFormat("yyyy-MM-dd'T'HH:mm:ss");
          showTestbedImage(pointDt);
        });
        weatherChart.on('legendselectchanged', (params) => {
          updateWeatherYAxisForSelection(params.selected);
        });
      }

      weatherChart.setOption(buildWeatherEchartsOption(legendSelected), { notMerge: true });
      weatherChart.resize();
    };

    var hideAllWeatherSeries = () => {
      if (!weatherChart) {
        return;
      }
      const selected = Object.fromEntries(
        weatherSeriesNames().map((name) => [name, false])
      );
      weatherChart.setOption(buildWeatherEchartsOption(selected), { notMerge: true });
    };

    const otherSeriesNames = () =>
      fieldNames.other.map((key) => labelValues.other[key]);

    const ruuvitagSeriesNames = () => {
      const seriesNames = [];
      for (const name of names.ruuvitag) {
        seriesNames.push(labelValues.rt[name].temperature);
        seriesNames.push(labelValues.rt[name].humidity);
      }
      return seriesNames;
    };

    const getOtherSeriesDataByName = (seriesName) => {
      for (const key of fieldNames.other) {
        if (labelValues.other[key] === seriesName) {
          return dataSets.other[key];
        }
      }
      return null;
    };

    const getRuuvitagSeriesDataByName = (seriesName) => {
      for (const name of names.ruuvitag) {
        if (labelValues.rt[name].temperature === seriesName) {
          return dataSets.rt[name].temperature;
        }
        if (labelValues.rt[name].humidity === seriesName) {
          return dataSets.rt[name].humidity;
        }
      }
      return null;
    };

    const buildObsDayMarkLines = (xLabels, yMin, yMax) => {
      if (!annotationIndices.other.length || !xLabels.length) {
        return [];
      }

      const oneDay = xLabels[0].getDate() === xLabels[xLabels.length - 1].getDate();
      if (oneDay) {
        return [];
      }

      const markData = [];
      for (let i = 0; i < annotationIndices.other.length; i++) {
        if (i === 0) {
          continue;
        }
        markData.push([
          { xAxis: annotationIndices.other[i].getTime(), yAxis: yMin },
          { xAxis: annotationIndices.other[i].getTime(), yAxis: yMax }
        ]);
      }
      return markData;
    };

    const buildObsEchartsOption = (plotType, legendSelected = null) => {
      const isRuuvitag = plotType === 'ruuvitag';
      const xValues = isRuuvitag ? dataLabels.rt : dataLabels.other;
      const pointCount = xValues.length;
      const showMarkers = pointCount <= 500;
      const seriesNameList = isRuuvitag
        ? ruuvitagSeriesNames()
        : otherSeriesNames();
      const selected = legendSelected || Object.fromEntries(
        seriesNameList.map((name) => [name, !isRuuvitag])
      );

      const visibleSeriesData = [];
      for (const seriesName of seriesNameList) {
        if (selected[seriesName] === false) {
          continue;
        }
        const seriesData = isRuuvitag
          ? getRuuvitagSeriesDataByName(seriesName)
          : getOtherSeriesDataByName(seriesName);
        if (seriesData) {
          visibleSeriesData.push(seriesData);
        }
      }

      const { yMin, yMax, yInterval } = yRangeFromVisibleSeries(visibleSeriesData);
      const xMin = xValues[0].getTime();
      const xMax = xValues[xValues.length - 1].getTime();
      const dayMarkLines = buildObsDayMarkLines(xValues, yMin, yMax);

      const series = [
        buildDayMarkLineSeries(dayMarkLines)
      ];
      if (!isRuuvitag) {
        for (let i = 0; i < fieldNames.other.length; i++) {
          const key = fieldNames.other[i];
          const name = labelValues.other[key];
          series.push({
            name,
            type: 'line',
            showSymbol: showMarkers,
            symbolSize: 3,
            triggerLineEvent: true,
            data: xValues.map((dt, idx) => [dt.getTime(), dataSets.other[key][idx]])
          });
        }
      } else {
        for (const tagName of names.ruuvitag) {
          for (const meas of ['temperature', 'humidity']) {
            const name = labelValues.rt[tagName][meas];
            series.push({
              name,
              type: 'line',
              showSymbol: showMarkers,
              symbolSize: 3,
              triggerLineEvent: true,
              data: xValues.map((dt, idx) => [
                dt.getTime(),
                dataSets.rt[tagName][meas][idx]
              ])
            });
          }
        }
      }

      const bottomLayout = buildObsBottomLayout(seriesNameList.length);

      return {
        title: {
          text: `${isRuuvitag ? 'Ruuvitag' : 'Other'} observations`,
          left: 'center'
        },
        legend: {
          ...bottomLayout.legend,
          selected,
          data: seriesNameList
        },
        grid: {
          left: 60,
          right: 30,
          top: 50,
          bottom: bottomLayout.gridBottom
        },
        dataZoom: buildXyDataZoom({
          xAxisIndex: 0,
          yAxisIndex: 0,
          sliderBottom: bottomLayout.sliderBottom
        }),
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'line' },
          formatter: (params) => {
            if (!params || !params.length) {
              return '';
            }
            const chart = isRuuvitag ? ruuvitagChart : otherChart;
            const liveSelected = chart?.getOption()?.legend?.[0]?.selected;
            const ts = DateTime.fromMillis(params[0].axisValue)
              .toFormat('dd.MM. HH:mm:ss');
            let html = `<b>${ts}</b>`;
            for (const p of params) {
              if (liveSelected && liveSelected[p.seriesName] === false) {
                continue;
              }
              const y = tooltipPointValue(p);
              if (!hasTooltipPointData(y)) {
                continue;
              }
              const unit = addUnitSuffix(p.seriesName);
              html += `<br/>${p.marker}${p.seriesName}: ${y}${unit}`;
            }
            return html;
          }
        },
        xAxis: {
          type: 'time',
          name: 'Time',
          nameLocation: 'middle',
          nameGap: 30,
          min: xMin,
          max: xMax,
          axisLabel: {
            hideOverlap: true,
            formatter: (value) =>
              DateTime.fromMillis(value).toFormat('dd.MM. HH:mm')
          }
        },
        yAxis: {
          type: 'value',
          name: 'Value',
          min: yMin,
          max: yMax,
          interval: yInterval,
          scale: false,
          axisLabel: {
            formatter: formatNiceAxisLabel
          }
        },
        series
      };
    };

    const updateObsYAxisForSelection = (plotType, selected) => {
      const chart = plotType === 'ruuvitag' ? ruuvitagChart : otherChart;
      if (!chart) {
        return;
      }

      const isRuuvitag = plotType === 'ruuvitag';
      const xValues = isRuuvitag ? dataLabels.rt : dataLabels.other;
      const seriesNameList = isRuuvitag
        ? ruuvitagSeriesNames()
        : otherSeriesNames();
      const visibleSeriesData = [];

      for (const seriesName of seriesNameList) {
        if (selected[seriesName] === false) {
          continue;
        }
        const seriesData = isRuuvitag
          ? getRuuvitagSeriesDataByName(seriesName)
          : getOtherSeriesDataByName(seriesName);
        if (seriesData) {
          visibleSeriesData.push(seriesData);
        }
      }

      const { yMin, yMax, yInterval } = yRangeFromVisibleSeries(visibleSeriesData);
      chart.setOption({
        yAxis: { min: yMin, max: yMax, interval: yInterval },
        series: [
          {
            id: DAY_MARK_LINE_SERIES,
            markLine: {
              data: buildObsDayMarkLines(xValues, yMin, yMax)
            }
          }
        ]
      });
    };

    var initOrUpdateOtherChart = (preserveLegend = false) => {
      let legendSelected = null;
      if (preserveLegend && otherChart) {
        const option = otherChart.getOption();
        legendSelected = option.legend && option.legend[0]
          ? option.legend[0].selected
          : null;
      }

      const el = document.getElementById('otherPlot');
      if (!otherChart) {
        otherChart = echarts.init(el);
        otherChart.on('click', (params) => {
          let ts = null;
          if (Array.isArray(params.value)) {
            ts = params.value[0];
          } else if (params.data && Array.isArray(params.data.value)) {
            ts = params.data.value[0];
          } else if (params.dataIndex != null
                     && dataLabels.other[params.dataIndex]) {
            ts = dataLabels.other[params.dataIndex].getTime();
          }

          if (ts == null || Number.isNaN(ts)) {
            return;
          }

          document.getElementById('showImages').checked = true;
          document.getElementById('imageDiv').classList.remove('display-none');
          const pointDt = DateTime.fromMillis(ts).toFormat("yyyy-MM-dd'T'HH:mm:ss");
          showTestbedImage(pointDt);
        });
        otherChart.on('legendselectchanged', (params) => {
          updateObsYAxisForSelection('other', params.selected);
        });
      }

      otherChart.setOption(buildObsEchartsOption('other', legendSelected), {
        notMerge: true
      });
      otherChart.resize();
    };

    var initOrUpdateRuuvitagChart = (preserveLegend = false) => {
      let legendSelected = null;
      if (preserveLegend && ruuvitagChart) {
        const option = ruuvitagChart.getOption();
        legendSelected = option.legend && option.legend[0]
          ? option.legend[0].selected
          : null;
      }

      const el = document.getElementById('ruuvitagPlot');
      if (!ruuvitagChart) {
        ruuvitagChart = echarts.init(el);
        ruuvitagChart.on('legendselectchanged', (params) => {
          updateObsYAxisForSelection('ruuvitag', params.selected);
        });
      }

      ruuvitagChart.setOption(buildObsEchartsOption('ruuvitag', legendSelected), {
        notMerge: true
      });
      ruuvitagChart.resize();
    };

    var setObsLegendSelection = (plotType, selected) => {
      const chart = plotType === 'ruuvitag' ? ruuvitagChart : otherChart;
      if (!chart) {
        return;
      }
      chart.setOption(buildObsEchartsOption(plotType, selected), { notMerge: true });
    };

    var hideAllObsSeries = (plotType) => {
      const seriesNameList = plotType === 'ruuvitag'
        ? ruuvitagSeriesNames()
        : otherSeriesNames();
      const selected = Object.fromEntries(
        seriesNameList.map((name) => [name, false])
      );
      setObsLegendSelection(plotType, selected);
    };

    var showAllObsSeries = (plotType) => {
      const seriesNameList = plotType === 'ruuvitag'
        ? ruuvitagSeriesNames()
        : otherSeriesNames();
      const selected = Object.fromEntries(
        seriesNameList.map((name) => [name, true])
      );
      setObsLegendSelection(plotType, selected);
    };

    var showRuuvitagSeriesType = (type) => {
      const selected = {};
      for (const seriesName of ruuvitagSeriesNames()) {
        selected[seriesName] = seriesName.includes(type);
      }
      setObsLegendSelection('ruuvitag', selected);
    };

    initOrUpdateWeatherChart();
    initOrUpdateOtherChart();
    initOrUpdateRuuvitagChart();

    document.getElementById('weatherPlotAccordion')
      .addEventListener('shown.bs.collapse', () => {
        if (weatherChart) {
          weatherChart.resize();
        }
      });

    document.getElementById('otherPlotAccordion')
      .addEventListener('shown.bs.collapse', () => {
        if (otherChart) {
          otherChart.resize();
        }
      });

    document.getElementById('ruuvitagPlotAccordion')
      .addEventListener('shown.bs.collapse', () => {
        if (ruuvitagChart) {
          ruuvitagChart.resize();
        }
      });

    document.getElementById('elecHourPlotAccordion')
      .addEventListener('shown.bs.collapse', () => {
        if (hourElecChart) {
          hourElecChart.resize();
        }
      });

    document.getElementById('elecDayPlotAccordion')
      .addEventListener('shown.bs.collapse', () => {
        if (dayElecChart) {
          dayElecChart.resize();
        }
      });

    document.getElementById('elecMinutePlotAccordion')
      .addEventListener('shown.bs.collapse', () => {
        if (minuteElecChart) {
          minuteElecChart.resize();
        }
      });
  }

  const toggleClassForElement = (elementId, className) => {
    document.getElementById(elementId).classList.toggle(className);
  };

  const toggleVisibility = (elementId) => {
    toggleClassForElement(elementId, 'display-none');
  };

  const toggleLoadingSpinner = () => {
    document.getElementsByTagName('body')[0].classList.toggle('top-padding');
    toggleClassForElement('bodyDiv', 'top-padding');
    toggleClassForElement('loadingSpinner', 'fg-blur');
    toggleVisibility('loadingSpinner');
    toggleClassForElement('bodyDiv', 'bg-blur');
  };

  const updateButtonClickHandler = (event) => {
    const startDate = document.getElementById('startDate').value;
    const endDate = document.getElementById('endDate').value;
    let isSpinnerShown = false;

    if ((startDate && DateTime.fromISO(startDate).invalid) ||
      (endDate && DateTime.fromISO(endDate).invalid)) {
      alert('Error: either the start or end date is invalid');
      event.preventDefault();
      return;
    }

    if (DateTime.fromISO(startDate) > DateTime.fromISO(endDate)) {
      alert('Error: start date must be smaller than the end date');
      event.preventDefault();
      return;
    }

    if (inclusiveDayCount(startDate, endDate) > maxDisplayDays) {
      showDateRangeError(dateRangeTooLargeMessage(maxDisplayDays));
      event.preventDefault();
      return;
    }

    hideDateRangeError();

    const diff = DateTime.fromISO(endDate).diff(
      DateTime.fromISO(startDate), ['days']);

    if (diff.days >= 7) {
      isSpinnerShown = true;
      toggleLoadingSpinner();
    }

    const plotUpdateAfterReset = (plotType) => {
      if (plotType === 'weather') {
        initOrUpdateWeatherChart(true);
        return;
      }
      if (plotType === 'other') {
        initOrUpdateOtherChart(true);
        return;
      }
      if (plotType === 'ruuvitag') {
        initOrUpdateRuuvitagChart(true);
      }
    };

    axios.get('data/display',
      {
        params: {
          startDate,
          endDate
        }
      })
      .then(resp => {
        const rData = resp.data;

        hideDateRangeError();
        data.weather = rData['weather-data'];
        data.weatherObs = rData['weather-obs-data'];
        data.other = rData['obs-data'];
        data.rt = rData['rt-data'];

        showDisplayResolution(rData['display-resolution']);

        document.getElementById('startDate').value = rData['obs-dates'].current.start;
        document.getElementById('endDate').value = rData['obs-dates'].current.end;

        transformData();

        plotUpdateAfterReset('weather');
        plotUpdateAfterReset('other');
        plotUpdateAfterReset('ruuvitag');
      })
      .catch(handleDisplayDataError)
      .then(() => {
        if (isSpinnerShown) {
          toggleLoadingSpinner();
        }
      });
  };

  const validateElecDateInterval = (startDate, endDate) => {
    if ((startDate && DateTime.fromISO(startDate).invalid) ||
      (endDate && DateTime.fromISO(endDate).invalid)) {
      alert('Error: either the start or end date is invalid');
      return false;
    }

    if (DateTime.fromISO(startDate) > DateTime.fromISO(endDate)) {
      alert('Error: start date must be smaller than the end date');
      return false;
    }

    return true;
  };

  const refreshElecDataForDateRange = (startDate, endDate) => {
    if (!validateElecDateInterval(startDate, endDate)) {
      return false;
    }

    axios.get('data/elec-data',
      {
        params: {
          startDate,
          endDate,
          addFees: document.getElementById('elecPriceShowFees').checked
        }
      })
      .then(resp => {
        const elecData = resp.data;

        if (elecData) {
          if (elecData.error) {
            if (elecData.error !== 'not-enabled') {
              console.log(`Electricity data fetch error: ${elecData.error}`);
            }

            return;
          }

          document.getElementById('elecStartDate').value = elecData.dates.current.start;
          document.getElementById('elecEndDate').value = elecData.dates.current.end;

          plotElectricityDataHour(elecData['data-hour']);
          plotElectricityDataDay(elecData['data-day']);

          if (elecData['month-price-avg']) {
            document.getElementById('elecMonthAvg').innerText = elecData['month-price-avg'];
          }
          document.getElementById('intervalCost').innerText =
            elecData['interval-cost'] !== null ? elecData['interval-cost'] : 0;
        }
      })
      .catch(error => {
        if (error.status === 401) {
          redirectToLogin();
        } else {
          console.log(`Electricity data fetch error: ${error}`);
        }
      });

    return true;
  };

  const elecUpdateButtonClickHandler = (event) => {
    const startDate = document.getElementById('elecStartDate').value;
    const endDate = document.getElementById('elecEndDate').value;

    if (!refreshElecDataForDateRange(startDate, endDate)) {
      event.preventDefault();
      return false;
    }

    return true;
  };


  const refreshElecMinutePriceForDate = (minuteDate) => {
    if ((minuteDate && DateTime.fromISO(minuteDate).invalid)) {
      alert('Error: electricity price date is invalid');
      return false;
    }

    const priceData = getElecMinutePriceData(minuteDate);
    if (priceData) {
      plotElectricityPriceMinute(elecMinutePriceData[minuteDate].prices);
    } else {
      axios.get('data/elec-price-minute',
                {
                  params: {
                    date: minuteDate,
                    addFees: document.getElementById('elecPriceShowFees').checked
                  }
                })
        .then(resp => {
          const elecData = resp.data;

          if (elecData.error) {
            console.log(`Electricity data fetch error: ${elecData.error}`);
            return;
          }

          plotElectricityPriceMinute(elecData.prices);
          elecMinutePriceData[minuteDate] = {prices: elecData.prices,
                                             addFees: document.getElementById('elecPriceShowFees').checked};
        })
        .catch(error => {
          if (error.status === 401) {
            redirectToLogin();
          } else {
            console.log(`Electricity price fetch error: ${error}`);
          }
        });
    }

    return true;
  };

  const elecMinuteDateUpdateBtnClickHandler = (event) => {
    const minuteDate = document.getElementById('elecMinuteDate').value;

    if (!refreshElecMinutePriceForDate(minuteDate)) {
      event.preventDefault();
      return false;
    }

    return true;
  };

  const elecPriceShowFeesChangeHandler = () => {
    // Fees affect both hourly / daily and 15 minute electricity prices
    refreshElecDataForDateRange(
      document.getElementById('elecStartDate').value,
      document.getElementById('elecEndDate').value
    );
    refreshElecMinutePriceForDate(document.getElementById('elecMinuteDate').value);
  };

  // Hide all series for a plot
  const plotHideAll = (plotType) => {
    hideAllObsSeries(plotType);
  };

  // Show all series for a plot
  const plotShowAll = (plotType) => {
    showAllObsSeries(plotType);
  };

  const updateMinuteElecPrice = (direction) => {
    let dateField = document.getElementById('elecMinuteDate');

    const fetchPrice = (newDate) => {
      const elecPrice = getElecMinutePriceData(newDate);
      if (elecPrice) {
        dateField.value = newDate;
        plotElectricityPriceMinute(elecPrice);
      } else {
        axios.get('data/elec-price-minute',
                  {
                    params: {
                      date: newDate,
                      addFees: document.getElementById('elecPriceShowFees').checked
                    }
                  })
          .then(resp => {
            const elecData = resp.data;

            if (elecData.error) {
              console.log(`Electricity data fetch error: ${elecData.error}`);
              return;
            }

            dateField.value = newDate;
            plotElectricityPriceMinute(elecData.prices);

            elecMinutePriceData[newDate] = {prices: elecData.prices,
                                            addFees: document.getElementById('elecPriceShowFees').checked};
          })
          .catch(error => {
            if (error.status === 401) {
              redirectToLogin();
            } else {
              console.log(`Electricity price fetch error: ${error}`);
            }
          });
      }
    };

    if (direction === 'forward') {
      const newDate = DateTime.fromISO(dateField.value).plus({days: 1});

      if (DateTime.fromISO(dateField.max) >= newDate) {
        fetchPrice(newDate.toISODate());
      } else {
        alert('You are already at the newest date');
      }
    } else {
      const newDate = DateTime.fromISO(dateField.value).minus({days: 1});

      if (DateTime.fromISO(dateField.min) <= newDate) {
        fetchPrice(newDate.toISODate());
      } else {
        alert('You are already at the oldest date');
      }
    }
  };

  // Click handlers
  document.getElementById('updateBtn').addEventListener(
    'click',
    updateButtonClickHandler,
    false);

  document.getElementById('elecUpdateBtn').addEventListener(
    'click',
    elecUpdateButtonClickHandler,
    false);

  document.getElementById('elecMinuteDateUpdateBtn').addEventListener(
    'click',
    elecMinuteDateUpdateBtnClickHandler,
    false);

  document.getElementById('elecPriceShowFees').addEventListener(
    'change',
    elecPriceShowFeesChangeHandler,
    false);

  document.getElementById('elecMinuteDayBackward').addEventListener(
    'click',
    () => {
      updateMinuteElecPrice('backward');
    },
    false);

   document.getElementById('elecMinuteDayForward').addEventListener(
    'click',
    () => {
      updateMinuteElecPrice('forward');
    },
    false);

  document.getElementById('showImages').addEventListener(
    'click',
    () => {
      toggleVisibility('imageDiv');
    },
    false);

  document.getElementById('weatherHideAll').addEventListener(
    'click',
    () => {
      hideAllWeatherSeries();
    },
    false);

  if (data.other.recorded.length > 0) {
    showElectricityData();

    document.getElementById('showInfoText').addEventListener('click',
      () => {
        toggleVisibility('infoText');
      },
      false);

    document.getElementById('otherHideAll')
      .addEventListener('click',
                        () => {
                          plotHideAll('other');
        },
        false);

    document.getElementById('otherShowAll')
      .addEventListener('click',
                        () => {
                          plotShowAll('other');
        },
        false);

    document.getElementById('ruuvitagHideAll')
      .addEventListener('click',
                        () => {
                          plotHideAll('ruuvitag');
        },
        false);

    document.getElementById('ruuvitagShowAll')
      .addEventListener('click',
                        () => {
                          plotShowAll('ruuvitag');
        },
        false);

    document.getElementById('ruuvitagShowTemperature')
      .addEventListener('click',
        () => {
          showRuuvitagSeriesType('temperature');
        },
        false);

    document.getElementById('ruuvitagShowHumidity')
      .addEventListener('click',
        () => {
          showRuuvitagSeriesType('humidity');
        },
        false);

    document.getElementById('elecPlotAccordion')
      .addEventListener('shown.bs.collapse', () => {
        // Scroll page to bottom after loading the image for improved viewing
        scrollToBottom(0);
      },
      false);
  }
};

axios.get('data/display')
  .then(resp => {
    const rData = resp.data;

    testbedImageBasepath = rData['tb-image-basepath'];
    data.weather = rData['weather-data'];
    data.weatherObs = rData['weather-obs-data'];
    data.other = rData['obs-data'];
    data.rt = rData['rt-data'];
    names.ruuvitag = rData['rt-names'];
    maxDisplayDays = rData['max-display-days'] ?? maxDisplayDays;

    showDisplayResolution(rData['display-resolution']);

    if (rData['obs-dates']['min-max']) {
      const intMinMax = rData['obs-dates']['min-max'];

      document.getElementById('startDate').min = intMinMax.start;
      document.getElementById('startDate').max = intMinMax.end;

      document.getElementById('endDate').min = intMinMax.start;
      document.getElementById('endDate').max = intMinMax.end;
    }

    if (rData['obs-dates'].current) {
      document.getElementById('startDate').value = rData['obs-dates'].current.start;
      document.getElementById('endDate').value = rData['obs-dates'].current.end;
    }

    loadPage();
  })
  .catch(handleDisplayDataError);

setInterval(() => {
  refreshTokensIfNeeded();
}, 30000);
