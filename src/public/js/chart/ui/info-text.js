import { getDateTime } from '../globals.js';
import { chartState } from '../state.js';
import { addUnitSuffix, lowerFL } from '../data/labels.js';

const WEATHER_KEYS = [
  'temperature',
  'feels-like',
  'cloudiness',
  'wind-speed',
  'humidity'
];

const bold = (text) => `<span class="weight-bold">${text}</span>`;

const appendInfoText = (html) => {
  if (!html) {
    return;
  }
  document.getElementById('infoText').innerHTML += html;
};

const optionalLabeledValue = (label, value, unitKey, separator = ', ') => {
  if (value == null) {
    return `${label}:`;
  }
  return `${label}: ${value}${addUnitSuffix(unitKey)}${separator}`;
};

export const formatSunHtml = (ast) => {
  if (!ast) {
    return '';
  }
  return `${bold('Sun')}: sunrise ${ast.sunrise}, sunset ${ast.sunset}<br>`;
};

export const formatWeatherHtml = (wd, labelValues) => {
  if (!wd) {
    return '';
  }

  const DateTime = getDateTime();
  const parts = WEATHER_KEYS.map((key) => {
    if (key === 'wind-speed') {
      return `wind: ${wd['wind-direction-str'].long} ${wd[key]} ${addUnitSuffix(key)}`;
    }
    const unitKey = key === 'feels-like' ? 'temperature' : key;
    return `${lowerFL(labelValues.weather[key])}: ${wd[key]}${addUnitSuffix(unitKey)}`;
  });

  const when =
    `${DateTime.now().setLocale('fi').toLocaleString()} ` +
    DateTime.fromISO(wd.time).toLocaleString(DateTime.TIME_SIMPLE);
  return `${bold('Weather')} at ${when}: ${parts.join(', ')}<br>`;
};

export const formatObservationsHtml = ({
  dataSets,
  dataLabels,
  labelValues,
  names
}) => {
  const DateTime = getDateTime();
  const idx = dataSets.other['inside-light'].length - 1;
  const other = dataSets.other;
  const labels = labelValues.other;
  const time = DateTime.fromJSDate(dataLabels.other[idx])
    .toLocaleString(DateTime.TIME_SIMPLE);

  let html = `${bold('Observations')} at ${time}: `;
  html += `${lowerFL(labels['inside-light'])}: ${other['inside-light'][idx]}` +
    `${addUnitSuffix('inside-light')}, `;
  html += optionalLabeledValue(
    lowerFL(labels['inside-temperature']),
    other['inside-temperature'][idx],
    'temperature'
  );
  html += optionalLabeledValue(
    lowerFL(labels['co2']),
    other['co2'][idx],
    'co2'
  );
  html += optionalLabeledValue(
    labels['ruuvi-co2'],
    other['ruuvi-co2'][idx],
    'ruuvi-co2'
  );
  html += optionalLabeledValue(
    labels['pm-25'],
    other['pm-25'][idx],
    'pm-25',
    ','
  );
  html += `<br>${optionalLabeledValue(
    labels['iaqs'],
    other['iaqs'][idx],
    'iaqs'
  )}`;

  if (other['beacon-rssi'][idx] != null) {
    const battery = other['beacon-battery'][idx];
    const batteryText = battery
      ? `${battery} ${addUnitSuffix('beacon-battery')}`
      : 'NA';
    html += `beacon "${names.bleBeacon[idx]}": RSSI` +
      ` ${other['beacon-rssi'][idx]}${addUnitSuffix('beacon-rssi')}`;
    html += `; battery level ${batteryText}, `;
  }

  html += optionalLabeledValue(
    lowerFL(labels['outside-temperature']),
    other['outside-temperature'][idx],
    'temperature',
    ''
  );
  return html;
};

export const formatRuuvitagHtml = ({ dataSets, labelValues }) => {
  let html = '<br>RuuviTags: ';
  if (!dataSets.rt) {
    return html;
  }

  const firstTag = Object.keys(dataSets.rt)[0];
  const idx = dataSets.rt[firstTag].temperature.length - 1;
  let itemsAdded = 0;
  for (const tag in labelValues.rt) {
    if (itemsAdded > 0 && itemsAdded % 4 === 0) {
      html += '<br>';
    }
    html += `${labelValues.rt[tag].temperature}: ` +
      `${dataSets.rt[tag].temperature[idx]}` +
      `${addUnitSuffix('temperature')}, ` +
      `${labelValues.rt[tag].humidity}: ${dataSets.rt[tag].humidity[idx]}` +
      `${addUnitSuffix('humidity')}, `;
    itemsAdded += 2;
  }
  return html.slice(0, -2);
};

