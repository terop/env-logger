#include <Wire.h>
#include "SHT31.h"

#include <rpcWiFi.h>
#include <WebServer.h>

#include <ArduinoJson.h>

#include "arduino_secrets.h"

/*
 * Required libraries:
 * ArduinoJson
 * Grove_SHT31_Temp_Humi_Sensor
 * Seeed_Arduino_rpcWiFi
 */

// Network SSID
char ssid[] = SECRET_SSID;
// Network password
char password[] = SECRET_PASS;
// WiFi radio status
int status = WL_IDLE_STATUS;

SHT31 sht31 = SHT31();

const int ledPin = 13;

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

  while (status != WL_CONNECTED) {
    Serial.println("Connecting to WiFi");
    status = WiFi.begin(ssid, password);

    delay(4000);
  }
  Serial.println("Connected to the WiFi network");
  printNetworkData();
}

// Reads the sensors and returns values as JSON
String readSensors() {
  digitalWrite(ledPin, 1);

  const uint8_t lightMeasCount = 5;
  int lightValuesSum = 0;

  for (uint8_t i = 0; i < lightMeasCount; i++) {
    delay(50);
    lightValuesSum += analogRead(A0);
  }

  float temperature = sht31.getTemperature();
  float humidity = sht31.getHumidity();
  int lightValue = round((float)lightValuesSum / lightMeasCount);
  int builtInLightValue = analogRead(WIO_LIGHT);

  delay(400);

  DynamicJsonDocument doc(96);
  String output;

  doc["temperature"] = temperature;
  doc["humidity"] = humidity;
  doc["light"] = lightValue;
  doc["builtInLight"] = builtInLightValue;

  serializeJson(doc, output);

  digitalWrite(ledPin, 0);

  return output;
}

void sendSensorData() {
  server.send(200, "application/json", readSensors());
}

void setup(void) {
  pinMode(A0, INPUT);
  pinMode(WIO_LIGHT, INPUT);
  pinMode(ledPin, OUTPUT);

  digitalWrite(ledPin, 1);

  Serial.begin(115200);
  while(!Serial);

  sht31.begin();

  connectToWifi();

  server.on("/", sendSensorData);
  server.begin();
  Serial.println("HTTP server started");
}

void loop(void) {
  Serial.println(readSensors());
  server.handleClient();

  delay(1000);
}
