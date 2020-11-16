// Chart colors
const colors = [
    '#000066', '#666600', '#b30000', '#001247', '#474700', '#7d0000',
    '#220066', '#3d6600', '#b34800', '#006666', '#665200', '#b30086',
    '#004747', '#473900', '#7d005e'];

var labelValues = {'weather': {},
                   'other': {}},
    plotData = [],
    dataLabels = [],
    dataSets = {'weather': [],
                'other': []},
    yardcamImageNames = [],
    testbedImageNames = [],
    beaconName = '',
    observationCount = 0;

// Formats the given date as 'dd.mm.yyyy hh:MM:ss'
var formatDate = function (date) {
    return luxon.DateTime.fromJSDate(date).toFormat('dd.MM.yyyy HH:mm:ss');
};

// Persist state of chart data sets
var persistDatasetState = function () {
    var hidden = {'weather': {},
                 'other': {}};

    for (var i = 0; i < weatherChart.data.datasets.length; i++)
        hidden['weather'][i.toString()] = !!weatherChart.getDatasetMeta(i).hidden;

    if (mode === 'all')
        for (var j = 0; j < otherChart.data.datasets.length; j++)
            hidden['other'][j.toString()] = !!otherChart.getDatasetMeta(j).hidden;

    localStorage.setItem('hiddenDatasets', JSON.stringify(hidden));
};

// Restore chart data sets state
var restoreDatasetState = function () {
    if (!localStorage.getItem('hiddenDatasets'))
        return;

    const hidden = JSON.parse(localStorage.getItem('hiddenDatasets'));
    localStorage.removeItem('checkedBoxes');

    for (var i = 0; i < weatherChart.data.datasets.length; i++)
        weatherChart.getDatasetMeta(i).hidden = hidden['weather'][i.toString()] ? true : null;
    weatherChart.update();

    if (mode === 'all') {
        for (var j = 0; j < otherChart.data.datasets.length; j++)
            otherChart.getDatasetMeta(j).hidden = hidden['other'][j.toString()] ? true : null;
        otherChart.update();
    }
};

// Parses an observation.
// observation - JSON input
var parseData = function (observation) {
    const weatherFields = ['o_temperature', 'fmi_temperature', 'temp_delta',
                           'cloudiness'],
          otherFields = ['temperature', 'brightness', 'rssi'];

    // dataMode - string, which mode to process data in, values: weather, other
    // observation - object, observation to process
    // selectKeys - array, which data keys to select
    var processFields = function(dataMode, observation, selectKeys) {
        for (key in observation) {
            if (selectKeys.includes(key)) {
                if (dataSets[dataMode][key] !== undefined)
                    dataSets[dataMode][key].push(observation[key]);
                else
                    dataSets[dataMode][key] = [observation[key]];
            }
            if (dataMode === 'other' && key.indexOf('rt') !== -1) {
                const tag = key.slice(3),
                      temp_key = `rt_${tag}_temperature`,
                      humidity_key = `rt_${tag}_humidity`;
                if (dataSets[dataMode][temp_key] !== undefined)
                    dataSets[dataMode][temp_key].push(observation[key]['temperature']);
                else
                    dataSets[dataMode][temp_key] = [observation[key]['temperature']];

                if (dataSets[dataMode][humidity_key] !== undefined)
                    dataSets[dataMode][humidity_key].push(observation[key]['humidity']);
                else
                    dataSets[dataMode][humidity_key] = [observation[key]['humidity']];
            }
        }
    };

    if (mode === 'all') {
        dataLabels.push(new Date(observation['recorded']));

        // Weather
        processFields('weather', observation, weatherFields);

        // Other
        processFields('other', observation, otherFields);

        if (beaconName === '' && observation['name']) {
            beaconName = observation['name'];
        }

        yardcamImageNames.push(observation['yc_image_name']);
    } else {
        const date = observation['time'] ? observation['time'] : observation['recorded'];
        dataLabels.push(new Date(date));

        processFields('weather', observation, weatherFields);
    }
    testbedImageNames.push(observation['tb_image_name']);
};


var getRTLabels = function(observations) {
    var labels = null;

    for (var i = 0; i < observations.length; i++) {
        for (const key in observations[i]) {
            if (key.indexOf('rt') !== -1) {
                const tag = key.slice(3);
                labels = {};
                labels[`rt_${tag}_temperature`] = `RT "${tag}" temperature`;
                labels[`rt_${tag}_humidity`] = `RT "${tag}" humidity`;
                break;
            }
        }
    }

    return labels;
};

