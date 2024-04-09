"""Secrets template file."""
# This file is where you keep secret settings, passwords, and tokens!
# If you put them in the code you risk committing that info or sharing it

secrets = {
    # WLAN settings
    'ssid': 'MyWifi',
    'password': 'MyPassword',
    # Timezone
    'timezone': 'Etc/UTC',
    # env-logger settings
    'data-read-user': {
        'username': 'data-reader',
        'password': 'MyPassword123'
    },
    'backend-url': 'https://mydomain.com/env-logger',
    # Hide RuuviTags with given name(s), an empty array means that all
    # tags are shown
    'hidden_ruuvitag_names': []
}
