"""Constants for HA Media Bridge integration."""

DOMAIN = "ha_media_bridge"
PLATFORMS = ["sensor"]

# Event types from Android app
EVENT_DEVICE_ON = "android_device_on"
EVENT_DEVICE_OFF = "android_device_off"
EVENT_DEVICE_VOLUME = "android_device_volume"

# Entity ID for device list storage
ENTITY_DEVICE_LIST = "input_text.android_media_bridge_devices"

# Services
SERVICE_TURN_ON = "turn_on_device"
SERVICE_TURN_OFF = "turn_off_device"
SERVICE_SET_BRIGHTNESS = "set_brightness"
SERVICE_ADD_DEVICE = "add_device"
SERVICE_REMOVE_DEVICE = "remove_device"
SERVICE_CLEAR_DEVICES = "clear_devices"

# Attributes
ATTR_ENTITY_ID = "entity_id"
ATTR_BRIGHTNESS_PCT = "brightness_pct"
ATTR_NAME = "name"
ATTR_VOLUME = "volume"

# Config
CONF_AUTO_CREATE_INPUT = "auto_create_input"

# Default values
DEFAULT_BRIGHTNESS_STEP = 5
