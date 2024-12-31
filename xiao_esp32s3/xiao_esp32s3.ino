#include <Wire.h>
#include <Digital_Light_TSL2561.h>

#include <WiFi.h>
#include <WebServer.h>

#include <ArduinoJson.h>

#include "arduino_secrets.h"

/*
 * Required libraries:
 * ArduinoJson
 * https://github.com/Seeed-Studio/Grove_Digital_Light_Sensor
 */

// Network SSID
char ssid[] = SECRET_SSID;
// Network password
char password[] = SECRET_PASSWORD;

WebServer server(80);

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

  connectToWifi();

  server.on("/", sendSensorData);
  server.begin();
  Serial.println("HTTP server started");
}

void loop() {
  reconnectToWifi();

  server.handleClient();

  delay(2000);
}
