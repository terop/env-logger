#include <Wire.h>
#include <Digital_Light_TSL2561.h>
#include <SensirionI2CScd4x.h>

#include <WiFi.h>
#include <WebServer.h>

#include <ArduinoJson.h>

#include "arduino_secrets.h"

/*
 * Required libraries:
 * ArduinoJson
 * https://github.com/Sensirion/arduino-core
 * https://github.com/Sensirion/arduino-i2c-scd4x
 * https://github.com/Seeed-Studio/Grove_Digital_Light_Sensor
 */

// Network SSID
char ssid[] = SECRET_SSID;
// Network password
char password[] = SECRET_PASSWORD;

WebServer server(80);

SensirionI2CScd4x scd4x;

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

// Reads the sensors and returns values as JSON
String readSensors() {
  JsonDocument doc;
  String output;

  // Light measurement
  doc["light"] = TSL2561.readVisibleLux();

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

  connectToWifi();

  server.on("/", sendSensorData);
  server.begin();
  Serial.println("HTTP server started");
}

void loop() {
  reconnectToWifi();

  // Only enable the line below when debugging sensor output as it interferes
  // with SDC41 sensor data value transmission over HTTP
  // Serial.println(readSensors());

  server.handleClient();

  // Long delay here is needed to get correct values for the SDC41 sensor
  delay(5000);
}
