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

1. Install this integration via HACS (Download)
2. **Restart Home Assistant** (required!)
3. Add the required `input_text` helper to your configuration.yaml:

```yaml
input_text:
  android_media_bridge_devices:
    name: "Android Media Bridge Devices"
    max: 255
```

4. Restart Home Assistant again (for the input_text)
5. Go to **Settings → Devices & Services → Add Integration** (+ button)
6. Search for "HA Media Bridge" and add it
7. Install the Android app and configure it

**Note:** HACS only downloads the files. You MUST add the integration via UI (step 5-6) to activate it!

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

See the [GitHub repository](https://github.com/mwenghi/ha-media-bridge) for:
- Android app download
- Full documentation
- Troubleshooting guide
