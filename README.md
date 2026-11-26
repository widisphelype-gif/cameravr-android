# CameraVR

CameraVR is an experimental Android-to-Windows companion for a phone mounted in a VR Box.

## Current Android milestone

- Native Android app using Kotlin, CameraX and MediaPipe.
- Rear camera frame analysis and two-hand tracking (up to 21 landmarks per hand).
- Side-by-side VR Box compositor with separate left/right lens viewports, GPU radial pre-distortion, lens-centre offset, and black outside-lens regions. Both eyes still sample the same camera frame: this is a 2D passthrough view, not true stereoscopic depth.
- Optional TCP JPEG streaming to the Windows preview receiver.
- USB transport through ADB reverse and Wi-Fi transport over the same local network.
- Head orientation estimation from camera motion is still experimental / not implemented as reliable pose tracking.
- SteamVR/OpenXR driver integration is not included yet.

## Run on Android

Build the debug APK with the GitHub Actions workflow. Install it, grant camera permission, and place the phone so its rear camera is not blocked by the VR Box mount.

The VR Box view uses two eye viewports and an OpenGL ES radial lens pre-warp adapted to the CameraX bitmap pipeline. Both eye viewports currently show the same camera frame, so it does not create stereoscopic depth. Lens coefficients and optical-centre offset are initial generic values and require calibration against the physical viewer.

## Windows PC receiver

Install Python 3, then install dependencies:

```powershell
py -m pip install opencv-python numpy
py pc/receiver.py
```

Allow Python through Windows Firewall on a trusted private network when prompted. Keep the PC and phone on the same Wi-Fi for wireless streaming.

### Wi-Fi

1. Start `py pc/receiver.py` on the PC.
2. Find the PC's local IPv4 address with `ipconfig`.
3. In CameraVR, tap **PC / conexão**, enter that IPv4 address and port `4242`, then tap **Conectar**.

### USB

1. Enable Android USB debugging and authorize the connected PC.
2. Start the receiver on the PC.
3. Run `adb reverse tcp:4242 tcp:4242` on the PC.
4. In CameraVR, set host to `127.0.0.1`, port `4242`, and tap **Conectar**.

USB reverse forwarding requires Android Platform Tools (ADB). Wi-Fi and USB are selectable by choosing the corresponding host address.

## Important tracking limitations

The rear camera does not see the wearer's face. Estimating head rotation from the outside scene requires visual odometry / optical-flow work and calibration; it is not yet implemented as reliable head pose. It should not be treated as a substitute for an IMU or as full 6DoF tracking.

Both hands must remain inside the rear camera's field of view. Depending on the VR Box mount, that may require a separate camera position or external camera.

## Build

Every push to `main` runs GitHub Actions and publishes the `CameraVR-debug-apk` artifact if the build succeeds.

## Current status

The Android viewer and initial PC frame receiver are prototype milestones. They do not yet expose a SteamVR headset or controllers. Real-device testing is required for camera orientation, performance, thermal behavior, USB and Wi-Fi stability.


## Mixed Reality (MR) prototype

The `feature/mixed-reality-vrbox` branch adds an optional visual mixed-reality overlay to the existing rear-camera passthrough. Toggle it with **MR: ON/OFF**. The overlay draws a per-eye status panel, reticle, and wireframe hologram while preserving hand landmarks and the existing PC streaming pipeline.

**Limitations:** this milestone is camera passthrough with screen-space holographic UI, not full spatial AR. Both eye viewports still use the same camera frame, so there is no real stereoscopic depth, world locking, plane detection, or reliable head pose. Treat the hologram as a visual demo, not a depth-accurate object in the room.
