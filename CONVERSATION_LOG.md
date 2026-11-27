# Conversation Log

This file keeps the project decisions and handoff context from our conversations in the repository, so work can continue on another computer. `AGENTS.md` remains the detailed product and engineering specification; this log records clarifications and decisions made while discussing it.

When continuing the project, add a dated entry for durable decisions, changed assumptions, and useful setup context. Avoid copying transient chat or secrets into this file.

## 2026-11-26 - Conversation log maintenance rule

- `AGENTS.md` now requires every contributor and future Codex session to update this log after conversations that establish or change project requirements, decisions, setup state, implementation status, blockers, or next steps. Keep entries concise, dated, and free of secrets or transient chat.

## 2026-11-26 - Development computer inventory

- Verified VS Code 1.148.0 and Git 2.50.1 are installed.
- Android Studio is installed at `C:\Program Files\Android\Android Studio`; its bundled Java runtime is OpenJDK 21.0.10. Java is not available on the command-line PATH.
- Android SDK exists at `%LOCALAPPDATA%\Android\Sdk`. Platform Tools include ADB 1.0.41, Command-line Tools are installed, and Android platforms 36 and 36.1 plus Build Tools 35.0.0, 36.1.0, and 37.0.0 are present. An emulator installation is present.
- `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset; ADB is not on PATH. ADB runs by full path and currently reports no connected devices.
- No global Gradle installation is present. Prefer a Gradle Wrapper in the project, so a separate global Gradle install is not required.
- Android SDK Platform 33 is not currently installed. Consider adding it if building against API 33; newer compile SDKs can also honor minSdk 33, so choose compileSdk when establishing the project build configuration.

### Follow-up: command-line environment configured

- Set persistent Windows user variables `ANDROID_HOME` and `ANDROID_SDK_ROOT` to `%LOCALAPPDATA%\Android\Sdk`, and `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr`.
- Added Android `platform-tools`, `cmdline-tools\latest\bin`, `emulator`, and Android Studio JBR `bin` to the persistent user PATH. Verified `java`, `adb`, and `sdkmanager` resolve in the current shell; Java 21 and ADB 1.0.41 run successfully.
- After restarting VS Code, verified the SDK and Java environment variables and command paths are available in its terminal; `adb devices -l` still lists the Galaxy Watch 4 as connected.
- The Galaxy Watch 4 was paired with this computer over Wi-Fi using ADB. `adb devices -l` reports the watch as a connected `SM_R870` device. Do not store temporary pairing codes in this repository log.
- Watch 4 clarification: Developer options is hidden until Developer Mode is enabled. Samsung's Galaxy Watch setup path is Settings > About watch > Software; tap Software version five times. Then open Developer options, enable ADB debugging and Wireless debugging.

## 2026-11-26 - Project start and setup

- The developer environment is VS Code in the `wearos-flight-computer` repository. VS Code is already installed and in use.
- A Samsung Galaxy Watch 4 is available for testing. The primary target remains Galaxy Watch Ultra 2; Watch 4 is the secondary target and current test device.
- ADB (Android Debug Bridge) is the Android developer tool used to connect to a watch from the computer, install the app, and inspect logs. For a Galaxy Watch, it can commonly connect wirelessly after enabling Developer Options and Wireless Debugging. ADB setup is still to be done when deploying the first build.

## Product decisions and clarifications

- **Application:** Swirlock Wearable Flight Computer
- **Repository:** `wearos-flight-computer`
- **Package ID:** `com.swirlock.flightcomputer`
- **Minimum platform:** Wear OS 4 / Android API 33
- **Implementation:** Manufacturer-independent Kotlin app using standard Android and Wear OS APIs.
- **Terrain:** Offline Copernicus DEM GLO-30 coverage for Romania initially. Coverage must be extensible without application code changes. AGL must be unavailable or marked unreliable when inputs or terrain data do not support a trustworthy estimate.
- **Initial fuel model:** 5.0 L starting fuel; 0.20 L fixed takeoff consumption; 5.5 L/h climb, 3.5 L/h level, and 0.8 L/h idle descent; orange at 2.5 L; red at 1.0 L; critical endurance at 15 minutes.
- Fuel rates and thresholds are provisional, uncalibrated constants centralized in application configuration. Estimation must account for uncertainty in engine power and must not assume every descent is at idle.
- Flight detection and phase classification use provisional engineering parameters, not fixed product requirements. The algorithm combines GPS, barometric altitude, vertical speed, and motion sensors; it must support low-altitude powered flight, terrain surfing, level flight, climbs, and descents.
- Takeoff and landing detection must be automatic, with no pilot confirmation or interaction. The classifier should distinguish flight from walking, running, and ground vehicle movement as reliably as possible.

## Still to resolve or validate

- Set up Android SDK/build tooling and ADB wireless debugging for the Watch 4, if not already installed/configured.
- Choose terrain packaging and update workflow for Romania and future coverage areas.
- Calibrate fuel and flight detection parameters with recorded or observed flight data.
- Verify unattended foreground activation, off-wrist display behavior, and sustained bright-screen battery life on target firmware.
- Confirm exact display typography/colors and the supported device/SDK range beyond the stated minimum.

## 2026-11-26 - Recommended next milestone

- The repository currently contains the product instructions and conversation log only; no Android project has been scaffolded.
- Recommended first implementation milestone: create a minimal Kotlin/Gradle Wear OS app with the agreed package ID and API 33 minimum, build and install it on the connected Watch 4, and show a static circular instrument mock. This validates the build and display path before implementing automatic flight detection.
- After the first install, add a small on-device sensor/location diagnostics view or log path to verify which required sensors and GNSS updates are available on the Watch 4.

## 2026-11-26 - First app scaffold installed

- Created a standalone Wear OS Compose app with package `com.swirlock.flightcomputer`, minSdk 33, targetSdk/compileSdk 36, and a static circular instrument mock labeled `DEMO DATA`. It currently uses sample values only; it does not read sensors, GNSS, terrain, or fuel state.
- Added a Gradle 9.6.1 Wrapper and build/install instructions in `README.md`. The build uses Android Gradle Plugin 9.5.3, Compose BOM 2026.06.00, and Wear Compose Material 3 1.6.2. Newer Compose/Wear Compose releases required API 37, so the initial app uses versions compatible with the installed API 36 SDK. The Android SDK manager on this computer did not recognize the API 37 platform package.
- `:app:assembleDebug` succeeds, and the APK installed and launched on the connected Galaxy Watch 4 (`SM-R870`, Android 16 / API 36).
- Captured and visually checked the round-screen layout while the watch was awake; moved the lower readouts inward to avoid overlap with the fuel ring. After idle, the watch returns to ambient/dozing behavior and the screenshot no longer shows the full bright UI. Continuous active-flight display behavior remains to be investigated.

## 2026-11-26 - Large-type instrument revision

- User reports that distance-corrected contact lenses make the close watch display out of focus. Prioritize large, high-contrast flight readings over decorative compass detail.
- Narrowed the compass band, kept the 5-degree ticks and 30-degree labels, reduced the HOME arrow, and enlarged the key type: track 44, AGL 38, ground speed 40, HOME 27, flight time/vertical speed 26, and fuel 26 design units. Increased label contrast and size as well.
- Rebuilt and installed the revised static demo on the Watch 4. A fresh screenshot while awake confirms the readings fit on the round display. It remains mock data; ask the user to assess actual readability on-wrist before treating the type sizes as final.

## 2026-11-26 - Compass and center marker revision

- Tightened the compass band again, enlarged the 12-o'clock pointer and changed it to red. N/E/S/W are now large, bold, differently colored bezel labels, with nearby tick marks removed; other 30-degree labels remain.
- Replaced the HOME arrow and text label with a green circular distance marker containing `1.3 km` and a directional notch. Replaced the visible FUEL word with a cyan gas-pump icon while retaining the numeric estimate.
- Enlarged the main readouts further and adjusted the lower row to keep the fuel icon clear of the flight timer. The build succeeds, the update is installed on the Watch 4, and the awake round-screen screenshot has been visually checked. All readings are still static demo data.

## 2026-11-26 - Home marker refinement

- Centered the home distance marker at the screen center, reduced its diameter, and changed it to a solid green circle. The distance is now displayed as bold `1.3` with `km` centered underneath.
- Enlarged the directional notch and changed it to red. Rebuilt and reinstalled successfully; the awake Watch 4 screenshot confirms the centered circle and stacked distance fit.
- Darkened the circle fill, made the notch a brighter red, and changed both distance lines to bold white. The updated build is installed and visually checked on the Watch 4.

## 2026-11-26 - Purple compass letters and spacing revision

- Made N/W/S/E larger, bold, and bright purple. They are drawn above the compass/fuel arcs so the ring does not obscure them, and nearby tick marks remain suppressed.
- The Watch 4 screenshot review showed the lower E crowding the fuel pump and the flight readouts. Moved the flight timer, vertical speed, and fuel row inward/upward to separate the bezel and instrument labels.
- Rebuilt and installed successfully; the awake Watch 4 layout was visually checked after the spacing change.

## 2026-11-26 - Glanceability layout redesign

- User found the previous screen crowded and hard to use. Reviewed the Watch 4 capture and reorganized the visual hierarchy: kept the track/time header, placed AGL and GS as a balanced left/right pair around the centered HOME marker, grouped flight time and vertical speed symmetrically below, and grouped the pump icon with the fuel quantity on the bottom row.
- Moved `DEMO DATA` away from the red home notch. Put large purple cardinal letters on the outer bezel above the compass/fuel arcs, with clear tick gaps. Reduced visual competition from small bezel details while retaining the 30-degree labels.
- Applied Wear OS guidance to reserve the strongest display text for glanceable metrics and use high contrast; reference: https://developer.android.com/design/ui/wear/guides/styles/typography/accessibility. Also checked W3C contrast guidance: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html.
- Rebuilt and installed successfully; visually checked an awake Watch 4 screenshot. The screen still contains static demo values.

## 2026-11-26 - Distinct vertical instrument layout

- User reported that the installed revision still looked too similar to the previous layout. Reworked the screen into a compact track/time header, an isolated centered HOME distance marker, a paired AGL/GS band, and a single compact lower band for flight time, fuel and vertical speed.
- Moved the fuel arc outward to create more interior space and omitted small degree labels along the lower arc so they do not collide with telemetry. Retained the large bright-purple N/W/S/E cardinal letters and red index/notch.
- Rebuilt and reinstalled the APK on the connected Galaxy Watch4. Captured and visually inspected the awake display. Values remain static demo data; the user should judge real on-wrist readability before typography is considered final.

## 2026-11-26 - Flight display redrawn from scratch

- User rejected the accumulated radial layout as cramped and asked for a fresh interface. Replaced the crowded clustered readouts with a three-zone face: large track/time header, central green HOME direction arrow and distance, paired AGL/ground-speed readings, and a compact lower row for flight time, vertical speed and fuel.
- Rebuilt the compass perimeter with sparse graduations and small white cardinal markers; retained a thin fuel arc at the perimeter. Removed the solid HOME disk, oversize purple cardinal letters, and lower degree labels.
- Rebuilt and installed the new screen on the connected Galaxy Watch4, woke the display, and inspected an awake screenshot. The values are still static demo data.

## 2026-11-26 - Bottom-row collision fixed

- On close review, the rotating E cardinal marker touched/overlapped the lower-left flight-time reading, while the pump icon was crowded against the fuel value. Replaced the stacked lower items with three separated single-line columns (flight time, fuel, vertical speed) and centered the pump icon above the fuel value.
- Rebuilt, reinstalled and reopened the corrected screen on the Watch 4. Checked a fresh awake screenshot; the lower data row and E marker now have visible separation.

## 2026-11-26 - Foreground sensor diagnostics

- Kept the watch face layout and static DEMO values unchanged. Added a foreground-only diagnostics collector for pressure, accelerometer, gyroscope, magnetic field, and GPS; sensor readings and GPS fix quality are logged without writing coordinates or flight records.
- Watch 4 hardware inventory exposes GPS, barometer, accelerometer, gyroscope, and compass. Installed the updated app and granted its precise-location runtime permission on the paired test watch. Logcat confirms all four sensor listeners register and emit live readings; the GPS provider accepts a 1-second request, but no satellite fix arrived during this indoor check.
- Added `adb logcat -s SwirlockSensors` instructions to README. This diagnostic collector stops when the Activity leaves the foreground and is not yet the background flight service or production telemetry pipeline.
- Build check: `:app:assembleDebug` succeeded. Official Android references: [sensor lifecycle and registration](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview) and [LocationManager updates](https://developer.android.com/reference/android/location/LocationManager).

## 2026-11-26 - Continue without outdoor watch testing

- User cannot perform an outdoor GNSS check, and the wireless ADB link would not remain available away from the computer. Do not make a live outdoor session a prerequisite for development.
- Clarified that ADB disconnection does not stop the app or its on-watch sensor listeners; it only prevents live installation/log inspection until reconnection. Continue using verified indoor pressure/motion data and represent GPS-dependent readings as unavailable until a fix exists. Defer field validation until practical.

## 2026-11-26 - Restored first face and enabled compass sources

- User preferred the original instrument-style face, so restored its perimeter graduations/fuel ring, centered HOME arrow, side AGL/GS readouts, and lower flight-time/vertical-speed/fuel row. Kept the known bottom-row spacing correction.
- Made the compass use a fresh reliable GPS course when available (speed at least 1.5 m/s, reported horizontal accuracy no worse than 30 m, and fix age at most 5 seconds). When GPS course is missing or stale, the compass falls back to tilt-compensated accelerometer/magnetometer heading. The header identifies the source as TRK or amber MAG; if both sources are stale/unavailable, it shows `---°` and holds the rose at north-up.
- Installed on the Watch 4 and visually confirmed indoor magnetic fallback displaying a MAG heading. GPS course could not be exercised without a satellite fix. Other face readings remain explicitly marked DEMO DATA.
- Updated AGENTS.md and README.md so the magnetometer fallback/source-label requirement persists. Build `:app:assembleDebug` succeeded.
- Final on-watch visual review found the restored lower labels initially shared the HOME row. Moved FLT/V/S below HOME and above the fuel-arc endpoint; rebuilt/reinstalled and checked the awake screenshot for separation. The Watch 4 currently displays a live MAG fallback heading because no GPS course is available indoors.

## 2026-11-26 - Cardinal letter color and size

- Updated only cardinal glyph appearance: increased N/E/S/W from 19 to 22 design units, kept bold weight and their existing radius, colored E/S/W bright orange and N red.
- Built and installed on the Watch 4; the awake screenshot confirms the requested colors and size.

## 2026-11-26 - Compass jitter investigation

- User reports visible jitter in the compass. Current MAG fallback derives azimuth from accelerometer plus magnetometer, then applies only a 0.25 per-sample circular low-pass filter at a 200ms sensor request period; the registered gyroscope is not fused into compass orientation.
- A prior magnetometer log sample was approximately (352, -363, -1067) µT, vector magnitude about 1,181 µT. Android documents magnetic sensor values in µT; this is far above Earth's usual roughly 25–70 µT field and suggests strong local interference or calibration distortion. Cause is not yet confirmed.
- Next improvement: use a rotation-vector/gyro-fused heading where available, add magnetic-field quality checks and stronger smoothing with responsive turn tracking. Avoid claiming the current MAG heading is stable aircraft heading.

## 2026-11-26 - Compass smoothing and sensor-quality update

- Replaced the direct accelerometer/magnetometer heading on the Watch 4 with its Android rotation-vector sensor; added a direct tilt-compensated accelerometer/magnetometer fallback for devices without a rotation vector. GPS course remains preferred and labeled TRK; magnetic direction is labeled MAG.
- Added circular angle smoothing with a small deadband, faster response for larger turns, Compose interpolation between sensor updates, and filtering for GPS course. Rejects unreliable rotation-vector status, valid estimated heading errors above 45 degrees, and magnetic vector magnitude outside the provisional 15–100 µT range. Unknown rotation-vector accuracy metadata does not reject an otherwise accurate sensor event with a plausible magnetic field.
- Build succeeded and the updated APK is installed/open on the Watch 4. Stationary on-watch log samples remained 94° for 12 seconds during one capture and 91° for 5 seconds after the final build; magnetic magnitude was 15–24 µT and the sensor reported high status. This checks stationary stability; responsiveness while physically turning the watch remains to be observed.
- Updated README.md and retained the original preferred face. Android reference: [position sensors and rotation-vector data](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position), [SensorEvent rotation-vector accuracy](https://developer.android.com/reference/android/hardware/SensorEvent).

## 2026-11-26 - Tilt-aware compass and readable degree labels

- User reports MAG compass heading only works with the watch horizontal and rotating degree numerals visibly tremble, while cardinal letters appear steadier.
- Replaced Euler azimuth extraction for both rotation-vector and direct accelerometer/magnetometer paths with the bearing of the watch screen's top edge projected onto Android's horizontal east/north plane. This handles ordinary device tilt; when the top edge points nearly vertical, its horizontal bearing is mathematically undefined, so the app rejects that sample instead of showing an arbitrary heading. GPS track behavior is unchanged.
- Increased the rotating 30-degree numeric labels from 13 to 16 design units, bold, to match stronger visual weight of the cardinal labels and reduce perceived shimmer while the rose moves.
- `:app:assembleDebug` succeeded; installed and opened on the connected Galaxy Watch4. Physical tilt-angle behavior and perceived numeral motion still need user observation on-wrist. Official Android reference: [SensorManager rotation matrix and device-to-world coordinates](https://developer.android.com/reference/android/hardware/SensorManager).
- Follow-up refinement: Android's standard azimuth is already tilt-aware for the screen-top axis, but becomes singular when that axis is nearly vertical. Added a 3D fallback to the screen-normal axis in that posture, so MAG remains available when the watch is held upright; an exact axis transition may change which physical watch-face direction the heading represents. Build succeeded again and APK reinstalled/reopened.
## 2026-11-26 - Prevent compass tumble at steep inclinations

- User compared the Watch 4 display with Samsung's compass and reports the app tumbles at high watch inclinations.
- Root cause found in the previous change: the MAG heading switched abruptly from the screen-top axis to the screen-normal axis near vertical, although those axes represent different directions. Also, when all heading data expired, the display animator targeted 000 degrees, rotating the rose to north and making sensor loss look like a compass tumble.
- Removed the alternate-axis switch. The compass now keeps one screen-top heading reference, rejects numerically unstable near-vertical samples, and holds the last valid value only until the magnetic heading becomes stale. When no source is valid, the rose holds its last displayed orientation instead of animating to north; numeric heading shows unavailable.
- `:app:assembleDebug` succeeded, and the APK was installed/opened on the connected Watch 4. No physical steep-angle comparison was possible from the computer; user should check whether the axis-switch jump is gone. Near the exact vertical singularity this axis-based compass can still become unavailable; it will not jump to a different axis.
- Android describes rotation-vector data as a full 3D orientation referenced to east/north/up: [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion).
## 2026-11-26 - Wireless fuel-level sensor feasibility

- User asked whether a wireless tank-mounted fuel sensor could report remaining fuel to the watch. Feasible in principle over BLE, but no sensor hardware or implementation selected yet.
- Potential low-intrusion prototype: external non-invasive ultrasonic level transducer on a compatible tank, connected to a small BLE GATT transmitter. Tank shape/material/thickness, vibration, slosh, mounting, calibration, power, and fuel-vapor/environmental suitability need validation; generic industrial sensor claims do not establish paramotor-flight suitability. Do not modify or penetrate the tank without a purpose-designed, qualified installation.
- Fuel indication from a sensor should remain distinct from the existing modeled estimate until accuracy and failure behavior are validated. Android Wear OS supports BLE GATT clients: https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview.
## 2026-11-26 - Statistical fuel-level readings

- User prefers the external ultrasonic approach and accepts slow fuel-level updates. Plan to statistically aggregate repeated samples across approximately one minute (provisional window) so short-term tank slosh does not dominate the displayed value.
- Updated AGENTS.md with this direction. Tank compatibility, level-to-volume calibration and flight suitability remain to be established. Averaging does not remove sustained attitude/acceleration bias, so calibration and confidence handling remain necessary.
## 2026-11-26 - Navigation source abstraction and inertial fallback

- User requests two navigation source modes: GPS and magnetic-plus-inertial when GPS is unavailable, with navigation calculations abstracted from sensor acquisition.
- Updated AGENTS.md to require a common navigation-solution interface and automatic source selection, with GPS as the absolute position/track source and MAG+inertial as degraded relative dead reckoning. Preserve session/HOME on source changes and publish source, validity and uncertainty.
- Documented the watch IMU limit: accelerometer/gyro drift and vibration make standalone position unreliable; magnetometer supplies heading but not position/speed. Without a prior GPS anchor and velocity reference, no absolute HOME navigation can be derived. Never present the fallback as precise or safety-guaranteed.
- Technical basis: Android documents linear acceleration offset/calibration needs; NASA describes inertial position drift from accelerometer bias and vibration and correction with GPS: https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion ; https://ntrs.nasa.gov/citations/20120014137.
## 2026-11-26 - Keep dead-reckoned navigation live through GPS loss

- User corrected the degraded-navigation requirement: do not blank navigation values when GPS is jammed during flight. The instrument must keep doing its best automatically, without pilot confirmation, so the pilot can navigate toward familiar landmarks.
- Revised AGENTS.md: propagate the latest GPS position/velocity through the MAG + inertial estimator; continue estimated track, speed and HOME geometry; communicate source and increasing uncertainty unobtrusively. Preserve HOME/session across provider changes. No GPS anchor is a genuine absolute-position limit, not a reason to fabricate precision.
- No navigation implementation exists yet; this updates the product requirement and architecture direction only.
## 2026-11-26 - Corrected local inertial HOME initialization

- User correctly points out that inertial navigation can integrate a 3D trajectory using accelerometer and gyro measurements, as used by submarines. Clarification: inertial navigation needs an initial position and velocity, but HOME-relative navigation can define the launch point as a local origin; it does not require GPS latitude/longitude.
- Corrected AGENTS.md: initialize the local origin at launch/start, use automatically detected stationary standby for zero-velocity initialization where possible, retain the rolling sensor buffer to integrate the takeoff run, and compute HOME bearing/distance from local relative displacement during GPS loss. GPS remains valuable for absolute map coordinates and corrections, but is not a prerequisite for a local inertial HOME frame.
- Low-cost watch IMU drift, mounting movement, vibration and wind still make the estimate uncertain; preserve best estimates and communicate their quality. This corrects the overly restrictive statement in the preceding navigation entry.
- Reference: the USCG Navigation Center describes INS position as double-integrated inertial measurements and lists initial position/velocity errors among INS error sources: https://navcen.uscg.gov/sites/default/files/pubs/gpsaug/auggps.pdf. Smithsonian overview of submarine INS notes periodic external position updates are used to maintain accuracy: https://timeandnavigation.si.edu/satellite-navigation/reliable-global-navigation/first-satellite-navigation-system/navigating-a-submarine.
## 2026-11-26 - Separate navigation providers; MAG/inertial-only exercise build

- User wants GPS and MAG/INRT kept as clearly separate code components behind an input-independent navigation solution API. The current exercise must run without GPS; a future GPS provider should be easy to add.
- Added `NavigationProvider` and shared `NavigationSolution` models. Moved the active sensor path into `MagInertialNavigationProvider`; created an isolated `GpsNavigationProvider` implementing the same contract. MainActivity wires only the MAG/inertial provider. Removed location permissions and GPS runtime-permission flow from this build.
- Updated README.md and AGENTS.md. The MAG provider currently outputs smoothed magnetic heading and samples motion/pressure sensors; inertial position/velocity integration is not yet implemented. GPS provider is not instantiated. `:app:assembleDebug` succeeded and the APK was installed/opened on the Watch 4; package dump shows no location permissions requested.
## 2026-11-26 - Mathematical GPS-independent INS guide

- User requested an online investigation and a repository guide for reasoning about pure inertial navigation, accepting substantial drift to return near recognizable landmarks without GPS.
- Created `docs/INERTIAL_NAVIGATION_GUIDE.md`. It covers NED/body frames, specific force, quaternion attitude propagation, full and local mechanization equations, launch-origin/zero-velocity initialization, HOME-relative vector calculation, drift sensitivity examples, optional magnetic aiding, strict-IMU separation, test/debug sequence, and software boundaries.
- Findings distinguish standardized strapdown equations/conventions from a single prescribed implementation. The local HOME frame needs initial conditions but not global GPS latitude/longitude. Pure gyro/accelerometer yaw alignment and the difference between submarine-grade systems and a watch IMU are explained. Sources include USCG NAVCEN, NASA mechanization reports, Android sensor documentation, and Smithsonian submarine navigation history.
- Linked the guide from AGENTS.md and README.md. This is a design guide; it does not implement strapdown position/velocity integration.
## 2026-11-26 - Wrist-worn INS is the primary configuration

- User objected that requiring a rigid airframe mount is impractical for the intended wrist-worn watch. Acknowledged and removed that requirement.
- Revised `docs/INERTIAL_NAVIGATION_GUIDE.md` and AGENTS.md: the strapdown equations estimate the watch trajectory; aircraft acceleration must be separated from arm/wrist motion as a nuisance/model error. The estimator should identify likely stable control-holding and local arm-motion intervals, preserve likely aircraft acceleration, and carry uncertainty. A cockpit mount is optional comparison testing only.
- Reviewed wrist-worn inertial localization research. Existing smartwatch PDR work uses learned motion patterns and pedestrian-specific constraints; it supports wrist motion estimation as a research direction but does not validate paramotor-flight performance. References added to guide: IEEE 2026 wrist-worn neural inertial localization and IPIN 2016 wrist-worn pedestrian positioning.
## 2026-11-26 - Mathematical wrist-motion compensation investigation

- User asked whether wrist movement can be compensated mathematically. Researched wrist-worn inertial localization and arm-motion methods.
- Added a rigid-body relative-acceleration model to `docs/INERTIAL_NAVIGATION_GUIDE.md`: watch acceleration includes aircraft translation, rotational lever-arm terms, and arm articulation. A single wrist IMU does not observe all arm-relative position/velocity/acceleration terms, so exact aircraft-motion recovery is not uniquely identifiable without constraints or an additional observation.
- Proposed a best-effort wrist-motion-aware estimator: automatic control-hold/arm-moving/unknown classification, strapdown integration during likely stable control-holding, uncertainty-weighted gating during arm movement without zeroing navigation, and a learned sequence model only after a physics baseline and representative paramotor labels exist. Filtering alone is insufficient because spectra overlap.
- Added sources: IEEE 2026 single-wrist neural inertial odometry and ION wrist-worn pedestrian navigation. These support wrist IMU odometry for human motion but do not validate paramotor flight.
## 2026-11-26 - Clarified wrist orientation versus wrist translation

- User notes that trajectory should not depend on watch orientation and that wrist movement may be only orientation. Clarified and updated the guide/spec: a correct attitude transform removes coordinate-axis rotation; if the IMU origin stays fixed relative to the aircraft, changing watch orientation alone does not change trajectory. Extra acceleration terms arise only when the sensor origin translates relative to the aircraft reference point.
- Updated docs to exploit the likely hands-on-controls condition as an approximately fixed wrist position with changing watch attitude, while modeling actual wrist-origin translation separately. Added a rigid-body acceleration reference for the lever-arm terms.
## 2026-11-26 - Arm swing as bounded endpoint offset

- User points out that arm-swing distances are small compared with a multi-kilometre flight trajectory. Corrected the estimator framing: attitude changes are handled by rotating specific force into the navigation frame; they do not change the trajectory. Actual arm motion means the watch follows its own nearby point, and its endpoint displacement differs from the aircraft reference point by `r(t)-r(0)`, bounded by the physical change in wrist-to-aircraft offset (under ideal integration).
- The first algorithm should therefore integrate the watch's trajectory directly and accept this small endpoint offset for landmark-scale navigation, rather than trying to classify/filter every arm swing. Sensor bias, attitude error and vibration remain separate cumulative drift sources. Updated the inertial guide and AGENTS.md; this supersedes the previous proposal to gate wrist-motion samples as a prerequisite.
## 2026-11-26 - Explicit rotation-invariance criterion for INS

- User asks whether we have the mathematical apparatus to calculate trajectory independently of watch rotations. Clarified that strapdown INS equations are documented, but no trajectory integration is implemented in app code yet.
- Added an explicit synthetic rotation-invariance criterion to `docs/INERTIAL_NAVIGATION_GUIDE.md`: generate body-frame specific-force readings from a known navigation-frame trajectory under several different watch-attitude histories, then require all mechanizations to recover the same position/velocity. Added the stationary gravity-cancellation case to catch frame/sign errors.
## 2026-11-26 - First local strapdown INS implementation

- User asked to begin implementing GPS-independent MAG/inertial navigation and explicitly said not to deploy to the disconnected, discharged watch.
- Added `StrapdownIns`, a local ENU quaternion mechanization integrating raw gyroscope attitude, bias-corrected accelerometer specific force, gravity compensation, and trapezoidal local velocity/position with monotonic sensor timestamps. Long accelerometer gaps are counted and excluded.
- `MagInertialNavigationProvider` performs a two-second stationary initialization, estimates initial gyro/accelerometer bias, uses magnetometer for local-north alignment when plausible (otherwise arbitrary yaw), and publishes local displacement, estimated speed/track, HOME range/bearing and heading through the shared `NavigationSolution`. GPS remains isolated and unused.
- `:app:assembleDebug` succeeds. No watch connection or installation was attempted. No automated rotation-invariance validation has been added or run. Important current limitation: stationary initialization currently sets the local HOME origin where alignment completes; automatic takeoff detection and pre-takeoff rolling-buffer replay are still missing, so it does not yet guarantee actual launch as HOME.
- Android phone compatibility question: the project is native Android/Kotlin, but its manifest currently declares the watch hardware feature required, which prevents phone installation. Phone support would also need a phone-appropriate/adaptive UI; the current instrument screen targets a circular watch display. No phone-target change was made in this implementation step.

## 2026-11-27 - Watch deployment attempt

- User asked to connect the powered-on watch and transfer the current app. `adb devices -l` showed no connected devices, and `adb mdns services` found no wireless debugging services. No connection or installation was possible, and no watch deployment command was run.
- Next setup step: enable Wireless debugging on the watch while it is on the same Wi-Fi network as this computer, then provide the current pairing IP/port (and pairing code if prompted) or connect by USB if supported by the watch/cable.

## 2026-11-27 - Installed inertial build on Galaxy Watch4

- User supplied the current Wireless debugging pair code and endpoint. Successfully paired ADB with the Galaxy Watch4 (`SM_R870`, device ID `adb-RFAR729EBHR-iuyN1t`) over Wi-Fi.
- Rebuilt the current debug APK successfully, installed it with `adb install -r`, and launched `com.swirlock.flightcomputer/.MainActivity`. ADB reported successful install and activity launch.

## 2026-11-27 - Corrected display wake and verified rendered app

- User reported that the watch display was not lit after the prior deployment report. ADB confirmed the app Activity was resumed, but the display was in a dark/ambient state; an initial screenshot was black.
- Sent a display wake interaction, reopened the Activity while awake, and captured a screenshot showing the instrument UI rendering on the 450Ã—450 watch display. The app is now in the foreground. This confirms rendering after wake, not unattended off-wrist or ambient-mode behavior.

## 2026-11-27 - Clarified what is live on the instrument screen

- User observed that only the compass visibly changes after the first INS implementation. Confirmed that MainActivity animates only `NavigationSolution.directionDegrees`; the remaining displayed AGL, ground speed, HOME distance, flight time, vertical speed, and fuel are hardcoded demo strings.
- The MAG/inertial provider calculates local displacement, velocity, track and HOME geometry, but those values are not connected to the corresponding screen readouts. The compass is supplied from the separate magnetometer heading calculation, not evidence that the inertial trajectory is visually working. The implementation is an early calculation core, not yet a live end-to-end inertial instrument.

## 2026-11-27 - Live inertial navigation readouts on Watch4

- User requested the inertial navigation outputs be implemented live on the watch. Wired `NavigationSolution` values to the instrument: heading, relative height from the local INS origin, estimated horizontal speed, vertical speed, HOME distance and a HOME arrow rotated by the relative bearing. Added elapsed time since INS initialization and device local time.
- Removed hardcoded demo values and the fictitious filled fuel arc; fuel now shows unavailable until a fuel estimator exists. Readouts distinguish relative inertial height from AGL and estimated speed/vertical speed from measured values.
- The displayed heading now identifies `MAG` only while magnetometer heading is fresh. Current Watch4 magnetic samples are outside the accepted field magnitude range, so the UI shows `REL INS` and suppresses cardinal/degree bearing labels instead of claiming magnetic north.
- Build succeeded; latest APK installed and launched on the paired Galaxy Watch4. ADB screenshot confirmed the live screen is rendered and currently near zero while stationary. Sensor logs have shown rapid dead-reckoning drift during prior handling; this remains an unvalidated experimental estimator, not a flight-tested navigation instrument. No automated tests were added or run.

## 2026-11-27 - Configurable motion-based fuel estimate

- User requested a best-guess fuel calculation from horizontal and vertical watch speed, with editable model numbers in a calibrated JSON-like file.
- Added `app/src/main/assets/fuel_model.json`, marked `calibrationStatus: provisional`. It centralizes initial fuel/takeoff burn, speed/climb takeoff trigger and hold duration, vertical phase thresholds, input smoothing, horizontal speed bands with climb/level/descent L/h rates, unknown-phase rate, and orange/red/endurance thresholds.
- Added `FuelEstimator`: filters INS speed inputs, activates after sustained speed or climb evidence, deducts the fixed takeoff estimate once, uses vertical phase plus horizontal-speed band to select consumption, integrates against monotonic elapsed time, clamps fuel at zero, and derives ring warning status. Wired it to the watch readout and fuel arc. Pre-takeoff estimate remains at the configured 5.0 L until the provisional trigger fires.
- Updated AGENTS.md and README.md. Build succeeded; current APK installed and launched on the paired Watch4. ADB screenshot showed `FUEL ~5.0 L` with a full cyan arc while the motion trigger had not activated. Inertial speed/position drift remains visible in other fields, so these fuel inputs and all JSON rates are guesses pending real-flight and engine calibration. No automated tests were added or run.

## 2026-11-27 - Manual app launch and Galaxy Watch Ultra2 compatibility

- User asks where to manually launch the app and whether it can run on a Galaxy Watch Ultra2. Current ADB inventory is empty, so this computer cannot currently inspect or relaunch the Watch4 remotely.
- App launcher label is `Swirlock Flight Computer`; launch from the watch's app grid. The APK requires watch hardware and API 33+, and the current project targets API 36 with standard Wear OS/Android APIs.
- Samsung's official Ultra2 pages identify it as a Wear OS 7 watch with Play Store app support. The app should be sideloadable there through Wireless debugging/ADB, but it has not yet been installed or sensor-validated on that device. Current watch-specific magnetic/IMU behavior may differ.
- Source checked: Samsung official Ultra2 page https://www.samsung.com/us/watches/galaxy-watch-ultra2/ and Samsung Business Ultra2 product page https://www.samsung.com/uk/business/watches/galaxy-watch/galaxy-watch-ultra2-titanium-silver-lte-sm-l715fzsaeub/.
