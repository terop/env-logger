import { getDateTime } from '../globals.js';
import { chartState } from '../state.js';
import { addUnitSuffix, lowerFL } from '../data/labels.js';

export const showLastObservation = () => {
  let observationText = '';
  const weatherKeys = ['temperature', 'feels-like', 'cloudiness', 'wind-speed', 'humidity'];
  const DateTime = getDateTime();
  const { data, dataSets, dataLabels, labelValues, names } = chartState;

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

export const appendElecLatestPrices = (priceData, getClosestIndex) => {
  const DateTime = getDateTime();
  const now = DateTime.now();

  if (now > DateTime.fromISO(priceData[priceData.length - 1]['start-time'])) {
    console.log('No recent electricity price data to show');
    return;
  }

  const currentIdx = getClosestIndex(
    priceData.map((item) => new Date(item['start-time']))
  );

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

export const appendElecMonthSummary = (elecData) => {
  if (elecData['month-price-avg'] === null && elecData['month-consumption'] === null) {
    return;
  }

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
};
