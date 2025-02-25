#include <Wire.h>
#include <Digital_Light_TSL2561.h>
#include <SensirionI2cScd4x.h>
#include <SensirionI2CSgp41.h>
#include "sensirion_gas_index_algorithm.h"

#include <WiFi.h>
#include <WebServer.h>

#include <ArduinoJson.h>

#include "arduino_secrets.h"

/*
 * Required libraries:
 * ArduinoJson
 * https://github.com/Sensirion/arduino-core
 * https://github.com/Sensirion/arduino-i2c-scd4x
 * https://github.com/Sensirion/arduino-i2c-sgp41
 * https://github.com/Seeed-Studio/Grove_Digital_Light_Sensor
 *
 * sensirion_gas_index_algorithm.{c,h} files need to be placed into this
 * directory to compile this sketch. They can be downloaded from
 * https://github.com/Sensirion/gas-index-algorithm.
 */

const uint16_t loopDelaySeconds = 3;

// Network SSID
char ssid[] = SECRET_SSID;
// Network password
char password[] = SECRET_PASSWORD;

WebServer server(80);

SensirionI2cScd4x scd41;
SensirionI2CSgp41 sgp41;

const uint16_t sgp41DefaultRh = 0x8000;
const uint16_t sgp41DefaultT = 0x6666;

GasIndexAlgorithmParams vocParams;
GasIndexAlgorithmParams noxParams;

float latestTemperature;
float latestHumidity;
uint16_t latestCo2;
int32_t latestVocIndex;
int32_t latestNoxIndex;

void printNetworkData() {
  Serial.println("Board information:");
  IPAddress ip = WiFi.localIP();
  Serial.print("IP Address: ");
  Serial.println(ip);

  Serial.println();
  Serial.println("Network information:");
  Serial.print("SSID: ");
  Serial.println(WiFi.SSID());

  long rssi = WiFi.RSSI();
  Serial.print("Signal strength (RSSI): ");
  Serial.println(rssi);
}

void connectToWifi() {
  Serial.print("Connecting to WiFi network: ");
  Serial.println(ssid);

  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(2000);
    Serial.println("Connecting");
  }

  Serial.println("Connected to the WiFi network");
  printNetworkData();
}

void reconnectToWifi() {
  if (WiFi.status() != WL_CONNECTED) {
    int connectionAttempts = 15;

    Serial.println("WiFi connection dropped, attempting to reconnect");

    WiFi.begin(ssid, password);

    while (connectionAttempts && WiFi.status() != WL_CONNECTED) {
      delay(2000);
      Serial.println("Connecting");
      connectionAttempts--;
    }
    if (!connectionAttempts) {
      Serial.println("Failed to connect, restarting board");
      ESP.restart();
    }

    Serial.println("Connected");
    printNetworkData();
  }
}

void calculateCompensationValues(float temperature, float humidity,
                                 uint16_t &compTemperature, uint16_t &compHumidity) {
  compHumidity = (uint16_t)lround(humidity * 65535 / 100);
  compTemperature = (uint16_t)lround((temperature + 45) * 65535 / 175);
}

uint16_t readScd41Values(float &temperature, float &humidity, uint16_t &co2) {
  uint16_t error;
  bool isDataReady = false;
  char errorMessage[256];

  error = scd41.getDataReadyStatus(isDataReady);
  if (error) {
    Serial.print("SCD41: error trying to execute getDataReadyStatus(): ");
    errorToString(error, errorMessage, sizeof errorMessage);
    Serial.println(errorMessage);
  }

  if (!error) {
    while (!isDataReady) {
      delay(100);
      error = scd41.getDataReadyStatus(isDataReady);
      if (error) {
        Serial.print("SCD41: error trying to execute getDataReadyStatus(): ");
        errorToString(error, errorMessage, sizeof errorMessage);
        Serial.println(errorMessage);
      }
    }

    error = scd41.readMeasurement(co2, temperature, humidity);
    if (error) {
      Serial.print("SCD41: error trying to execute readMeasurement(): ");
      errorToString(error, errorMessage, sizeof errorMessage);
      Serial.println(errorMessage);
    }
  }

  return error;
}

