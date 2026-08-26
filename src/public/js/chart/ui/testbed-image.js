import { getDateTime } from '../globals.js';
import { chartState } from '../state.js';
import { scrollToBottom } from './dom.js';

export const showTestbedImage = (pointDt) => {
  const pattern = /testbed-(.+).png/;
  const images = chartState.names.testbedImage;
  const imageCountIdx = images.length - 1;
  const DateTime = getDateTime();
  const refDt = DateTime.fromISO(pointDt.replace(' ', 'T'));
  let smallest = 100000;
  let smallestIdx = imageCountIdx;

  for (let i = imageCountIdx; i >= 0; i--) {
    const match = pattern.exec(images[i]);
    if (match) {
      const diff = Math.abs(refDt.diff(DateTime.fromISO(match[1]), 'minutes').minutes);
      if (diff <= smallest) {
        smallest = diff;
        smallestIdx = i;
      } else {
        break;
      }
    }
  }

  const imageName = images[smallestIdx];
  const datePattern = /testbed-([\d-]+)T.+/;
  const result = datePattern.exec(imageName);
  if (result) {
    document.getElementById('testbedImage').src =
      `${chartState.testbedImageBasepath}${result[1]}/${imageName}`;
    scrollToBottom(500);
  }
};

export const extractClickTimestamp = (params, xLabels, skipSeriesName = null) => {
  let ts = null;
  if (Array.isArray(params.value)) {
    ts = params.value[0];
  } else if (params.data && Array.isArray(params.data.value)) {
    ts = params.data.value[0];
  } else if (params.seriesName !== skipSeriesName
             && params.dataIndex != null
             && xLabels[params.dataIndex]) {
    ts = xLabels[params.dataIndex].getTime();
  }
  if (ts == null || Number.isNaN(ts)) {
    return null;
  }
  return ts;
};

export const createTestbedImageClickHandler = (getXLabels, skipSeriesName = null) =>
  (params) => {
    const ts = extractClickTimestamp(params, getXLabels(), skipSeriesName);
    if (ts == null) {
      return;
    }
    document.getElementById('showImages').checked = true;
    document.getElementById('imageDiv').classList.remove('display-none');
    const DateTime = getDateTime();
    const pointDt = DateTime.fromMillis(ts).toFormat("yyyy-MM-dd'T'HH:mm:ss");
    showTestbedImage(pointDt);
  };
