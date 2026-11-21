# CameraVR

CameraVR is an experimental Android-to-Windows VR companion for a phone mounted in a VR Box.

## Initial milestone

This first milestone establishes the native Android camera app and an automated APK build. It is **not yet a complete VR tracker**.

- Android native app (Kotlin)
- Rear camera capture through CameraX
- Frame-analysis pipeline prepared for visual tracking
- Planned two-hand tracking with MediaPipe
- Planned visual head-orientation estimation without a gyroscope
- Planned USB (ADB port forwarding) and Wi-Fi transport
- Planned Windows bridge for SteamVR/OpenXR

## Important hardware limitation

With the phone's rear camera facing outward, it can observe the room and hands that enter its field of view. It cannot directly observe the wearer's face. Visual head rotation estimation therefore needs stable environmental features and careful camera mounting/calibration. It will not provide the same reliability or low latency as a physical IMU. Translation/depth and full 6DoF tracking are separate, harder problems.

For hand tracking, both hands must remain visible to the rear camera. The VR Box and phone mount must not obstruct the camera.

## Build APK

Every push to `main` runs GitHub Actions. Download the `CameraVR-debug-apk` artifact from the workflow run.

## Current status

The initial app provides a camera preview and frame analyzer scaffold. Hand landmarks, head pose estimation, network transport, and SteamVR/OpenXR integration are subsequent milestones.

## Planned transport

- Wi-Fi: local network connection to the Windows bridge.
- USB: Android Debug Bridge (ADB) port forwarding, requiring USB debugging to be enabled and authorized.

## Safety and privacy

Camera frames are processed on-device in this initial design. Do not point the camera at people without their consent.