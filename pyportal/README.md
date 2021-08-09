# Data display on PyPortal Titano

This directory contains the files to display env-logger data on a
[PyPortal Titano](https://learn.adafruit.com/adafruit-pyportal-titano).

The main code is located in `code.py`. Some settings are located at
the top of the file and are documented there.

The following files must be copied to the PyPortal Titano device for the display to work:

* `code.py`
* `secrets.py`: a template for this file is located in this directory and is called
`secrets_template.py`. Copy the template and adjust necessary settings.
* A suitable bitmap font. See PyPortal documentation on how to convert the font file.
The font file name is set at the top of `code.py`.
* Necessary library files in PyPortal `<root>/lib` directory. The code is tested to work with the libraries below, however some of them may
not be needed.

    * `adafruit_bitmap_font/`
    * `adafruit_bus_device/`
    * `adafruit_display_text/`
    * `adafruit_esp32spi/`
    * `adafruit_io/`
    * `adafruit_portalbase/`
    * `adafruit_requests.mpy`
    * `adafruit_simple_text_display.mpy`
    * `neopixel.mpy`
    * `simpleio.mpy`

    See the PyPortal documentation on how to acquire and copy them to the device.

See the [PyPortal Titano documentation](https://learn.adafruit.com/adafruit-pyportal-titano)
on how to do the initial setup and update of your PyPortal device.

*NOTE!* This code may also work on other PyPortal models but it is not tested
and no guarantee nor support for those is provided.
