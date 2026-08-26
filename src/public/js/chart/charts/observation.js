import {
  WIND_DIRECTION_SERIES,
  fieldNames
} from '../constants.js';
import { chartState } from '../state.js';
import { addUnitSuffix } from '../data/labels.js';
import { formatNiceAxisLabel, yRangeFromVisibleSeries } from '../echarts/axis.js';
import { buildObsBottomLayout, buildXyDataZoom } from '../echarts/layout.js';
import {
  buildDayMarkLineSeries,
  buildDayMarkLines
} from '../echarts/mark-lines.js';
import {
  hasTooltipPointData,
  tooltipPointValue
} from '../echarts/tooltips.js';
import { getDateTime } from '../globals.js';
import { buildWindArrowPoints } from '../wind.js';
import {
  getSeriesData,
  getSeriesNames,
  weatherSeriesNames
} from './series-meta.js';

export const buildWeatherEchartsOption = (legendSelected = null) => {
  const windArrowBandTop = 48;
  const windArrowBandHeight = 56;
  const windArrowPath = 'path://M0,-14 L1.4,6 L0,2 L-1.4,6 Z';

  const xValues = chartState.dataLabels.weather;
  const pointCount = xValues.length;
  const showMarkers = pointCount <= 500;
  const selected = legendSelected || Object.fromEntries(
    weatherSeriesNames().map((name) => [name, true])
  );

  const visibleSeriesData = [];
  for (const key of fieldNames.weather) {
    const name = chartState.labelValues.weather[key];
    if (selected[name] !== false) {
      visibleSeriesData.push(chartState.dataSets.weather[key]);
    }
  }

  const { yMin, yMax, yInterval } = yRangeFromVisibleSeries(visibleSeriesData);
  const xMin = xValues[0].getTime();
  const xMax = xValues[xValues.length - 1].getTime();
  const mainGridTop = windArrowBandTop + windArrowBandHeight + 8;

  const dayMarkLines = buildDayMarkLines(
    chartState.annotationIndices.weather,
    yMin,
    yMax,
    { skipFirst: true, xLabels: xValues }
  );

  const series = [
    buildDayMarkLineSeries(dayMarkLines, {
      xAxisIndex: 1,
      yAxisIndex: 1
    }),
    ...fieldNames.weather.map((key) => {
      const name = chartState.labelValues.weather[key];
      return {
        name,
        type: 'line',
        xAxisIndex: 1,
        yAxisIndex: 1,
        showSymbol: showMarkers,
        symbolSize: 2,
        triggerLineEvent: true,
        data: xValues.map((dt, i) => [dt.getTime(), chartState.dataSets.weather[key][i]])
      };
    })
  ];

  const { points: windPoints, symbolSize } = buildWindArrowPoints(
    xValues,
    chartState.dataSets.weather['wind-direction'],
    chartState.dataSets.weather['wind-speed'],
    pointCount
  );

  if (windPoints.length) {
    series.push({
      name: WIND_DIRECTION_SERIES,
      type: 'scatter',
      xAxisIndex: 0,
      yAxisIndex: 0,
      symbol: windArrowPath,
      symbolSize,
      clip: false,
      itemStyle: { color: '#838383' },
      data: windPoints,
      z: 10
    });
  }

  const weatherNames = weatherSeriesNames();
  const bottomLayout = buildObsBottomLayout(weatherNames.length);
  const DateTime = getDateTime();

  return {
    title: {
      text: 'FMI weather observations',
      left: 'center'
    },
    legend: {
      ...bottomLayout.legend,
      selected,
      symbolRotate: 0,
      symbolKeepAspect: true,
      itemWidth: 12,
      itemHeight: 12,
      data: weatherNames.map((name) => (
        name === WIND_DIRECTION_SERIES
          ? { name, icon: 'triangle' }
          : name
      ))
    },
    grid: [
      {
        left: 60,
        right: 30,
        top: windArrowBandTop,
        height: windArrowBandHeight
      },
      {
        left: 60,
        right: 30,
        top: mainGridTop,
        bottom: bottomLayout.gridBottom
      }
    ],
    dataZoom: buildXyDataZoom({
      xAxisIndex: [0, 1],
      yAxisIndex: 1,
      sliderBottom: bottomLayout.sliderBottom
    }),
    axisPointer: {
      link: [{ xAxisIndex: 'all' }]
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      formatter: (params) => {
        if (!params || !params.length) {
          return '';
        }
        const ts = DateTime.fromMillis(params[0].axisValue)
          .toFormat('dd.MM. HH:mm:ss');
        let html = `<b>${ts}</b>`;
        for (const p of params) {
          if (p.seriesName === WIND_DIRECTION_SERIES) {
            const label = p.data && p.data.label ? p.data.label : '';
            if (!label) {
              continue;
            }
            html += `<br/>${p.marker}${p.seriesName}: ${label}`;
          } else {
            const y = tooltipPointValue(p);
            if (!hasTooltipPointData(y)) {
              continue;
            }
            const unit = addUnitSuffix(p.seriesName);
            html += `<br/>${p.marker}${p.seriesName}: ${y}${unit}`;
          }
        }
        return html;
      }
    },
    xAxis: [
      {
        type: 'time',
        gridIndex: 0,
        min: xMin,
        max: xMax,
        show: false
      },
      {
        type: 'time',
        gridIndex: 1,
        name: 'Time',
        nameLocation: 'middle',
        nameGap: 30,
        min: xMin,
        max: xMax,
        axisLabel: {
          hideOverlap: true,
          formatter: (value) =>
            DateTime.fromMillis(value).toFormat('dd.MM. HH:mm')
        }
      }
    ],
    yAxis: [
      {
        type: 'value',
        gridIndex: 0,
        min: 0,
        max: 1,
        show: false
      },
      {
        type: 'value',
        gridIndex: 1,
        name: 'Value',
        min: yMin,
        max: yMax,
        interval: yInterval,
        scale: false,
        axisLabel: {
          formatter: formatNiceAxisLabel
        }
      }
    ],
    series
  };
};

