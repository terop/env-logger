// Chart colors
const colors = [
    '#7864cb', '#5ab642', '#bf51b6', '#cf615e', '#dc437e',
    '#49b9a9', '#cc572c', '#628bcb', '#a5b043', '#d089c5',
    '#4c7e3d', '#a0496c', '#d79d48', '#6abe77', '#8c6f33'],
    // Data field names
    fieldNames = {
        'weather': ['fmi-temperature', 'cloudiness', 'wind-speed'],
        'other': ['brightness', 'rssi', 'o-temperature']
    };

var labelValues = {
    'weather': {},
    'other': {},
    'rt': {}
},
    dataSets = {
        'weather': {},
        'other': {},
        'rt': {}
    },
    data = {
        'obs': [],
        'rt': [],
        'weather': []
    },
    dataLabels = {
        'weather': [],
        'other': []
    },
    annotationIndexes = {
        'weather': [],
        'other': []
    },
    beaconName = '',
    mode = null,
    testbedImageBasepath = '',
    testbedImageNames = [],
    rtDefaultShow = null,
    rtDefaultValues = [],
    rtNames = [],
    charts = {
        'weather': null,
        'other': null,
        'elecDataHour': null,
        'elecDataDay': null
    };

var loadPage = () => {
    // Persist state of chart data sets
    var persistDatasetState = () => {
        var hidden = {
            'weather': {},
            'other': {}
        };

        if (!charts['weather'])
            return;

        for (var i = 0; i < charts['weather'].data.datasets.length; i++)
            hidden['weather'][i.toString()] = !!charts['weather'].getDatasetMeta(i).hidden;

        if (mode === 'all')
            // State persistence and restore are broken for RuuviTag series
            for (var j = 0; j < 2; j++)
                hidden['other'][j.toString()] = !!charts['other'].getDatasetMeta(j).hidden;

        localStorage.setItem('hiddenDatasets', JSON.stringify(hidden));
    };

    // Restore chart data sets state
    var restoreDatasetState = () => {
        if (!localStorage.getItem('hiddenDatasets'))
            return;

        const hidden = JSON.parse(localStorage.getItem('hiddenDatasets'));

        if (!charts['weather'])
            // Do not attempt restore if there is no data
            return;

        for (var i = 0; i < charts['weather'].data.datasets.length; i++)
            charts['weather'].getDatasetMeta(i).hidden = hidden['weather'][i.toString()] ? true : null;
        charts['weather'].update();

        if (mode === 'all') {
            for (var j = 0; j < 2; j++)
                charts['other'].getDatasetMeta(j).hidden = hidden['other'][j.toString()] ? true : null;
            charts['other'].update();
        }

        localStorage.removeItem('hiddenDatasets');
    };

    // Parse RuuviTag observations
    // rtObservations - observations as JSON
    // rtLabels - RuuviTag labels
    var parseRTData = (rtObservations, rtLabels) => {
        const timeDiffThreshold = 10;
        var location = null,
            dateRef = null,
            obsByDate = {};

        for (const label of rtLabels)
            dataSets['rt'][label] = {
                'temperature': [],
                'humidity': []
            };

        for (var i = 0; i < rtObservations.length; i++) {
            const obs = rtObservations[i];

            if (!dateRef)
                dateRef = obs['recorded'];

            location = obs['location'];

            const diff = luxon.DateTime.fromMillis(dateRef).diff(
                luxon.DateTime.fromMillis(obs['recorded']), 'seconds');

            if (Math.abs(diff.toObject()['seconds']) > timeDiffThreshold) {
                dateRef = obs['recorded'];
            }

            if (obsByDate[dateRef] === undefined)
                obsByDate[dateRef] = {};

            if (obsByDate[dateRef] !== undefined) {
                if (obsByDate[dateRef][location] === undefined)
                    obsByDate[dateRef][location] = {};

                obsByDate[dateRef][location]['temperature'] = obs['temperature'];
                obsByDate[dateRef][location]['humidity'] = obs['humidity'];
            }
        }

        for (const key of Object.keys(obsByDate)) {
            for (const label of rtLabels) {
                if (obsByDate[key][label] !== undefined) {
                    dataSets['rt'][label]['temperature'].push(obsByDate[key][label]['temperature']);
                    dataSets['rt'][label]['humidity'].push(obsByDate[key][label]['humidity']);
                } else {
                    dataSets['rt'][label]['temperature'].push(null);
                    dataSets['rt'][label]['humidity'].push(null);
                }
            }
        }

        // "null pad" RuuviTag observations to align latest observations with non-RuuviTag observations
        for (const key of Object.keys(dataSets['rt'])) {
            let lenDiff = dataSets['other']['brightness'].length - dataSets['rt'][key]['temperature'].length;
            for (var j = 0; j < lenDiff; j++) {
                dataSets['rt'][key]['temperature'].unshift(null);
                dataSets['rt'][key]['humidity'].unshift(null);
            }
        }
    };

    /* Parse an observation.
     *
     * Arguments:
     * observation - an observation as JSON
     */
    var parseData = (observation) => {
        // dataMode - string, which mode to process data in, values: weather, other
        // observation - object, observation to process
        // selectKeys - array, which data keys to select
        // skipWeatherKeyNulls - whether to skip null values for weather keys
        var processFields = (dataMode, observation, selectKeys, skipWeatherKeyNulls) => {
            for (key in observation) {
                if (selectKeys.includes(key)) {
                    if (dataMode === 'weather' && skipWeatherKeyNulls && observation[key] === null)
                        continue;

                    if (dataSets[dataMode][key] !== undefined)
                        dataSets[dataMode][key].push(observation[key]);
                    else
                        dataSets[dataMode][key] = [observation[key]];
                }
            }
        };

        var recordAnnotationIndexes = (dataMode, observationTime) => {
            // Skip the first few observations as a line annotation is not needed
            // in the beginning of a chart
            if (dataLabels[dataMode].length > 2) {
                var recorded = luxon.DateTime.fromMillis(observationTime);
                if (recorded.hour === 0 && recorded.minute == 0) {
                    annotationIndexes[dataMode].push(dataLabels[dataMode].length - 1);
                }
            }
        };

        if (mode === 'all') {
            dataLabels['other'].push(new Date(observation['recorded']));

            recordAnnotationIndexes('other', observation['recorded']);

            if (observation['weather-recorded']) {
                dataLabels['weather'].push(new Date(observation['weather-recorded']));

                recordAnnotationIndexes('weather', observation['weather-recorded']);
            }

            // Weather
            processFields('weather', observation, fieldNames['weather'], true);

            // Other
            processFields('other', observation, fieldNames['other'], false);

            if (beaconName === '' && observation['name']) {
                beaconName = observation['name'];
            }
        } else {
            dataLabels['weather'].push(new Date(observation['time']));

            recordAnnotationIndexes('weather', observation['time']);

            processFields('weather', observation, fieldNames['weather'], false);
        }
        testbedImageNames.push(observation['tb-image-name']);
    };

    // Transform data to Chart.js compatible format. Returns the data series labels.
    var transformData = () => {
        dataSets = {
            'weather': {},
            'other': {},
            'rt': {}
        };
        dataLabels = {
            'weather': [],
            'other': []
        };
        annotationIndexes = {
            'weather': [],
            'other': []
        };

        data['obs'].map((element) => {
            parseData(element);
        });

        // Data labels
        if (mode === 'all') {
            parseRTData(data['rt'], rtNames);

            labelValues['other'] = {
                'brightness': 'Brightness',
                'o-temperature': 'Outside temperature',
                'rssi': beaconName !== '' ? `Beacon "${beaconName}" RSSI` : 'Beacon RSSI'
            };
            for (const name of rtNames)
                labelValues['rt'][name] = {
                    'temperature': `RT "${name}" temperature`,
                    'humidity': `RT "${name}" humidity`
                };
        }
        labelValues['weather'] = {
            'fmi-temperature': 'Temperature (FMI)',
            'cloudiness': 'Cloudiness',
            'wind-speed': 'Wind speed'
        };

        return labelValues;
    };

    var hideElement = (elementId) => {
        document.getElementById(elementId).style.display = 'none';
    };

    if (data['obs'].length === 0) {
        document.getElementById('noDataError').style.display = 'block';
        hideElement('weatherDiv');
        hideElement('imageButtonDiv');
        if (mode === 'all') {
            hideElement('latestCheckboxDiv');
            hideElement('weatherCheckboxDiv');
            hideElement('otherDiv');
            hideElement('otherCheckboxDiv');
            hideElement('elecDataCheckboxDiv');
        }
    } else {
        labelValues = transformData();

        // Add unit suffix
        var addUnitSuffix = (keyName) => {
            return `${keyName.indexOf('temperature') >= 0 ? ' \u2103' : ''}` +
                `${keyName.indexOf('wind') >= 0 ? ' m/s' : ''}` +
                `${keyName.indexOf('humidity') >= 0 ? ' %H' : ''}` +
                `${keyName.indexOf('rssi') >= 0 ? ' dB' : ''}`;
        };

        // Format a given Unix second timestamp in hour:minute format
        var formatUnixSecondTs = (unixTs) => {
            return luxon.DateTime.fromSeconds(unixTs).toFormat('HH:mm');
        };

        // Show last observation and some other data for quick viewing
        var showLastObservation = () => {
            var observationText = '',
                weatherKeys = ['fmi-temperature', 'cloudiness', 'wind-speed'];

            if (!data['weather']) {
                console.log('Error: no weather data');
                return;
            }

            const wd = (mode === 'all' ? data['weather']['fmi']['current'] : data['weather']);
            if (wd) {
                observationText += `${luxon.DateTime.now().setLocale('fi').toLocaleString()}` +
                    ` ${luxon.DateTime.fromISO(wd['time']).toLocaleString(luxon.DateTime.TIME_SIMPLE)}: `;
                for (const key of weatherKeys) {
                    if (key === 'wind-speed')
                        observationText += `Wind: ${wd['wind-direction']['long']} ` +
                            `${wd[key]} ${addUnitSuffix(key)}, `;
                    else if (key === 'fmi-temperature')
                        observationText += `${labelValues['weather'][key]}: ` +
                            `${wd['temperature']} ${addUnitSuffix(key)}, `;
                    else
                        observationText += `${labelValues['weather'][key]}: ${wd[key]}` +
                            `${addUnitSuffix(key)}, `;
                }
            }
            if (mode === 'all') {
                if (data['weather']['owm'])
                    observationText += `Description: ${data['weather']['owm']['current']['weather'][0]['description']}<br>` +
                        `Sun: Sunrise ${formatUnixSecondTs(data['weather']['owm']['current']['sunrise'])},` +
                        ` Sunset ${formatUnixSecondTs(data['weather']['owm']['current']['sunset'])}<br>`;

                let obsIndex = dataSets['other']['brightness'].length - 1;

                observationText += `${labelValues['other']['brightness']}: ${dataSets['other']['brightness'][obsIndex]}` +
                    `${addUnitSuffix('brightness')}, ` +
                    `${labelValues['other']['rssi']}: ${dataSets['other']['rssi'][obsIndex]}${addUnitSuffix('rssi')}, ` +
                    `${labelValues['other']['o-temperature']}:`;
                if (dataSets['other']['o-temperature'][obsIndex] !== null) {
                    observationText += ` ${dataSets['other']['o-temperature'][obsIndex]}` +
                        `${addUnitSuffix('temperature')}`;
                }
                observationText += ',';

                let itemsAdded = 0;
                for (const key in dataSets['rt']) {
                    obsIndex = dataSets['rt'][key]['temperature'].length - 1;
                    break;
                }
                for (const tag in labelValues['rt']) {
                    if ((itemsAdded % 4) === 0)
                        observationText += '<br>';

                    observationText += `${labelValues['rt'][tag]['temperature']}: ${dataSets['rt'][tag]['temperature'][obsIndex]}` +
                        `${addUnitSuffix('temperature')}, ` +
                        `${labelValues['rt'][tag]['humidity']}: ${dataSets['rt'][tag]['humidity'][obsIndex]}` +
                        `${addUnitSuffix('humidity')}, `;
                    itemsAdded += 2;
                }
                observationText = observationText.slice(0, -2);

                const forecast = data['weather']['fmi']['forecast'];
                if (forecast && data['weather']['owm']['forecast'])
                    document.getElementById('forecast').innerHTML =
                        '<br><br>Forecast for ' +
                        luxon.DateTime.fromISO(forecast['time']).toFormat('dd.MM.yyyy HH:mm') +
                        `: temperature: ${forecast['temperature']} \u2103, ` +
                        `cloudiness: ${forecast['cloudiness']} %, ` +
                        `wind: ${forecast['wind-direction']['long']} ${forecast['wind-speed']} m/s, ` +
                        `precipitation: ${forecast['precipitation']} mm, ` +
                        `description: ${data['weather']['owm']['forecast']['weather'][0]['description']}`;
            } else {
                observationText = observationText.slice(0, -2);
            }

            document.getElementById('lastObservation').innerHTML = observationText;
            document.getElementById('latestDiv').classList.remove('display-none');
        };
        showLastObservation();

        // Show the hourly electricity price and consumption data in a chart
        var plotElectricityDataHour = (elecData, updateDate = false,
            removeLast = false) => {
            let generateElecAnnotationConfig = (plotData) => {
                const currentIdx = getClosestElecPriceDataIndex(plotData);
                var annotations = {},
                    currentLineIndex = 1;

                // Skip first and last data points as lines are not needed there
                for (var i = 1; i < plotData.length - 1; i++) {
                    if (luxon.DateTime.fromISO(plotData[i]['start-time']).hour === 0) {
                        annotations[`line${currentLineIndex}`] = {
                            type: 'line',
                            xMin: labels[i],
                            xMax: labels[i],
                            borderColor: '#838b93',
                            borderWidth: 1
                        };
                        currentLineIndex++;
                    }
                }

                if (luxon.DateTime.fromISO(document.getElementById('elecEndDate').value) >=
                    luxon.DateTime.fromISO(luxon.DateTime.now().toISODate())) {
                    annotations[`line${currentLineIndex}`] = {
                        type: 'line',
                        xMin: labels[currentIdx],
                        xMax: labels[currentIdx],
                        borderColor: 'rgb(0, 0, 0)',
                        borderWidth: 2
                    };
                }

                return { annotations: annotations };
            };

            let labels = [],
                data = {
                    'price': [],
                    'consumption': []
                };

            for (var i = 0; i < elecData.length - (removeLast ? 1 : 0); i++) {
                let item = elecData[i];
                labels.push(luxon.DateTime.fromISO(item['start-time']).toJSDate());
                data['price'].push(item['price']);
                data['consumption'].push(item['consumption']);
            }

            if (updateDate)
                document.getElementById('elecEndDate').value = labels.length ?
                    luxon.DateTime.fromJSDate(labels[labels.length - 1]).toISODate() :
                    luxon.DateTime.now().toISODate();

            if (!charts['elecDataHour']) {
                charts['elecDataHour'] = new Chart(document.getElementById('hourElecDataChart').getContext('2d'), {
                    data: {
                        labels: labels,
                        datasets: [
                            {
                                type: 'line',
                                label: 'Price',
                                yAxisID: 'yPrice',
                                borderColor: colors[6],
                                data: data['price'],
                                fill: false,
                                pointRadius: 1,
                                borderWidth: 1
                            },
                            {
                                type: 'bar',
                                label: 'Consumption',
                                yAxisID: 'yConsumption',
                                backgroundColor: colors[7],
                                data: data['consumption']
                            }
                        ]
                    },
                    options: {
                        scales: {
                            x: {
                                type: 'time',
                                time: {
                                    unit: 'hour',
                                    tooltipFormat: 'd.L.yyyy HH:mm',
                                    displayFormats: {
                                        hour: 'HH',
                                        minute: 'HH:mm'
                                    }
                                },
                                title: {
                                    display: true,
                                    text: 'Time'
                                }
                            },
                            yPrice: {
                                title: {
                                    display: true,
                                    text: 'Price (c / kWh)'
                                },
                                ticks: {
                                    beginAtZero: true
                                }
                            },
                            yConsumption: {
                                position: 'right',
                                title: {
                                    display: true,
                                    text: 'Consumption (kWh)'
                                },
                                ticks: {
                                    beginAtZero: true
                                }
                            }
                        },
                        interaction: {
                            mode: 'index'
                        },
                        plugins: {
                            title: {
                                display: true,
                                text: 'Hourly electricity price and consumption'
                            },
                            zoom: {
                                zoom: {
                                    drag: {
                                        enabled: true
                                    },
                                    mode: 'x'
                                }
                            },
                            annotation: generateElecAnnotationConfig(elecData)
                        },
                        spanGaps: true,
                        normalized: true,
                        tension: 0.1,
                        animation: false
                    }
                });

                document.getElementById('elecDataResetZoom').addEventListener(
                    'click',
                    () => {
                        charts['elecDataHour'].resetZoom();
                    },
                    false);

                document.getElementById('showElecDataCharts').addEventListener(
                    'click',
                    () => {
                        toggleVisibility('elecDataDiv');
                        // Scroll page to bottom after loading the image for improved viewing
                        window.setTimeout(() => {
                            window.scroll(0, document.body.scrollHeight);
                        }, 100);
                    },
                    false);

                document.getElementById('showElecDayData').addEventListener(
                    'click',
                    (event) => {
                        if (event.currentTarget.checked) {
                            document.getElementById('dayElecDataChart').style.display = 'block';
                            document.getElementById('elecDataDiv').style.height = '1300px';
                        } else {
                            hideElement('dayElecDataChart');
                            document.getElementById('elecDataDiv').style.height = '730px';
                        }
                        // Scroll page to bottom after loading the image for improved viewing
                        window.setTimeout(() => {
                            window.scroll(0, document.body.scrollHeight);
                        }, 100);
                    },
                    false);
            } else {
                charts['elecDataHour'].data.labels = labels;
                charts['elecDataHour'].data.datasets[0].data = data['price'];
                charts['elecDataHour'].data.datasets[1].data = data['consumption'];

                charts['elecDataHour'].options.plugins.annotation = generateElecAnnotationConfig(elecData);
                charts['elecDataHour'].update();
            }
        };

        // Show the daily electricity price and consumption data in a chart
        var plotElectricityDataDay = (elecData, removeLast = false) => {
            let labels = [],
                data = {
                    'price': [],
                    'consumption': []
                };

            for (var i = 0; i < elecData.length - (removeLast ? 1 : 0); i++) {
                let item = elecData[i];
                labels.push(item['date']);
                data['price'].push(item['price']);
                data['consumption'].push(item['consumption']);
            }

            if (!charts['elecDataDay']) {
                charts['elecDataDay'] = new Chart(document.getElementById('dayElecDataChart').getContext('2d'), {
                    data: {
                        labels: labels,
                        datasets: [
                            {
                                type: 'line',
                                label: 'Price',
                                yAxisID: 'yPrice',
                                borderColor: colors[6],
                                data: data['price'],
                                fill: false,
                                pointRadius: 1,
                                borderWidth: 1
                            },
                            {
                                type: 'bar',
                                label: 'Consumption',
                                yAxisID: 'yConsumption',
                                backgroundColor: colors[7],
                                data: data['consumption']
                            }
                        ]
                    },
                    options: {
                        scales: {
                            x: {
                                type: 'time',
                                time: {
                                    unit: 'day',
                                    tooltipFormat: 'd.L.yyyy',
                                    displayFormats: {
                                        day: 'd.L.yyyy'
                                    }
                                },
                                title: {
                                    display: true,
                                    text: 'Time'
                                }
                            },
                            yPrice: {
                                title: {
                                    display: true,
                                    text: 'Price (c / kWh)'
                                },
                                ticks: {
                                    beginAtZero: true
                                }
                            },
                            yConsumption: {
                                position: 'right',
                                title: {
                                    display: true,
                                    text: 'Consumption (kWh)'
                                },
                                ticks: {
                                    beginAtZero: true
                                }
                            }
                        },
                        interaction: {
                            mode: 'index'
                        },
                        plugins: {
                            title: {
                                display: true,
                                text: 'Daily electricity price and consumption'
                            }
                        },
                        spanGaps: true,
                        normalized: true,
                        tension: 0.1,
                        animation: false
                    }
                });
            } else {
                charts['elecDataDay'].data.labels = labels;
                charts['elecDataDay'].data.datasets[0].data = data['price'];
                charts['elecDataDay'].data.datasets[1].data = data['consumption'];

                charts['elecDataDay'].update();
            }
        };

        // Determine the index of electricity price data value which is closest to the current hour
        var getClosestElecPriceDataIndex = (priceData) => {
            const now = luxon.DateTime.now();

            var smallest = 1000000000,
                smallestIdx = -1;

            for (i = 0; i < priceData.length; i++) {
                var diff = Math.abs(luxon.DateTime.fromISO(priceData[i]['start-time']).diff(now).milliseconds);
                if (diff < smallest) {
                    smallest = diff;
                    smallestIdx = i;
                }
            }

            // Special case handling for the situation when the next hour is closer than the current
            if (now.hour < luxon.DateTime.fromISO(priceData[smallestIdx]['start-time']).hour) {
                smallestIdx -= 1;
            }

            return smallestIdx;
        };

        // Fetch and display current electricity price data
        var showElectricityPrice = () => {
            // Displays the latest price as text
            var showLatestPrice = (priceData) => {
                const now = luxon.DateTime.now();

                if (now > luxon.DateTime.fromISO(priceData[priceData.length - 1]['start-time'])) {
                    console.log('No recent electricity price data to show');
                    return;
                }

                const currentIdx = getClosestElecPriceDataIndex(priceData);

                const currentHourData = priceData[currentIdx];
                if (currentHourData) {
                    const currentPriceTime = luxon.DateTime.fromISO(currentHourData['start-time']).toFormat('HH:mm');
                    document.getElementById('lastObservation').innerHTML += `<br>Electricity price at ` +
                        `${currentPriceTime}: ${currentHourData['price']} c / kWh`;
                }

                const nextHourData = priceData[currentIdx + 1];
                if (nextHourData) {
                    const nextPriceTime = luxon.DateTime.fromISO(nextHourData['start-time']).toFormat('HH:mm');
                    document.getElementById('forecast').innerHTML += `<br>Electricity price at ` +
                        `${nextPriceTime}: ${nextHourData['price']} c / kWh`;
                }
            };

            axios.get('data/elec-data')
                .then(resp => {
                    const elecData = resp.data;

                    if (elecData) {
                        if (elecData['error']) {
                            if (elecData['error'] !== 'not-enabled') {
                                console.log(`Electricity data fetch error: ${elecData['error']}`);
                            }
                            toggleClassForElement('elecDataCheckboxDiv', 'display-none');

                            return;
                        }

                        if (!elecData['data-hour'] || !elecData['data-day'][0]) {
                            toggleVisibility('elecDataCheckboxDiv');
                            return;
                        }

                        if (elecData['dates']['max']) {
                            const dateMax = elecData['dates']['max'];

                            document.getElementById('elecStartDate').max = dateMax;
                            document.getElementById('elecEndDate').max = dateMax;
                        }

                        if (elecData['dates']['min']) {
                            const dateMin = elecData['dates']['min'];

                            document.getElementById('elecStartDate').min = dateMin;
                            document.getElementById('elecEndDate').min = dateMin;
                        }

                        if (elecData['dates']['current']['start'])
                            document.getElementById('elecStartDate').value = elecData['dates']['current']['start'];

                        showLatestPrice(elecData['data-hour']);
                        plotElectricityDataHour(elecData['data-hour'], true, true);

                        plotElectricityDataDay(elecData['data-day'], true);
                        hideElement('dayElecDataChart');
                    }
                }).catch(error => {
                    console.log(`Electricity data fetch error: ${error}`);
                });
        };

        var showTestbedImage = (pointDt) => {
            const pattern = /testbed-(.+).png/,
                imageCountIdx = testbedImageNames.length - 1,
                refDt = luxon.DateTime.fromMillis(pointDt);
            let smallest = 100000,
                smallestIdx = imageCountIdx;

            for (let i = imageCountIdx; i >= 0; i--) {
                const match = pattern.exec(testbedImageNames[i]);
                if (match) {
                    const diff = Math.abs(refDt.diff(luxon.DateTime.fromISO(match[1]), 'minutes').minutes);
                    if (diff <= smallest) {
                        smallest = diff;
                        smallestIdx = i;
                    } else {
                        break;
                    }
                }
            }

            const imageName = testbedImageNames[smallestIdx],
                datePattern = /testbed-([\d-]+)T.+/,
                result = datePattern.exec(imageName);
            if (result) {
                document.getElementById('testbedImage').src =
                    `${testbedImageBasepath}${result[1]}/${imageName}`;
                // Scroll page to bottom after loading the image for improved viewing
                window.setTimeout(() => {
                    window.scroll(0, document.body.scrollHeight);
                }, 500);
            }
        };

        // Decide if RuuviTag dataset should be shown or hidden by default
        var checkRTDatasetDisplayStatus = (location, measurable) => {
            if (rtDefaultShow.length === 1 && rtDefaultShow[0] === 'all' &&
                rtDefaultValues.includes(measurable))
                return true;
            else if (rtDefaultShow.includes(location) &&
                rtDefaultValues.includes(measurable))
                return true;
            else
                return false;
        };

        var generateAnnotationConfig = (chartType) => {
            var lineConfigs = {},
                currentLineIndex = 0;

            for (var i = 0; i < annotationIndexes[chartType].length; i++) {
                lineConfigs[`line${currentLineIndex}`] = {
                    type: 'line',
                    xMin: dataLabels[chartType][annotationIndexes[chartType][i]],
                    xMax: dataLabels[chartType][annotationIndexes[chartType][i]],
                    borderColor: '#838b93',
                    borderWidth: 1
                };
                currentLineIndex++;
            }

            return { annotations: lineConfigs };
        };

        var generateDataConfig = (dataMode) => {
            var config = {
                labels: dataMode === 'weather' ? dataLabels['weather'] : dataLabels['other'],
                datasets: []
            },
                index = 0;

            for (const key of (dataMode === 'weather' ? fieldNames['weather'] : fieldNames['other'])) {
                config.datasets.push({
                    label: labelValues[dataMode][key],
                    borderColor: colors[index],
                    data: dataSets[dataMode][key],
                    hidden: false,
                    fill: false,
                    pointRadius: 1,
                    borderWidth: 1
                });
                index++;
            }
            if (dataMode === 'other') {
                const rtMeasurables = ['temperature', 'humidity'];
                for (const name of rtNames)
                    for (const meas of rtMeasurables) {
                        config.datasets.push({
                            label: labelValues['rt'][name][meas],
                            borderColor: colors[index],
                            data: dataSets['rt'][name][meas],
                            hidden: !checkRTDatasetDisplayStatus(name, meas),
                            fill: false,
                            pointRadius: 1,
                            borderWidth: 1
                        });
                        index++;
                    }
            }

            return config;
        };

        var generatePluginConfig = (chartType) => {
            return {
                title: {
                    display: true,
                    text: (chartType === 'weather') ?
                        'Weather observations' : 'Other observations'
                },
                zoom: {
                    zoom: {
                        drag: {
                            enabled: true
                        },
                        mode: 'x'
                    }
                },
                annotation: generateAnnotationConfig(chartType)
            };
        };

        const scaleOptions = {
            x: {
                type: 'time',
                time: {
                    unit: 'hour',
                    tooltipFormat: 'd.L.yyyy HH:mm:ss',
                    displayFormats: {
                        hour: 'HH',
                        minute: 'HH:mm'
                    }
                },
                title: {
                    display: true,
                    text: 'Time'
                }
            },
            y: {
                title: {
                    display: true,
                    text: 'Value'
                },
                ticks: {
                    beginAtZero: true
                }
            }
        },
            interactionOptions = {
                mode: 'index'
            },
            onClickFunction = (event, elements) => {
                if (!elements.length)
                    return;

                document.getElementById('showImages').checked = true;
                document.getElementById('imageDiv').classList.remove('display-none');

                const chart = event.chart.canvas.id.includes('weather') ? charts['weather'] : charts['other'],
                    canvasPosition = Chart.helpers.getRelativePosition(event, chart);

                showTestbedImage(chart.scales.x.getValueForPixel(canvasPosition.x));
            };

        Chart.defaults.animation.duration = 400;

        charts['weather'] = new Chart(document.getElementById('weatherChart').getContext('2d'), {
            type: 'line',
            data: generateDataConfig('weather'),
            options: {
                scales: scaleOptions,
                interaction: interactionOptions,
                plugins: generatePluginConfig('weather'),
                onClick: onClickFunction,
                spanGaps: true,
                normalized: true,
                tension: 0.1,
                animation: false
            }
        });

        if (mode === 'all') {
            charts['other'] = new Chart(document.getElementById('otherChart').getContext('2d'), {
                type: 'line',
                data: generateDataConfig('other'),
                options: {
                    scales: scaleOptions,
                    interaction: interactionOptions,
                    plugins: generatePluginConfig('other'),
                    onClick: onClickFunction,
                    spanGaps: true,
                    normalized: true,
                    tension: 0.1,
                    animation: false
                }
            });
            document.getElementById('otherResetZoom').addEventListener(
                'click',
                () => {
                    charts['other'].resetZoom();
                },
                false);
        }
        document.getElementById('weatherResetZoom').addEventListener(
            'click',
            () => {
                charts['weather'].resetZoom();
            },
            false);
    }

    var toggleClassForElement = (elementId, className) => {
        document.getElementById(elementId).classList.toggle(className);
    };

    var toggleVisibility = (elementId) => {
        toggleClassForElement(elementId, 'display-none');
    };

    var toggleLoadingSpinner = () => {
        document.getElementsByTagName('body')[0].classList.toggle('top-padding');
        toggleClassForElement('bodyDiv', 'top-padding');
        toggleClassForElement('loadingSpinner', 'fg-blur');
        toggleVisibility('loadingSpinner');
        toggleClassForElement('bodyDiv', 'bg-blur');
    };

    var updateButtonClickHandler = (event) => {
        const startDate = document.getElementById('startDate').value,
            endDate = document.getElementById('endDate').value;
        var isSpinnerShown = false;

        if ((startDate && luxon.DateTime.fromISO(startDate).invalid) ||
            (endDate && luxon.DateTime.fromISO(endDate).invalid)) {
            alert('Error: either the start or end date is invalid');
            event.preventDefault();
            return;
        }

        if (luxon.DateTime.fromISO(startDate) > luxon.DateTime.fromISO(endDate)) {
            alert('Error: start date must be smaller than the end date');
            event.preventDefault();
            return;
        }

        const diff = luxon.DateTime.fromISO(endDate).diff(
            luxon.DateTime.fromISO(startDate), ['days']);

        if (mode == 'all' || diff.days >= 7) {
            isSpinnerShown = true;
            toggleLoadingSpinner();
        }

        persistDatasetState();

        axios.get('data/display',
            {
                params: {
                    'startDate': startDate,
                    'endDate': endDate,
                }
            })
            .then(resp => {
                const rData = resp.data;

                data['weather'] = rData['weather-data'],
                    data['obs'] = rData['obs-data'],
                    data['rt'] = rData['rt-data'];

                document.getElementById('startDate').value = rData['obs-dates']['current']['start'];
                document.getElementById('endDate').value = rData['obs-dates']['current']['end'];

                transformData();

                charts['weather'].data = generateDataConfig('weather');
                charts['weather'].options.plugins.annotation = generateAnnotationConfig('weather');
                charts['weather'].update();

                if (mode === 'all') {
                    charts['other'].data = generateDataConfig('other');
                    charts['other'].options.plugins.annotation = generateAnnotationConfig('other');
                    charts['other'].update();
                }

                restoreDatasetState();
            })
            .catch(error => {
                console.log(`Display data fetch error: ${error}`);
            })
            .then(() => {
                if (isSpinnerShown)
                    toggleLoadingSpinner();
            });
    };

    var elecUpdateButtonClickHandler = (event) => {
        const startDate = document.getElementById('elecStartDate').value,
            endDate = document.getElementById('elecEndDate').value;

        if ((startDate && luxon.DateTime.fromISO(startDate).invalid) ||
            (endDate && luxon.DateTime.fromISO(endDate).invalid)) {
            alert('Error: either the start or end date is invalid');
            event.preventDefault();
            return;
        }

        if (luxon.DateTime.fromISO(startDate) > luxon.DateTime.fromISO(endDate)) {
            alert('Error: start date must be smaller than the end date');
            event.preventDefault();
            return;
        }

        axios.get('data/elec-data',
            {
                params: {
                    'startDate': startDate,
                    'endDate': endDate
                }
            })
            .then(resp => {
                const elecData = resp.data;

                if (elecData) {
                    if (elecData['error']) {
                        if (elecData['error'] !== 'not-enabled') {
                            console.log(`Electricity data fetch error: ${elecData['error']}`);
                        }

                        return;
                    }

                    document.getElementById('elecStartDate').value = elecData['dates']['current']['start'];
                    document.getElementById('elecEndDate').value = elecData['dates']['current']['end'];

                    plotElectricityDataHour(elecData['data-hour']);
                    plotElectricityDataDay(elecData['data-day']);
                }
            })
            .catch(error => {
                console.log(`Electricity data fetch error: ${error}`);
            });
    };

    document.getElementById('updateBtn').addEventListener('click',
        updateButtonClickHandler,
        false);

    if (mode === 'all')
        document.getElementById('elecUpdateBtn').addEventListener('click',
            elecUpdateButtonClickHandler,
            false);

    document.getElementById('showImages').addEventListener('click',
        () => {
            toggleVisibility('imageDiv');
        },
        false);

    document.getElementById('weatherHideAll')
        .addEventListener('click',
            () => {
                for (var i = 0; i < charts['weather'].data.datasets.length; i++)
                    charts['weather'].getDatasetMeta(i).hidden = true;
                charts['weather'].update();
            },
            false);


    if (mode === 'all' && data['obs'].length > 0) {
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


        document.getElementById('showOtherChart').addEventListener('click',
            () => {
                toggleVisibility('otherDiv');
            },
            false);

        document.getElementById('otherHideAll')
            .addEventListener('click',
                () => {
                    for (var i = 0; i < charts['other'].data.datasets.length; i++)
                        charts['other'].getDatasetMeta(i).hidden = true;
                    charts['other'].update();
                },
                false);
        document.getElementById('ruuvitagMode')
            .addEventListener('click',
                () => {
                    for (var i = 0; i < charts['other'].data.datasets.length; i++)
                        if (charts['other'].data.datasets[i].label.indexOf('RT ') === -1)
                            charts['other'].getDatasetMeta(i).hidden = true;
                    charts['other'].update();
                },
                false);
    }
};

axios.get('data/display')
    .then(resp => {
        const rData = resp.data;

        mode = rData['mode'];
        testbedImageBasepath = rData['tb-image-basepath'],
            data['weather'] = rData['weather-data'],
            data['obs'] = rData['obs-data'];
        if (mode === 'all') {
            rtNames = rData['rt-names'],
                rtDefaultShow = rData['rt-default-show'],
                rtDefaultValues = rData['rt-default-values'],
                data['rt'] = rData['rt-data'];
        }

        if (rData['obs-dates']['min-max']) {
            const intMinMax = rData['obs-dates']['min-max'];

            document.getElementById('startDate').min = intMinMax['start'];
            document.getElementById('startDate').max = intMinMax['end'];

            document.getElementById('endDate').min = intMinMax['start'];
            document.getElementById('endDate').max = intMinMax['end'];
        }

        if (rData['obs-dates']['current']) {
            document.getElementById('startDate').value = rData['obs-dates']['current']['start'];
            document.getElementById('endDate').value = rData['obs-dates']['current']['end'];
        }

        loadPage();
    })
    .catch(error => {
        console.log(`Display data fetch error: ${error}`);
    });
