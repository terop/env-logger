"""This a Scrapy spider for scraping Nordpool Finland spot electricity prices."""

import logging

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

        for row in table[0].css('tr')[2:28]:
            yield {
                'hour': row.css('td::text')[0].get().strip(),
                'price': row.css('td::text')[1].get().strip()
            }
