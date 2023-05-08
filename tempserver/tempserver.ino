#include <WiFiNINA.h>
#include <ArduinoJson.h>

#include "arduino_secrets.h"

/*
 * Required libraries:
 * ArduinoJson
 * WiFiNINA
 */

// Target Arduino board: MKR WiFi 1010

// Network SSID
char ssid[] = SECRET_SSID;
// Network password
char pass[] = SECRET_PASS;
// WiFi radio status
int status = WL_IDLE_STATUS;
// Network server
WiFiServer server(80);

// Input pins
const int PHOTORESISTOR_PIN = A4;
const int THERMISTOR_PIN = A6;

/* A reading from the ADC might give one value at one sample and then a little
   different the next time around. To eliminate noisy readings, we can sample
   the ADC pin a few times and then average the samples to get something more
   solid. This constant is utilized in the readThermistor function.
*/
const int SAMPLE_NUMBER = 10;

// This helps calculate the thermistor's resistance
const double MAX_ADC = 1023.0;

/* This is also needed for the conversion equation as "typical" room temperature
   is needed as an input. */
const double ROOM_TEMP = 298.15;   // room temperature in Kelvin

/* Thermistors will have a typical resistance at room temperature so write this
   down here. Again, needed for conversion equations. */
const double RESISTOR_ROOM_TEMP = 10000.0;

// Temperature reading related variables
double previousTemperature = 1000;
boolean temperatureInitDone = false;

void setup() {
  // Initialise LED
  pinMode(LED_BUILTIN, OUTPUT);

  // Open serial communications and wait for port to open
  Serial.begin(9600);

  // Attempt to connect to Wifi network
  while (status != WL_CONNECTED) {
    Serial.print("Attempting to connect to network: ");
    Serial.println(ssid);
    // Connect to WPA/WPA2 network
    status = WiFi.begin(ssid, pass);

    // Wait 5 seconds for connection
    delay(5000);
  }

  Serial.println("Connected to the network:");
  printNetworkData();

  server.begin();
}

void loop() {
  // Listen for incoming clients
  WiFiClient client = server.available();

  if (client) {
    // A HTTP request ends with a blank line
    boolean currentLineIsBlank = true;

    while (client.connected()) {
      if (client.available()) {
        digitalWrite(LED_BUILTIN, HIGH);

        char c = client.read();
        // If you've gotten to the end of the line (received a newline
        // character) and the line is blank, the HTTP request has ended,
        // so you can send a reply
        if (c == '\n' && currentLineIsBlank) {
          DynamicJsonDocument doc = readSensors();

          // Send a standard http response header
          client.println("HTTP/1.1 200 OK");
          client.println("Content-Type: application/json");
          client.println("Connection: close");
          client.print("Content-Length: ");
          client.println(measureJsonPretty(doc));
          client.println();

          serializeJsonPretty(doc, client);
          break;
        }
        if (c == '\n') {
          // You're starting a new line
          currentLineIsBlank = true;
        } else if (c != '\r') {
          // You've gotten a character on the current line
          currentLineIsBlank = false;
        }
      }
    }

    // Close the connection
    client.stop();

    digitalWrite(LED_BUILTIN, LOW);
  }
}

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

// Reads the sensors and returns them in a JSON object
DynamicJsonDocument readSensors() {
  const int temperatureCount = 3;
  double temperatureSum = 0;
  DynamicJsonDocument doc(96);

  for (int i = 0; i < temperatureCount; i++)
    temperatureSum += getTemperatureSensorValue();

  doc["extTempSensor"] = temperatureSum / temperatureCount;
  doc["insideLight"] = analogRead(PHOTORESISTOR_PIN);

  return doc;
}

// Reads the temperature sensor and returns its value
double getTemperatureSensorValue() {
  const int diffThreshold = 2;
  const int attemptsThreshold = 4;
  int attempts = 0;
  double temperature;

  if (!temperatureInitDone) {
    previousTemperature = readThermistor(THERMISTOR_PIN, 9800.0, 3380.0);
    temperatureInitDone = true;
    delay(100);
  }

  temperature = readThermistor(THERMISTOR_PIN, 9800.0, 3380.0);
  // Retry temperature reading if needed to prevent sudden changes in
  // reported values
  while (attempts <= attemptsThreshold &&
         (abs(previousTemperature - temperature) >= diffThreshold)) {
    delay(50);
    attempts++;
    temperature = readThermistor(THERMISTOR_PIN, 9800.0, 3380.0);

    if ((abs(previousTemperature - temperature) < diffThreshold) ||
        attempts == attemptsThreshold) {
      previousTemperature = temperature;
      break;
    }
  }

  return temperature;
}

// The code below is from https://www.allaboutcircuits.com/projects/measuring-temperature-with-an-ntc-thermistor/
/**
   This function reads the analog pin as shown below. Converts voltage signal
   to a digital representation with analog to digital conversion. However, this is
   done multiple times so that we can average it to eliminate measurement errors.
   This averaged number is then used to calculate the resistance of the thermistor.
   After this, the resistance is used to calculate the temperature of the
   thermistor. Finally, the temperature is converted to celsius. Please refer to
   the allaboutcircuits.com article for the specifics and general theory of this
   process.

   Quick Schematic in case you are too lazy to look at the site :P

   (Ground) ----\/\/\/-------|-------\/\/\/---- V_supply
   R_balance      |     R_thermistor
   |
   Analog Pin
*/
double readThermistor(int thermistorPin, double balanceResistor, double beta) {
  // variables that live in this function
  double rThermistor = 0;            // Holds thermistor resistance value
  double tKelvin     = 0;            // Holds calculated temperature
  double tCelsius    = 0;            // Hold temperature in celsius
  double adcAverage  = 0;            // Holds the average voltage measurement
  int adcSamples[SAMPLE_NUMBER];  // Array to hold each voltage measurement

  /* Calculate thermistor's average resistance:
     As mentioned in the top of the code, we will sample the ADC pin a few times
     to get a bunch of samples. A slight delay is added to properly have the
     analogRead function sample properly */

  for (int i = 0; i < SAMPLE_NUMBER; i++) {
    adcSamples[i] = analogRead(thermistorPin);  // read from pin and store
    delay(10);        // wait 10 milliseconds
  }

  /* Then, we will simply average all of those samples up for a "stiffer"
     measurement. */
  for (int i = 0; i < SAMPLE_NUMBER; i++) {
    adcAverage += adcSamples[i];      // add all samples up . . .
  }
  adcAverage /= SAMPLE_NUMBER;        // . . . average it w/ divide

  /* Here we calculate the thermistor’s resistance using the equation
     discussed in the article. */
  rThermistor = balanceResistor * ( (MAX_ADC / adcAverage) - 1);

  /* Here is where the Beta equation is used, but it is different
     from what the article describes. Don't worry! It has been rearranged
     algebraically to give a "better" looking formula. I encourage you
     to try to manipulate the equation from the article yourself to get
     better at algebra. And if not, just use what is shown here and take it
     for granted or input the formula directly from the article, exactly
     as it is shown. Either way will work! */
  tKelvin = (beta * ROOM_TEMP) /
    (beta + (ROOM_TEMP * log(rThermistor / RESISTOR_ROOM_TEMP)));

  /* I will use the units of Celsius to indicate temperature. I did this
     just so I can see the typical room temperature, which is 25 degrees
     Celsius, when I first try the program out. I prefer Fahrenheit, but
     I leave it up to you to either change this function, or create
     another function which converts between the two units. */
  tCelsius = tKelvin - 273.15;  // convert kelvin to celsius

  return tCelsius;
}