void readSgp41Values() {
  char errorMessage[256];

  uint16_t scd41_error = 0;
  uint16_t co2 = 0;
  float temperature = 0.0;
  float humidity = 0.0;
  uint16_t compHumidity = sgp41DefaultRh;
  uint16_t compTemperature = sgp41DefaultT;

  scd41_error = readScd41Values(temperature, humidity, co2);

  if (scd41_error) {
    latestTemperature = 0;
    latestHumidity = 0;
    latestCo2 = 0;
  } else {
    calculateCompensationValues(temperature, humidity,
                                compTemperature, compHumidity);

    latestTemperature = temperature;
    latestHumidity = humidity;
    latestCo2 = co2;
  }

  // SGP41 reading
  uint16_t sgp41_error;
  uint16_t rawVoc = 0;
  uint16_t rawNox = 0;
  int32_t vocIndexValue = 0;
  int32_t noxIndexValue = 0;

  sgp41_error = sgp41.measureRawSignals(compHumidity, compTemperature, rawVoc, rawNox);

  if (sgp41_error) {
    Serial.print("SGP41: error trying to execute measureRawSignals(): ");
    errorToString(sgp41_error, errorMessage, 256);
    Serial.println(errorMessage);
  } else {
    GasIndexAlgorithm_process(&vocParams, rawVoc, &vocIndexValue);
    GasIndexAlgorithm_process(&noxParams, rawNox, &noxIndexValue);
  }

  latestVocIndex = vocIndexValue;
  latestNoxIndex = noxIndexValue;
}

// Reads the sensors and returns values as JSON
String readSensors() {
  JsonDocument doc;
  String output;

  doc["light"] = TSL2561.readVisibleLux();

  doc["temperature"] = latestTemperature;
  doc["humidity"] = latestHumidity;
  doc["co2"] = latestCo2;
  doc["vocIndex"] = latestVocIndex;
  doc["noxIndex"] = latestNoxIndex;

  serializeJson(doc, output);

  return output;
}

void sendSensorData() {
  server.send(200, "application/json", readSensors());
}

void setup() {
  Serial.begin(115200);

  Wire.begin();
  TSL2561.init();

  uint16_t error;
  char errorMessage[256];

  // SCD41 setup
  scd41.begin(Wire, SCD41_I2C_ADDR_62);

  delay(30);
  // Ensure sensor is in clean state
  error = scd41.wakeUp();
  if (error) {
    Serial.print("Error trying to execute wakeUp(): ");
    errorToString(error, errorMessage, sizeof errorMessage);
    Serial.println(errorMessage);
  }

  error = scd41.stopPeriodicMeasurement();
  if (error) {
    Serial.print("Error trying to execute stopPeriodicMeasurement(): ");
    errorToString(error, errorMessage, sizeof errorMessage);
    Serial.println(errorMessage);
  }

  error = scd41.reinit();
  if (error) {
    Serial.print("Error trying to execute reinit(): ");
    errorToString(error, errorMessage, sizeof errorMessage);
    Serial.println(errorMessage);
  }

  error = scd41.startPeriodicMeasurement();
  if (error) {
    Serial.print("Error trying to execute startPeriodicMeasurement(): ");
    errorToString(error, errorMessage, sizeof errorMessage);
    Serial.println(errorMessage);
    return;
  }

  // SGP41 setup
  sgp41.begin(Wire);

  uint16_t testResult;
  error = sgp41.executeSelfTest(testResult);
  if (error) {
    Serial.print("Error trying to execute executeSelfTest(): ");
    errorToString(error, errorMessage, 256);
    Serial.println(errorMessage);
  } else if (testResult != 0xD400) {
    Serial.print("executeSelfTest failed with error: ");
    Serial.println(testResult);
  }

  // Time in seconds needed for SGP41 NOx conditioning
  uint16_t sgp41ConditioningTime = 9;
  uint16_t rawVoc;

  float temperature;
  float humidity;
  uint16_t co2;
  uint16_t compTemperature = sgp41DefaultT;
  uint16_t compHumidity = sgp41DefaultRh;

  error = readScd41Values(temperature, humidity, co2);
  if (!error) {
    calculateCompensationValues(temperature, humidity,
                                compTemperature, compHumidity);
  }

  Serial.println("Starting SGP41 conditioning");
  while (sgp41ConditioningTime > 0) {
    // During NOx conditioning NOx will remain zero
    error = sgp41.executeConditioning(compHumidity, compTemperature, rawVoc);

    if (error) {
      Serial.print("Error executing conditioning: ");
      errorToString(error, errorMessage, 256);
      Serial.println(errorMessage);
    }

    delay(1000);
    sgp41ConditioningTime--;
  }
  Serial.println("SGP41 conditioning ready");

  GasIndexAlgorithm_init_with_sampling_interval(&vocParams,
                                                GasIndexAlgorithm_ALGORITHM_TYPE_VOC,
                                                loopDelaySeconds);
  GasIndexAlgorithm_init_with_sampling_interval(&noxParams,
                                                GasIndexAlgorithm_ALGORITHM_TYPE_NOX,
                                                loopDelaySeconds);

  // WiFi setup
  connectToWifi();

  server.on("/", sendSensorData);
  server.begin();
  Serial.println("HTTP server started");
}

void loop() {
  reconnectToWifi();

  // Only enable the line below when debugging sensor output as it interferes
  // with SCD41 sensor data value transmission over HTTP
  // Serial.println(readSensors());

  server.handleClient();

  readSgp41Values();

  // Use a more than one second delay to get correct values from the SCD41 sensor
  delay(loopDelaySeconds * 1000);
}
