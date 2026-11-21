# CameraVR

CameraVR is an experimental Android-to-Windows VR companion for a phone mounted in a VR Box.

## Initial milestone

This first milestone establishes the native Android camera app and an automated APK build. It is **not yet a complete VR tracker**.

- Android native app (Kotlin)
- Rear camera capture through CameraX
- Frame-analysis pipeline prepared for visual tracking
- MediaPipe Hand Landmarker configured for up to two hands at once
- Left/right handedness labels and separate overlay colors
- 21 landmarks per detected hand (up to 42 points total)
- Planned visual head-orientation estimation without a gyroscope
- Planned USB (ADB port forwarding) and Wi-Fi transport
- Planned Windows bridge for SteamVR/OpenXR

## Important hardware limitation

With the phone's rear camera facing outward, it can observe the room and hands that enter its field of view. It cannot directly observe the wearer's face. Visual head rotation estimation therefore needs stable environmental features and careful camera mounting/calibration. It will not provide the same reliability or low latency as a physical IMU. Translation/depth and full 6DoF tracking are separate, harder problems.

For hand tracking, both hands must remain visible to the rear camera. The VR Box and phone mount must not obstruct the camera.

## Build APK

Every push to `main` runs GitHub Actions. Download the `CameraVR-debug-apk` artifact from the workflow run.

## Current status

The Android app now runs MediaPipe Hand Landmarker in video mode with `numHands = 2`, reads the left/right handedness output, and draws both hand skeletons with separate colors and labels. The GitHub Actions workflow downloads the official `.task` model into the Android assets folder before building. Head pose estimation, network transport, and SteamVR/OpenXR integration remain subsequent milestones. The two-hand detector still needs a real-device test under suitable lighting and with both hands inside the rear camera view.

## Planned transport

- Wi-Fi: local network connection to the Windows bridge.
- USB: Android Debug Bridge (ADB) port forwarding, requiring USB debugging to be enabled and authorized.

## Safety and privacy

Camera frames are processed on-device in this initial design. Do not point the camera at people without their consent.