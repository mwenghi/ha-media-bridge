"""HA Media Bridge - Control Home Assistant devices from Android smartwatch."""
from __future__ import annotations

import json
import logging
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import (
    ATTR_ENTITY_ID,
    SERVICE_TURN_OFF,
    SERVICE_TURN_ON,
)
from homeassistant.core import HomeAssistant, ServiceCall, callback
from homeassistant.helpers.typing import ConfigType

from .const import (
    ATTR_BRIGHTNESS_PCT,
    ATTR_NAME,
    ATTR_VOLUME,
    DOMAIN,
    ENTITY_DEVICE_LIST,
    EVENT_DEVICE_OFF,
    EVENT_DEVICE_ON,
    EVENT_DEVICE_VOLUME,
    PLATFORMS,
    SERVICE_ADD_DEVICE,
    SERVICE_CLEAR_DEVICES,
    SERVICE_REMOVE_DEVICE,
    SERVICE_SET_BRIGHTNESS,
)

_LOGGER = logging.getLogger(__name__)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Set up HA Media Bridge from YAML (deprecated, use config flow)."""
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up HA Media Bridge from a config entry."""
    _LOGGER.info("Setting up HA Media Bridge integration")

    hass.data.setdefault(DOMAIN, {})
    hass.data[DOMAIN][entry.entry_id] = {}

    # Set up event listeners
    _setup_event_listeners(hass)
    _LOGGER.debug("Event listeners registered for android_device_on/off/volume")

    # Register services
    _register_services(hass)
    _LOGGER.debug("Services registered: add_device, remove_device, clear_devices, turn_on/off, set_brightness")

    # Check input_text helper
    await _ensure_input_text_exists(hass)

    # Set up platforms (sensors)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    _LOGGER.info("HA Media Bridge setup complete")

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)

    if unload_ok:
        hass.data[DOMAIN].pop(entry.entry_id)

    return unload_ok


def _setup_event_listeners(hass: HomeAssistant) -> None:
    """Set up event listeners for Android app events."""

    @callback
    def handle_device_on(event):
        """Handle device on event from Android app."""
        entity_id = event.data.get(ATTR_ENTITY_ID)
        if not entity_id:
            _LOGGER.warning("Received device_on event without entity_id")
            return

        _LOGGER.info("Turning on device: %s", entity_id)
        hass.async_create_task(_turn_on_device(hass, entity_id))

    @callback
    def handle_device_off(event):
        """Handle device off event from Android app."""
        entity_id = event.data.get(ATTR_ENTITY_ID)
        if not entity_id:
            _LOGGER.warning("Received device_off event without entity_id")
            return

        _LOGGER.info("Turning off device: %s", entity_id)
        hass.async_create_task(_turn_off_device(hass, entity_id))

    @callback
    def handle_device_volume(event):
        """Handle device volume/brightness event from Android app."""
        entity_id = event.data.get(ATTR_ENTITY_ID)
        volume = event.data.get(ATTR_VOLUME)

        if not entity_id or volume is None:
            _LOGGER.warning("Received device_volume event without entity_id or volume")
            return

        _LOGGER.info("Setting brightness for %s to %s%%", entity_id, volume)
        hass.async_create_task(_set_brightness(hass, entity_id, int(volume)))

    # Register event listeners
    hass.bus.async_listen(EVENT_DEVICE_ON, handle_device_on)
    hass.bus.async_listen(EVENT_DEVICE_OFF, handle_device_off)
    hass.bus.async_listen(EVENT_DEVICE_VOLUME, handle_device_volume)


async def _turn_on_device(hass: HomeAssistant, entity_id: str) -> None:
    """Turn on a device based on its domain."""
    domain = entity_id.split(".")[0]

    service_map = {
        "light": ("light", SERVICE_TURN_ON),
        "switch": ("switch", SERVICE_TURN_ON),
        "fan": ("fan", SERVICE_TURN_ON),
        "media_player": ("media_player", "media_play"),
        "cover": ("cover", "open_cover"),
        "scene": ("scene", SERVICE_TURN_ON),
        "script": ("script", SERVICE_TURN_ON),
        "input_boolean": ("input_boolean", SERVICE_TURN_ON),
        "automation": ("automation", "trigger"),
    }

    if domain in service_map:
        svc_domain, svc_name = service_map[domain]
    else:
        svc_domain, svc_name = "homeassistant", SERVICE_TURN_ON

    _LOGGER.debug("Calling %s.%s for %s", svc_domain, svc_name, entity_id)
    try:
        await hass.services.async_call(
            svc_domain, svc_name, {ATTR_ENTITY_ID: entity_id}, blocking=True
        )
    except Exception as err:
        _LOGGER.error("Failed to call %s.%s for %s: %s", svc_domain, svc_name, entity_id, err)


async def _turn_off_device(hass: HomeAssistant, entity_id: str) -> None:
    """Turn off a device based on its domain."""
    domain = entity_id.split(".")[0]

    service_map = {
        "light": ("light", SERVICE_TURN_OFF),
        "switch": ("switch", SERVICE_TURN_OFF),
        "fan": ("fan", SERVICE_TURN_OFF),
        "media_player": ("media_player", "media_pause"),
        "cover": ("cover", "close_cover"),
        "input_boolean": ("input_boolean", SERVICE_TURN_OFF),
    }

    if domain in service_map:
        svc_domain, svc_name = service_map[domain]
    else:
        svc_domain, svc_name = "homeassistant", SERVICE_TURN_OFF

    _LOGGER.debug("Calling %s.%s for %s", svc_domain, svc_name, entity_id)
    try:
        await hass.services.async_call(
            svc_domain, svc_name, {ATTR_ENTITY_ID: entity_id}, blocking=True
        )
    except Exception as err:
        _LOGGER.error("Failed to call %s.%s for %s: %s", svc_domain, svc_name, entity_id, err)