// Transform data to Chart.js compatible format. Returns the data series labels.
// jsonData - JSON input data
var transformData = function (jsonData) {
    var observations = JSON.parse(jsonData),
        labels = null;
    observationCount = observations.length;

    // Get labels for RuuviTag data
    const rtLabels = getRTLabels(observations.slice(0, 5));

    observations.map(function (element) {
        parseData(element);
    });

    // Data labels
    if (mode === 'all') {
        labelValues['other'] = {'temperature': 'Inside temperature',
                                'brightness': 'Brightness',
                                'rssi': beaconName !== '' ? 'Beacon "' + beaconName + '" RSSI' : 'Beacon RSSI'};
        for (const key in rtLabels) {
            labelValues['other'][key] = rtLabels[key];
        }
    }
    labelValues['weather'] = {'o_temperature': 'Temperature (outside)',
                              'fmi_temperature': 'Temperature (FMI)',
                              'temp_delta': 'Temperature delta',
                              'cloudiness': 'Cloudiness'};

    if (mode === 'all' && beaconName !== '') {
        var label = 'Beacon "' + beaconName + '" RSSI';
    }
    return labelValues;
};

if (JSON.parse(document.getElementById('plotData').innerText).length === 0) {
    document.getElementById('noDataError').style.display = 'block';
    document.getElementById('weatherDiv').style.display = 'none';
    document.getElementById('imageButtonDiv').style.display = 'none';
    if (mode === 'all') {
        document.getElementById('otherDiv').style.display = 'none';
    }
} else {
    labelValues = transformData(document.getElementById('plotData').innerText);

    // Show last observation with FMI data for quick viewing
    var showLastObservation = function () {
        var lastObservationIndex = observationCount - 1,
            observationText = 'Date: ' + formatDate(dataLabels[lastObservationIndex]) + ', ';

        for (var i = lastObservationIndex; i > 0; i--) {
            if (dataSets['weather']['fmi_temperature'][i] !== null) {
                lastObservationIndex = i;
                break;
            }
        }

        for (const key in labelValues['weather']) {
            observationText += labelValues['weather'][key] + ': ' +
                dataSets['weather'][key][lastObservationIndex] + ', ';
        }
        if (mode === 'all') {
            observationText += '<br>';
            for (const key in labelValues['other']) {
                observationText += labelValues['other'][key] + ': ' +
                    dataSets['other'][key][lastObservationIndex] + ', ';
            }
        }

        document.getElementById('lastObservation').innerHTML = observationText.slice(0, -2);
        document.getElementById('lastObservation').classList.remove('display-none');
    };
    showLastObservation();

    var showYardcamImage = function (dataIndex) {
        var imageName = yardcamImageNames[dataIndex];
        if (imageName) {
            var pattern = /yc-([\d-]+)T.+/,
                result = pattern.exec(imageName);
            if (result) {
                document.getElementById('yardcamImageLink').style.display = '';
                document.getElementById('yardcamImage').src = ycImageBasepath +
                    result[1] + '/' + imageName;
                document.getElementById('yardcamImageLink').href = ycImageBasepath +
                    result[1] + '/' + imageName;
                // For improved viewing scroll page to bottom after loading the image
                window.setTimeout(function() {
                    window.scroll(0, document.body.scrollHeight);
                }, 500);
            }
        } else {
            document.getElementById('yardcamImageLink').style.display = 'none';
        }
    };

    var showTestbedImage = function (dataIndex) {
        var imageName = testbedImageNames[dataIndex];
        if (imageName) {
            var pattern = /testbed-([\d-]+)T.+/,
                result = pattern.exec(imageName);
            if (result) {
                document.getElementById('testbedImage').src = tbImageBasepath +
                    result[1] + '/' + imageName;
                // For improved viewing scroll page to bottom after loading the image
                window.setTimeout(function() {
                    window.scroll(0, document.body.scrollHeight);
                }, 500);
            }
        } else {
            document.getElementById('testbedImage').src = '';
        }
    };

    var generateDataConfig = function(dataMode) {
        var config = {
            labels: dataLabels,
            datasets: []
        },
            index = 0;
        const weatherFields = ['o_temperature', 'fmi_temperature', 'temp_delta',
                               'cloudiness'];
        var otherFields = ['temperature', 'brightness', 'rssi'];
        if (dataMode === 'other') {
            for (const key in labelValues['other']) {
                if (key.indexOf('rt') !== -1)
                    otherFields.push(key);
            }
        }

        for (const key of (dataMode === 'weather' ? weatherFields : otherFields)) {
            config.datasets.push({
                label: labelValues[dataMode][key],
                borderColor: colors[index],
                data: dataSets[dataMode][key],
                // Hide inside temperature by default because the data is bad
                hidden: key !== 'temperature' ? false : true,
                fill: false,
                pointRadius: 1,
                borderWidth: 1
            });
            index++;
        }

        return config;
    };
    const scaleOptions = {
        xAxes: [{
            type: 'time',
            distribution: 'linear',
            time: {
                unit: 'hour',
                tooltipFormat: 'D.M.YYYY HH:mm:ss',
                displayFormats: {
                    hour: 'HH',
                    minute: 'HH:mm'
                }
            },
            scaleLabel: {
                display: true,
                labelString: 'Time'
            }
        }],
        yAxes: [{
            scaleLabel: {
                display: true,
                labelString: 'Value'
            },
            ticks: {
                beginAtZero: true
            }
        }]
    },
          tooltipOptions = {
              mode: 'index'
          },
          plugins = {
              zoom: {
                  zoom: {
                      enabled: true,
                      drag: true,
                      mode: 'x'
                  }
              }
          },
          onClickFunction = function (event, elements) {
              if (!elements.length)
                  return;

              document.getElementById('showImages').checked = true;
              document.getElementById('imageDiv').classList.remove('display-none');

              showTestbedImage(elements[0]._index);
              if (mode === 'all')
                  showYardcamImage(elements[0]._index);
          };

    Chart.defaults.global.animation.duration = 400;

    var weatherCtx = document.getElementById('weatherChart').getContext('2d');
    var weatherChart = new Chart(weatherCtx, {
        type: 'line',
        data: generateDataConfig('weather'),
        options: {
            title: {
                text: 'Weather observations',
                display: true
            },
            scales: scaleOptions,
            tooltips: tooltipOptions,
            plugins: plugins,
            onClick: onClickFunction
        }
    });

    if (mode === 'all') {
        var otherCtx = document.getElementById('otherChart').getContext('2d');
        var otherChart = new Chart(otherCtx, {
            type: 'line',
            data: generateDataConfig('other'),
            options: {
                title: {
                    text: 'Other observations',
                    display: true
                },
                scales: scaleOptions,
                tooltips: tooltipOptions,
                plugins: plugins,
                onClick: onClickFunction
            }
        });
        document.getElementById('otherResetZoom').addEventListener(
            'click',
            function() {
                otherChart.resetZoom();
            },
            false);
    }
    document.getElementById('weatherResetZoom').addEventListener(
        'click',
        function() {
            weatherChart.resetZoom();
        },
        false);
}

