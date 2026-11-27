# Swirlock Wearable Flight Computer

Standalone Wear OS application for the Swirlock paramotor flight computer.

## Build and install

Requires Android SDK Platform 36, Android Studio's JDK, and an ADB-connected Wear OS watch.

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.swirlock.flightcomputer/.MainActivity
```

The current exercise build is GPS-free and uses raw accelerometer/gyroscope samples with a separate magnetometer heading path. The live screen displays local inertial speed, relative height, vertical speed, and HOME-relative position; these estimates are experimental and drift-prone. A separate `NavigationProvider` interface keeps GPS isolated for later integration. Terrain/AGL remains unimplemented. Fuel is a configurable motion-based estimate using `app/src/main/assets/fuel_model.json`; its speed bands and burn rates are provisional guesses, not calibrated measurements.

Edit the JSON fuel model to adjust initial fuel, takeoff burn/trigger, vertical-speed phase boundaries, smoothing windows, horizontal-speed rate bands, and warning thresholds without changing Kotlin code. Its `calibrationStatus` is `provisional` until measured against actual engine consumption.

## Watch sensor diagnostics

The MAG + inertial provider is foreground-only. It calibrates gyro and accelerometer biases during a two-second stationary interval, uses the magnetometer to initialize a local north reference when field data are plausible, and otherwise initializes an arbitrary-yaw local frame from gravity and screen-up. After alignment, raw gyro samples propagate attitude and raw accelerometer specific force is rotated to ENU, gravity-compensated, and integrated to local velocity/position. Magnetic compass readout is estimated separately from accelerometer/magnetometer samples. Sensor details and local INS state are written to Android logcat. The listener stops when the app leaves the foreground; it is not the production flight service. The unused GPS provider currently requires the future GPS build to add location permissions and select that provider at the app composition root.

On the connected watch, view the diagnostic stream from the development computer:

```powershell
adb logcat -s SwirlockSensors
```

The current build does not request location permission or use GPS.

## Inertial navigation design

The mathematical guide for the GPS-independent strapdown INS exercise is [docs/INERTIAL_NAVIGATION_GUIDE.md](docs/INERTIAL_NAVIGATION_GUIDE.md). It defines local-frame conventions, sensor model, attitude/velocity/position mechanization, launch initialization, HOME-relative navigation, rotation-invariance validation criteria, and the separation between strict IMU integration and magnetic initialization/aiding.
