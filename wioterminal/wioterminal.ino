#include <Wire.h>
#include <SensirionI2CScd4x.h>

#include <ArduinoJson.h>

/*
 * Required libraries:
 * ArduinoJson
 * https://github.com/Sensirion/arduino-core
 * https://github.com/Sensirion/arduino-i2c-scd4x
 */

SensirionI2CScd4x scd4x;

const int ledPin = 13;

// Reads the sensors and returns values as JSON
String readSensors() {
  JsonDocument doc;
  String output;

  digitalWrite(ledPin, 1);

  // Light measurement
  const uint8_t lightMeasCount = 5;
  int lightValuesSum = 0;

  for (uint8_t i = 0; i < lightMeasCount; i++) {
    delay(50);
    lightValuesSum += analogRead(A0);
  }
  int lightValue = round((float)lightValuesSum / lightMeasCount);

  doc["light"] = lightValue;

  // Other measurements
  uint16_t error;
  char errorMessage[256];

  uint16_t co2 = 0;
  float temperature = 0.0f;
  float humidity = 0.0f;
  bool isDataReady = false;
  bool isInvalidMeasurement = false;

  error = scd4x.getDataReadyFlag(isDataReady);
  if (error) {
    Serial.print("Error trying to execute getDataReadyFlag(): ");
    errorToString(error, errorMessage, 256);
    Serial.println(errorMessage);
  }
  if (isDataReady) {
    error = scd4x.readMeasurement(co2, temperature, humidity);
    if (error) {
      Serial.print("Error trying to execute readMeasurement(): ");
      errorToString(error, errorMessage, 256);
      Serial.println(errorMessage);
    } else if (co2 == 0) {
      Serial.println("Invalid sample detected, skipping");
      isInvalidMeasurement = true;
    }
  }

  if (error || isInvalidMeasurement) {
    doc["temperature"] = 0;
    doc["humidity"] = 0;
    doc["co2"] = 0;
  } else {
    doc["temperature"] = temperature;
    doc["humidity"] = humidity;
    doc["co2"] = co2;
  }

  serializeJson(doc, output);

  digitalWrite(ledPin, 0);

  return output;
}

void setup(void) {
  pinMode(A0, INPUT);
  pinMode(ledPin, OUTPUT);

  digitalWrite(ledPin, 1);

  Serial.begin(115200);
  while (!Serial) {
    delay(100);
  }

  Wire.begin();

  uint16_t error;
  char errorMessage[256];

  scd4x.begin(Wire);

  // Stop potentially previously started measurement
  error = scd4x.stopPeriodicMeasurement();
  if (error) {
    Serial.print("Error trying to execute stopPeriodicMeasurement(): ");
    errorToString(error, errorMessage, 256);
    Serial.println(errorMessage);
  }

  // Start SDC41 measurement
  error = scd4x.startPeriodicMeasurement();
  if (error) {
    Serial.print("Error trying to execute startPeriodicMeasurement(): ");
    errorToString(error, errorMessage, 256);
    Serial.println(errorMessage);
  }
}

void loop(void) {
  Serial.println(readSensors());

  // Long delay here is needed to get correct values for the SDC41 sensor
  delay(5000);
}
