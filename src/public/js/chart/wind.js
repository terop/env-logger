export const degreesToCompassShort = (deg) => {
  if (deg == null || Number.isNaN(deg)) {
    return '?';
  }
  if (deg >= 0 && deg < 25) {
    return 'N';
  }
  if (deg >= 25 && deg < 65) {
    return 'NE';
  }
  if (deg >= 65 && deg < 115) {
    return 'E';
  }
  if (deg >= 115 && deg < 155) {
    return 'SE';
  }
  if (deg >= 155 && deg < 205) {
    return 'S';
  }
  if (deg >= 205 && deg < 245) {
    return 'SW';
  }
  if (deg >= 245 && deg < 295) {
    return 'W';
  }
  if (deg >= 295 && deg < 335) {
    return 'NW';
  }
  if (deg >= 335 && deg <= 360) {
    return 'N';
  }
  return '?';
};

export const windFlowAngle = (fromDeg) => (fromDeg + 180) % 360;

export const buildWindArrowPoints = (xValues, windDirections, windSpeeds, pointCount) => {
  const calmWindSpeed = 0.5;
  const windArrowY = 0.5;
  const windArrowMaxCount = 90;

  if (!windDirections || !windSpeeds || !xValues.length) {
    return { points: [], symbolSize: 14 };
  }

  const arrowStep = Math.max(1, Math.ceil(pointCount / windArrowMaxCount));
  const symbolSize = arrowStep === 1 ? 14 : arrowStep === 2 ? 12 : 10;
  const points = [];

  for (let i = 0; i < xValues.length; i++) {
    const dir = windDirections[i];
    const speed = windSpeeds[i];
    if (i % arrowStep !== 0
        || dir == null
        || speed == null
        || speed < calmWindSpeed) {
      continue;
    }

    points.push({
      value: [xValues[i].getTime(), windArrowY],
      symbolRotate: windFlowAngle(dir),
      label: `${degreesToCompassShort(dir)} (${dir}\u00b0)`
    });
  }

  return { points, symbolSize };
};
