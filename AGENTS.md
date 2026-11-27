# Wear OS Paramotor Flight Computer

## Conversation log maintenance

Keep `CONVERSATION_LOG.md` updated throughout project work. After each conversation that establishes or changes requirements, decisions, setup state, implementation status, blockers, or next steps, add a concise dated entry to the log before finishing the work. Update an existing entry when that is clearer than adding another. Do not record secrets or transient chat that has no lasting project value. Treat this as a required project instruction for every contributor and future Codex session.

## 1. Product overview

A native, standalone Wear OS flight computer for powered paraglider (paramotor) flight, particularly low-level flight with the engine running. The application combines navigation, flight telemetry, automatic flight recording and estimated fuel consumption in a single circular aviation instrument display.

The primary devices are the Samsung Galaxy Watch Ultra 2 and Galaxy Watch4 with updated firmware. The application is intended to be portable to other Wear OS watches with compatible sensors and display capabilities.

Typical flight duration is one to two hours. The watch is fully charged before takeoff and recharged after landing. It may be worn over a flight suit or mounted on the cockpit, without contact with skin. The pilot may be wearing gloves and holding the flight controls throughout the flight.

The application operates independently of a phone, internet connection, backend, user account or subscription. Following initial installation and device permissions, a normal flight requires no application-related interaction before takeoff, in flight or after landing.

## 2. Automatic operation

The application has two principal operating modes:

| Property | Ground standby | Active flight |
|---|---|---|
| Display | Normal watch behavior | Flight instrument screen continuously visible |
| Brightness | Normal system behavior | Maximum brightness permitted by the device |
| GPS | Continuous or adaptive monitoring suitable for takeoff detection | Continuous, target at least one fresh fix per second |
| Barometer and motion sensors | Low-power monitoring where feasible | Continuous acquisition |
| Energy policy | Battery conservation | Instrument availability and readability take priority |
| Recording | Short rolling telemetry buffer | Persistent flight session |
| Pilot interaction | None | None |

Takeoff is detected automatically. The flight screen activates without an arm command, recording button, notification tap or confirmation. The takeoff position is stored as HOME, the flight timer begins and fuel estimation starts.

Landing is detected automatically. The flight is saved locally, the active-flight display policy ends and the watch returns to normal ground standby behavior.

## 3. Flight instrument display

![Flight display concept](assets/flight-display-reference.png)

The reference image illustrates the overall visual style. The application display is a flat, circular, black-background instrument panel, not a rendering of a physical watch case. Compass graduations and fuel colors in the illustration are conceptual; their actual behavior is defined below.

All instruments are visible simultaneously on one screen. Large, high-contrast numerals and saturated colors support rapid reading in daylight. The layout scales proportionally to the usable diameter of a round display.

| Instrument | Position | Display example |
|---|---|---|
| Compass rose | Outermost circular perimeter | N, E, S, W and degree graduations |
| Fuel gauge | Continuous circular arc immediately inside the compass | Blue, orange or red |
| GPS track | Top center | `TRK 247°` |
| Local time | Below track | `22:49` |
| Height above ground | Left | `AGL ≈38 m` |
| Ground speed | Right | `GS 47 km/h` |
| HOME direction | Center | Large green navigation arrow |
| HOME distance | Below arrow | `HOME 1.3 km` |
| Flight time | Lower left | `FLT 00:18` |
| Vertical speed | Lower right | `V/S +0.8 m/s` |
| Estimated fuel | Bottom center | `FUEL ≈3.2 L` |

### Compass rose and numeric direction

The exterior compass rose rotates relative to GPS track, with the current direction of travel at the fixed twelve-o'clock index. Minor graduations are spaced at 5°, with emphasized major graduations and labels at 30° intervals, including the cardinal directions. Labels remain readable as the rose rotates.

