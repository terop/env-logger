
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

// Returns indices of selected checkboxes
var getCheckboxIndices = function () {
    var mapping = {};
    if (mode === 'all') {
        mapping = {'showTemperature': 1,
                   'showBrightness': 2,
                   'showOutsideTemperature': 3,
                   'showCloudiness': 4};
    } else {
        mapping = {'showOutsideTemperature': 1,
                   'showCloudiness': 2};
    }
    var shownColumns = [0];

    var keys = Object.keys(mapping);
    for (var i = 0; i < keys.length; i++) {
        if (document.getElementById(keys[i]).checked) {
            shownColumns.push(mapping[keys[i]]);
        }
    }
    return shownColumns;
};

// Transform data to Google Chart compatible format
// jsonData - JSON input data
// plotData - output array in Google compatible format
// imageData - array with yardcam image names
var transformData = function (jsonData, plotData, imageData) {
    var dataJson = JSON.parse(jsonData),
        dataPoint = [];

    // Data labels
    if (mode === 'all') {
        plotData.push(['Date', 'Temperature [\xB0C]', 'Brightness',
                       'Temperature (outside) [\xB0C]', 'Cloudiness']);
    } else {
        plotData.push(['Date', 'Temperature (outside) [\xB0C]', 'Cloudiness']);
    }
    for (var i = 0; i < dataJson.length; i++) {
        if (mode === 'all') {
            dataPoint = [new Date(dataJson[i]['recorded']),
                         dataJson[i]['temperature'],
                         dataJson[i]['brightness'],
                         dataJson[i]['o_temperature'],
                         dataJson[i]['cloudiness']];
        } else {
            dataPoint = [new Date(dataJson[i]['time']),
                         dataJson[i]['o_temperature'],
                         dataJson[i]['cloudiness']];
        }
        plotData.push(dataPoint);
        imageData.push(dataJson[i]['yc_image_name']);
    }
};
var plotData = [],
    imageData = [];
transformData(document.getElementById('plotData').innerHTML, plotData, imageData);

google.charts.load('current', {'packages': ['corechart']});
google.charts.setOnLoadCallback(drawChart);

function drawChart() {
    var options = {
        width: 900,
        height: 450,
        chartArea: {
            width: 800
        },
        hAxis: {
            gridlines: {
                count: -1,
                units: {
                    days: {format: ['d.M.yyyy']},
                    hours: {format: ['HH:mm']}
                }
            },
            minorGridlines: {
                units: {
                    hours: {format: ['hh:mm']},
                    minutes: {format: ['HH:mm']}
                }
            }
        },
        title: 'Observations',
        legend: { position: 'bottom' },
        explorer: {
            actions: ['dragToZoom', 'rightClickToReset'],
            keepInBounds: true,
            axis: 'horizontal'
        }
    },
    dateFormatter = new google.visualization.DateFormat({
        pattern: 'd.M.yyyy HH:mm:ss'
    }),
    data = google.visualization.arrayToDataTable(
        plotData,
        false);

    dateFormatter.format(data, 0);
    var chart = new google.visualization.LineChart(document.getElementById('chartDiv'));
    chart.draw(data, options);

    google.visualization.events.addListener(chart, 'select', selectHandler);

    function selectHandler(e) {
        var selectedItem = chart.getSelection()[0],
            imageName = imageData[selectedItem.row];
        if (imageName) {
            var pattern = /yc-([\d-]+)T.+/,
                result = pattern.exec(imageName);
            if (result) {
                document.getElementById('yardcamImage').src = imageBasepath +
                    result[1] + '/' + imageName;
            }
        } else {
            alert('There is no image to show');
        }
    }

    // Hides or shows the selected data series
    var hideOrShowSeries = function (event) {
        columnIndices = getCheckboxIndices();

        if (columnIndices.length == 1) {
            alert('At least one data series must be selected');
            return;
        }

        view = new google.visualization.DataView(data);
        view.setColumns(columnIndices);
        chart.draw(view, options);
    };

    var checkboxes = document.getElementsByClassName('selectBox');
    for (var i = 0; i < checkboxes.length; i++) {
        checkboxes[i].addEventListener('click', hideOrShowSeries);
    }
    restoreCheckboxState();

    // Redraw chart after new data has been added
    var redrawChart = function () {
        var data = google.visualization.arrayToDataTable(
            plotData,
            false);
        dateFormatter.format(data, 0);

        view = new google.visualization.DataView(data);
        view.setColumns(getCheckboxIndices());
        chart.draw(view, options);
    };

    // Various WebSocket operations
    var wsOperations = function () {
        socket.onerror = function(error) {
            console.log('WebSocket error: ' + error);
        };

        socket.onopen = function(event) {
            console.log('WebSocket connected to: ' + event.currentTarget.url);
        };

        socket.onmessage = function(event) {
            var dataJson = JSON.parse(event.data),
            dataPoint = null;
            if (mode === 'all') {
                dataPoint = [new Date(dataJson[0]['recorded']),
                             dataJson[0]['temperature'],
                             dataJson[0]['brightness'],
                             dataJson[0]['o_temperature'],
                             dataJson[0]['cloudiness']];
            } else {
                dataPoint = [new Date(dataJson[0]['recorded']),
                             dataJson[0]['o_temperature'],
                             dataJson[0]['cloudiness']];
            }
            plotData.push(dataPoint);
            redrawChart();
        };

        socket.onclose = function(event) {
            console.log('WebSocket disconnected: ' + event.code + ', reason ' + event.reason);
            socket = undefined;
        };
    };
    wsOperations();
}
if (plotData.length === 0) {
    document.getElementById('noDataError').style.display = 'inline';
    document.getElementById('plotControls').style.display = 'none';
}

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
    document.getElementById('yardcamImage').style.display =
        document.getElementById('showImage').checked ? '' : 'none';
};

document.getElementById('showImage').addEventListener('click',
                                                      imageButtonHandler,
                                                      false);
