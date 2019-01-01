
// Formats the given date as 'dd.mm.yyyy hh:MM:ss'
var formatDate = function (date) {
    return moment(date).format('DD.MM.YYYY HH:mm:ss');
};

// Persist checkbox state in local storage
var persistCheckboxes = function () {
    var boxes = document.querySelectorAll('div.checkboxes > input'),
        checked = {};

    boxes.forEach(function (element) {
        checked[element.id] = element.checked;
    });
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
                   'showPressure': 6,
                   'showBeacon': 7};
        if (ruuvitagEnabled) {
            mapping['showRTTemperature'] = 8;
            mapping['showRTHumidity'] = 9;
        }
    } else {
        mapping = {'showOutsideTemperature': 0,
                   'showFMITemperature': 1,
                   'showTempDelta': 2,
                   'showCloudiness': 3,
                   'showPressure': 4};
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
                     observation['pressure'],
                     observation['rssi']];
        if (ruuvitagEnabled) {
            dataPoint.push(observation['rt-temperature']);
            dataPoint.push(observation['rt-humidity']);
        }
        if (beaconName === '' && observation['name']) {
            beaconName = observation['name'];
        }
        yardcamImageNames.push(observation['yc_image_name']);
    } else {
        var date = observation['time'] ? observation['time'] : observation['recorded'];
        dataPoint = [new Date(date),
                     observation['o_temperature'],
                     observation['fmi_temperature'],
                     observation['temp_delta'],
                     observation['cloudiness'],
                     observation['pressure']];
    }
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
                  'Brightness', 'Cloudiness', 'Pressure (FMI)', 'Beacon'];
        if (ruuvitagEnabled) {
            labels.push('RuuviTag inside temperature');
            labels.push('RuuviTag humidity');
        }
    } else {
        labels = ['Date', 'Temperature (outside)', 'Temperature (FMI)',
                  'Temperature delta', 'Cloudiness', 'Pressure (FMI)'];
    }
    plotData = observations.map(function (element) {
        return parseData(element);
    });
    if (mode === 'all' && beaconName !== '') {
        var label = 'Beacon "' + beaconName + '" RSSI';
        labels[8] = label;
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

        lastObservation.forEach(function (element, index) {
            observationText += labels[index] + ': ';
            if (index === 0) {
                // Reformat date
                observationText += formatDate(element);
            } else {
                observationText += element;
            }
            observationText += ', ';
            if (mode === 'all' && index === Math.round(lastObservation.length / 2)) {
                observationText += '<br>';
            }
        });
        observationText = observationText.slice(0, -2);

        document.getElementById('lastObservation').innerHTML = observationText;
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
                document.getElementById('yardcamImageLink').href = ycImageBasepath +
                    result[1] + '/' + imageName;
                // For improved viewing scroll page to bottom after loading the image
                window.setTimeout(function() {
                    window.scroll(0, document.body.scrollHeight);
                }, 500);
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
                // For improved viewing scroll page to bottom after loading the image
                window.setTimeout(function() {
                    window.scroll(0, document.body.scrollHeight);
                }, 500);
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
                                height: 500,
                                width: 950,
                                axes: {
                                    x: {
                                        valueFormatter: function (ms) {
                                            return formatDate(new Date(ms));
                                        }
                                    }
                                },
                                pointClickCallback: function (e, point) {
                                    document.getElementById('showImages').checked = true;
                                    document.getElementById('imageDiv').classList.remove('displayNone');
                                    showTestbedImage(point.idx);
                                    if (mode === 'all') {
                                        showYardcamImage(point.idx);
                                    }
                                }
                            });
    // Disable the pressure series by default as its values are in a very different
    // range as the other values
    graph.setVisibility(getCheckboxIndex('showPressure'), false);
}

var checkboxes = document.getElementsByClassName('selectBox');
for (var i = 0; i < checkboxes.length; i++) {
    checkboxes[i].addEventListener('click',
                                   function (event) {
                                       graph.setVisibility(getCheckboxIndex(event.currentTarget.id),
                                                           event.currentTarget.checked);
                                       if (!event.currentTarget.checked) {
                                           document.getElementById('showAll').checked = false;
                                       }
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

// Function validating date field values
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

var toggleImageDiv = function () {
    document.getElementById('imageDiv').classList.toggle('displayNone');
};

document.getElementById('showImages').addEventListener('click',
                                                       toggleImageDiv,
                                                       false);

var showAllHandler = function (event) {
    var show = event.currentTarget.checked;

    var checkboxes = document.getElementsByClassName('selectBox');
    for (var i = 0; i < checkboxes.length; i++) {
        graph.setVisibility(getCheckboxIndex(checkboxes[i].id), show);
        checkboxes[i].checked = show;
    }
};
document.getElementById('showAll').addEventListener('click',
                                                    showAllHandler,
                                                    false);
