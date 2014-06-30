$(function () {
	// Arrays for data
	var temperatureData = [],
		brightnessData = [];
	// Graph objects
	var temperatureGraph = null,
		brightnessGraph = null;

	// Transform data to Rickshaw presentable format
	var transformData = function (data, variable) {
		var dataJson = JSON.parse(data),
			dataArray = null;
		if (variable === 'temperature') {
			dataArray = temperatureData;
		} else if (variable === 'brightness') {
			dataArray = brightnessData;
		}

		for (var i = 0; i < dataJson.length; i++) {
			dataArray.push({
				x: Date.parse(dataJson[i]['recorded']) / 1000.0,
				y: dataJson[i][variable]
			});
		}
	};

	// Creates the temperature graph
	var createTemperatureGraph = function () {
		temperatureGraph = new Rickshaw.Graph({
			element: document.querySelector('#tempChart'),
			width: 1000,
			height: 300,
			renderer: 'line',
			series: [{
				data: temperatureData,
				color: '#9cc1e0',
				name: 'Temperature'
			}]
		});
		temperatureGraph.render();

		var hoverDetail = new Rickshaw.Graph.HoverDetail({
			graph: temperatureGraph
		});

		var xAxis = new Rickshaw.Graph.Axis.Time({
			graph: temperatureGraph,
			timeUnit: new Rickshaw.Fixtures.Time().unit('day'),
			timeFixture: new Rickshaw.Fixtures.Time()
		});
		xAxis.render();

		var yAxis = new Rickshaw.Graph.Axis.Y({
		    graph: temperatureGraph,
		    tickFormat: Rickshaw.Fixtures.Number.formatKMBT
		    //orientation: 'left'
		});
		yAxis.render();
	};

	// Creates the brightness graph
	var createBrightnessGraph = function () {
		brightnessGraph = new Rickshaw.Graph({
			element: document.querySelector('#brightnessChart'),
			width: 1000,
			height: 400,
			renderer: 'line',
			series: [{
				data: brightnessData,
				color: '#9cc1e0',
				name: 'Brightness'
			}]
		});
		brightnessGraph.render();

		var hoverDetail = new Rickshaw.Graph.HoverDetail({
			graph: brightnessGraph
		});

		var xAxis = new Rickshaw.Graph.Axis.Time({
			graph: brightnessGraph,
			timeUnit: new Rickshaw.Fixtures.Time().unit('day'),
			timeFixture: new Rickshaw.Fixtures.Time()
		});
		xAxis.render();

		var yAxis = new Rickshaw.Graph.Axis.Y({
		    graph: brightnessGraph,
		    tickFormat: Rickshaw.Fixtures.Number.formatKMBT
		    //orientation: 'left'
		});
		yAxis.render();
	};

	// Parse initial data and create graph
	transformData($('#plotData').text(), 'temperature');
	transformData($('#plotData').text(), 'brightness');
	createTemperatureGraph();
	createBrightnessGraph();
});
