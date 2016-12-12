
// Transform data to Dygraphs compatible format
var transformData = function (data) {
    var dataJson = JSON.parse(data),
        plotData = [],
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
    }
    return plotData;
};
var plotData = transformData(document.getElementById('plotData').innerHTML);

if (plotData.length > 1) {
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
            data = google.visualization.arrayToDataTable(
                plotData,
                false),
            dateFormatter = new google.visualization.DateFormat({
                pattern: 'd.M.yyyy HH:mm:ss'
            });

        dateFormatter.format(data, 0);
        var chart = new google.visualization.LineChart(document.getElementById('chartDiv'));
        chart.draw(data, options);

        // Hides or shows the selected data series
        var hideOrShowSeries = function (event) {
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

            if (shownColumns.length == 1) {
                alert('At least one data series must be selected');
                return;
            }

            view = new google.visualization.DataView(data);
            view.setColumns(shownColumns);
            chart.draw(view, options);
        };

        var checkboxes = document.getElementsByClassName('selectBox');
        for (var i = 0; i < checkboxes.length; i++) {
            checkboxes[i].addEventListener('click', hideOrShowSeries);
        }
    }
} else {
    document.getElementById('noDataError').style.display = 'inline';
    document.getElementById('plotControls').style.display = 'none';
}
