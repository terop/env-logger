
// Transform data to Dygraphs compatible format
var transformData = function (data) {
    var dataJson = JSON.parse(data),
        plotData = [],
        dataPoint = [];

    for (var i = 0; i < dataJson.length; i++) {
        dataPoint = [new Date(dataJson[i]['recorded']),
                     dataJson[i]['temperature'],
                     dataJson[i]['brightness'],
                     dataJson[i]['o_temperature'],
                     dataJson[i]['cloudiness']];
        plotData.push(dataPoint);
    }
    return plotData;
};
var plotData = transformData(document.getElementById('plotData').innerHTML);

if (plotData.length > 0) {
    var g = new Dygraph(
        document.getElementById('tempChart'),
        plotData,
        {
            labels: ['Date', 'Temperature [\xB0C]', 'Brightness',
                     'Temperature (outside) [\xB0C]', 'Cloudiness'],
            title: 'Observations',
            labelsDiv: 'legendDiv',
            labelsSeparateLines: true
        });


    // Hides or shows the selected data series
    var hideOrShowSeries = function (event) {
        var mapping = {'showTemperature': 0,
                       'showBrightness': 1,
                       'showOutsideTemperature': 2,
                       'showCloudiness': 3};

        var keys = Object.keys(mapping);
        for (var i = 0; i < keys.length; i++) {
            g.setVisibility(mapping[keys[i]],
                            document.getElementById(keys[i]).checked);
        }
    };

    var checkboxes = document.getElementsByClassName('selectBox');
    for (var i = 0; i < checkboxes.length; i++) {
        checkboxes[i].addEventListener('click', hideOrShowSeries);
    }
} else {
    document.getElementById('noDataError').style.display = 'inline';
    document.getElementById('plotControls').style.display = 'none';
}