When GPS course is valid, the numeric direction is a three-digit angle from 000° to 359°, referenced to true north and labeled TRK. GPS track represents the direction of movement over the ground rather than the physical heading of the aircraft. When GPS track is unavailable or unreliable, use the Android rotation-vector sensor (or tilt-compensated accelerometer/magnetometer readings if no rotation vector is available) and label its magnetic heading MAG. Calculate the bearing by projecting the watch screen's top-edge vector onto the horizontal plane and keep that reference axis fixed. Never switch to another device axis to conceal the top edge's near-vertical singularity, as that creates a discontinuity. Reject unstable near-vertical samples and hold the last stable bearing only while it remains fresh; otherwise show unavailable. Apply circular smoothing, sensor-quality checks and smooth display interpolation. The display must make the source clear; magnetic heading can be disturbed by nearby magnetic material. Wind can cause aircraft heading and ground track to differ.

### Fuel ring

A thick, continuous, circular arc represents the estimated fraction of the initial 5 L of fuel remaining. It is positioned directly inside the compass rose, leaving the graduations visible. The filled arc starts at twelve o'clock and extends clockwise. Its length decreases as the estimated fuel is consumed. The unfilled portion is dark gray.

The entire filled arc has one saturated status color at any instant:

- **Blue:** normal remaining fuel.
- **Orange:** reduced fuel reserve.
- **Red:** low remaining fuel or short estimated time to empty.

The colors are deliberately vivid. Arc length and the numeric estimate provide information independently of color. The numeric fuel value is expressed in liters with one decimal place and an estimate indicator. At zero estimated fuel, the numeric indication remains red even though the filled arc is empty.

### HOME navigation

The center of the display contains a large, highly visible arrow toward the takeoff location. The arrow rotates relative to current GPS track: upward means HOME is directly ahead, right means HOME lies to the right of the current trajectory, and downward means HOME is behind.

The distance to HOME is shown immediately below the arrow, in meters below 1 km and kilometers with one decimal place otherwise.

### Remaining instruments

- Local time: 24-hour format, device local timezone.
- AGL: estimated height above the local terrain in meters.
- Ground speed: GPS speed in kilometers per hour.
- Flight time: elapsed hours and minutes since detected takeoff.
- Vertical speed: signed numeric meters per second, derived from filtered barometric altitude changes. No audible or haptic variometer signals.

The display contains no flight-time menus, swipe pages, modal dialogs or mandatory notifications. Invalid or stale measurements have an explicit unavailable or stale indication rather than appearing as current measurements. An unavailable track or HOME bearing suppresses the directional arrow until valid data returns.

## 4. Flight detection and session lifecycle

The flight detector combines GPS, barometric pressure and motion sensor data. It recognizes low-level and approximately horizontal takeoffs as well as climbing departures. A sustained climb or large increase in AGL is not a prerequisite for flight detection.

The lifecycle consists of the following states:

```text
STANDBY → TAKEOFF_CANDIDATE → IN_FLIGHT → LANDING_CANDIDATE → STANDBY
```

During standby, a rolling buffer retains recent telemetry, including GPS positions when available. Takeoff detection considers acceleration, sustained movement, vertical motion and available terrain-relative altitude information. The buffer allows the estimated liftoff position and time to be recovered retrospectively when confirmation occurs after actual liftoff.

The active flight session contains the estimated takeoff time, HOME coordinates, elapsed time, current telemetry, flight phase, fuel estimate and recorded trajectory. The flight timer uses a monotonic clock, independent of changes to the system wall clock.

Landing detection uses sustained evidence of ground-like motion and altitude behavior. Brief low passes, touch-and-go maneuvers, a temporary GPS outage or low ground speed in a strong headwind do not by themselves establish landing. A landing candidate can revert to active flight until landing is confirmed.

Watch-only sensors cannot always distinguish low-level flight from driving, running, elevators or other ground motion. Detection thresholds, filtering windows, confidence rules and transition delays remain empirical parameters. Sensor degradation is represented independently of the flight lifecycle; a missing measurement does not reset the session.

