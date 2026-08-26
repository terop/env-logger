export const chartState = {
  data: {
    other: null,
    rt: null,
    weather: null,
    weatherObs: null
  },
  dataSets: {
    weather: {},
    other: {},
    rt: {}
  },
  dataLabels: {
    weather: [],
    other: [],
    rt: []
  },
  labelValues: {
    weather: {},
    other: {},
    rt: {}
  },
  annotationIndices: {
    weather: [],
    other: []
  },
  names: {
    bleBeacon: null,
    testbedImage: null,
    ruuvitag: null
  },
  charts: {},
  elec: {
    thresholds: {},
    minuteCache: {},
    hourCache: { xValues: [], prices: [] },
    minutePriceCache: { xValues: [], prices: [] },
    colourRefreshIntervals: []
  },
  displayResolution: null,
  maxDisplayDays: 90,
  testbedImageBasepath: ''
};