restoreDatasetState();

// Various WebSocket operations
var wsOperations = function () {
    socket.onerror = function(error) {
        console.log('WebSocket error: ' + error);
    };

    socket.onopen = function(event) {
        console.log('WebSocket connected to: ' + event.currentTarget.url);
    };

    socket.onmessage = function(event) {
        plotData.push(parseData(JSON.parse(event.data)[0]));
        graph.updateOptions({'file': plotData});
    };

    socket.onclose = function(event) {
        console.log('WebSocket disconnected: ' + event.code + ', reason ' + event.reason);
        socket = undefined;
    };
};
wsOperations();

// Function validating date field values
var validateDates = function (event) {
    var startDate = document.getElementById('startDate').value,
        endDate = document.getElementById('endDate').value;

    if ((startDate && luxon.DateTime.fromISO(startDate).invalid) ||
        (endDate && luxon.DateTime.fromISO(endDate).invalid)) {
        alert('Error: either the start or end date is invalid!');
        event.preventDefault();
        return;
    }
    persistDatasetState();
};

document.getElementById('updateBtn').addEventListener('click',
                                                      validateDates,
                                                      false);

var toggleVisibility = function (elementId) {
    document.getElementById(elementId).classList.toggle('display-none');
};

document.getElementById('showImages').addEventListener('click',
                                                       function () {
                                                           toggleVisibility('imageDiv');
                                                       },
                                                       false);

if (mode === 'all') {
    document.getElementById('showWeatherChart').addEventListener('click',
                                                                 function () {
                                                                     toggleVisibility('weatherDiv');
                                                                 },
                                                                 false);


    document.getElementById('showOtherChart').addEventListener('click',
                                                               function () {
                                                                   toggleVisibility('otherDiv');
                                                               },
                                                               false);
}

document.getElementById('weatherHideAll')
    .addEventListener('click',
                      function () {
                          for (var i = 0; i < weatherChart.data.datasets.length; i++)
                              weatherChart.getDatasetMeta(i).hidden = true;
                          weatherChart.update();
                      },
                      false);

if (mode === 'all')
    document.getElementById('otherHideAll')
        .addEventListener('click',
                          function () {
                              for (var i = 0; i < otherChart.data.datasets.length; i++)
                                  otherChart.getDatasetMeta(i).hidden = true;
                              otherChart.update();
                          },
                          false);