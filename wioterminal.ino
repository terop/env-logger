#include <Wire.h>
#include "SHT31.h"

#include <ArduinoJson.h>

/*
 * Required libraries:
 * ArduinoJson
 * Grove_SHT31_Temp_Humi_Sensor
 */

SHT31 sht31 = SHT31();

const int ledPin = 13;

// Reads the sensors and returns values as JSON
String readSensors() {
  digitalWrite(ledPin, 1);

  const uint8_t lightMeasCount = 5;
  int lightValuesSum = 0;

  for (uint8_t i = 0; i < lightMeasCount; i++)
    delay(50);
    lightValuesSum += analogRead(A0);

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

void setup(void) {
  pinMode(A0, INPUT);
  pinMode(WIO_LIGHT, INPUT);
  pinMode(ledPin, OUTPUT);

  digitalWrite(ledPin, 1);

  Serial.begin(115200);

  sht31.begin();
}

void loop(void) {
  Serial.println(readSensors());
  delay(2000);
}
