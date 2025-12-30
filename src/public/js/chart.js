/* global applicationUrl,axios,luxon,Plotly,refreshTokensIfNeeded */

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

const redirectToLogin = () => {
  window.location.href = `${applicationUrl}login`;
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
    let currentTag = null;
    let currentTs = null;
    let nextTs = null;

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

      // Pad the array if there is missing values in the beginning
      dataSets.other[value] = padArrayFromStart(dataSets.other[value],
                                                dataLabels.other.length, null);
    });
  };

  // Transform data to Plotly compatible format. Returns the data series labels.
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

  if (!data.other) {
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
          type: 'bar',
          xhoverformat: '<b>%d.%m. %H:%M</b>',
          hovertemplate: '%{y}%{text}',
          text: Array(xValues.length).fill(' c / kWh'),
          textposition: 'none',
          marker: {
            color: generateElecHourBarChartColours(xValues, data.price)
          }
        },
        {
          x: xValues,
          y: data.consumption,
          name: 'Consumption',
          type: 'lines',
          line: {
            color: 'rgb(0, 0, 0)',
            width: 2,
          },
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
          mode: 'lines',
          line: {
            width: 2
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
            },
            overlaying: 'y2',
          },
          yaxis2: {
            title: {
              text: 'Consumption (kWh)'
            },
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

    // Highlight the current hour's values in the hourly electricity
    // price data chart
    const generateElecHourBarChartColours = (xValues, prices) => {
      const now = DateTime.now();
      let colours = [];
      let currentDt = null;

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
            color: generateElecMinuteChartBarColours(xValues, data.price)
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
              // Regularly update the current hour annotation to match the current hour
              setInterval(() => {
                const plot = document.getElementById('hourElecDataPlot');
                const update = {'marker.color': [generateElecHourBarChartColours(plot.data[0].x,
                                                                                 plot.data[0].y)]};
                Plotly.restyle(plot, update, [0]);
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
            const plot = document.getElementById('minuteElecDataPlot');
            const update = {'marker.color': [generateElecMinuteChartBarColours(plot.data[0].x,
                                                                               plot.data[0].y)]};
            Plotly.restyle(plot, update, [0]);
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

        for (const name of names.ruuvitag) {
          for (const meas of rtMeasurables) {
            changingOpts = {
              x: dataLabels.rt,
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
      if (annotationIndices[plotType].length) {
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

        for (let i = 0; i < annotationIndices[plotType].length; i++) {
          index = annotationIndices[plotType][i];
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
        data.weatherObs = rData['weather-obs-data'];
        data.other = rData['obs-data'];
        data.rt = rData['rt-data'];

        document.getElementById('startDate').value = rData['obs-dates'].current.start;
        document.getElementById('endDate').value = rData['obs-dates'].current.end;

        transformData();

        plotUpdateAfterReset('weather');
        plotUpdateAfterReset('other');
        plotUpdateAfterReset('ruuvitag');
      })
      .catch(error => {
        if (error.status === 401) {
          redirectToLogin();
        } else {
          console.log(`Display data fetch error: ${error}`);
        }
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
        if (error.status === 401) {
          redirectToLogin();
        } else {
          console.log(`Electricity data fetch error: ${error}`);
        }
      });
  };


  const elecMinuteDateUpdateBtnClickHandler = (event) => {
    const minuteDate = document.getElementById('elecMinuteDate').value;

    if ((minuteDate && DateTime.fromISO(minuteDate).invalid)) {
      alert('Error: electricity price date is invalid');
      event.preventDefault();
      return;
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

  if (data.other) {
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
    data.weatherObs = rData['weather-obs-data'];
    data.other = rData['obs-data'];
    data.rt = rData['rt-data'];
    names.ruuvitag = rData['rt-names'];

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
    if (error.status === 401) {
      redirectToLogin();
    } else {
      console.log(`Initial display data fetch error: ${error}`);
    }
  });

setInterval(() => {
  refreshTokensIfNeeded();
}, 30000);
