/** CDN globals from chart.html (luxon, echarts). Read at call time for testability. */

export const getLuxon = () => globalThis.luxon;

export const getDateTime = () => globalThis.luxon.DateTime;

export const getEcharts = () => globalThis.echarts;
