
// Persist checkbox state in local storage
var persistCheckboxes = function () {
    var boxes = document.querySelectorAll('div.checkboxes > input'),
        checked = {};

    for (var i = 0; i < boxes.length; i++) {
        checked[boxes[i].id] = boxes[i].checked;
    }
    localStorage.setItem('checkedBoxes', JSON.stringify(checked));
};

// Returns the index of selected checkbox
var getCheckboxIndex = function (checkboxId) {
    var mapping = {};
    if (mode === 'all') {
        mapping = {'showTemperature': 0,
                   'showOutsideTemperature': 1,
                   'showFMITemperature': 2,
                   'showTempDelta': 3,
                   'showBrightness': 4,
                   'showCloudiness': 5,
                   'showBeacon': 6};
    } else {
        mapping = {'showOutsideTemperature': 0,
                   'showFMITemperature': 1,
                   'showTempDelta': 2,
                   'showCloudiness': 3};
    }
    return mapping[checkboxId];
};

var labels = null,
    plotData = [],
    yardcamImageNames = [],
    testbedImageNames = [],
    beaconName = '';

// Parses an observation. Returns Dygraph compatible data point.
// observation - JSON input
var parseData = function (observation) {
    var dataPoint = null;
    if (mode === 'all') {
        dataPoint = [new Date(observation['recorded']),
                     observation['temperature'],
                     observation['o_temperature'],
                     observation['fmi_temperature'],
                     observation['temp_delta'],
                     observation['brightness'],
                     observation['cloudiness'],
                     observation['rssi']];
        if (beaconName === '' && observation['name']) {
            beaconName = observation['name'];
        }
    } else {
        var date = observation['time'] ? observation['time'] : observation['recorded'];
        dataPoint = [new Date(date),
                     observation['o_temperature'],
                     observation['fmi_temperature'],
                     observation['temp_delta'],
                     observation['cloudiness']];
    }
    yardcamImageNames.push(observation['yc_image_name']);
    testbedImageNames.push(observation['tb_image_name']);

    return dataPoint;
};

// Transform data to Dygraph compatible format. Returns the data series labels.
// jsonData - JSON input data
var transformData = function (jsonData) {
    var observations = JSON.parse(jsonData),
        dataPoint = [],
        labels = null;

    // Data labels
    if (mode === 'all') {
        labels = ['Date', 'Inside temperature', 'Temperature (outside)',
                  'Temperature (FMI)', 'Temperature delta',
                  'Brightness', 'Cloudiness', 'Beacon'];
    } else {
        labels = ['Date', 'Temperature (outside)', 'Temperature (FMI)',
                  'Temperature delta', 'Cloudiness'];
    }
    for (var i = 0; i < observations.length; i++) {
        plotData.push(parseData(observations[i]));
    }
    if (mode === 'all' && beaconName !== '') {
        var label = 'Beacon "' + beaconName + '" RSSI';
        labels[labels.length - 1] = label;
        document.getElementById('showBeaconLabel').innerHTML = label;
    }
    return labels;
};

labels = transformData(document.getElementById('plotData').innerHTML);
if (plotData.length === 0) {
    document.getElementById('noDataError').style.display = 'inline';
    document.getElementById('imageButtonDiv').style.display = 'none';
    document.getElementById('plotControls').style.display = 'none';
} else {
    // Show last observation with FMI data for quick viewing
    var showLastObservation = function () {
        var lastObservation = plotData[plotData.length - 1],
            observationText = '';
        if (mode === 'all' && !lastObservation[3]) {
            // Get last observation with FMI data
            for (var i = plotData.length - 1; i > 0; i--) {
                lastObservation = plotData[i];
                if (lastObservation[3]) {
                    break;
                }
            }
        }

        for (i = 0; i < lastObservation.length; i++) {
            observationText += labels[i] + ': ';
            if (i === 0) {
                // Reformat date
                observationText += lastObservation[i].getDate() + '.' + (lastObservation[i].getMonth() + 1)
                    + '.' + lastObservation[i].getFullYear() + ' ' + lastObservation[i].getHours() + ':'
                    + lastObservation[i].getMinutes() + ':' + lastObservation[i].getSeconds();
            } else {
                observationText += lastObservation[i];
            }
            observationText += ', ';
        }
        observationText = observationText.slice(0, -2);

        document.getElementById('lastObservation').innerText = observationText;
        document.getElementById('lastObservation').classList.remove('displayNone');
    };
    showLastObservation();

    var showYardcamImage = function (dataIndex) {
        var imageName = yardcamImageNames[dataIndex];
        if (imageName) {
            var pattern = /yc-([\d-]+)T.+/,
                result = pattern.exec(imageName);
            if (result) {
                document.getElementById('yardcamImage').src = ycImageBasepath +
                    result[1] + '/' + imageName;
            }
        } else {
            alert('No yardcam image to show');
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
            }
        } else {
            document.getElementById('testbedImage').src = '';
        }
    };

    var graph = new Dygraph(document.getElementById('graphDiv'),
                            plotData,
                            {
                                labels: labels,
                                labelsDiv: 'labelDiv',
                                labelsSeparateLines: true,
                                title: 'Observations',
                                height: 450,
                                width: 900,
                                axes: {
                                    x: {
                                        valueFormatter: function (ms) {
                                            var date = new Date(ms);
                                            return date.getDate() + '.' + (date.getMonth() + 1) + '.' +
                                                date.getFullYear() + ' ' + date.getHours() + ':' +
                                                (date.getMinutes() < 10 ?
                                                 ('0' + date.getMinutes()) : date.getMinutes())
                                                + ':' + date.getSeconds();
                                        }
                                    }
                                },
                                pointClickCallback: function (e, point) {
                                    document.getElementById('showImages').checked = true;
                                    document.getElementById('imageDiv').classList.remove('displayNone');
                                    showYardcamImage(point.idx);
                                    showTestbedImage(point.idx);
                                }
                            });
}

var checkboxes = document.getElementsByClassName('selectBox');
for (var i = 0; i < checkboxes.length; i++) {
    checkboxes[i].addEventListener('click',
                                   function (event) {
                                       graph.setVisibility(getCheckboxIndex(event.currentTarget.id),
                                                           event.currentTarget.checked);
                                   },
                                   false);
}

// Load possible checkbox state
var restoreCheckboxState = function () {
    if (localStorage.getItem('checkedBoxes')) {
        var boxes = JSON.parse(localStorage.getItem('checkedBoxes'));
        for (box in boxes) {
            document.getElementById(box).checked = boxes[box];
            if (!boxes[box]) {
                graph.setVisibility(getCheckboxIndex(box), false);
            }
        }
        localStorage.removeItem('checkedBoxes');
    }
};
restoreCheckboxState();

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

// Function for validating date field values
var validateDates = function (event) {
    var datePattern = /\d{1,2}\.\d{1,2}\.\d{4}/,
        startDate = document.getElementById('startDate').value,
        endDate = document.getElementById('endDate').value;

    if ((startDate && !datePattern.exec(startDate)) ||
        (endDate && !datePattern.exec(endDate))) {
        alert('Error: either the start or end date is invalid!');
        event.preventDefault();
        return;
    }
    persistCheckboxes();
};

document.getElementById('submitBtn').addEventListener('click',
                                                      validateDates,
                                                      false);

var toggleImageDiv = function (event) {
    document.getElementById('imageDiv').classList.toggle('displayNone');
};

document.getElementById('showImages').addEventListener('click',
                                                       toggleImageDiv,
                                                       false);
