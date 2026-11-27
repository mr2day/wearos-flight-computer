# GPS-Independent Inertial Navigation: Mathematical Guide

## Purpose and design intent

This document is the project's mathematical guide for developing a navigator that does not depend on GPS. The exercise accepts drift. Its purpose is to estimate the aircraft's trajectory relative to the launch point well enough to indicate a broad direction and approximate distance toward HOME, including after a long GPS-denied flight.

The essential idea is simple:

> Gyroscopes estimate how the sensor frame rotates. Accelerometers measure specific force in that rotating frame. Estimate attitude, rotate specific force into a navigation frame, restore gravity, integrate once for velocity, and integrate again for position.

The equations are established strapdown inertial-navigation mechanization. There is no single source-code standard or one mandatory coordinate convention: implementations choose a frame and signs, then must apply them consistently. This guide uses local North-East-Down (NED) notation and also gives a short-range local approximation suitable for the first experiment. The USCG navigation reference describes the same double integration and the role of gyroscopes in transforming body-frame acceleration; NASA's mechanization report explains the coordinate-frame relationships used in strapdown systems. ([USCG Navigation Center, INS overview](https://navcen.uscg.gov/sites/default/files/pubs/gpsaug/auggps.pdf), [NASA, *Mechanization*](https://ntrs.nasa.gov/api/citations/19740013176/downloads/19740013176.pdf))

The accepted objective is **relative inertial navigation**, not precise global latitude/longitude. Define the launch point as the local origin. If the aircraft is observed at rest before launch, initialize its velocity to zero. GPS can be added as an independent future aiding/input component, but it is not part of this exercise's estimator.

## What “pure inertial” means here

A strict inertial core uses only raw three-axis accelerometer and gyroscope samples. It does not read GPS, magnetometer, barometer, map data, airspeed, or a phone. A magnetometer may be added as a separately identified heading aid, but once used to correct yaw the system is magnetically aided INS rather than pure INS.

Android's `TYPE_ROTATION_VECTOR` may combine gyroscope, accelerometer, and magnetic-field sensors. It is useful for the current compass display, but it is not a clean input for a strict gyro/accelerometer-only experiment. The pure INS path should ingest timestamped raw `TYPE_ACCELEROMETER` and `TYPE_GYROSCOPE` samples, perform its own attitude propagation, and keep optional magnetic aiding outside the core. Android documents the sensor axes, specific-force behavior, and the offset in its linear-acceleration estimate. ([Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion))

## The mathematical state

At time `t`, maintain at least:

```text
x = (p, v, q, b_a, b_g)
```

where:

- `p` is position in the local navigation frame, metres;
- `v` is velocity in that frame, metres per second;
- `q` is attitude, a unit quaternion rotating body-frame vectors into the navigation frame;
- `b_a` is accelerometer bias in body axes, metres per second squared;
- `b_g` is gyroscope bias in body axes, radians per second.

The implementation also carries sensor timestamps, initialization state, and a measure of uncertainty. Biases are not optional bookkeeping: constant bias is integrated into velocity and then position. A covariance or uncertainty model describes how much trust to place in the propagated state; it does not magically correct the state without an observation.

For an estimator that carries formal uncertainty, a common error-state vector is `δx = [δp, δv, δθ, δb_a, δb_g]`. Linearize the mechanization around the current estimate to obtain `δx_dot = F δx + G w`, then propagate covariance with `P_dot = F P + P Fᵀ + G Q Gᵀ` (or its discrete equivalent). This predicts uncertainty growth; without external observations it cannot identify all errors or pull the nominal trajectory back to truth.

Use a right-handed frame and write its axis directions beside every equation. In this guide, NED means `x = North`, `y = East`, `z = Down`. The sensor/body frame follows the watch axes. The rotation matrix `C_b^n` maps a vector expressed in body coordinates into NED coordinates:

```text
v^n = C_b^n v^b
```

Never rely on variable names alone to define transform direction. Unit-test the identity pose, a +90° yaw, and each axis sign with known synthetic vectors before using live sensor data.

### Rotation-invariance check

