package app.organicmaps.sdk.location;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import app.organicmaps.sdk.routing.JunctionInfo;

/**
 * NavShlyah UA local-only vehicle dead reckoning.
 *
 * IMPORTANT: phone IMU cannot measure absolute vehicle speed at constant velocity.
 * This provider therefore combines:
 *  - last GNSS seed speed;
 *  - route-longitudinal acceleration integration;
 *  - conservative launch/stillness classification that rejects casual phone movement;
 *  - zero-velocity update after braking + sustained stillness;
 *  - gyroscope-based turn tracking;
 *  - route-constrained map matching.
 *
 * It is deliberately best-effort, not GNSS-equivalent odometry.
 */
final class DeadReckoningProvider extends BaseLocationProvider implements SensorEventListener
{
  private static final double EARTH_RADIUS_M = 6378137.0;
  private static final float STEP_METERS = 0.75f;
  private static final float MAX_SPEED_MPS = 55.0f;

  // Motion classifier. Values are for TYPE_LINEAR_ACCELERATION magnitude.
  private static final float QUIET_MOTION_EMA = 0.032f;
  private static final float QUIET_YAW_RAD_S = 0.022f;

  // Conservative launch detector. Do not interpret casual phone/body movement
  // as vehicle motion. A real launch must accumulate a sustained linear-
  // acceleration impulse before the stationary lock is released.
  private static final float LAUNCH_ACCEL_THRESHOLD = 0.16f;
  private static final float LAUNCH_PEAK_THRESHOLD = 0.34f;
  private static final float LAUNCH_IMPULSE_THRESHOLD = 0.42f; // m/s equivalent.
  private static final double LAUNCH_WINDOW_SECONDS = 1.6;
  private static final float INITIAL_ROLL_SPEED_MPS = 0.22f;

  // Speed / stop logic.
  private static final float BRAKE_FORWARD_MPS2 = -0.14f;
  private static final float ACCEL_DEADBAND_MPS2 = 0.050f;
  private static final double STOP_QUIET_SECONDS = 2.4;
  private static final double BRAKE_MEMORY_SECONDS = 7.0;

  // Off-route detection.
  private static final double OFF_ROUTE_DISTANCE_M = 28.0;
  private static final double OFF_ROUTE_HEADING_DEG = 48.0;
  private static final int OFF_ROUTE_CONFIRM_TICKS = 3;

  @NonNull private final SensorManager mSensorManager;
  @NonNull private final Handler mHandler = new Handler(Looper.getMainLooper());
  @NonNull private final Location mLocation;

  private Sensor mRotation;
  private Sensor mGyro;
  private Sensor mLinearAcceleration;
  private Sensor mAccelerometer;
  private Sensor mStep;

  private final float[] mRotationMatrix = new float[9];
  private final float[] mGravity = new float[3];
  private boolean mHaveRotation;
  private boolean mHaveGravity;

  private double mPhoneHeadingRad;
  private double mTravelHeadingRad;
  private double mShadowHeadingRad;
  private float mYawRateRadS;

  private float mSpeedMps;
  private float mForwardAccelEma;
  private float mMotionEma;

  private long mLastTickNanos;
  private long mLastAccelNanos;
  private long mLastGyroNanos;
  private long mQuietSinceNanos;
  private long mLastBrakeNanos;
  private long mMoveEvidenceSinceNanos;
  private long mLaunchWindowStartNanos;
  private float mLaunchImpulse;
  private float mLaunchPeak;

  private boolean mStationary;
  private boolean mRunning;
  private boolean mHadBrake;

  @Nullable private JunctionInfo[] mRoute;
  private int mRouteIndex;

  // Free-running shadow solution used only to detect a real route deviation.
  private double mShadowLat;
  private double mShadowLon;
  private boolean mOffRoute;
  private int mOffRouteTicks;

  DeadReckoningProvider(@NonNull Context context, @NonNull Listener listener, @NonNull Location seed)
  {
    super(listener);
    mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    mLocation = new Location(seed);
    mSpeedMps = seed.hasSpeed() ? clamp(seed.getSpeed(), 0.0f, MAX_SPEED_MPS) : 0.0f;
    mPhoneHeadingRad = Math.toRadians(seed.hasBearing() ? seed.getBearing() : 0.0f);
    mTravelHeadingRad = mPhoneHeadingRad;
    mShadowHeadingRad = mTravelHeadingRad;
    mShadowLat = seed.getLatitude();
    mShadowLon = seed.getLongitude();
    mStationary = mSpeedMps < 0.35f;
  }