The session is persisted locally, including its identifier, start time, HOME, accumulated fuel consumption and recent telemetry. Activity recreation or process recovery retains the existing session rather than starting a new one or deducting takeoff fuel twice. A device reboot or missing telemetry interval introduces explicit uncertainty where the original session cannot be reconstructed exactly.

## 5. Navigation calculations

### Ground speed and track

Ground speed comes from GNSS and is converted from meters per second to kilometers per hour:

```text
GS_kmh = GS_ms × 3.6
```

GPS course over ground provides the true track angle. The compass and arrow use the same track reference when GPS track is valid. When GPS is lost in flight, continue navigation from the fused MAG + inertial estimate. Its estimated movement direction comes from propagating local-frame inertial velocity; it is not equivalent to GPS ground track and accumulates sensor error. If an estimate is derived from magnetic heading or airspeed rather than inertial ground motion, wind can make it differ from ground track.

### Navigation sources and degraded mode

The mathematical design reference for the GPS-independent strapdown estimator is [docs/INERTIAL_NAVIGATION_GUIDE.md](docs/INERTIAL_NAVIGATION_GUIDE.md). Follow its stated coordinate and sign conventions; keep strict accelerometer/gyroscope-only mechanization separate from optional magnetic aiding.

Navigation calculations must be independent of sensor acquisition through a common `NavigationProvider` / navigation-solution interface. Keep each input path in a separate component. The current development exercise is strictly MAG + inertial: it wires only the MAG/inertial provider, has no location permission, and must not access GPS. Keep a separate GPS provider component implementing the same interface, but leave it unwired until the GPS phase. The eventual GPS-enabled application may automatically select the best source:

1. **GPS:** use valid GNSS position, speed and track.
2. **MAG + inertial:** when GPS is unavailable or stale, use magnetic heading plus gyroscope/accelerometer/barometer observations for a best-effort relative dead-reckoning solution.

Both providers publish the same domain solution shape: geographic position or local relative displacement, track/heading, speed, HOME bearing/distance, source mode, validity and uncertainty/confidence. The UI and flight session consume this solution rather than sensor-specific APIs. When GPS is later enabled and returns, hand back to GPS without creating a new session or HOME point.

The MAG + inertial mode is degraded navigation, not an equivalent GPS replacement. During an active flight, never blank navigation values solely because GPS became unavailable. Propagate the latest fused position and velocity using the inertial observations and magnetic orientation, continue estimating track, speed and HOME bearing/distance, and increase the indicated uncertainty as the outage continues. Do not require a geographic GPS fix to define HOME: initialize a local navigation frame with the launch/start point as the origin and use detected stationary standby to initialize zero ground speed when possible. Retain the pre-takeoff sensor buffer so the motion into takeoff can be integrated retrospectively. GPS fixes, when available, improve the geographic anchor and velocity estimate. Keep source/quality visible but unobtrusive and require no pilot confirmation or mode selection.

The primary inertial configuration is the watch worn on the pilot's wrist while flying. Do not require a rigid airframe mount. Watch orientation changes alone are coordinate changes handled by the attitude transform. Integrate the watch's own trajectory; arm motion changes the watch-to-aircraft offset, so its effect on endpoint displacement is bounded by the change in that physical offset, while sensor bias/attitude error cause separate cumulative drift. Do not discard genuine watch acceleration merely because the arm moved. A cockpit mount is optional for comparison testing only.

A local inertial trajectory can be integrated from a known launch origin and initial velocity without knowing global latitude/longitude. Use the launch origin and estimated local displacement to calculate HOME bearing and distance in that local frame. A watch IMU's acceleration bias, attitude error, mounting movement and vibration accumulate into velocity/position drift; the magnetometer supplies magnetic orientation, not position or airspeed. Use the best continuous estimate rather than freezing the display or replacing it with zero/north. If stationary initialization is unavailable, use the best recent motion history and carry greater uncertainty instead of discarding the navigation solution. Confidence must decrease with elapsed outage and sensor quality. This mode is advisory dead reckoning and is not a guaranteed return route.