export const buildObsEchartsOption = (plotType, legendSelected = null) => {
  const isRuuvitag = plotType === 'ruuvitag';
  const xValues = isRuuvitag
    ? chartState.dataLabels.rt
    : chartState.dataLabels.other;
  const pointCount = xValues.length;
  const showMarkers = pointCount <= 500;
  const seriesNameList = getSeriesNames(plotType);
  const selected = legendSelected || Object.fromEntries(
    seriesNameList.map((name) => [name, !isRuuvitag])
  );

  const visibleSeriesData = [];
  for (const seriesName of seriesNameList) {
    if (selected[seriesName] === false) {
      continue;
    }
    const seriesData = getSeriesData(plotType, seriesName);
    if (seriesData) {
      visibleSeriesData.push(seriesData);
    }
  }

  const { yMin, yMax, yInterval } = yRangeFromVisibleSeries(visibleSeriesData);
  const xMin = xValues[0].getTime();
  const xMax = xValues[xValues.length - 1].getTime();
  const dayMarkLines = buildDayMarkLines(
    chartState.annotationIndices.other,
    yMin,
    yMax,
    { skipFirst: true, xLabels: xValues }
  );

  const series = [buildDayMarkLineSeries(dayMarkLines)];
  if (!isRuuvitag) {
    for (const key of fieldNames.other) {
      const name = chartState.labelValues.other[key];
      series.push({
        name,
        type: 'line',
        showSymbol: showMarkers,
        symbolSize: 3,
        triggerLineEvent: true,
        data: xValues.map((dt, idx) => [
          dt.getTime(),
          chartState.dataSets.other[key][idx]
        ])
      });
    }
  } else {
    for (const tagName of chartState.names.ruuvitag) {
      for (const meas of ['temperature', 'humidity']) {
        const name = chartState.labelValues.rt[tagName][meas];
        series.push({
          name,
          type: 'line',
          showSymbol: showMarkers,
          symbolSize: 3,
          triggerLineEvent: true,
          data: xValues.map((dt, idx) => [
            dt.getTime(),
            chartState.dataSets.rt[tagName][meas][idx]
          ])
        });
      }
    }
  }

  const bottomLayout = buildObsBottomLayout(seriesNameList.length);
  const DateTime = getDateTime();

  return {
    title: {
      text: `${isRuuvitag ? 'Ruuvitag' : 'Other'} observations`,
      left: 'center'
    },
    legend: {
      ...bottomLayout.legend,
      selected,
      data: seriesNameList
    },
    grid: {
      left: 60,
      right: 30,
      top: 50,
      bottom: bottomLayout.gridBottom
    },
    dataZoom: buildXyDataZoom({
      xAxisIndex: 0,
      yAxisIndex: 0,
      sliderBottom: bottomLayout.sliderBottom
    }),
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      formatter: (params) => {
        if (!params || !params.length) {
          return '';
        }
        const chart = chartState.charts[plotType]?.getInstance();
        const liveSelected = chart?.getOption()?.legend?.[0]?.selected;
        const ts = DateTime.fromMillis(params[0].axisValue)
          .toFormat('dd.MM. HH:mm:ss');
        let html = `<b>${ts}</b>`;
        for (const p of params) {
          if (liveSelected && liveSelected[p.seriesName] === false) {
            continue;
          }
          const y = tooltipPointValue(p);
          if (!hasTooltipPointData(y)) {
            continue;
          }
          const unit = addUnitSuffix(p.seriesName);
          html += `<br/>${p.marker}${p.seriesName}: ${y}${unit}`;
        }
        return html;
      }
    },
    xAxis: {
      type: 'time',
      name: 'Time',
      nameLocation: 'middle',
      nameGap: 30,
      min: xMin,
      max: xMax,
      axisLabel: {
        hideOverlap: true,
        formatter: (value) =>
          DateTime.fromMillis(value).toFormat('dd.MM. HH:mm')
      }
    },
    yAxis: {
      type: 'value',
      name: 'Value',
      min: yMin,
      max: yMax,
      interval: yInterval,
      scale: false,
      axisLabel: {
        formatter: formatNiceAxisLabel
      }
    },
    series
  };
};