  void setRoute(@Nullable JunctionInfo[] route)
  {
    if (route == null || route.length < 2)
    {
      mRoute = null;
      mRouteIndex = 0;
      mOffRoute = false;
      mOffRouteTicks = 0;
      return;
    }

    mRoute = route;
    mOffRoute = false;
    mOffRouteTicks = 0;

    int nearest = 0;
    double best = Double.MAX_VALUE;
    for (int i = 0; i < route.length; ++i)
    {
      double d = distanceMeters(mLocation.getLatitude(), mLocation.getLongitude(), route[i].mLat, route[i].mLon);
      if (d < best)
      {
        best = d;
        nearest = i;
      }
    }

    mRouteIndex = Math.min(nearest, route.length - 2);
    JunctionInfo p = route[nearest];
    mLocation.setLatitude(p.mLat);
    mLocation.setLongitude(p.mLon);
    mShadowLat = p.mLat;
    mShadowLon = p.mLon;
    updateRouteBearing();
    mShadowHeadingRad = mTravelHeadingRad;
  }

  boolean isOffRoute()
  {
    return mOffRoute;
  }

  @Override
  @RequiresPermission(anyOf = {"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"})
  protected void start(long interval)
  {
    if (mRunning)
      return;

    mRunning = true;

    mRotation = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    if (mRotation == null)
      mRotation = mSensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);

    mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

    mLinearAcceleration = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    if (mLinearAcceleration == null)
      mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