### Compass and HOME bearing

```text
normalize360(a) = ((a % 360) + 360) % 360
normalize180(a) = normalize360(a + 180) - 180
compassScreenAngle = normalize360(markTrueBearing - trackTrue)
homeArrowAngle = normalize180(homeTrueBearing - trackTrue)
```

Screen angles are clockwise from the top. The HOME true bearing and distance are calculated between the current fused navigation position (GNSS in GPS mode, propagated estimate in MAG + inertial mode) and the recorded takeoff position. In MAG + inertial mode, these are continuing best estimates with uncertainty that grows during GNSS outage. Near HOME, the bearing may become undefined or unstable; the distance remains available where valid.

HOME indicates the desired direct ground track toward takeoff, not an obstacle-cleared route or a prediction of sufficient fuel to return.

## 6. AGL and vertical speed

### Height above ground

```text
AGL = aircraft altitude MSL − terrain elevation MSL
```

Terrain elevation is obtained from an offline digital elevation dataset stored on the watch. A candidate source is Copernicus DEM GLO-30 or a suitable regional terrain model. The dataset covers the intended flight areas and includes its spatial reference, vertical datum, coverage and missing-data metadata. Terrain tiles can be prepared outside the watch and transferred to local storage.

Aircraft altitude is derived from barometric altitude changes referenced to an appropriate MSL anchor, with GNSS altitude available as a reference or correction source. GNSS ellipsoid altitude and terrain elevations in an orthometric datum require compatible datum conversion before subtraction. At takeoff, known terrain elevation can provide an approximate barometric anchor.

The terrain elevation is interpolated from valid local DEM cells. Missing tiles, invalid altitude references or unusable location data result in an unavailable AGL reading. Launch-relative altitude is not interchangeable with AGL over changing terrain.

AGL is an estimate, not a direct measurement of clearance. At very low altitude, terrain resolution, GNSS accuracy and pressure errors may exceed the actual height above ground. The application has no obstacle, wire or collision detection capability.

### Numeric variometer

The pressure sensor provides relative altitude changes. Vertical speed is the filtered rate of altitude change over monotonic time, expressed in m/s. A smoothing window of approximately 3–5 seconds is a provisional parameter. The numeric variometer is silent and is also an input to the fuel phase classifier.

## 7. Fuel estimation

The current application has no physical fuel-level or throttle-position sensor, so its implemented fuel gauge is a software estimate based on flight duration and inferred engine operating conditions. The intended hardware direction is to investigate a non-invasive external ultrasonic tank-level sensor with wireless BLE transmission to the watch.

### Proposed external fuel-level sensor

The initial candidate is an ultrasonic sensor attached to the outside of a compatible tank, with a BLE transmitter sending samples directly to the watch. Do not drill into or modify the tank as part of a generic sensor installation. The actual tank material, wall thickness, shape, sensor mounting and acoustic coupling must be checked before selecting hardware. Flight suitability, secure mounting, vibration, temperature, fuel-vapor exposure, electrical safety and wireless reliability require validation.

Fuel level is expected to move with sloshing. The intended display value is a slow statistical estimate from repeated readings over approximately one minute (provisional), rather than an instantaneous sample. Reject invalid readings and validate how the measured level converts to liters against the actual tank geometry. Averaging can reduce short-term slosh variation, but it cannot remove persistent bias from sustained aircraft attitude or acceleration. The sensor path and model-based fuel estimate require distinct validity/confidence handling until sensor accuracy and failure behavior are established.

Each flight starts with exactly **5.0 L** in the tank. Takeoff consumes a fixed estimated amount, deducted once. Subsequent fuel consumption depends on the detected flight phase: normal climb, approximately level flight or descent. Descent is assumed to correspond approximately to idle operation, with a low but nonzero consumption rate.