async def _set_brightness(hass: HomeAssistant, entity_id: str, brightness_pct: int) -> None:
    """Set brightness/volume/position for a device based on its domain."""
    domain = entity_id.split(".")[0]

    _LOGGER.debug("Setting brightness/volume for %s (domain: %s) to %s%%", entity_id, domain, brightness_pct)

    try:
        if domain == "light":
            await hass.services.async_call(
                "light",
                SERVICE_TURN_ON,
                {ATTR_ENTITY_ID: entity_id, "brightness_pct": brightness_pct},
                blocking=True,
            )
        elif domain == "fan":
            await hass.services.async_call(
                "fan",
                "set_percentage",
                {ATTR_ENTITY_ID: entity_id, "percentage": brightness_pct},
                blocking=True,
            )
        elif domain == "media_player":
            volume_level = float(brightness_pct) / 100.0
            _LOGGER.debug("Calling media_player.volume_set for %s with volume_level=%s", entity_id, volume_level)
            await hass.services.async_call(
                "media_player",
                "volume_set",
                {ATTR_ENTITY_ID: entity_id, "volume_level": volume_level},
                blocking=True,
            )
        elif domain == "cover":
            await hass.services.async_call(
                "cover",
                "set_cover_position",
                {ATTR_ENTITY_ID: entity_id, "position": brightness_pct},
                blocking=True,
            )
        else:
            # Default: try light service
            await hass.services.async_call(
                "light",
                SERVICE_TURN_ON,
                {ATTR_ENTITY_ID: entity_id, "brightness_pct": brightness_pct},
                blocking=True,
            )
    except Exception as err:
        _LOGGER.error("Failed to set brightness for %s: %s", entity_id, err)


def _register_services(hass: HomeAssistant) -> None:
    """Register custom services."""

    async def handle_add_device(call: ServiceCall) -> None:
        """Add a device to the list."""
        name = call.data.get(ATTR_NAME)
        entity_id = call.data.get(ATTR_ENTITY_ID)

        if not name or not entity_id:
            _LOGGER.error("add_device requires both name and entity_id")
            return

        devices = await _get_device_list(hass)
        devices.append({"n": name, "e": entity_id})
        await _set_device_list(hass, devices)

    async def handle_remove_device(call: ServiceCall) -> None:
        """Remove a device from the list."""
        entity_id = call.data.get(ATTR_ENTITY_ID)

        if not entity_id:
            _LOGGER.error("remove_device requires entity_id")
            return

        devices = await _get_device_list(hass)
        devices = [d for d in devices if d.get("e") != entity_id]
        await _set_device_list(hass, devices)

    async def handle_clear_devices(call: ServiceCall) -> None:
        """Clear all devices from the list."""
        await _set_device_list(hass, [])

    async def handle_turn_on(call: ServiceCall) -> None:
        """Turn on a device."""
        entity_id = call.data.get(ATTR_ENTITY_ID)
        if entity_id:
            await _turn_on_device(hass, entity_id)

    async def handle_turn_off(call: ServiceCall) -> None:
        """Turn off a device."""
        entity_id = call.data.get(ATTR_ENTITY_ID)
        if entity_id:
            await _turn_off_device(hass, entity_id)

    async def handle_set_brightness(call: ServiceCall) -> None:
        """Set brightness for a device."""
        entity_id = call.data.get(ATTR_ENTITY_ID)
        brightness_pct = call.data.get(ATTR_BRIGHTNESS_PCT, 50)
        if entity_id:
            await _set_brightness(hass, entity_id, int(brightness_pct))

    # Register services
    hass.services.async_register(DOMAIN, SERVICE_ADD_DEVICE, handle_add_device)
    hass.services.async_register(DOMAIN, SERVICE_REMOVE_DEVICE, handle_remove_device)
    hass.services.async_register(DOMAIN, SERVICE_CLEAR_DEVICES, handle_clear_devices)
    hass.services.async_register(DOMAIN, "turn_on_device", handle_turn_on)
    hass.services.async_register(DOMAIN, "turn_off_device", handle_turn_off)
    hass.services.async_register(DOMAIN, SERVICE_SET_BRIGHTNESS, handle_set_brightness)


async def _get_device_list(hass: HomeAssistant) -> list[dict[str, Any]]:
    """Get the current device list from input_text."""
    state = hass.states.get(ENTITY_DEVICE_LIST)
    if not state or state.state in ("unknown", "unavailable", ""):
        return []

    try:
        return json.loads(state.state)
    except (json.JSONDecodeError, TypeError):
        return []


async def _set_device_list(hass: HomeAssistant, devices: list[dict[str, Any]]) -> None:
    """Set the device list in input_text."""
    await hass.services.async_call(
        "input_text",
        "set_value",
        {
            ATTR_ENTITY_ID: ENTITY_DEVICE_LIST,
            "value": json.dumps(devices, separators=(",", ":")),
        },
        blocking=True,
    )


async def _ensure_input_text_exists(hass: HomeAssistant) -> None:
    """Ensure the input_text helper exists."""
    state = hass.states.get(ENTITY_DEVICE_LIST)
    if state is None:
        _LOGGER.warning(
            "input_text.android_media_bridge_devices does not exist. "
            "Please create it manually or add to configuration.yaml:\n"
            "input_text:\n"
            "  android_media_bridge_devices:\n"
            "    name: 'Android Media Bridge Devices'\n"
            "    max: 255"
        )