This is the direct mathematical test that watch rotations are being abstracted away. Choose a known navigation-frame trajectory `p^n(t)` with acceleration `a^n(t)`. Choose several different watch-attitude histories `C_b^n(t)`, including a stationary watch, arbitrary wrist rotations, and rotation while translating. Synthesize the accelerometer specific force for each case:

```text
f^b(t) = (C_b^n(t))ᵀ (a^n(t) - g^n)
```

Synthesize gyro input from each attitude history, then feed each sensor stream through the same mechanization with identical initial `p`, `v`, attitude, and biases. After transforming specific force back into NED and restoring gravity, all reconstructed position and velocity histories should agree with the chosen trajectory, up to discretization error. If changing only the watch attitude changes the reconstructed path, the quaternion direction, multiplication order, sensor axes, gravity sign, or timestamps are wrong.

Include the stationary special case `a^n = 0`: for every watch orientation, `C_b^n f^b + g^n = 0`. This catches the common error of adding gravity with the wrong sign or in the wrong frame. These are mathematical validation criteria for the future implementation; they are not yet implemented tests.

### Wrist motion and the trajectory we actually need

The watch's attitude is not itself a navigation error. The accelerometer measures specific force in watch axes; the attitude transform rotates that vector into the local navigation frame. If the sensor origin is fixed relative to the aircraft, any watch rotation is handled mathematically and the recovered trajectory is unchanged.

An arm swing does move the sensor origin, so the watch follows a slightly different path from the aircraft reference point. Let `r^n(t)` be the vector from the aircraft reference point to the watch, expressed in the navigation frame. Then:

```text
p_watch(t) = p_aircraft(t) + r(t)
Δp_watch = Δp_aircraft + [r^n(t) - r^n(0)]
```

Thus, with ideal inertial measurements, the difference in the two **endpoint displacements** is bounded by the change in the watch-to-aircraft offset: `|Δp_watch - Δp_aircraft| ≤ |r^n(t)| + |r^n(0)| ≤ 2 r_max`. The arm swing's acceleration is real and appears in the watch trajectory, but it does not create an endlessly accumulating positional offset by itself. For the accepted goal of reaching the vicinity of landmarks, the simplest first approach is to integrate the watch trajectory directly and accept the small endpoint offset. Sensor bias, attitude error, and vibration still accumulate independently.

The rigid-body acceleration relation explains the instantaneous terms when one specifically wants aircraft-reference acceleration:

```text
a_watch = a_aircraft + α × ρ + ω × (ω × ρ) + 2 ω × ρ_dot + ρ_ddot
```

Here `ρ^b` is the watch-to-aircraft vector expressed in aircraft/body coordinates; the relative-motion terms describe articulation. They matter for reconstructing exact aircraft reference-point acceleration. They need not be subtracted to navigate with the watch's own trajectory. The watch gyroscope supplies the attitude change needed to rotate accelerometer measurements; it does not need to infer a separate aircraft attitude for this first endpoint-navigation objective. A steady hand on the controls makes the watch-to-aircraft offset especially stable, but is not a prerequisite.

