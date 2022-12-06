"""This a Scrapy spider for scraping Nordpool Finland spot electricity prices."""

import logging
from datetime import date, datetime, time

import scrapy

VAT_MULTIPLIER = 1.24
VAT_MULTIPLIER_DECREASED = 1.10


class SpotPriceSpider(scrapy.Spider):
    """Main spider class."""
    name = 'spot_price'

    def start_requests(self):
        yield scrapy.Request(url='https://www.herrfors.fi/fi/spot-hinnat/',
                             callback=self.parse)

    def parse(self, response):  # pylint: disable=arguments-differ
        price_list = response.xpath('//ul[@class="spotprice-list mt-3"]')[1].css('li')

        if not price_list:
            logging.error('Could not find price list')
            return

        today = date.today()
        vat_decrease_end = date(2023, 4, 30)

        # Use temporarily decrease electricity VAT (10 %) until 30th of April 2023
        vat_multiplier = VAT_MULTIPLIER_DECREASED if today < vat_decrease_end else \
            VAT_MULTIPLIER

        for item in price_list[1:25]:
            yield {
                'time': datetime.combine(today,
                                         time(hour=int(item.css('li').css('span::text')[0].
                                                       get().strip().split(' ')[0]))).isoformat(),
                # Price is without VAT so it is manually added
                'price': round(float(item.css('li').css('span')[1].css('pricedata').
                                     attrib['data-price']) * vat_multiplier, 2)
            }
