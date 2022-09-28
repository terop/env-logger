"""This a Scrapy spider for scraping Nordpool Finland spot electricity prices."""

import logging
from datetime import date, datetime, time

import scrapy


class SpotPriceSpider(scrapy.Spider):
    """Main spider class."""
    name = 'spot_price'

    def start_requests(self):
        yield scrapy.Request(url='https://www.sahkon-kilpailutus.fi/porssisahkon-hinta/',
                             callback=self.parse)

    def parse(self, response):  # pylint: disable=arguments-differ
        table = response.xpath('//table[@id="supsystic-table-2"]')
        if not table:
            logging.error('Could not find data table')
            return

        today = date.today()
        for row in table[0].css('tr')[2:28]:
            yield {
                'time': datetime.combine(today, time(hour=int(row.css('td::text')[0].get()
                                                              .strip().split('-')[0]))).isoformat(),
                'price': row.css('td::text')[1].get().strip()
            }
