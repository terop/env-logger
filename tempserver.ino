#include <SPI.h>
#include <Ethernet.h>

#include <ArduinoJson.h>

/*
 * Required libraries:
 * ArduinoJson
 */

// Input pins
const int photoresistorPin = A1;

// Enter a MAC address and IP address for your controller below.
// The IP address will be dependent on your local network:
byte mac[] = { 0x90, 0xA2, 0xDA, 0x0E, 0x9A, 0xFC };
IPAddress ip(192, 168, 1, 10);

// Initialize the Ethernet server library
// with the IP address and port you want to use
EthernetServer server(80);

/* A reading from the ADC might give one value at one sample and then a little
   different the next time around. To eliminate noisy readings, we can sample
   the ADC pin a few times and then average the samples to get something more
   solid. This constant is utilized in the readThermistor function.
   */
const int SAMPLE_NUMBER = 10;

// This helps calculate the thermistor's resistance (check article for details).
const double MAX_ADC = 1023.0;

/* This is also needed for the conversion equation as "typical" room temperature
   is needed as an input. */
const double ROOM_TEMP = 298.15;   // room temperature in Kelvin

/* Thermistors will have a typical resistance at room temperature so write this
   down here. Again, needed for conversion equations. */
const double RESISTOR_ROOM_TEMP = 10000.0;

// Reads the sensors and returns them in a JSON object
DynamicJsonDocument readSensors()
{
  DynamicJsonDocument doc(96);

  doc["insideTemperature"] = readThermistor(A3, 9750.0, 3950.0);
  doc["insideLight"] = analogRead(photoresistorPin);
  doc["extTempSensor"] = readThermistor(A2, 9800.0, 3380.0);

  return doc;
}

void setup() {
  // Open serial communications and wait for port to open:
  Serial.begin(9600);

  // start the Ethernet connection and the server:
  Ethernet.begin(mac, ip);
  server.begin();
}

void loop() {
  // Listen for incoming clients
  EthernetClient client = server.available();

  if (client) {
    // An HTTP request ends with a blank line
    boolean currentLineIsBlank = true;

    while (client.connected()) {
      if (client.available()) {
        char c = client.read();
        // If you've gotten to the end of the line (received a newline
        // character) and the line is blank, the http request has ended,
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

          serializeJson(doc, client);
          break;
        }
        if (c == '\n') {
          // You're starting a new line
          currentLineIsBlank = true;
        }
        else if (c != '\r') {
          // You've gotten a character on the current line
          currentLineIsBlank = false;
        }
      }
    }
    // Give the web browser time to receive the data
    delay(1);
    // Close the connection
    client.stop();
  }
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

  return tCelsius;    // Return the temperature in Celsius
}
