
// Persist checkbox state in local storage
var persistCheckboxes = function () {
    var boxes = document.querySelectorAll('div.checkboxes > input'),
        checked = {};

    for (var i = 0; i < boxes.length; i++) {
        checked[boxes[i].id] = boxes[i].checked;
    }
    localStorage.setItem('checkedBoxes', JSON.stringify(checked));
};

// Load possible checkbox state
var restoreCheckboxState = function () {
    if (localStorage.getItem('checkedBoxes')) {
        var boxes = JSON.parse(localStorage.getItem('checkedBoxes'));
        for (box in boxes) {
            document.getElementById(box).checked = boxes[box];
        }
        document.getElementById('showCloudiness').dispatchEvent(new CustomEvent('click', null));
        localStorage.removeItem('checkedBoxes');
    }
};

// Returns the index of selected checkbox
var getCheckboxIndex = function (checkboxId) {
    var mapping = {};
    if (mode === 'all') {
        mapping = {'showTemperature': 0,
                   'showBrightness': 1,
                   'showFMITemperature': 2,
                   'showCloudiness': 3,
                   'showOutsideTemperature': 4};
    } else {
        mapping = {'showFMITemperature': 0,
                   'showCloudiness': 1,
                   'showOutsideTemperature': 2};
    }
    return mapping[checkboxId];
};

// Parses an observation. Returns Dygraph compatible data point.
// observation - JSON input
// yardcamImageNames - yardcam image names
// idArray - array with Testbed image lookup IDs
var parseData = function (observation, yardcamImageNames, idArray) {
    var dataPoint = null;
    if (mode === 'all') {
        dataPoint = [new Date(observation['recorded']),
                     observation['temperature'],
                     observation['brightness'],
                     observation['fmi_temperature'],
                     observation['cloudiness'],
                     observation['o_temperature']];
    } else {
        var date = observation['time'] ? observation['time'] : observation['recorded'];
        dataPoint = [new Date(date),
                     observation['fmi_temperature'],
                     observation['cloudiness'],
                     observation['o_temperature']];
    }
    yardcamImageNames.push(observation['yc_image_name']);
    idArray.push(observation['id']);

    return dataPoint;
};

// Transform data to Dygraph compatible format. Returns the data series labels.
// jsonData - JSON input data
// plotData - output array in Google compatible format
// yardcamImageNames - array with yardcam image names
// idArray - array with Testbed image lookup IDs
var transformData = function (jsonData, plotData, yardcamImageNames, idArray) {
    var observations = JSON.parse(jsonData),
        dataPoint = [],
        labels = null;

    // Data labels
    if (mode === 'all') {
        labels = ['Date', 'Temperature', 'Brightness',
                  'Temperature (FMI)', 'Cloudiness', 'Temperature (outside)'];
    } else {
        labels = ['Date', 'Temperature (FMI)', 'Cloudiness',
                  'Temperature (outside)'];
    }
    for (var i = 0; i < observations.length; i++) {
        plotData.push(parseData(observations[i], yardcamImageNames, idArray));
    }
    return labels;
};
var labels = null,
    plotData = [],
    yardcamImageNames = [],
    idArray = [];
labels = transformData(document.getElementById('plotData').innerHTML, plotData,
                       yardcamImageNames, idArray);

if (plotData.length === 0) {
    document.getElementById('noDataError').style.display = 'inline';
    document.getElementById('plotControls').style.display = 'none';
} else {
    // Function for showing the yardcam and testbed images for an index
    function showImages(dataIndex) {
        var imageName = yardcamImageNames[dataIndex],
            tbImageId = idArray[dataIndex];
        if (imageName) {
            var pattern = /yc-([\d-]+)T.+/,
                result = pattern.exec(imageName);
            if (result) {
                document.getElementById('yardcamImage').src = imageBasepath +
                    result[1] + '/' + imageName;
            }
        } else {
            alert('No yardcam image to show');
        }
        axios.head('tb-image/' + tbImageId)
            .then(function (response) {
                document.getElementById('testbedImage').src =
                    'tb-image/' + tbImageId;
            })
            .catch(function (error) {
                document.getElementById('testbedImage').src = '';
                if (error.response.status !== 404) {
                    alert('Error fetching the testbed image');
                }
            });
    }

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
                                    showImages(point.idx);
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
        plotData.push(parseData(JSON.parse(event.data)[0], yardcamImageNames, idArray));
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

var imageButtonHandler = function (event) {
    var images = document.getElementsByClassName('images'),
        display = document.getElementById('showImages').checked ? '' : 'none';

    for (var i = 0; i < images.length; i++) {
        images[i].style.display = display;
    }
};

document.getElementById('showImages').addEventListener('click',
                                                       imageButtonHandler,
                                                       false);
