"""Sensor platform for HA Media Bridge."""
from __future__ import annotations

import json
import logging
from typing import Any

from homeassistant.components.sensor import SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.event import async_track_state_change_event

from .const import DOMAIN, ENTITY_DEVICE_LIST

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant,
    config_entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up HA Media Bridge sensors."""
    async_add_entities(
        [
            DeviceCountSensor(hass),
            DeviceListSensor(hass),
        ]
    )


class DeviceCountSensor(SensorEntity):
    """Sensor showing the number of configured devices."""

    _attr_has_entity_name = True
    _attr_name = "Device Count"
    _attr_icon = "mdi:devices"
    _attr_unique_id = f"{DOMAIN}_device_count"

    def __init__(self, hass: HomeAssistant) -> None:
        """Initialize the sensor."""
        self.hass = hass
        self._attr_native_value = 0

    async def async_added_to_hass(self) -> None:
        """Run when entity is added to hass."""
        await super().async_added_to_hass()

        # Update on input_text state changes
        self.async_on_remove(
            async_track_state_change_event(
                self.hass,
                [ENTITY_DEVICE_LIST],
                self._async_state_changed,
            )
        )

        # Initial update
        self._update_state()

    @callback
    def _async_state_changed(self, event) -> None:
        """Handle state changes."""
        self._update_state()
        self.async_write_ha_state()

    def _update_state(self) -> None:
        """Update the sensor state."""
        state = self.hass.states.get(ENTITY_DEVICE_LIST)
        if not state or state.state in ("unknown", "unavailable", ""):
            self._attr_native_value = 0
            return

        try:
            devices = json.loads(state.state)
            self._attr_native_value = len(devices)
        except (json.JSONDecodeError, TypeError):
            self._attr_native_value = 0

    @property
    def device_info(self):
        """Return device info."""
        return {
            "identifiers": {(DOMAIN, "ha_media_bridge")},
            "name": "HA Media Bridge",
            "manufacturer": "Android Media Bridge",
            "model": "Smartwatch Controller",
        }


class DeviceListSensor(SensorEntity):
    """Sensor showing device list details."""

    _attr_has_entity_name = True
    _attr_name = "Device List"
    _attr_icon = "mdi:format-list-bulleted"
    _attr_unique_id = f"{DOMAIN}_device_list"

    def __init__(self, hass: HomeAssistant) -> None:
        """Initialize the sensor."""
        self.hass = hass
        self._attr_native_value = "No devices"
        self._devices: list[dict[str, Any]] = []

    async def async_added_to_hass(self) -> None:
        """Run when entity is added to hass."""
        await super().async_added_to_hass()

        # Update on input_text state changes
        self.async_on_remove(
            async_track_state_change_event(
                self.hass,
                [ENTITY_DEVICE_LIST],
                self._async_state_changed,
            )
        )

        # Initial update
        self._update_state()

    @callback
    def _async_state_changed(self, event) -> None:
        """Handle state changes."""
        self._update_state()
        self.async_write_ha_state()

    def _update_state(self) -> None:
        """Update the sensor state."""
        state = self.hass.states.get(ENTITY_DEVICE_LIST)
        if not state or state.state in ("unknown", "unavailable", ""):
            self._attr_native_value = "No devices"
            self._devices = []
            return

        try:
            devices = json.loads(state.state)
            self._devices = devices
            count = len(devices)
            self._attr_native_value = f"{count} device{'s' if count != 1 else ''}"
        except (json.JSONDecodeError, TypeError):
            self._attr_native_value = "No devices"
            self._devices = []

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        """Return extra state attributes."""
        devices_expanded = []
        for d in self._devices:
            entity_id = d.get("e", "")
            entity_state = self.hass.states.get(entity_id)
            devices_expanded.append({
                "name": d.get("n", "Unknown"),
                "entity_id": entity_id,
                "state": entity_state.state if entity_state else "unknown",
            })

        return {
            "devices": devices_expanded,
            "device_names": [d.get("n", "") for d in self._devices],
            "device_entities": [d.get("e", "") for d in self._devices],
        }

    @property
    def device_info(self):
        """Return device info."""
        return {
            "identifiers": {(DOMAIN, "ha_media_bridge")},
            "name": "HA Media Bridge",
            "manufacturer": "Android Media Bridge",
            "model": "Smartwatch Controller",
        }