The development estimator filters the INS horizontal-speed and vertical-speed estimates. Sustained speed or climb evidence provisionally activates the fuel estimate and applies the one-time takeoff deduction; then a horizontal-speed band and vertical phase select the configured burn rate. This activation heuristic is not the completed flight detector. Current provisional thresholds and rates live in `app/src/main/assets/fuel_model.json` and must be calibrated against measured engine/fuel data.

Fuel-model constants are stored in `app/src/main/assets/fuel_model.json` so provisional values can be edited without changing Kotlin code. The file identifies its calibration status and contains initial/takeoff fuel, takeoff trigger, vertical-speed phase boundaries, smoothing/confirmation windows, horizontal-speed bands with climb/level/descent rates, unknown-phase rate, and warning thresholds. There is no per-flight fuel entry or pilot phase selection.

### Model constants

| Constant | Value | Description |
|---|---:|---|
| `INITIAL_FUEL_L` | 5.00 L | Fixed initial quantity |
| `TAKEOFF_BURN_L` | 0.20 L | Provisional fixed takeoff consumption |
| `speedBands[].climbLph` | 5.0–6.5 L/h | Provisional climb rates by estimated horizontal-speed band |
| `speedBands[].levelLph` | 3.0–4.5 L/h | Provisional level rates by estimated horizontal-speed band |
| `speedBands[].descentLph` | 0.8–1.2 L/h | Provisional descent rates by estimated horizontal-speed band |
| `ORANGE_AT_L` | 2.50 L | Proposed orange threshold |
| `RED_AT_L` | 1.00 L | Proposed red threshold |
| `redEnduranceMinutes` | 15 min | Critical estimated remaining endurance |
| `unknownPhaseLph` | 5.50 L/h | Conservative rate for uncertain vertical phase |

Only the 5 L initial quantity, fixed takeoff deduction concept, phase-dependent consumption model and blue/orange/red status scheme are established requirements. The numerical consumption rates and warning thresholds above are illustrative, uncalibrated values. Fixed takeoff consumption may incorporate pre-takeoff engine running if represented by the calibrated model.

### Flight phase classification

The internal fuel phases are `CLIMB`, `LEVEL`, `DESCENT` and `UNKNOWN`. Classification uses filtered vertical speed, sustained evidence and hysteresis to avoid rapid transitions around level flight. A provisional vertical-speed boundary is ±0.5 m/s. The exact boundary and transition durations are unspecified.

Flight phase is only a proxy for engine power. For example, descending under power or ascending in rising air can produce different fuel consumption than the nominal model. Uncertain classification uses a conservative burn rate.

### Fuel integration

At takeoff:

```text
remainingFuel = INITIAL_FUEL_L − TAKEOFF_BURN_L
```

For each subsequent elapsed interval:

```text
consumedFuel = phaseBurnRate_Lph × elapsedSeconds / 3600
remainingFuel = max(0, previousRemainingFuel − consumedFuel)
fuelFraction = remainingFuel / INITIAL_FUEL_L
fuelArcDegrees = 360 × fuelFraction
```

Consumption is integrated using elapsed time, not a count of sensor events. The estimate is nonincreasing over an active session. A service interruption does not imply zero fuel consumption; known gaps are accounted for conservatively, and unresolved gaps reduce estimate confidence.

Estimated time to empty uses an appropriate conservative consumption rate rather than assuming the current low idle consumption will persist:

```text
estimatedMinutesRemaining = 60 × remainingFuel / conservativeBurnRate_Lph
```

The ring becomes red if the low-fuel threshold is reached or estimated remaining endurance falls below the critical time threshold. Red overrides orange and blue. Estimate uncertainty is indicated separately from the status color. The gauge represents modeled remaining fuel, not measured fuel or guaranteed endurance.

