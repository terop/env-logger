#include <rpcWiFi.h>
#include <WebServer.h>

#include <Wire.h>
#include "SHT31.h"

#include <ArduinoJson.h>

/*
 * Required libraries:
 * ArduinoJson
 * Grove_SHT31_Temp_Humi_Sensor
 * Seeed_Arduino_FS
 * Seeed_Arduino_mbedtls
 * Seeed_Arduino_rpcUnified
 * Seeed_Arduino_rpcWiFi
 * Seeed_Arduino_SFUD
 */

// CHANGE THESE before running!
const char* SSID = "MyNetwork";
const char* PASSWORD = "MyPassword";

WebServer server(80);
SHT31 sht31 = SHT31();

const int ledPin = 13;

// Reads the sensors and returns values as JSON
String readSensors() {
  float temperature = sht31.getTemperature();
  float humidity = sht31.getHumidity();
  int lightValue = analogRead(A0);
  int builtInLightValue = analogRead(WIO_LIGHT);

  delay(400);

  DynamicJsonDocument doc(96);
  String output;

  doc["temperature"] = temperature;
  doc["humidity"] = humidity;
  doc["light"] = lightValue;
  doc["builtInLight"] = builtInLightValue;

  serializeJson(doc, output);

  return output;
}

void handleRoot() {
  digitalWrite(ledPin, 1);

  server.send(200, "application/json", readSensors());

  digitalWrite(ledPin, 0);
}

void handleNotFound() {
  digitalWrite(ledPin, 1);

  server.send(404, "text/plain", "File Not Found\n\n");

  digitalWrite(ledPin, 0);
}

void setup(void) {
  pinMode(WIO_LIGHT, INPUT);
  pinMode(ledPin, OUTPUT);

  digitalWrite(ledPin, 0);

  Serial.begin(115200);

  WiFi.mode(WIFI_STA);
  WiFi.begin(SSID, PASSWORD);

  // Wait for connection
  while (WiFi.status() != WL_CONNECTED) {
    delay(1000);
    Serial.print(".");
  }
  Serial.print("Connected to ");
  Serial.println(SSID);
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());

  sht31.begin();

  server.on("/", handleRoot);

  server.onNotFound(handleNotFound);

  server.begin();
  Serial.println("HTTP server started");
}

void loop(void) {
  Serial.println(readSensors());
  server.handleClient();
  delay(2000);
}