export const formatForecastHtml = (forecast) => {
  if (!forecast) {
    return '';
  }
  const DateTime = getDateTime();
  return (
    `<br>${bold('Forecast')} for ` +
    DateTime.fromISO(forecast.time).toFormat('dd.MM.yyyy HH:mm') +
    `: temperature: ${forecast.temperature} ${addUnitSuffix('temperature')}, ` +
    `feels like: ${forecast['feels-like']} ${addUnitSuffix('temperature')}, ` +
    `cloudiness: ${forecast.cloudiness} %, ` +
    `wind: ${forecast['wind-direction-str'].long} ${forecast['wind-speed']} ${addUnitSuffix('wind')}, ` +
    `precipitation: ${forecast.precipitation} ${addUnitSuffix('precipitation')}, ` +
    `humidity: ${forecast.humidity} ${addUnitSuffix('humidity')}`
  );
};

export const formatLastObservationHtml = () => {
  const { data, dataSets, dataLabels, labelValues, names } = chartState;
  if (!data.weather) {
    return null;
  }

  return (
    formatSunHtml(data.weather.ast) +
    formatWeatherHtml(data.weather.fmi.current, labelValues) +
    formatObservationsHtml({ dataSets, dataLabels, labelValues, names }) +
    formatRuuvitagHtml({ dataSets, labelValues }) +
    formatForecastHtml(data.weather.fmi.forecast)
  );
};

export const showLastObservation = () => {
  const observationText = formatLastObservationHtml();
  if (observationText == null) {
    console.error('Error: no weather data');
    return;
  }

  const infoText = document.getElementById('infoText');
  infoText.innerHTML = observationText;
  infoText.classList.remove('display-none');
};

export const formatElecLatestPricesHtml = (priceData, getClosestIndex) => {
  const DateTime = getDateTime();
  const now = DateTime.now();

  if (now > DateTime.fromISO(priceData[priceData.length - 1]['start-time'])) {
    return null;
  }

  const currentIdx = getClosestIndex(
    priceData.map((item) => new Date(item['start-time']))
  );

  let html = '';
  const currentHourData = priceData[currentIdx];
  if (currentHourData) {
    const currentPriceTime = DateTime.fromISO(currentHourData['start-time'])
      .toFormat('HH:mm');
    html += '<br><br>Electricity price: at ' +
      `${currentPriceTime}: ${currentHourData.price} c / kWh`;
  }

  const nextHourData = priceData[currentIdx + 1];
  if (nextHourData) {
    const nextPriceTime = DateTime.fromISO(nextHourData['start-time'])
      .toFormat('HH:mm');
    html += `, at ${nextPriceTime}: ${nextHourData.price} c / kWh`;
  }
  return html;
};

export const appendElecLatestPrices = (priceData, getClosestIndex) => {
  const html = formatElecLatestPricesHtml(priceData, getClosestIndex);
  if (html == null) {
    console.log('No recent electricity price data to show');
    return;
  }
  appendInfoText(html);
};

export const formatElecMonthSummaryHtml = (elecData) => {
  if (elecData['month-price-avg'] === null &&
      elecData['month-consumption'] === null) {
    return '';
  }

  const parts = [];
  if (elecData['month-consumption'] !== null) {
    parts.push(`consumption: ${elecData['month-consumption']} kWh`);
  }
  if (elecData['month-price-avg'] !== null) {
    parts.push(
      `average price: <span id="elecMonthAvg">${elecData['month-price-avg']}</span> c / kWh`
    );
  }
  if (elecData['month-cost'] !== null) {
    parts.push(`total cost: ${elecData['month-cost']} €`);
  }
  return `<br>Current month: ${parts.join(', ')}`;
};

export const appendElecMonthSummary = (elecData) => {
  appendInfoText(formatElecMonthSummaryHtml(elecData));
};