## 8. Display and off-wrist operation

The active flight display remains continuously visible, full-color and updated for the duration of a normal flight. The pilot does not need to raise the wrist, touch the display or press a button. Skin contact and optical wear detection are not part of the application logic.

Active flight operation requests the highest display brightness permitted by the platform. App-controlled timeouts, dimming, ambient-mode transitions and sensor throttling for energy conservation are inconsistent with this operating mode. Ground standby has a separate power policy.

The target duration is two hours of uninterrupted active operation from full charge, with a proposed three-hour endurance target for margin. Battery capacity and sustained brightness vary by device. Existing system-level thermal, battery and lockscreen protections can override application requests.

Wear OS offers `FLAG_KEEP_SCREEN_ON` for a visible Activity and a per-window brightness request (`screenBrightness = 1.0f`). Ongoing Activity mechanisms can support continuity of the flight experience. A foreground service can maintain telemetry acquisition within platform restrictions. These mechanisms do not themselves guarantee automatic launch of an Activity from the background, unlock an off-wrist device, or override firmware brightness policies.

Automatic foreground activation, persistent standby monitoring and off-wrist display continuity are required product behaviors whose feasibility on the target firmware remains unverified. Some devices may require one-time wrist-detection or other system configuration. No per-flight setup action is part of the intended operating model.

## 9. Technical architecture

| Component | Technology / approach |
|---|---|
| Application platform | Native standalone Android / Wear OS |
| Language | Kotlin |
| UI | Jetpack Compose for Wear OS, Compose Canvas for instrument graphics |
| Build system | Gradle Kotlin DSL |
| Development environment | VS Code with Codex; Android SDK; Android Studio optional |
| Device deployment | APK installation through ADB, including wireless debugging |
| Motion and pressure sensors | Android SensorManager |
| Location | On-watch GNSS through Android location APIs |
| Continuous flight processing | Foreground service, subject to platform permissions and lifecycle rules |
| State propagation | Kotlin Coroutines and Flow / StateFlow |
| Local persistence | Room database; local terrain files; DataStore where appropriate |
| Flight track export | GPX, on the ground |

The application consists conceptually of a sensor acquisition layer, flight detector and session state, navigation calculations, terrain lookup and altitude calculation, fuel estimation, flight recording and a reactive instrument display. One active flight session is the source of truth for the displayed telemetry and recorded values.

The domain model contains timestamped sensor observations, source validity and accuracy information, flight session state, HOME coordinates, derived navigation values, estimated AGL, vertical speed, flight phase and estimated fuel. Internal calculations use explicit units and monotonic timestamps where elapsed time is involved. The local flight history contains the recorded trajectory, session timestamps and relevant flight statistics.

The application uses standard Android and Wear OS APIs rather than Samsung-specific SDKs for core functionality. It has no mandatory companion phone application, cloud service or network dependency in flight. No health data or optical sensor measurements are needed.

## 10. Device compatibility

The primary devices are Galaxy Watch Ultra 2 and Galaxy Watch4 with current available firmware. A single application package and proportional round-screen layout support both device sizes.

Other potential devices include compatible Wear OS models from OnePlus, OPPO, Google Pixel Watch, Mobvoi TicWatch and Xiaomi. A proposed minimum platform is Wear OS 4 / Android 13 (API 33). The exact supported SDK range is not yet fixed.

Required device capabilities are an independently accessible GNSS receiver, barometric pressure sensor, motion sensors, a circular color display and sufficient battery endurance. The magnetometer is used as a labeled fallback when GPS course is unavailable.

Support for the full unattended operating mode depends on device-specific firmware behavior, foreground/background restrictions, off-wrist display policies and actual battery endurance. Hardware specifications alone do not establish compatibility. Watches running other operating systems are outside the scope of this application package.

The application name, package identifier, exact typography, color hex values, terrain coverage, calibrated fuel constants and flight detection parameters remain unspecified.
