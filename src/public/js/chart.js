// Chart colors
const colors = [
    '#7864cb', '#5ab642', '#bf51b6', '#cf615e', '#dc437e',
    '#49b9a9', '#cc572c', '#628bcb', '#a5b043', '#d089c5',
    '#4c7e3d', '#a0496c', '#d79d48', '#6abe77', '#8c6f33'];

var labelValues = {'weather': {},
                   'other': {}},
    dataSets = {'weather': [],
                'other': []},
    data = {'obs': [],
            'rt': [],
            'weather': []},
    dataLabels = {'weather': [],
                  'other': []},
    beaconName = '',
    mode = null,
    testbedImageBasepath = '',
    testbedImageNames = [],
    rtDefaultShow = null,
    rtDefaultValues = [],
    rtNames = [],
    charts = {'weather': null,
              'other': null,
              'elecPrice': null};

var loadPage = () => {
    // Persist state of chart data sets
    var persistDatasetState = () => {
        var hidden = {'weather': {},
                      'other': {}};

        if (!charts['weather'])
            return;

        for (var i = 0; i < charts['weather'].data.datasets.length; i++)
            hidden['weather'][i.toString()] = !!charts['weather'].getDatasetMeta(i).hidden;

        if (mode === 'all')
            for (var j = 0; j < charts['other'].data.datasets.length; j++)
                hidden['other'][j.toString()] = !!charts['other'].getDatasetMeta(j).hidden;

        localStorage.setItem('hiddenDatasets', JSON.stringify(hidden));
    };

    // Restore chart data sets state
    var restoreDatasetState = () => {
        if (!localStorage.getItem('hiddenDatasets'))
            return;

        const hidden = JSON.parse(localStorage.getItem('hiddenDatasets'));
        localStorage.removeItem('checkedBoxes');

        if (!charts['weather'])
            // Do not attempt restore if there is no data
            return;

        for (var i = 0; i < charts['weather'].data.datasets.length; i++)
            charts['weather'].getDatasetMeta(i).hidden = hidden['weather'][i.toString()] ? true : null;
        charts['weather'].update();

        if (mode === 'all') {
            for (var j = 0; j < charts['other'].data.datasets.length; j++)
                charts['other'].getDatasetMeta(j).hidden = hidden['other'][j.toString()] ? true : null;
            charts['other'].update();
        }
    };

    // Parse RuuviTag observations
    // rtObservations - observations as JSON
    // rtLabels - RuuviTag labels
    var parseRTData = (rtObservations, rtLabels) => {
        const labelCount = rtLabels.length,
              timeDiffThreshold = 10;
        var location = null,
            dateRef = null,
            obsByDate = {};

        for (const label of rtLabels) {
            dataSets['other'][`rt_${label}_temperature`] = [];
            dataSets['other'][`rt_${label}_humidity`] = [];
        }

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
                    dataSets['other'][`rt_${label}_temperature`].push(obsByDate[key][label]['temperature']);
                    dataSets['other'][`rt_${label}_humidity`].push(obsByDate[key][label]['humidity']);
                } else {
                    dataSets['other'][`rt_${label}_temperature`].push(null);
                    dataSets['other'][`rt_${label}_humidity`].push(null);
                }
            }
        }

        // "null pad" RuuviTag observations to align latest observations with non-RuuviTag observations
        for (const key of Object.keys(dataSets['other']))
            if (key.indexOf('rt_') >= 0) {
                let lenDiff = dataSets['other']['brightness'].length - dataSets['other'][key].length;
                for (var j = 0; j < lenDiff; j++)
                    dataSets['other'][key].unshift(null);
            }
    };

    // Parses an observation.
    // observation - observation as JSON
    var parseData = (observation) => {
        const weatherFields = ['fmi-temperature', 'cloudiness', 'wind-speed'],
              otherFields = ['brightness', 'rssi'];

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

        if (mode === 'all') {
            dataLabels['other'].push(new Date(observation['recorded']));
            if (observation['weather-recorded'])
                dataLabels['weather'].push(new Date(observation['weather-recorded']));

            // Weather
            processFields('weather', observation, weatherFields, true);

            // Other
            processFields('other', observation, otherFields, false);

            if (beaconName === '' && observation['name']) {
                beaconName = observation['name'];
            }
        } else {
            dataLabels['weather'].push(new Date(observation['time']));

            processFields('weather', observation, weatherFields, false);
        }
        testbedImageNames.push(observation['tb-image-name']);
    };


    var getRTLabels = (rtNames) => {
        var labels = {};

        for (const name of rtNames) {
            labels[`rt_${name}_temperature`] = `RT "${name}" temperature`;
            labels[`rt_${name}_humidity`] = `RT "${name}" humidity`;
        }

        return labels;
    };

    // Transform data to Chart.js compatible format. Returns the data series labels.
    var transformData = () => {
        dataSets['weather'] = [],
        dataSets['other'] = [],
        dataLabels = {'weather': [],
                      'other': []};

        data['obs'].map((element) => {
            parseData(element);
        });

        // Data labels
        if (mode === 'all') {
            // Get labels for RuuviTag data
            const rtLabels = getRTLabels(rtNames);

            parseRTData(data['rt'], rtNames);

            labelValues['other'] = {'brightness': 'Brightness',
                                    'rssi': beaconName !== '' ? `Beacon "${beaconName}" RSSI` : 'Beacon RSSI'};
            for (const key in rtLabels) {
                labelValues['other'][key] = rtLabels[key];
            }
        }
        labelValues['weather'] = {'fmi-temperature': 'Temperature (FMI)',
                                  'cloudiness': 'Cloudiness',
                                  'wind-speed': 'Wind speed'};

        if (mode === 'all' && beaconName !== '') {
            var label = `Beacon "${beaconName}" RSSI`;
        }
        return labelValues;
    };

    if (data['obs'].length === 0) {
        document.getElementById('noDataError').style.display = 'block';
        document.getElementById('weatherDiv').style.display = 'none';
        document.getElementById('imageButtonDiv').style.display = 'none';
        if (mode === 'all') {
            document.getElementById('weatherCheckboxDiv').style.display = 'none';
            document.getElementById('otherDiv').style.display = 'none';
            document.getElementById('otherCheckboxDiv').style.display = 'none';
        }
    } else {
        labelValues = transformData();

        // Add unit suffix
        var addUnitSuffix = (keyName) => {
            return `${keyName.indexOf('temperature') >= 0 ? ' \u2103' : ''}` +
                `${keyName.indexOf('wind') >= 0 ? ' m/s' : ''}` +
                `${keyName.indexOf('humidity') >= 0 ? ' %H' : ''}`;
        };

        // Format a given Unix second timestamp in hour:minute format
        var formatUnixSecondTs = (unixTs) => {
            return luxon.DateTime.fromSeconds(unixTs).toFormat('HH:mm');
        };

        // Show last observation and some other data for quick viewing
        var showLastObservation = () => {
            var observationText = '',
                itemsAdded = 0,
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
                    ` Sunset ${formatUnixSecondTs(data['weather']['owm']['current']['sunset'])}`;

                let obsIndex = 0;
                // Update obsIndex for RuuviTags as the number of "normal" and RuuviTags observations
                // may differ
                for(const key in dataSets['other'])
                    if (key.indexOf('rt_') >= 0) {
                        obsIndex = dataSets['other'][key].length - 1;
                        break;
                    }

                var firstRTLabelSeen = false;
                for (const key in labelValues['other']) {
                    if ((!firstRTLabelSeen && (itemsAdded % 5) === 0) ||
                        (firstRTLabelSeen && (itemsAdded % 4) === 0))
                        observationText += '<br>';
                    if (!firstRTLabelSeen && key.indexOf('rt') >= 0) {
                        firstRTLabelSeen = true;
                        observationText += '<br>';
                        itemsAdded = 0;
                    }
                    observationText += `${labelValues['other'][key]}: ${dataSets['other'][key][obsIndex]}` +
                        `${addUnitSuffix(key)}, `;
                    itemsAdded++;
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

        // Show the electricity price data in a chart
        var plotElectricityPrice = (priceData) => {
            var labels = [],
                data = [];

            for (const item of priceData) {
                labels.push(luxon.DateTime.fromISO(item['start-time']).toJSDate());
                data.push(item['price']);
            }

            const currentIdx = getClosestElecPriceDataIndex(priceData);

            if (!charts['elecPrice']) {
                charts['elecPrice'] = new Chart(document.getElementById('elecPriceChart').getContext('2d'), {
                    type: 'line',
                    data: {
                        labels: labels,
                        datasets: [{
                            label: 'Price',
                            borderColor: colors[6],
                            data: data,
                            hidden: false,
                            fill: false,
                            pointRadius: 1,
                            borderWidth: 1
                        }]
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
                            y: {
                                title: {
                                    display: true,
                                    text: 'Price (c / kWh)'
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
                                text: 'Electricity price'
                            },
                            zoom: {
                                zoom: {
                                    drag: {
                                        enabled: true
                                    },
                                    mode: 'x'
                                }
                            },
                            annotation: {
                                annotations: {
                                    line1: {
                                        type: 'line',
                                        xMin: labels[currentIdx],
                                        xMax: labels[currentIdx],
                                        borderColor: 'rgb(0, 0, 0)',
                                        borderWidth: 2,
                                    }
                                }
                            }
                        },
                        spanGaps: true,
                        normalized: true,
                        tension: 0.1,
                        animation: false
                    }
                });

                document.getElementById('elecPriceResetZoom').addEventListener(
                    'click',
                    () => {
                        charts['elecPrice'].resetZoom();
                    },
                    false);

                document.getElementById('showElecPriceChart').addEventListener(
                    'click',
                    () => {
                        toggleVisibility('elecPriceDiv');
                    },
                    false);
            } else {
                charts['elecPrice'].data.labels = labels;
                charts['elecPrice'].data.datasets[0].data = data;
                charts['elecPrice'].update();
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

            axios.get('data/elec-price')
                .then(resp => {
                    const priceData = resp.data;

                    if (priceData) {
                        if (priceData['error']) {
                            if (priceData['error'] !== 'not-enabled') {
                                console.log(`Electricity price data fetch error: ${priceData['error']}`);
                            }
                            toggleClassForElement('elecPriceCheckboxDiv', 'display-none');

                            return;
                        }

                        showLatestPrice(priceData);
                        plotElectricityPrice(priceData);
                    }
                }).catch(error => {
                    console.log(`Electricity data fetch error: ${error}`);
                });
        };

        var showTestbedImage = (dataIndex) => {
            var imageName = testbedImageNames[dataIndex];
            if (imageName) {
                var pattern = /testbed-([\d-]+)T.+/,
                    result = pattern.exec(imageName);
                if (result) {
                    document.getElementById('testbedImage').src =
                        `${testbedImageBasepath}${result[1]}/${imageName}`;
                    // Scroll page to bottom after loading the image for improved viewing
                    window.setTimeout(() => {
                        window.scroll(0, document.body.scrollHeight);
                    }, 500);
                }
            } else {
                document.getElementById('testbedImage').src = '';
            }
        };

        // Infer if dataset should be shown or hidden by default
        var checkDatasetDisplayStatus = (keyName) => {
            if (keyName.indexOf('rt_') === -1) {
                return true;
            } else {
                const keyParts = keyName.split('_');
                if (rtDefaultShow.length === 1 && rtDefaultShow[0] === 'all' &&
                    rtDefaultValues.includes(keyParts[2])) {
                    return true;
                } else if (rtDefaultShow.includes(keyParts[1]) &&
                           rtDefaultValues.includes(keyParts[2])) {
                    return true;
                } else {
                    return false;
                }
            }
        };

        var generateDataConfig = (dataMode) => {
            var config = {
                labels: dataMode === 'weather' ? dataLabels['weather'] : dataLabels['other'],
                datasets: []
            },
                index = 0,
                weatherFields = ['fmi-temperature', 'cloudiness', 'wind-speed'],
                otherFields = ['brightness', 'rssi'];

            if (dataMode === 'other')
                for (const key in labelValues['other']) {
                    if (key.indexOf('rt') !== -1)
                        otherFields.push(key);
                }

            for (const key of (dataMode === 'weather' ? weatherFields : otherFields)) {
                config.datasets.push({
                    label: labelValues[dataMode][key],
                    borderColor: colors[index],
                    data: dataSets[dataMode][key],
                    hidden: !checkDatasetDisplayStatus(key),
                    fill: false,
                    pointRadius: 1,
                    borderWidth: 1
                });
                index++;
            }

            return config;
        };

        var getPluginConfig = (chartType) => {
            return  {
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
                }
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

                  showTestbedImage(elements[0].index);
              };

        Chart.defaults.animation.duration = 400;

        charts['weather'] = new Chart(document.getElementById('weatherChart').getContext('2d'), {
            type: 'line',
            data: generateDataConfig('weather'),
            options: {
                scales: scaleOptions,
                interaction: interactionOptions,
                plugins: getPluginConfig('weather'),
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
                    plugins: getPluginConfig('other'),
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
        var startDate = document.getElementById('startDate').value,
            endDate = document.getElementById('endDate').value,
            isSpinnerShown = false;

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
                      }})
            .then(resp => {
                const rData = resp.data;

                data['weather'] = rData['weather-data'],
                data['obs'] = rData['obs-data'],
                data['rt'] = rData['rt-data'];

                document.getElementById('startDate').value = rData['start-date'];
                document.getElementById('endDate').value = rData['end-date'];

                transformData();

                charts['weather'].data = generateDataConfig('weather');
                charts['weather'].update();

                if (mode === 'all') {
                    charts['other'].data = generateDataConfig('other');
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

        axios.get('data/elec-price',
                  {
                      params: {
                          'startDate': startDate,
                          'endDate': endDate,
                      }})
            .then(resp => {
                const priceData = resp.data;

                if (priceData) {
                    if (priceData['error']) {
                        if (priceData['error'] !== 'not-enabled') {
                            console.log(`Electricity price data fetch error: ${priceData['error']}`);
                        }

                        return;
                    }

                    plotElectricityPrice(priceData);
                }
            })
            .catch(error => {
                console.log(`Electricity data fetch error: ${error}`);
            });
    };

    document.getElementById('updateBtn').addEventListener('click',
                                                          updateButtonClickHandler,
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


    if (mode === 'all') {
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

        document.getElementById('startDate').value = rData['start-date'];
        document.getElementById('endDate').value = rData['end-date'];

        loadPage();
    })
    .catch(error => {
        console.log(`Display data fetch error: ${error}`);
    });
