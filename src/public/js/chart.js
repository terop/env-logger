/* global alert,axios,luxon,Plotly */

const DateTime = luxon.DateTime;

// Data field names
const fieldNames = {
  weather: ['fmi-temperature', 'cloudiness', 'wind-speed'],
  other: ['brightness', 'o-temperature', 'beacon-rssi', 'beacon-battery']
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
  obs: [],
  rt: [],
  weather: []
};
let dataLabels = {
  weather: [],
  other: []
};
let annotationIndexes = {
  weather: [],
  other: []
};
let bleBeaconNames = [];
let mode = null;
let testbedImageBasepath = '';
let testbedImageNames = [];
let rtNames = [];

const loadPage = () => {
  // Parse RuuviTag observations
  // rtObservations - observations as JSON
  // rtLabels - RuuviTag labels
  const parseRTData = (rtObservations, rtLabels) => {
    const timeDiffThreshold = 10;
    let location = null;
    let dateRef = null;
    const obsByDate = {};

    for (const label of rtLabels) {
      dataSets.rt[label] = {
        temperature: [],
        humidity: []
      };
    }

    for (let i = 0; i < rtObservations.length; i++) {
      const obs = rtObservations[i];

      if (!dateRef) {
        dateRef = obs.recorded;
      }

      location = obs.location;

      const diff = DateTime.fromMillis(dateRef).diff(
        DateTime.fromMillis(obs.recorded), 'seconds');

      if (Math.abs(diff.toObject().seconds) > timeDiffThreshold) {
        dateRef = obs.recorded;
      }

      if (obsByDate[dateRef] === undefined) {
        obsByDate[dateRef] = {};
      }

      if (obsByDate[dateRef] !== undefined) {
        if (obsByDate[dateRef][location] === undefined) {
          obsByDate[dateRef][location] = {};
        }

        obsByDate[dateRef][location].temperature = obs.temperature;
        obsByDate[dateRef][location].humidity = obs.humidity;
      }
    }

    for (const key of Object.keys(obsByDate)) {
      for (const label of rtLabels) {
        if (obsByDate[key][label] !== undefined) {
          dataSets.rt[label].temperature.push(obsByDate[key][label].temperature);
          dataSets.rt[label].humidity.push(obsByDate[key][label].humidity);
        } else {
          dataSets.rt[label].temperature.push(null);
          dataSets.rt[label].humidity.push(null);
        }
      }
    }

    // "null pad" RuuviTag observations to align latest observations with non-RuuviTag observations
    for (const key of Object.keys(dataSets.rt)) {
      const lenDiff = dataSets.other.brightness.length - dataSets.rt[key].temperature.length;
      for (let j = 0; j < lenDiff; j++) {
        dataSets.rt[key].temperature.unshift(null);
        dataSets.rt[key].humidity.unshift(null);
      }
    }
  };

  /* Parse an observation.
   *
   * Arguments:
   * observation - an observation as JSON
   */
  const parseData = (observation) => {
    // dataMode - string, which mode to process data in, values: weather, other
    // observation - object, observation to process
    // selectKeys - array, which data keys to select
    const processFields = (dataMode, observation, selectKeys) => {
      for (const key in observation) {
        if (selectKeys.includes(key)) {
          if (dataSets[dataMode][key] !== undefined) {
            dataSets[dataMode][key].push(observation[key]);
          } else {
            dataSets[dataMode][key] = [observation[key]];
          }
        }
      }
    };

    const recordAnnotationIndexes = (dataMode, observationTime) => {
      // Skip the first few observations as a line annotation is not needed
      // in the beginning of a chart
      if (dataLabels[dataMode].length > 2) {
        const recorded = DateTime.fromMillis(observationTime);
        if (recorded.hour === 0 && recorded.minute === 0) {
          annotationIndexes[dataMode].push(recorded.toJSDate());
        }
      }
    };

    if (mode === 'all') {
      dataLabels.other.push(new Date(observation.recorded));

      recordAnnotationIndexes('other', observation.recorded);

      if (observation['weather-recorded']) {
        dataLabels.weather.push(new Date(observation['weather-recorded']));

        recordAnnotationIndexes('weather', observation['weather-recorded']);
      }

      // Weather
      if (observation['weather-recorded']) {
        processFields('weather', observation, fieldNames.weather);
      }

      // Other
      processFields('other', observation, fieldNames.other);

      bleBeaconNames.push(observation['beacon-name']);
    } else {
      dataLabels.weather.push(new Date(observation.time));

      recordAnnotationIndexes('weather', observation.time);

      processFields('weather', observation, fieldNames.weather);
    }
    testbedImageNames.push(observation['tb-image-name']);
  };

  // Transform data to Plotly compatible format. Returns the data series labels.
  const transformData = () => {
    dataSets = {
      weather: {},
      other: {},
      rt: {}
    };
    dataLabels = {
      weather: [],
      other: []
    };
    annotationIndexes = {
      weather: [],
      other: []
    };
    testbedImageNames = [];
    bleBeaconNames = [];

    data.obs.forEach((element) => {
      parseData(element);
    });

    // Data labels
    if (mode === 'all') {
      parseRTData(data.rt, rtNames);

      let beaconName = null;
      for (const item of bleBeaconNames) {
        if (item !== null) {
          beaconName = item;
          break;
        }
      }

      labelValues.other = {
        brightness: 'Brightness',
        'o-temperature': 'Outside temperature',
        'beacon-rssi': beaconName
          ? `Beacon "${beaconName}" RSSI`
          : 'Beacon RSSI',
        'beacon-battery': beaconName
          ? `Beacon "${beaconName}" battery level`
          : 'Beacon battery level'
      };
      for (const name of rtNames) {
        labelValues.rt[name] = {
          temperature: `RT "${name}" temperature`,
          humidity: `RT "${name}" humidity`
        };
      }
    }
    labelValues.weather = {
      'fmi-temperature': 'Temperature',
      cloudiness: 'Cloudiness',
      'wind-speed': 'Wind speed'
    };

    return labelValues;
  };

  const hideElement = (elementId) => {
    document.getElementById(elementId).style.display = 'none';
  };

  if (data.obs.length === 0) {
    document.getElementById('noDataError').style.display = 'block';
    hideElement('weatherDiv');
    hideElement('imageButtonDiv');
    if (mode === 'all') {
      hideElement('latestCheckboxDiv');
      hideElement('weatherCheckboxDiv');
      hideElement('plotAccordion');
      hideElement('elecDataCheckboxDiv');
    }
  } else {
    labelValues = transformData();

    // Add unit suffix
    const addUnitSuffix = (keyName) => {
      keyName = keyName.toLowerCase();
      return `${keyName.includes('temperature') ? ' \u2103' : ''}` +
        `${keyName.includes('wind') ? ' m/s' : ''}` +
        `${keyName.includes('humidity') ? ' %H' : ''}` +
        `${keyName.includes('rssi') ? ' dBm' : ''}` +
        `${keyName.includes('battery') ? ' %' : ''}`;
    };

    // Format a given Unix second timestamp in hour:minute format
    const formatUnixSecondTs = (unixTs) => {
      return DateTime.fromSeconds(unixTs).toFormat('HH:mm');
    };

    /* eslint-disable no-var */
    var scrollToBottom = (timeout) => {
      window.setTimeout(() => {
        window.scroll(0, document.body.scrollHeight);
      }, timeout);
    };
    /* eslint-enable no-var */

    // Show last observation and some other data for quick viewing
    const showLastObservation = () => {
      let observationText = '';
      const weatherKeys = ['fmi-temperature', 'cloudiness', 'wind-speed'];

      if (!data.weather) {
        console.log('Error: no weather data');
        return;
      }

      const wd = (mode === 'all' ? data.weather.fmi.current : data.weather);
      if (wd) {
        observationText += `${DateTime.now().setLocale('fi').toLocaleString()}` +
          ` ${DateTime.fromISO(wd.time).toLocaleString(DateTime.TIME_SIMPLE)}: `;
        for (const key of weatherKeys) {
          if (key === 'wind-speed') {
            observationText += `Wind: ${wd['wind-direction'].long} ` +
              `${wd[key]} ${addUnitSuffix(key)}, `;
          } else if (key === 'fmi-temperature') {
            observationText += `${labelValues.weather[key]}: ` +
              `${wd.temperature} ${addUnitSuffix(key)}, `;
          } else {
            observationText += `${labelValues.weather[key]}: ${wd[key]}` +
              `${addUnitSuffix(key)}, `;
          }
        }
      }

      if (mode === 'all') {
        if (data.weather.owm) {
          observationText += `Description: ${data.weather.owm.current.weather[0].description}<br>` +
            `Sun: Sunrise ${formatUnixSecondTs(data.weather.owm.current.sunrise)},` +
            ` Sunset ${formatUnixSecondTs(data.weather.owm.current.sunset)}<br>`;
        }

        let obsIndex = dataSets.other.brightness.length - 1;

        observationText += `${labelValues.other.brightness}: ${dataSets.other.brightness[obsIndex]}` +
          `${addUnitSuffix('brightness')}, `;
        observationText += `${labelValues.other['o-temperature']}:`;
        if (dataSets.other['o-temperature'][obsIndex] !== null) {
          observationText += ` ${dataSets.other['o-temperature'][obsIndex]}` +
            `${addUnitSuffix('temperature')}, `;
        }

        observationText += `Beacon "${bleBeaconNames[obsIndex]}": RSSI`;
        if (dataSets.other['beacon-rssi'][obsIndex] !== null) {
          observationText += ` ${dataSets.other['beacon-rssi'][obsIndex]}${addUnitSuffix('beacon-rssi')}`;

          const battery = dataSets.other['beacon-battery'][obsIndex];
          const batteryText = battery ? `${battery} ${addUnitSuffix('beacon-battery')}` : 'NA';
          observationText += `, battery level ${batteryText}`;
        }

        observationText += ',';

        let itemsAdded = 0;
        if (dataSets.rt) {
          obsIndex = dataSets.rt[Object.keys(dataSets.rt)[0]].temperature.length - 1;
          for (const tag in labelValues.rt) {
            if ((itemsAdded % 4) === 0) {
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
        if (forecast && data.weather.owm.forecast) {
          document.getElementById('forecast').innerHTML =
            '<br><br>Forecast for ' +
            DateTime.fromISO(forecast.time).toFormat('dd.MM.yyyy HH:mm') +
            `: temperature: ${forecast.temperature} \u2103, ` +
            `cloudiness: ${forecast.cloudiness} %, ` +
            `wind: ${forecast['wind-direction'].long} ${forecast['wind-speed']} m/s, ` +
            `precipitation: ${forecast.precipitation} mm, ` +
            `description: ${data.weather.owm.forecast.weather[0].description}`;
        }
      } else {
        observationText = observationText.slice(0, -2);
      }

      document.getElementById('lastObservation').innerHTML = observationText;
      document.getElementById('latestDiv').classList.remove('display-none');
    };
    showLastObservation();

    // Show the hourly electricity price and consumption data in a chart
    /* eslint-disable no-var */
    var plotElectricityDataHour = (elecData, updateDate = false,
      removeLast = false) => {
      /* eslint-disable no-unused-vars */
      const generateElecAnnotationConfig = (xValues, yValues) => {
        const currentIdx = getClosestElecPriceDataIndex(xValues);
        const shapes = [];
        const extValues = getDataExtremeValues(yValues);
        const yMinMax = [extValues[0] - 0.4, extValues[1] + 0.4];

        // Skip first and last data points as lines are not needed there
        for (let i = 1; i < xValues.length - 1; i++) {
          if (DateTime.fromJSDate(xValues[i]).hour === 0) {
            shapes.push({
              type: 'line',
              x0: xValues[i],
              y0: yMinMax[0],
              x1: xValues[i],
              y1: yMinMax[1],
              line: {
                color: '#838b93',
                width: 1
              }
            });
          }
        }

        if (DateTime.fromISO(document.getElementById('elecEndDate').value) >=
          DateTime.fromISO(DateTime.now().toISODate())) {
          shapes.push({
            type: 'line',
            x0: xValues[currentIdx],
            y0: yMinMax[0],
            x1: xValues[currentIdx],
            y1: yMinMax[1],
            line: {
              width: 2
            }
          });
        }

        return shapes;
      };
      /* eslint-enable no-unused-vars */

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

      if (updateDate) {
        document.getElementById('elecEndDate').value = xValues.length
          ? DateTime.fromJSDate(xValues[xValues.length - 1]).toISODate()
          : DateTime.now().toISODate();
      }

      const generateElecTraceConfig = () => {
        return [{
          x: xValues,
          y: data.price,
          name: 'Price',
          type: 'scattergl',
          mode: 'lines+markers',
          marker: {
            size: 5
          },
          xhoverformat: '<b>%d.%m. %H:%M</b>',
          hovertemplate: '%{y}%{text}',
          text: Array(xValues.length).fill(' c / kWh')
        },
        {
          x: xValues,
          y: data.consumption,
          name: 'Consumption',
          type: 'bar',
          yaxis: 'y2',
          xhoverformat: '<b>%d.%m. %H:%M</b>',
          hovertemplate: '%{text}',
          text: data.consumption.map((value) => `${value} kWh`)
        }];
      };

      const extValuesConsp = getDataExtremeValues([data.consumption]);
      const extValuesPrice = getDataExtremeValues([data.price]);

      const generateElecLayoutConfig = (diffInDays) => {
        return {
          width: 1300,
          height: 650,
          title: 'Hourly electricity price and consumption',
          xaxis: {
            title: 'Time',
            type: 'date',
            dtick: getXAxisTickSize(diffInDays),
            tickformat: '%H',
            tickangle: -45
          },
          yaxis: {
            title: 'Price (c / kWh)',
            range: [extValuesPrice[0] - 0.5,
              extValuesPrice[extValuesPrice.length - 1] + 0.5]
          },
          yaxis2: {
            title: 'Consumption (kWh)',
            overlaying: 'y',
            side: 'right',
            range: [extValuesConsp[0] - 0.1, extValuesConsp[extValuesConsp.length - 1] + 0.1]
          },
          legend: {
            orientation: 'h'
          },
          hovermode: 'x unified',
          shapes: generateElecAnnotationConfig(xValues, [data.price, data.consumption])
        };
      };

      const diffInDays = DateTime.fromJSDate(xValues[xValues.length - 1]).diff(
        DateTime.fromJSDate(xValues[0]), 'days').toObject().days;
      if (!document.getElementById('hourElecDataPlot').data) {
        Plotly.newPlot('hourElecDataPlot',
          generateElecTraceConfig(),
          generateElecLayoutConfig(diffInDays));
      } else {
        Plotly.react('hourElecDataPlot',
          generateElecTraceConfig(),
          generateElecLayoutConfig(diffInDays));
      }
    };

    // Show the daily electricity price and consumption data in a chart
    var plotElectricityDataDay = (elecData, removeLast = false) => {
      const labels = [];
      const data = {
        price: [],
        consumption: []
      };

      for (let i = 0; i < elecData.length - (removeLast ? 1 : 0); i++) {
        const item = elecData[i];
        labels.push(item.date);
        data.price.push(item.price);
        data.consumption.push(item.consumption);
      }

      const generateElecTraceConfig = () => {
        return [{
          x: labels,
          y: data.price,
          name: 'Price',
          type: 'scattergl',
          mode: 'lines+markers',
          marker: {
            size: 5
          },
          xhoverformat: '<b>%d.%m.%Y</b>',
          hovertemplate: '%{y}%{text}',
          text: Array(labels.length).fill(' c / kWh')
        },
        {
          x: labels,
          y: data.consumption,
          name: 'Consumption',
          type: 'bar',
          yaxis: 'y2',
          xhoverformat: '<b>%d.%m.%Y</b>',
          hovertemplate: '%{text}',
          text: data.consumption.map((value) => `${value} kWh`)
        }];
      };

      const generateElecLayoutConfig = () => {
        return {
          width: 1300,
          height: 550,
          title: 'Daily electricity price and consumption',
          xaxis: {
            title: 'Time',
            type: 'date',
            dtick: 86400000,
            tickformat: '%d.%m.%Y'
          },
          yaxis: {
            title: 'Average price (c / kWh)',
            type: 'log'
          },
          yaxis2: {
            title: 'Consumption (kWh)',
            overlaying: 'y',
            side: 'right'
          },
          legend: {
            orientation: 'h'
          },
          hovermode: 'x unified'
        };
      };

      if (!document.getElementById('dayElecDataPlot').data) {
        Plotly.newPlot('dayElecDataPlot',
          generateElecTraceConfig(),
          generateElecLayoutConfig());
      } else {
        Plotly.react('dayElecDataPlot',
          generateElecTraceConfig(),
          generateElecLayoutConfig());
      }
    };
    /* eslint-enable no-var */

    // Determine the index of electricity price data value which is closest to the current hour
    const getClosestElecPriceDataIndex = (xValues) => {
      const now = DateTime.now();

      let smallest = Infinity;
      let smallestIdx = -1;

      for (let i = 0; i < xValues.length; i++) {
        const diff = Math.abs(DateTime.fromJSDate(xValues[i]).diff(now).milliseconds);
        if (diff < smallest) {
          smallest = diff;
          smallestIdx = i;
        }
      }

      // Special case handling for the situation when the next hour is closer than the current
      if (now.hour < DateTime.fromJSDate(xValues[smallestIdx]).hour) {
        smallestIdx -= 1;
      }

      return smallestIdx;
    };

    // Fetch and display current electricity price data
    /* eslint-disable no-var */
    var showElectricityPrice = () => {
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
          document.getElementById('lastObservation').innerHTML += '<br>Electricity price at ' +
            `${currentPriceTime}: ${currentHourData.price} c / kWh`;
        }

        const nextHourData = priceData[currentIdx + 1];
        if (nextHourData) {
          const nextPriceTime = DateTime.fromISO(nextHourData['start-time']).toFormat('HH:mm');
          document.getElementById('forecast').innerHTML += '<br>Electricity price at ' +
            `${nextPriceTime}: ${nextHourData.price} c / kWh`;
        }
      };

      axios.get('data/elec-data')
        .then(resp => {
          const elecData = resp.data;

          if (elecData) {
            if (elecData.error) {
              if (elecData.error !== 'not-enabled') {
                console.log(`Electricity data fetch error: ${elecData.error}`);
              }
              toggleClassForElement('elecDataCheckboxDiv', 'display-none');

              return;
            }

            if (!elecData['data-hour'] || !elecData['data-day'][0]) {
              toggleVisibility('elecDataCheckboxDiv');
              return;
            }

            if (elecData.dates.max) {
              const dateMax = elecData.dates.max;

              document.getElementById('elecStartDate').max = dateMax;
              document.getElementById('elecEndDate').max = dateMax;
            }

            if (elecData.dates.min) {
              const dateMin = elecData.dates.min;

              document.getElementById('elecStartDate').min = dateMin;
              document.getElementById('elecEndDate').min = dateMin;
            }

            if (elecData.dates.current.start) {
              document.getElementById('elecStartDate').value = elecData.dates.current.start;
            }

            showLatestPrice(elecData['data-hour']);
            plotElectricityDataHour(elecData['data-hour'], true, true);

            plotElectricityDataDay(elecData['data-day'], true);

            if (elecData['elec-price-avg'] !== null) {
              document.getElementById('lastObservation').innerHTML +=
                `<br>Average electricity price for the current month: ${elecData['elec-price-avg']} c / kWh`;
            }
          }
        }).catch(error => {
          console.log(`Electricity data fetch error: ${error}`);
        });
    };
    /* eslint-enable no-var */

    const showTestbedImage = (pointDt) => {
      const pattern = /testbed-(.+).png/;
      const imageCountIdx = testbedImageNames.length - 1;
      const refDt = DateTime.fromISO(pointDt.replace(' ', 'T'));
      let smallest = 100000;
      let smallestIdx = imageCountIdx;

      for (let i = imageCountIdx; i >= 0; i--) {
        const match = pattern.exec(testbedImageNames[i]);
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

      const imageName = testbedImageNames[smallestIdx];
      const datePattern = /testbed-([\d-]+)T.+/;
      const result = datePattern.exec(imageName);
      if (result) {
        document.getElementById('testbedImage').src =
          `${testbedImageBasepath}${result[1]}/${imageName}`;
        // Scroll page to bottom after loading the image for improved viewing
        scrollToBottom(500);
      }
    };

    // Return extreme (min and max) values for all plot data y-axis values
    const getDataExtremeValues = (plotData) => {
      let minValue = 1000000;
      let maxValue = -100000;

      for (let i = 0; i < plotData.length; i++) {
        const series = plotData[i].filter((item) => !Number.isNaN(item) && item !== null);

        const seriesMin = Math.min(...series);
        const seriesMax = Math.max(...series);

        if (seriesMin < minValue) {
          minValue = seriesMin;
        }
        if (seriesMax > maxValue) {
          maxValue = seriesMax;
        }
      }

      return [minValue, maxValue];
    };

    // Return the desired plot x-axis tick size for the given x-values difference
    const getXAxisTickSize = (diffInDays) => {
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

    // Return the "padding" i.e. amount of empty area for the y-axis
    const getYAxisPadding = (extremeValues) => {
      const diff = Math.abs(extremeValues[1] - extremeValues[0]);

      if (diff < 20) {
        return 1;
      } else if (diff >= 20 && diff < 100) {
        return 4;
      } else {
        return 8;
      }
    };

    /* eslint-disable no-var */
    var generateTraceConfig = (plotType) => {
      const xValues = plotType === 'weather' ? dataLabels.weather : dataLabels.other;
      const traces = [];
      const commonOpts = {
        type: 'scatter',
        mode: 'lines+markers',
        marker: {
          size: 3
        },
        xhoverformat: '<b>%d.%m. %H:%M:%S</b>',
        hovertemplate: '%{y}%{text}'
      };
      let changingOpts = {};

      const getTraceVisibility = (isRuuvitag) => {
        if (plotType === 'weather' || !isRuuvitag) {
          return true;
        } else {
          return 'legendonly';
        }
      };

      if (plotType !== 'ruuvitag') {
        for (const key of plotType === 'weather' ? fieldNames.weather : fieldNames.other) {
          changingOpts = {
            x: xValues,
            y: dataSets[plotType][key],
            name: labelValues[plotType][key],
            visible: getTraceVisibility(false),
            text: Array(xValues.length).fill(addUnitSuffix(labelValues[plotType][key]))
          };
          traces.push({ ...changingOpts, ...commonOpts });
        }
      } else {
        const rtMeasurables = ['temperature', 'humidity'];

        for (const name of rtNames) {
          for (const meas of rtMeasurables) {
            changingOpts = {
              x: xValues,
              y: dataSets.rt[name][meas],
              name: labelValues.rt[name][meas],
              visible: getTraceVisibility(true),
              text: Array(xValues.length).fill(addUnitSuffix(labelValues.rt[name][meas]))
            };
            traces.push({ ...changingOpts, ...commonOpts });
          }
        }
      }

      return traces;
    };
    /* eslint-enable no-var */

    const generateAnnotationConfig = (plotType, traceData) => {
      if (annotationIndexes[plotType].length) {
        const shapes = [];
        let yValues = [];

        if (traceData.length) {
          yValues = getDataExtremeValues(traceData);
          const padding = getYAxisPadding(yValues);
          yValues[0] -= padding;
          yValues[1] += padding;
        } else {
          yValues = [-1, 4];
        }

        for (const value of annotationIndexes[plotType]) {
          shapes.push({
            type: 'line',
            x0: value,
            y0: yValues[0],
            x1: value,
            y1: yValues[1],
            line: {
              color: '#838b93',
              width: 1
            }
          });
        }

        return shapes;
      }
      return null;
    };

    /* eslint-disable no-var */
    var generateLayoutConfig = (plotType, isUpdate = false) => {
      const xValues = plotType === 'weather' ? dataLabels.weather : dataLabels.other;
      const diffInDays = DateTime.fromJSDate(xValues[xValues.length - 1]).diff(
        DateTime.fromJSDate(xValues[0]), 'days').toObject().days;
      /* eslint-disable multiline-ternary */
      const plotTitleStart = (plotType === 'weather')
        ? 'FMI weather' : plotType === 'other'
          ? 'Other' : 'Ruuvitag';
      /* eslint-enable multiline-ternary */

      const traceData = [];
      if (!isUpdate) {
        if (plotType !== 'ruuvitag') {
          for (const key of plotType === 'weather' ? fieldNames.weather : fieldNames.other) {
            traceData.push(dataSets[plotType][key]);
          }
        }
      } else {
        const plot = document.getElementById(`${plotType}Plot`);
        for (const trace of plot.data) {
          if (trace.visible === true) {
            traceData.push(trace.y);
          }
        }
      }
      const yMinMax = getDataExtremeValues(traceData);
      const padding = getYAxisPadding(yMinMax);

      const layout = {
        width: 1300,
        height: 700,
        title: `${plotTitleStart} observations`,
        xaxis: {
          title: 'Time',
          type: 'date',
          range: [xValues[0], xValues[xValues.length - 1]],
          dtick: getXAxisTickSize(diffInDays),
          tickformat: '%H',
          tickangle: -45
        },
        yaxis: {
          title: 'Value',
          range: [yMinMax[0] - padding, yMinMax[1] + padding]
        },
        legend: {
          orientation: 'h'
        },
        hoverlabel: {
          namelength: -1
        },
        hovermode: 'x unified'
      };

      // other and ruuvitag plot types share the same annotation indexes
      const annConfig = generateAnnotationConfig(plotType === 'weather' ? plotType : 'other', traceData);
      if (annConfig) {
        layout.shapes = annConfig;
      }

      return layout;
    };

    // Updates y-axis annotation and range values based on currently visible traces
    var updateAnnotationAndRangeYValues = (plot, traceData) => {
      const update = {};
      const dataExtremes = getDataExtremeValues(traceData);
      const padding = getYAxisPadding(dataExtremes);
      dataExtremes[0] -= padding;
      dataExtremes[1] += padding;

      update['yaxis.range'] = dataExtremes;
      for (let i = 0; i < plot.layout.shapes.length; i++) {
        update[`shapes[${i}].y0`] = dataExtremes[0];
        update[`shapes[${i}].y1`] = dataExtremes[1];
      }

      Plotly.relayout(plot, update);
    };
    /* eslint-enable no-var */

    // Event handler for trace click events
    const updatePlot = (event) => {
      const plotTitle = event.layout.title.text.toLowerCase();
      /* eslint-disable multiline-ternary */
      const plotType = plotTitle.includes('weather')
        ? 'weather' : plotTitle.includes('other')
          ? 'other' : 'ruuvitag';
      /* eslint-enable multiline-ternary */
      const plot = document.getElementById(`${plotType}Plot`);
      const eData = event.data;
      const traceIndex = event.curveNumber;
      const traceVis = eData[traceIndex].visible;
      const visTraceArray = [];
      let visTraceCount = 0;

      for (let i = 0; i < event.data.length; i++) {
        if (event.data[i].visible === true) {
          visTraceCount++;
          visTraceArray.push(i);
        }
      }

      // Update trace tooltips
      if (visTraceCount === 0) {
        visTraceArray.push(traceIndex);
      } else {
        if (traceVis === true) {
          // Currently visible
          visTraceArray.splice(visTraceArray.indexOf(traceIndex), 1);
        } else {
          // Currently hidden
          visTraceArray.push(traceIndex);
        }
      }

      // Update annotation y-axis values
      if (plot.layout.shapes) {
        if (visTraceArray.length) {
          const traceData = [];

          for (const i of visTraceArray) {
            traceData.push(eData[i].y);
          }

          updateAnnotationAndRangeYValues(plot, traceData);
        } else {
          resetAnnotationAndRangeYValues(plot);
        }
      }
    };

    Plotly.newPlot('weatherPlot',
      generateTraceConfig('weather'),
      generateLayoutConfig('weather'));

    document.getElementById('weatherPlot').on('plotly_click', (data) => {
      document.getElementById('showImages').checked = true;
      document.getElementById('imageDiv').classList.remove('display-none');

      showTestbedImage(data.points[0].x);
    });

    document.getElementById('weatherPlot').on('plotly_legendclick', updatePlot);

    if (mode === 'all') {
      Plotly.newPlot('otherPlot',
        generateTraceConfig('other'),
        generateLayoutConfig('other'));

      document.getElementById('otherPlot').on('plotly_click', (data) => {
        document.getElementById('showImages').checked = true;
        document.getElementById('imageDiv').classList.remove('display-none');

        showTestbedImage(data.points[0].x);
      });

      document.getElementById('otherPlot').on('plotly_legendclick', updatePlot);

      Plotly.newPlot('ruuvitagPlot',
        generateTraceConfig('ruuvitag'),
        generateLayoutConfig('ruuvitag'));

      document.getElementById('ruuvitagPlot').on('plotly_legendclick', updatePlot);
    }
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

    const diff = DateTime.fromISO(endDate).diff(
      DateTime.fromISO(startDate), ['days']);

    if (mode === 'all' || diff.days >= 7) {
      isSpinnerShown = true;
      toggleLoadingSpinner();
    }

    const plotUpdateAfterReset = (plotType) => {
      const plot = document.getElementById(`${plotType}Plot`);
      const traceVisibility = plot.data.map(trace => trace.visible);
      const visTraceData = [];

      for (const trace of plot.data) {
        if (trace.visible === true) {
          visTraceData.push(trace.y);
        }
      }

      Plotly.react(plot,
        generateTraceConfig(plotType),
        generateLayoutConfig(plotType, true));

      Plotly.restyle(plot, { visible: traceVisibility });
      if (visTraceData.length) {
        updateAnnotationAndRangeYValues(plot, visTraceData);
      } else {
        resetAnnotationAndRangeYValues(plot);
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

        data.weather = rData['weather-data'];
        data.obs = rData['obs-data'];
        data.rt = rData['rt-data'];

        document.getElementById('startDate').value = rData['obs-dates'].current.start;
        document.getElementById('endDate').value = rData['obs-dates'].current.end;

        transformData();

        plotUpdateAfterReset('weather');

        if (mode === 'all') {
          plotUpdateAfterReset('other');
          plotUpdateAfterReset('ruuvitag');
        }
      })
      .catch(error => {
        console.log(`Display data fetch error: ${error}`);
      })
      .then(() => {
        if (isSpinnerShown) {
          toggleLoadingSpinner();
        }
      });
  };

  const elecUpdateButtonClickHandler = (event) => {
    const startDate = document.getElementById('elecStartDate').value;
    const endDate = document.getElementById('elecEndDate').value;

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

    axios.get('data/elec-data',
      {
        params: {
          startDate,
          endDate
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
        }
      })
      .catch(error => {
        console.log(`Electricity data fetch error: ${error}`);
      });
  };

  // Set visibility (shown / hidden) for all traces
  const setAllTracesVisibility = (plotId, showTraces) => {
    const plot = document.getElementById(plotId);
    const update = { visible: [] };

    for (let i = 0; i < plot.data.length; i++) {
      update.visible.push(showTraces ? true : 'legendonly');
    }

    Plotly.restyle(plotId, update);
  };

  const resetAnnotationAndRangeYValues = (plot) => {
    const update = {};
    const dataExtremes = [-1, 4];

    update['yaxis.range'] = dataExtremes;
    for (let i = 0; i < plot.layout.shapes.length; i++) {
      update[`shapes[${i}].y0`] = dataExtremes[0];
      update[`shapes[${i}].y1`] = dataExtremes[1];
    }

    Plotly.relayout(plot, update);
  };

  document.getElementById('updateBtn').addEventListener('click',
    updateButtonClickHandler,
    false);

  if (mode === 'all') {
    document.getElementById('elecUpdateBtn').addEventListener('click',
      elecUpdateButtonClickHandler,
      false);
  }

  document.getElementById('showImages').addEventListener('click',
    () => {
      toggleVisibility('imageDiv');
    },
    false);

  document.getElementById('weatherHideAll')
    .addEventListener('click',
      () => {
        setAllTracesVisibility('weatherPlot', false);
        resetAnnotationAndRangeYValues(document.getElementById('weatherPlot'));
      },
      false);

  if (mode === 'all' && data.obs.length) {
    showElectricityPrice();

    document.getElementById('showLatestObs').addEventListener('click',
      () => {
        toggleVisibility('latestDiv');
      },
      false);

    document.getElementById('showWeatherChart').addEventListener('click',
      () => {
        toggleVisibility('weatherDiv');
      },
      false);

    document.getElementById('otherHideAll')
      .addEventListener('click',
        () => {
          setAllTracesVisibility('otherPlot', false);
          resetAnnotationAndRangeYValues(document.getElementById('otherPlot'));
        },
        false);

    document.getElementById('ruuvitagHideAll')
      .addEventListener('click',
        () => {
          setAllTracesVisibility('ruuvitagPlot', false);
          resetAnnotationAndRangeYValues(document.getElementById('ruuvitagPlot'));
        },
        false);

    document.getElementById('ruuvitagShowAll')
      .addEventListener('click',
        () => {
          setAllTracesVisibility('ruuvitagPlot', true);

          const plot = document.getElementById('ruuvitagPlot');
          const traceData = [];

          for (const trace of plot.data) {
            traceData.push(trace.y);
          }
          updateAnnotationAndRangeYValues(plot, traceData);
        },
        false);

    document.getElementById('plotAccordion')
      .addEventListener('shown.bs.collapse', () => {
        // Scroll page to bottom after loading the image for improved viewing
        scrollToBottom(0);
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

    mode = rData.mode;
    testbedImageBasepath = rData['tb-image-basepath'];
    data.weather = rData['weather-data'];
    data.obs = rData['obs-data'];
    if (mode === 'all') {
      rtNames = rData['rt-names'];
      data.rt = rData['rt-data'];
    }

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
  .catch(error => {
    console.log(`Initial display data fetch error: ${error}`);
  });
