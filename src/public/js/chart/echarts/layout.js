export const buildXyDataZoom = ({
  xAxisIndex = 0,
  yAxisIndex = 0,
  sliderBottom = 36
} = {}) => [
  {
    type: 'inside',
    xAxisIndex,
    filterMode: 'none'
  },
  {
    type: 'inside',
    yAxisIndex,
    filterMode: 'none'
  },
  {
    type: 'slider',
    xAxisIndex,
    height: 22,
    bottom: sliderBottom,
    filterMode: 'none'
  }
];

export const buildObsBottomLayout = (seriesCount, {
  chartWidth = 1300,
  avgItemWidth = 170
} = {}) => {
  const usableWidth = chartWidth * 0.9;
  const itemsPerRow = Math.max(1, Math.floor(usableWidth / avgItemWidth));
  const legendRows = Math.max(1, Math.ceil(seriesCount / itemsPerRow));
  const legendRowHeight = 22;
  const legendBottom = 4;
  const legendHeight = legendRows * legendRowHeight;
  const sliderHeight = 22;
  const sliderGap = 10;
  const sliderBottom = legendBottom + legendHeight + sliderGap;
  const axisLabelGap = 50;
  const gridBottom = sliderBottom + sliderHeight + axisLabelGap;

  return {
    legend: {
      type: 'plain',
      orient: 'horizontal',
      left: 'center',
      bottom: legendBottom,
      width: '90%'
    },
    sliderBottom,
    gridBottom
  };
};
