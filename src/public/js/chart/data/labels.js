export const addUnitSuffix = (keyName) => {
  keyName = keyName.toLowerCase();
  return `${keyName.includes('temperature') ? ' \u2103' : ''}` +
    `${keyName.includes('wind') ? ' m/s' : ''}` +
    `${keyName.includes('humidity') ? ' %H' : ''}` +
    `${keyName.includes('rssi') ? ' dBm' : ''}` +
    `${keyName.includes('battery') ? ' %' : ''}` +
    `${keyName.includes('precipitation') ? ' mm' : ''}` +
    `${keyName.includes('light') ? ' lux' : ''}` +
    `${keyName.includes('co2') || keyName.includes('co\u2082') ? ' ppm' : ''}` +
    `${keyName.includes('pm 2') || keyName.includes('pm-2') ? ' \u00b5g/m\u00b3' : ''}`;
};

export const lowerFL = (str) => str.charAt(0).toLowerCase() + str.slice(1);
