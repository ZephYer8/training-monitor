# Lite Wearable page skeleton

This is a DevEco Studio source skeleton for the FIT 4 companion screen.

It intentionally avoids bundling Huawei SDK binaries or generated DevEco metadata. Import this directory into a real DevEco Studio wearable project, then copy the `entry/src/main` source tree into the generated project.

## Important placeholders

- `entry/src/main/js/MainAbility/pages/index/index.js`
  - `registerWearEngineReceiver()` is a placeholder. Replace it with the wearable-side Wear Engine receiver API for the selected FIT 4 SDK target.
  - `updateFromMessage(raw)` already parses the JSON payload produced by `WatchStatusPayload`.

## Development loop

1. Create a Huawei Lite Wearable project in DevEco Studio.
2. Copy this `entry/src/main` tree into the generated project.
3. Configure app id, icon, signing, and supported device target.
4. Install the watch app to FIT 4 through DevEco Studio.
5. Wire Android phone P2P sending as described in `../phone-wear-engine.md`.