Wrist-worn inertial navigation research exists, especially pedestrian dead reckoning. Those methods exploit structured human gait, step timing, learned motion patterns, or environmental interactions. They establish that a wrist IMU can support useful motion estimates; they do not establish paramotor-flight accuracy. ([IEEE, *Watch Your Position: Neural Inertial Localization With a Single Wrist-Worn Device*](https://doi.org/10.1109/TMC.2026.3672619), [IPIN, *Pedestrian Positioning from Wrist-worn Wearable Devices*](https://ipin2016.web.uah.es/usb/app/descargas/TR2_4.pdf))

## What the sensors measure

An ideal gyro measures angular rate. A practical gyro has bias and noise:

```text
ω_m^b = ω_ib^b + b_g + n_g
```

An ideal accelerometer measures **specific force**, not gravitational acceleration by itself. With body-frame specific force `f^b`:

```text
f_m^b = f^b + b_a + n_a
```

Specific force is the non-gravitational force per unit mass. A stationary, level accelerometer does not read zero; it reads approximately one `g` along the axis opposite gravity. At rest, the navigation solution must yield zero linear acceleration after rotating specific force into NED and adding gravity.

Let `g^n = [0, 0, g]ᵀ` in NED, with `g ≈ 9.80665 m/s²`. Then the local translational equation is:

```text
a^n = C_b^n (f_m^b - b_a) + g^n
```

For a stationary device, `C_b^n f^b` points up and cancels `g^n`. This gravity cancellation is one of the most important sign checks in the entire implementation.

## Strapdown mechanization

### Attitude propagation

The gyroscope's angular increments update the body-to-navigation attitude. A quaternion avoids Euler-angle singularities. Under a short-range local-frame approximation, and for a convention where `q_b^n` rotates body vectors into the navigation frame:

```text
Δθ^b = (ω_m^b - b_g) Δt
δq = [cos(|Δθ|/2),  (Δθ/|Δθ|) sin(|Δθ|/2)]
q_{k+1} = normalize(q_k ⊗ δq)
C_b^n = rotationMatrix(q_b^n)
```

For very small `|Δθ|`, evaluate `sin(|Δθ|/2)/|Δθ|` with its small-angle limit `1/2`. Quaternion multiplication order depends on the chosen convention; confirm it with controlled rotations. Do not copy an equation using another source's quaternion convention without reconciling its frame direction and multiplication order.

The full local-level NED attitude equation accounts for navigation-frame rotation:

```text
ω_in^n = ω_ie^n + ω_en^n
dot(C_b^n) = C_b^n [ω_ib^b]× - [ω_in^n]× C_b^n
```

`ω_ie` is Earth rotation, `ω_en` is the transport rate of the moving NED frame, and `[x]×` is the skew-symmetric cross-product matrix. These terms matter for a conventional Earth-referenced navigator. The first local-launch prototype can explicitly neglect Earth rotation and transport rate over a short flight segment, but should label this as a local approximation, not silently mix the simplified equation with full-NED equations. NASA's mechanization report presents the relationships among inertial, Earth, reference/navigation, and body/platform frames. ([NASA, *Mechanization*](https://ntrs.nasa.gov/api/citations/19740013176/downloads/19740013176.pdf))

### Velocity propagation

In a full local-level NED mechanization:

```text
dot(v^n) = C_b^n f^b + g^n - (2ω_ie^n + ω_en^n) × v^n
```

For the first local, short-range approximation, omit Earth-rate and transport-rate terms:

```text
a_k^n = C_b^n (f_m,k^b - b_a) + g^n
v_{k+1}^n = v_k^n + 0.5 (a_k^n + a_{k+1}^n) Δt
```

The trapezoid rule is a better first discretization than simply multiplying the newest sample by `Δt`. Each sensor sample must use its monotonic timestamp; do not assume callback intervals are perfectly uniform.

### Position propagation

For a local tangent frame whose axes remain fixed over the exercise's operating area:

```text
p_{k+1}^n = p_k^n + 0.5 (v_k^n + v_{k+1}^n) Δt
```

The first implementation can keep `p = [north, east, down]` in metres. It does not need latitude/longitude to compute a vector from current estimated position back to HOME. A later GPS adapter can map that local state to geographic coordinates when a valid geographic anchor is available.

For a full Earth-referenced NED solution, position rates are approximately:

```text
latitude_dot  = v_N / (R_M + h)
longitude_dot = v_E / ((R_N + h) cos(latitude))
height_dot    = -v_D
```

`R_M` and `R_N` are the meridian and transverse radii of curvature of the Earth. This is a distinct mechanization choice from a fixed local frame; select one representation deliberately.

## Initialization without GPS

For a launch-relative navigator, initialize:

```text
p_HOME = [0, 0, 0] m
v_0 = [0, 0, 0] m/s, when stationary ground state is confidently detected
```

The zero-velocity condition can come from a stationary standby interval, not a pilot button. Keep a rolling IMU buffer so takeoff detection can integrate the launch run retrospectively from the last stationary interval. If the watch never observes a reliable rest period, initialize from the best available motion history and carry an explicit initial-velocity/position uncertainty.

Gravity provides roll and pitch while stationary. Gravity alone cannot determine yaw: rotating a stationary device around vertical leaves the gravity vector unchanged. There are three conceptually different ways to define yaw:

1. **Arbitrary local yaw:** define the initial horizontal body-forward axis as local north for the mathematical frame. This can support relative trajectory and a HOME vector if all later transforms are consistent; it does not mean geographic north.
2. **Magnetic initialization/aiding:** use a calibrated magnetometer to align the local horizontal frame to magnetic north. This is MAG + INS, not strict inertial-only navigation.
3. **Gyrocompassing:** infer north from Earth rotation. This is mathematically possible with suitable gyroscopes and long alignment, but a consumer watch gyro should not be assumed capable of resolving Earth rate.

The submarine comparison is about the mechanization principle, not identical hardware. Inertial systems require initial conditions and alignment. The USCG reference describes alignment by gravity and Earth spin or external velocity comparison, and lists initial position/velocity, bias, noise, and misalignment as error sources. Submarines can maintain a useful solution without receiving GPS while submerged; they still benefit from known initial state and occasional external fixes or other aiding when available. ([USCG Navigation Center, INS overview](https://navcen.uscg.gov/sites/default/files/pubs/gpsaug/auggps.pdf), [Smithsonian, *Navigating a Submarine*](https://timeandnavigation.si.edu/satellite-navigation/reliable-global-navigation/first-satellite-navigation-system/navigating-a-submarine))

## Returning to HOME in a local frame

Store the launch point as `p_HOME = (N_HOME, E_HOME) = (0, 0)`. From current estimated local position `(N, E)`, the local vector toward HOME is:

```text
ΔN_HOME = N_HOME - N
ΔE_HOME = E_HOME - E
distance_HOME = sqrt(ΔN_HOME² + ΔE_HOME²)
bearing_HOME_NED = atan2(ΔE_HOME, ΔN_HOME)
```

The bearing is clockwise from North under this NED convention. To draw a watch-relative arrow, subtract the estimated body heading in the same reference frame and normalize the angle. If the local frame began with arbitrary yaw, the resulting bearing is relative to that arbitrary frame; the pilot-facing display still needs a consistent relationship between the IMU/body axes and screen direction.

This is a direct geometric estimate toward the launch origin. It does not encode terrain avoidance, wind correction, obstacles, or whether the aircraft can reach HOME with its remaining fuel.

## What drift means mathematically

Drift is the accumulated consequence of integrating imperfect measurements. It does not make the trajectory calculation meaningless; it makes the state an estimate whose error expands with time.

For a constant accelerometer bias `b_a` along one axis, ignoring other errors:

```text
δv(t) = b_a t
δp(t) = 0.5 b_a t²
```

Illustration only: a constant `1 mg` bias (`0.00980665 m/s²`) creates about `18 m` position error after one minute and `1.77 km` after ten minutes. These are sensitivity calculations, not claimed Watch 4 sensor specifications.

Attitude error leaks gravity into the horizontal acceleration estimate. For a small tilt error `ε` radians:

```text
a_horizontal_error ≈ g ε
δp(t) ≈ 0.5 g ε t²
```

Even a one-degree tilt error corresponds to roughly `0.171 m/s²` of false horizontal acceleration. Gyro bias causes attitude error to grow with time; gravity projected through that attitude error can make position error grow approximately with the cube of time in a simplified constant-bias case. The exact error growth depends on sensor noise, movement, filtering, and observability.

The USCG's INS reference lists sensor bias/noise, platform misalignment, initial position/velocity error, and unmodeled gravity as error sources, and states that unaided position error grows with time. Android also warns that its linear-acceleration output has offset that must be calibrated. ([USCG Navigation Center](https://navcen.uscg.gov/sites/default/files/pubs/gpsaug/auggps.pdf), [Android linear-acceleration guidance](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion))

## A principled first algorithm

The first pure-IMU implementation should be intentionally small and inspectable:

1. Read raw accelerometer and gyroscope values with monotonic event timestamps. Convert to SI units: `m/s²` and `rad/s`.
2. Establish sensor axes and quaternion convention with synthetic unit-vector tests.
3. During detected stationary standby, estimate gyro bias from the time average; estimate accelerometer offset only with a known attitude/gravity model. Set launch-relative position to zero and velocity to zero.
4. Keep a timestamped rolling sample buffer. When automatic takeoff is confirmed, replay the buffer from the last credible stationary state.
5. Propagate quaternion attitude from gyro samples. Normalize it after every numerical update.
6. Subtract estimated accelerometer bias, rotate specific force to the local frame, and add gravity exactly once.
7. Integrate velocity and position using measured `Δt`, preferably with midpoint/trapezoid integration. Reject impossible time gaps and mark uncertainty growth; do not pretend a gap had zero acceleration.
8. Derive the local HOME vector, distance, and bearing from the estimated position. Keep MAG heading and pure gyro-propagated attitude as explicitly separate measurements/solutions if magnetic aiding is later experimented with.
9. Treat wrist/arm movement as a model error source. Preserve sensor data, identify periods likely to represent stable control-holding versus local wrist motion, and reduce confidence or weight for ambiguous intervals. Do not call a detected gesture “aircraft acceleration” merely because it appears in the accelerometer.
10. Log raw sensor values, calibration, `Δt`, attitude, resolved acceleration, velocity, position, and initialization state. A trajectory that cannot be debugged from its intermediate values cannot be trusted as mathematics.

This is a mechanization plan, not yet the completed application estimator. Higher-order coning/sculling corrections, time synchronization, bias-state estimation, error covariance, barometric vertical aiding, and a full Earth model can be added after the basic signs and integrations are validated. Barometer and magnetometer aiding must remain distinct from the strict accelerometer/gyroscope-only core.

## Software boundary for this repository

Keep the pure inertial core independent of Android GPS and UI:

```text
Android raw IMU adapter
    └── ImuSample(timestamp, accelBody, gyroBody)
          └── StrapdownIns
                └── InertialState(positionLocal, velocityLocal, attitude, biases, uncertainty)
                      └── NavigationSolution(source = MAG_INERTIAL, HOME vector, bearing, distance)
```

`GpsNavigationProvider` remains a separate future adapter behind `NavigationProvider`. Do not import `LocationManager`, request location permission, or read a location/rotation-vector source that silently fuses GPS in the MAG/INRT-only exercise. Optional magnetometer aid should have a named interface and a configuration switch, so a strict pure-IMU run is distinguishable from MAG-aided INS.

The current code has separate `MagInertialNavigationProvider` and `GpsNavigationProvider` classes and a common `NavigationSolution`. `StrapdownIns` now implements local ENU quaternion, velocity, and position propagation. The MAG/inertial provider calibrates during stationary alignment, starts HOME at the local origin, and feeds raw accelerometer/gyro samples to the mechanization. Rotation-invariance validation criteria are documented above but are not yet implemented as tests or verified against synthetic trajectories. The first aircraft use case is wrist-worn; rigid cockpit mounting is optional for comparison, not a prerequisite.

## References

- [USCG Navigation Center, *Global Positioning System (GPS) and Inertial Navigation Systems (INS)*, INS section](https://navcen.uscg.gov/sites/default/files/pubs/gpsaug/auggps.pdf) — double integration, strapdown frame transformation, alignment, aiding, and error sources.
- [NASA, *Mechanization* (1974)](https://ntrs.nasa.gov/api/citations/19740013176/downloads/19740013176.pdf) — inertial/Earth/navigation/platform frames and strapdown attitude mechanization.
- [NASA NTRS, *Strapdown Navigation Equations for Geographic and Tangent Coordinate Frames*](https://ntrs.nasa.gov/citations/19690021973) — geographic and local tangent frame equations.
- [Android, *Motion sensors*](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion) — accelerometer specific force, gravity removal, gyro units, rotation-vector behavior, and linear acceleration offset.
- [Smithsonian, *Navigating a Submarine*](https://timeandnavigation.si.edu/satellite-navigation/reliable-global-navigation/first-satellite-navigation-system/navigating-a-submarine) — historical submarine INS context and external position updates.
