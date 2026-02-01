# HA Media Bridge

Control your Home Assistant devices from your Android smartwatch using media buttons.

## Features

- **Play/Pause**: Toggle devices on/off
- **Next/Previous**: Switch between configured devices
- **Volume Up/Down**: Adjust brightness/volume

## Supported Devices

- Lights (brightness control)
- Switches
- Fans (speed control)
- Media players (volume control)
- Covers (position control)
- Scenes
- Scripts

## Setup

1. Install this integration via HACS
2. Add the required `input_text` helper to your configuration:

```yaml
input_text:
  android_media_bridge_devices:
    name: "Android Media Bridge Devices"
    max: 255
```

3. Restart Home Assistant
4. Go to Settings → Devices & Services → Add Integration
5. Search for "HA Media Bridge"
6. Install the Android app and configure it

## Services

| Service | Description |
|---------|-------------|
| `ha_media_bridge.add_device` | Add a device to the list |
| `ha_media_bridge.remove_device` | Remove a device from the list |
| `ha_media_bridge.clear_devices` | Remove all devices |
| `ha_media_bridge.turn_on_device` | Turn on a device |
| `ha_media_bridge.turn_off_device` | Turn off a device |
| `ha_media_bridge.set_brightness` | Set brightness/volume |

## More Information

See the [GitHub repository](https://github.com/anthropics/ha-media-bridge) for:
- Android app download
- Full documentation
- Troubleshooting guide
