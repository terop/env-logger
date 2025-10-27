/* global axios,luxon,Plotly */

const DateTime = luxon.DateTime;

// Data field names
const fieldNames = {
  weather: ['fmi-temperature', 'cloudiness', 'wind-speed'],
  other: ['inside-light', 'inside-temperature', 'co2', 'outside-temperature', 'beacon-rssi', 'beacon-battery']
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
let testbedImageBasepath = '';
let testbedImageNames = [];
let rtNames = [];

const loadPage = () => {
  // Parse RuuviTag observations
  // rtObservations - observations as JSON
  // rtLabels - RuuviTag labels
  const parseRTData = (rtObservations, rtLabels) => {
    const timeDiffThreshold = 10;
    let name = null;
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

      name = obs.name;

      const diff = DateTime.fromMillis(dateRef).diff(
        DateTime.fromMillis(obs.recorded), 'seconds');

      if (Math.abs(diff.toObject().seconds) > timeDiffThreshold) {
        dateRef = obs.recorded;
      }

      if (obsByDate[dateRef] === undefined) {
        obsByDate[dateRef] = {};
      }

      if (obsByDate[dateRef] !== undefined) {
        if (obsByDate[dateRef][name] === undefined) {
          obsByDate[dateRef][name] = {};
        }

        obsByDate[dateRef][name].temperature = obs.temperature;
        obsByDate[dateRef][name].humidity = obs.humidity;
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
      const lenDiff = dataSets.other['inside-light'].length - dataSets.rt[key].temperature.length;
      for (let j = 0; j < lenDiff; j++) {
        dataSets.rt[key].temperature.unshift(null);
        dataSets.rt[key].humidity.unshift(null);
      }
    }
  };

  /* dataMode - string, which mode to process data in, values: weather, other
   * observation - object, observation to process
   * selectKeys - array, which data keys to select */
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
    const recorded = DateTime.fromMillis(observationTime);
    if (recorded.hour === 0 && recorded.minute === 0) {
      annotationIndexes[dataMode].push(recorded.toJSDate());
    }
  };

  /* Parse an observation.
   *
   * Arguments:
   * observation - an observation as JSON
   */
  const parseData = (observation) => {
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
    parseRTData(data.rt, rtNames);

    let beaconName = null;
    for (const item of bleBeaconNames) {
      if (item !== null) {
        beaconName = item;
        break;
      }
    }

    labelValues.other = {
      'inside-light': 'Inside light',
      'inside-temperature': 'Inside temperature',
      'co2': 'Inside CO\u2082',
      'outside-temperature': 'Outside temperature',
      'beacon-rssi': beaconName
        ? `Beacon "${beaconName}" RSSI`
        : 'Beacon RSSI',
      'beacon-battery': beaconName
        ? `Beacon "${beaconName}" battery level`
        : 'Beacon battery level'
    };
    for (const name of rtNames) {
      labelValues.rt[name] = {
        temperature: `"${name}" temperature`,
        humidity: `"${name}" humidity`
      };
    }
    labelValues.weather = {
      'fmi-temperature': 'Temperature',
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

  if (data.obs.length === 0) {
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
        `${keyName.includes('co2') || keyName.includes('co\u2082') ? ' ppm' : ''}`;
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
      const weatherKeys = ['fmi-temperature', 'feels-like', 'cloudiness', 'wind-speed', 'humidity'];

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
            observationText += `wind: ${wd['wind-direction'].long} ` +
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
      observationText += `${lowerFL(labelValues.other['outside-temperature'])}:`;
      if (dataSets.other['outside-temperature'][obsIndex] !== null) {
        observationText += ` ${dataSets.other['outside-temperature'][obsIndex]}` +
          `${addUnitSuffix('temperature')}, `;
      }

      observationText += `beacon "${bleBeaconNames[obsIndex]}": RSSI`;
      if (dataSets.other['beacon-rssi'][obsIndex] !== null) {
        observationText += ` ${dataSets.other['beacon-rssi'][obsIndex]}${addUnitSuffix('beacon-rssi')}`;

        const battery = dataSets.other['beacon-battery'][obsIndex];
        const batteryText = battery ? `${battery} ${addUnitSuffix('beacon-battery')}` : 'NA';
        observationText += `, battery level ${batteryText}`;
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
          `wind: ${forecast['wind-direction'].long} ${forecast['wind-speed']} ${addUnitSuffix('wind')}, ` +
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

      if (updateDate) {
        document.getElementById('elecEndDate').value = xValues.length
          ? DateTime.fromJSDate(xValues[xValues.length - 1]).toISODate()
          : DateTime.now().toISODate();
      }

        document.getElementById('elecInfoBox').innerHTML = 'Current interval: consumption: ' +
          `${arraySum(data.consumption).toFixed(2)} kWh, average price: ` +
          `${arrayAverage(data.price).toFixed(2)} c / kWh, ` +
          'total cost: <span id="intervalCost"></span> €';

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
          text: data.consumption.map((value) => `${value} kWh`),
          textposition: 'none'
        }];
      };

      const extValuesConsp = getDataExtremeValues([data.consumption]);
      const extValuesPrice = getDataExtremeValues([data.price]);

      const generateElecLayoutConfig = (diffInDays) => {
        return {
          width: 1300,
          height: 650,
          title: {
            text: 'Hourly electricity price and consumption'
          },
          xaxis: {
            title: {
              text: 'Time'
            },
            type: 'date',
            dtick: getXAxisTickSize(diffInDays),
            tickformat: '%H',
            tickangle: -45
          },
          yaxis: {
            title: {
              text: 'Price (c / kWh)'
            },
            range: [extValuesPrice[0] - 0.5,
              extValuesPrice[extValuesPrice.length - 1] + 0.5]
          },
          yaxis2: {
            title: {
              text: 'Consumption (kWh)'
            },
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
          name: 'Average price',
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
          title: {
            text: 'Daily electricity price and consumption'
          },
          xaxis: {
            title: {
              text: 'Date'
            },
            type: 'date',
            dtick: 86400000,
            tickformat: '%d.%m.%Y'
          },
          yaxis: {
            title: {
              text: 'Average price (c / kWh)'
            }
          },
          yaxis2: {
            title: {
              text: 'Consumption (kWh)'
            },
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


    // Show the 15 minute electricity price data in a chart
    var plotElectricityPriceMinute = (priceData) => {

      const generateBarColourValues = (xValues) => {
        const defaultColour = '#1565a3';
        const currentHourColour = '#60a5fa';

        if (DateTime.now().day !== DateTime.fromJSDate(xValues[0]).day) {
          return Array(xValues.length).fill(defaultColour);
        } else {
          const currentHour = DateTime.now().hour;
          let colours = [];

          for (const item of xValues) {
            colours.push(DateTime.fromJSDate(item).hour === currentHour ?
                         currentHourColour : defaultColour);
          }

          return colours;
        }
      };

      const xValues = [];
      const data = {
        price: []
      };

      for (const item of priceData) {
        xValues.push(DateTime.fromISO(item['start-time']).toJSDate());
        data.price.push(item.price);
      }

      const generateElecTraceConfig = () => {
        return [{
          x: xValues,
          y: data.price,
          name: 'Price',
          type: 'bar',
          xhoverformat: '<b>%d.%m. %H:%M</b>',
          hovertemplate: '%{y}%{text}',
          text: Array(xValues.length).fill(' c / kWh'),
          textposition: 'none',
          marker: {
            color: generateBarColourValues(xValues)
          }
        }];
      };

      const extValuesPrice = getDataExtremeValues([data.price]);

      const generateElecLayoutConfig = (diffInDays) => {
        return {
          width: 1300,
          height: 650,
          title: {
            text: 'Electricity price (15 minute resolution)'
          },
          xaxis: {
            title: {
              text: 'Time'
            },
            type: 'date',
            dtick: getXAxisTickSize(diffInDays),
            tickformat: '%H'
          },
          yaxis: {
            title: {
              text: 'Price (c / kWh)'
            },
            range: [extValuesPrice[0] - 0.4,
                    extValuesPrice[extValuesPrice.length - 1] + 0.4]
          },
          legend: {
            orientation: 'h'
          },
          hovermode: 'x unified'
        };
      };

      const diffInDays = DateTime.fromJSDate(xValues[xValues.length - 1]).diff(
        DateTime.fromJSDate(xValues[0]), 'days').toObject().days;
      if (!document.getElementById('minuteElecDataPlot').data) {
        Plotly.newPlot('minuteElecDataPlot',
          generateElecTraceConfig(),
          generateElecLayoutConfig(diffInDays));
      } else {
        Plotly.react('minuteElecDataPlot',
          generateElecTraceConfig(),
          generateElecLayoutConfig(diffInDays));
      }
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
              // Regularly update the current hour annotation to match the current hour
              setInterval(() => {
                const plot = document.getElementById('hourElecDataPlot');
                const xData = plot.data[0].x;
                const currentIdx = getClosestElecPriceDataIndex(xData);
                let update = {};
                let shapes = plot.layout.shapes;
                let hourAnnotation = shapes.splice(-1, 1)[0];

                hourAnnotation.x0 = xData[currentIdx];
                hourAnnotation.x1 = xData[currentIdx];
                shapes.push(hourAnnotation);

                update.shapes = shapes;
                Plotly.relayout(plot, update);
              }, 60000);
            }
          }
        }).catch(error => {
          console.log(`Electricity data fetch error: ${error}`);
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

          plotElectricityPriceMinute(elecData.prices);
          dateField.min = elecData['date-min'];
        })
        .catch(error => {
          console.log(`Electricity price fetch error: ${error}`);
        });
    };


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


    const generateAnnotationConfig = (plotType, traceData) => {
      if (annotationIndexes[plotType].length) {
        const shapes = [];
        let yValues = [];
        let oneDay = false;
        let index = null;
        let shape = {};

        if (traceData.length) {
          yValues = getDataExtremeValues(traceData);
          const padding = getYAxisPadding(yValues);
          yValues[0] -= padding;
          yValues[1] += padding;
        } else {
          yValues = [-1, 4];
        }

        const labels = dataLabels[plotType];
        if (labels[0].getDate() === labels[labels.length - 1].getDate()) {
          oneDay = true;
        }

        for (let i = 0; i < annotationIndexes[plotType].length; i++) {
          index = annotationIndexes[plotType][i];
          shape = {
              type: 'line',
              x0: index,
              y0: yValues[0],
              x1: index,
              y1: yValues[1],
              line: {
                color: '#838b93',
                width: 1
              }
          };

          if (oneDay) {
            shape['visible'] = false;
            shapes.push(shape);
          } else if (i > 0) {
            // Do not show the needless annotation at the beginning of the chart
            shapes.push(shape);
          }
        }

        return shapes;
      }
      return null;
    };


    var generateLayoutConfig = (plotType, isUpdate = false) => {
      const xValues = plotType === 'weather' ? dataLabels.weather : dataLabels.other;
      const diffInDays = DateTime.fromJSDate(xValues[xValues.length - 1]).diff(
        DateTime.fromJSDate(xValues[0]), 'days').toObject().days;

      const plotTitleStart = (plotType === 'weather')
        ? 'FMI weather' : plotType === 'other'
          ? 'Other' : 'Ruuvitag';


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
        title: {
          text: `${plotTitleStart} observations`
        },
        xaxis: {
          title: {
            text: 'Time'
          },
          type: 'date',
          range: [xValues[0], xValues[xValues.length - 1]],
          dtick: getXAxisTickSize(diffInDays),
          tickformat: '%H',
          tickangle: -45
        },
        yaxis: {
          title: {
            text: 'Value'
          },
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

    var resetAnnotationAndRangeYValues = (plot) => {
      const update = {};
      const dataExtremes = [-1, 4];

      update['yaxis.range'] = dataExtremes;
      if (plot.layout.shapes) {
        for (let i = 0; i < plot.layout.shapes.length; i++) {
          update[`shapes[${i}].y0`] = dataExtremes[0];
          update[`shapes[${i}].y1`] = dataExtremes[1];
        }
      }

      Plotly.relayout(plot, update);
    };

    // Updates y-axis annotation and range values based on currently visible traces
    var updateAnnotationAndRangeYValues = (plot, traceData) => {
      const update = {};
      const dataExtremes = getDataExtremeValues(traceData);
      const padding = getYAxisPadding(dataExtremes);
      dataExtremes[0] -= padding;
      dataExtremes[1] += padding;

      update['yaxis.range'] = dataExtremes;
      if (plot.layout.shapes) {
        for (let i = 0; i < plot.layout.shapes.length; i++) {
          update[`shapes[${i}].y0`] = dataExtremes[0];
          update[`shapes[${i}].y1`] = dataExtremes[1];
        }
      }

      Plotly.relayout(plot, update);
    };


    // Event handler for trace click events
    const updatePlot = (event) => {
      const plotTitle = event.layout.title.text.toLowerCase();

      const plotType = plotTitle.includes('weather')
        ? 'weather' : plotTitle.includes('other')
          ? 'other' : 'ruuvitag';

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
    resetAnnotationAndRangeYValues(document.getElementById('ruuvitagPlot'));

    document.getElementById('ruuvitagPlot').on('plotly_legendclick', updatePlot);
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

    if (diff.days >= 7) {
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

        plotUpdateAfterReset('other');
        plotUpdateAfterReset('ruuvitag');
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
        console.log(`Electricity data fetch error: ${error}`);
      });
  };


  const elecMinuteDateUpdateBtnClickHandler = (event) => {
    const minuteDate = document.getElementById('elecMinuteDate').value;

    if ((minuteDate && DateTime.fromISO(minuteDate).invalid)) {
      alert('Error: electricity price date is invalid');
      event.preventDefault();
      return;
    }

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
      })
      .catch(error => {
        console.log(`Electricity price fetch error: ${error}`);
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

  // Show all RuuviTag series of type: temperature or humidity
  const showRuuvitagSeriesType = (type) => {
    setAllTracesVisibility('ruuvitagPlot', false);

    const plot = document.getElementById('ruuvitagPlot');
    const traceData = [];
    const traceVisibility = [];

    for (const trace of plot.data) {
      if (trace.name.includes(type)) {
        traceData.push(trace.y);
        traceVisibility.push(true);
      } else {
        traceVisibility.push('legendonly');
      }
    }

    Plotly.restyle(plot, { visible: traceVisibility });
    updateAnnotationAndRangeYValues(plot, traceData);
  };

  // Hide all series for a plot
  const plotHideAll = (plot) => {
    setAllTracesVisibility(plot, false);
    resetAnnotationAndRangeYValues(document.getElementById(plot));
  };

  // Show all series for a plot
  const plotShowAll = (plot) => {
    setAllTracesVisibility(plot, true);

    const plotElem = document.getElementById(plot);
    const traceData = [];

    for (const trace of plotElem.data) {
      traceData.push(trace.y);
    }
    updateAnnotationAndRangeYValues(plotElem, traceData);
  };

  const updateMinuteElecPrice = (direction) => {
    let dateField = document.getElementById('elecMinuteDate');

    const fetchPrice = (newDate) => {
      axios.get('data/elec-price-minute',
                {
                  params: {
                    date: newDate.toISODate(),
                    addFees: document.getElementById('elecPriceShowFees').checked
                  }
                })
        .then(resp => {
          const elecData = resp.data;

          if (elecData.error) {
            console.log(`Electricity data fetch error: ${elecData.error}`);
            return;
          }

          dateField.value = newDate.toISODate();
          plotElectricityPriceMinute(elecData.prices);
        })
        .catch(error => {
          console.log(`Electricity price fetch error: ${error}`);
        });
    };

    if (direction === 'forward') {
      const newDate = DateTime.fromISO(dateField.value).plus({days: 1});

      if (DateTime.fromISO(dateField.max) >= newDate) {
        fetchPrice(newDate);
      } else {
        alert('You are already at the newest date');
      }
    } else {
      const newDate = DateTime.fromISO(dateField.value).minus({days: 1});

      if (DateTime.fromISO(dateField.min) <= newDate) {
        fetchPrice(newDate);
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
      plotHideAll('weatherPlot');
    },
    false);

  if (data.obs.length) {
    showElectricityData();

    document.getElementById('showInfoText').addEventListener('click',
      () => {
        toggleVisibility('infoText');
      },
      false);

    document.getElementById('otherHideAll')
      .addEventListener('click',
                        () => {
                          plotHideAll('otherPlot');
        },
        false);

    document.getElementById('otherShowAll')
      .addEventListener('click',
                        () => {
                          plotShowAll('otherPlot');
        },
        false);

    document.getElementById('ruuvitagHideAll')
      .addEventListener('click',
                        () => {
                          plotHideAll('ruuvitagPlot');
        },
        false);

    document.getElementById('ruuvitagShowAll')
      .addEventListener('click',
                        () => {
                          plotShowAll('ruuvitagPlot');
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
    data.obs = rData['obs-data'];
    rtNames = rData['rt-names'];
    data.rt = rData['rt-data'];

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