    mStep = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);

    if (mRotation != null)
      mSensorManager.registerListener(this, mRotation, SensorManager.SENSOR_DELAY_GAME);
    if (mGyro != null)
      mSensorManager.registerListener(this, mGyro, SensorManager.SENSOR_DELAY_GAME);
    if (mLinearAcceleration != null)
      mSensorManager.registerListener(this, mLinearAcceleration, SensorManager.SENSOR_DELAY_GAME);
    else if (mAccelerometer != null)
      mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
    if (mStep != null)
      mSensorManager.registerListener(this, mStep, SensorManager.SENSOR_DELAY_NORMAL);

    final long now = SystemClock.elapsedRealtimeNanos();
    mLastTickNanos = now;
    mLastAccelNanos = 0L;
    mLastGyroNanos = 0L;
    mQuietSinceNanos = 0L;
    mMoveEvidenceSinceNanos = 0L;
    mLaunchWindowStartNanos = 0L;
    mLaunchImpulse = 0.0f;
    mLaunchPeak = 0.0f;
    mHandler.post(mTicker);
  }

  @Override
  protected void stop()
  {
    if (!mRunning)
      return;
    mRunning = false;
    mHandler.removeCallbacks(mTicker);
    mSensorManager.unregisterListener(this);
  }

  private final Runnable mTicker = new Runnable()
  {
    @Override
    public void run()
    {
      if (!mRunning)
        return;

      final long now = SystemClock.elapsedRealtimeNanos();
      final double dt = Math.max(0.0, Math.min(2.0, (now - mLastTickNanos) / 1.0e9));
      mLastTickNanos = now;

      // Never fabricate a minimum vehicle speed. In v1.8 that fallback was
      // exactly why a stationary phone could display ~3 km/h and crawl along
      // the route. Only an actually integrated non-zero speed advances position.
      if (!mStationary && mSpeedMps >= 0.20f && dt > 0.0)
        advance(mSpeedMps * dt);

      emit(now);
      mHandler.postDelayed(this, 1000);
    }
  };

  @Override
  public void onSensorChanged(SensorEvent event)
  {
    if (!mRunning)
      return;

    final int type = event.sensor.getType();

    if (type == Sensor.TYPE_ROTATION_VECTOR || type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    {
      SensorManager.getRotationMatrixFromVector(mRotationMatrix, event.values);
      float[] orientation = new float[3];
      SensorManager.getOrientation(mRotationMatrix, orientation);
      mPhoneHeadingRad = orientation[0];
      mHaveRotation = true;

      // When no route is available use absolute fused heading. During routing
      // the gyroscope carries short-term turn changes and the route constrains
      // the displayed position.
      if (mRoute == null)
      {
        mTravelHeadingRad = mPhoneHeadingRad;
        mShadowHeadingRad = mPhoneHeadingRad;
      }
      return;
    }

    if (type == Sensor.TYPE_GYROSCOPE)
    {
      integrateGyro(event);
      return;
    }

    if (type == Sensor.TYPE_LINEAR_ACCELERATION)
    {
      integrateAcceleration(event.values[0], event.values[1], event.values[2], event.timestamp);
      return;
    }

    if (type == Sensor.TYPE_ACCELEROMETER)
    {
      final float alpha = 0.94f;
      if (!mHaveGravity)
      {
        mGravity[0] = event.values[0];
        mGravity[1] = event.values[1];
        mGravity[2] = event.values[2];
        mHaveGravity = true;
        return;
      }

      mGravity[0] = alpha * mGravity[0] + (1.0f - alpha) * event.values[0];
      mGravity[1] = alpha * mGravity[1] + (1.0f - alpha) * event.values[1];
      mGravity[2] = alpha * mGravity[2] + (1.0f - alpha) * event.values[2];

      integrateAcceleration(event.values[0] - mGravity[0],
                            event.values[1] - mGravity[1],
                            event.values[2] - mGravity[2],
                            event.timestamp);
      return;
    }

    if (type == Sensor.TYPE_STEP_DETECTOR && mRoute == null && mSpeedMps < 1.0f)
    {
      mStationary = false;
      advance(STEP_METERS);
      emit(SystemClock.elapsedRealtimeNanos());
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}

  private void integrateGyro(@NonNull SensorEvent event)
  {
    final long now = event.timestamp;
    if (mLastGyroNanos == 0L)
    {
      mLastGyroNanos = now;
      return;
    }

    final double dt = Math.max(0.0, Math.min(0.10, (now - mLastGyroNanos) / 1.0e9));
    mLastGyroNanos = now;
    if (dt <= 0.0)
      return;

    float verticalRate;
    if (mHaveRotation)
    {
      // Transform device angular velocity into earth coordinates. World Z is
      // approximately vertical, making turn detection independent of how the
      // phone is mounted in the cabin.
      verticalRate = mRotationMatrix[6] * event.values[0] +
                     mRotationMatrix[7] * event.values[1] +
                     mRotationMatrix[8] * event.values[2];
    }
    else
    {
      verticalRate = event.values[2];
    }

    mYawRateRadS = 0.88f * mYawRateRadS + 0.12f * verticalRate;

    // Android gyro positive vertical rotation is opposite navigation bearing
    // sign in our north/east convention.
    if (Math.abs(mYawRateRadS) > 0.008f)
      mShadowHeadingRad = normalizeRad(mShadowHeadingRad - mYawRateRadS * dt);
  }

  private void integrateAcceleration(float ax, float ay, float az, long timestamp)
  {
    if (mLastAccelNanos == 0L)
    {
      mLastAccelNanos = timestamp;
      return;
    }

    final double dt = Math.max(0.0, Math.min(0.20, (timestamp - mLastAccelNanos) / 1.0e9));
    mLastAccelNanos = timestamp;
    if (dt <= 0.0)
      return;

    final float magnitude = (float)Math.sqrt(ax * ax + ay * ay + az * az);
    mMotionEma = 0.94f * mMotionEma + 0.06f * magnitude;

    float forward = 0.0f;
    if (mHaveRotation)
    {
      final float east  = mRotationMatrix[0] * ax + mRotationMatrix[1] * ay + mRotationMatrix[2] * az;
      final float north = mRotationMatrix[3] * ax + mRotationMatrix[4] * ay + mRotationMatrix[5] * az;
      final double heading = mRoute != null ? mTravelHeadingRad : mShadowHeadingRad;
      forward = (float)(east * Math.sin(heading) + north * Math.cos(heading));
    }

    mForwardAccelEma = 0.90f * mForwardAccelEma + 0.10f * forward;

    // Conservative vehicle-start detector. Gyroscope rotation must NOT start
    // the car by itself (turning the phone while sitting still is common).
    // We require a real sustained linear-acceleration impulse within a short
    // window. This intentionally prefers a missed very-gentle launch over a
    // false "3 km/h" crawl while the phone is stationary.
    if (mStationary)
    {
      if (mLaunchWindowStartNanos == 0L ||
          (timestamp - mLaunchWindowStartNanos) / 1.0e9 > LAUNCH_WINDOW_SECONDS)
      {
        mLaunchWindowStartNanos = timestamp;
        mLaunchImpulse = 0.0f;
        mLaunchPeak = 0.0f;
      }

      final float excess = Math.max(0.0f, magnitude - LAUNCH_ACCEL_THRESHOLD);
      mLaunchImpulse += excess * (float)dt;
      mLaunchPeak = Math.max(mLaunchPeak, magnitude);

      if (mLaunchImpulse >= LAUNCH_IMPULSE_THRESHOLD &&
          mLaunchPeak >= LAUNCH_PEAK_THRESHOLD)
      {
        mStationary = false;
        mHadBrake = false;
        mQuietSinceNanos = 0L;
        mMoveEvidenceSinceNanos = timestamp;
        mSpeedMps = Math.max(INITIAL_ROLL_SPEED_MPS, mSpeedMps);
        mLaunchWindowStartNanos = 0L;
        mLaunchImpulse = 0.0f;
        mLaunchPeak = 0.0f;
      }
    }

    // Signed acceleration still improves the approximate speed, but it is no
    // longer the only mechanism deciding whether the vehicle is moving.
    if (!mStationary && Math.abs(mForwardAccelEma) >= ACCEL_DEADBAND_MPS2)
    {
      final float a = clamp(mForwardAccelEma, -4.5f, 4.5f);
      mSpeedMps = clamp(mSpeedMps + a * (float)dt, 0.0f, MAX_SPEED_MPS);

      if (a <= BRAKE_FORWARD_MPS2)
      {
        mHadBrake = true;
        mLastBrakeNanos = timestamp;
      }
    }

    final boolean quiet =
        mMotionEma <= QUIET_MOTION_EMA &&
        Math.abs(mYawRateRadS) <= QUIET_YAW_RAD_S &&
        Math.abs(mForwardAccelEma) <= 0.06f;

    if (!mStationary && quiet)
    {
      if (mQuietSinceNanos == 0L)
        mQuietSinceNanos = timestamp;

      final double quietFor = (timestamp - mQuietSinceNanos) / 1.0e9;
      final double brakeAge = mLastBrakeNanos == 0L
          ? Double.MAX_VALUE : (timestamp - mLastBrakeNanos) / 1.0e9;

      // Do not classify smooth constant-speed driving as a stop. We need either
      // recent braking or an already very low estimated speed.
      if (quietFor >= STOP_QUIET_SECONDS &&
          ((mHadBrake && brakeAge <= BRAKE_MEMORY_SECONDS) || mSpeedMps < 0.65f))
      {
        mStationary = true;
        mSpeedMps = 0.0f;
        mHadBrake = false;
        mMoveEvidenceSinceNanos = 0L;
        mLaunchWindowStartNanos = 0L;
        mLaunchImpulse = 0.0f;
        mLaunchPeak = 0.0f;
      }
    }
    else
    {
      mQuietSinceNanos = 0L;
    }

    mLocation.setSpeed(mSpeedMps);
  }

  private void advance(double meters)
  {
    if (meters <= 0.0)
      return;

    advanceShadow(meters);

    if (mRoute != null && mRoute.length >= 2 && !mOffRoute)
    {
      advanceAlongRoute(meters);
      evaluateRouteDeviation();
      if (!mOffRoute)
        return;
    }

    mLocation.setLatitude(mShadowLat);
    mLocation.setLongitude(mShadowLon);
    mTravelHeadingRad = mShadowHeadingRad;
    mLocation.setBearing(degreesBearing(mTravelHeadingRad));
  }

  private void advanceShadow(double meters)
  {
    final double heading = mShadowHeadingRad;
    double lat = Math.toRadians(mShadowLat);
    double lon = Math.toRadians(mShadowLon);
    final double d = meters / EARTH_RADIUS_M;

    lat += d * Math.cos(heading);
    final double cosLat = Math.max(0.01, Math.cos(lat));
    lon += d * Math.sin(heading) / cosLat;

    mShadowLat = Math.toDegrees(lat);
    mShadowLon = Math.toDegrees(lon);
  }

  private void evaluateRouteDeviation()
  {
    if (mRoute == null || mRoute.length < 2)
      return;

    final int from = Math.max(0, mRouteIndex - 20);
    final int to = Math.min(mRoute.length - 1, mRouteIndex + 100);

    double best = Double.MAX_VALUE;
    for (int i = from; i <= to; ++i)
      best = Math.min(best, distanceMeters(mShadowLat, mShadowLon, mRoute[i].mLat, mRoute[i].mLon));

    final double headingDiff = Math.abs(Math.toDegrees(
        normalizeRad(mShadowHeadingRad - mTravelHeadingRad)));

    final boolean suspicious =
        best > OFF_ROUTE_DISTANCE_M ||
        (mSpeedMps > 2.0f && headingDiff > OFF_ROUTE_HEADING_DEG);

    if (suspicious)
      mOffRouteTicks++;
    else
      mOffRouteTicks = Math.max(0, mOffRouteTicks - 1);

    if (mOffRouteTicks >= OFF_ROUTE_CONFIRM_TICKS)
    {
      mOffRoute = true;
      mLocation.setLatitude(mShadowLat);
      mLocation.setLongitude(mShadowLon);
      mTravelHeadingRad = mShadowHeadingRad;
      mLocation.setBearing(degreesBearing(mTravelHeadingRad));
    }
  }

  private void advanceAlongRoute(double meters)
  {
    double remaining = meters;

    while (remaining > 0.0 && mRoute != null && mRouteIndex < mRoute.length - 1)
    {
      JunctionInfo next = mRoute[mRouteIndex + 1];
      double hereLat = mLocation.getLatitude();
      double hereLon = mLocation.getLongitude();
      double segmentRemaining = distanceMeters(hereLat, hereLon, next.mLat, next.mLon);

      if (segmentRemaining < 0.05)
      {
        mRouteIndex++;
        if (mRouteIndex < mRoute.length - 1)
          updateRouteBearing();
        continue;
      }

      if (remaining < segmentRemaining)
      {
        double t = remaining / segmentRemaining;
        mTravelHeadingRad = bearingRad(hereLat, hereLon, next.mLat, next.mLon);
        mLocation.setLatitude(hereLat + (next.mLat - hereLat) * t);
        mLocation.setLongitude(hereLon + (next.mLon - hereLon) * t);
        mLocation.setBearing(degreesBearing(mTravelHeadingRad));
        remaining = 0.0;
      }
      else
      {
        mLocation.setLatitude(next.mLat);
        mLocation.setLongitude(next.mLon);
        remaining -= segmentRemaining;
        mRouteIndex++;
        if (mRouteIndex < mRoute.length - 1)
          updateRouteBearing();
      }
    }
  }

  private void updateRouteBearing()
  {
    if (mRoute == null || mRouteIndex >= mRoute.length - 1)
      return;

    JunctionInfo a = mRoute[mRouteIndex];
    JunctionInfo b = mRoute[mRouteIndex + 1];
    mTravelHeadingRad = bearingRad(a.mLat, a.mLon, b.mLat, b.mLon);
    mLocation.setBearing(degreesBearing(mTravelHeadingRad));
  }

  private static double distanceMeters(double lat1, double lon1, double lat2, double lon2)
  {
    double p1 = Math.toRadians(lat1);
    double p2 = Math.toRadians(lat2);
    double dp = Math.toRadians(lat2 - lat1);
    double dl = Math.toRadians(lon2 - lon1);

    double a = Math.sin(dp / 2.0) * Math.sin(dp / 2.0) +
               Math.cos(p1) * Math.cos(p2) *
               Math.sin(dl / 2.0) * Math.sin(dl / 2.0);

    return 2.0 * EARTH_RADIUS_M *
           Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
  }

  private static double bearingRad(double lat1, double lon1, double lat2, double lon2)
  {
    double p1 = Math.toRadians(lat1);
    double p2 = Math.toRadians(lat2);
    double dl = Math.toRadians(lon2 - lon1);

    double y = Math.sin(dl) * Math.cos(p2);
    double x = Math.cos(p1) * Math.sin(p2) -
               Math.sin(p1) * Math.cos(p2) * Math.cos(dl);

    return Math.atan2(y, x);
  }

  private static double normalizeRad(double rad)
  {
    while (rad > Math.PI) rad -= 2.0 * Math.PI;
    while (rad < -Math.PI) rad += 2.0 * Math.PI;
    return rad;
  }

  private static float degreesBearing(double rad)
  {
    return (float)((Math.toDegrees(rad) + 360.0) % 360.0);
  }

  private static float clamp(float value, float min, float max)
  {
    return Math.max(min, Math.min(max, value));
  }

  private void emit(long elapsedNanos)
  {
    mLocation.setProvider("navshlyah_inertial");
    mLocation.setTime(System.currentTimeMillis());
    mLocation.setElapsedRealtimeNanos(elapsedNanos);
    mLocation.setBearing(degreesBearing(mTravelHeadingRad));
    mLocation.setSpeed(mStationary ? 0.0f : mSpeedMps);

    float accuracy = mLocation.hasAccuracy() ? mLocation.getAccuracy() : 15.0f;
    mLocation.setAccuracy(Math.min(80.0f, accuracy + 0.30f));

    mListener.onLocationChanged(new Location(mLocation));
  }
}
