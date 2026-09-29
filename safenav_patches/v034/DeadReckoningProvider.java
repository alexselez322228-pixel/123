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
 * Local-only dead reckoning used by NavShlyah UA after a one-shot GNSS fix.
 *
 * The provider emits one position every second. When an offline route is known,
 * all estimated movement is constrained to that route polyline. Vehicle speed
 * is propagated between acceleration/braking events, because an accelerometer
 * cannot directly observe constant velocity.
 */
final class DeadReckoningProvider extends BaseLocationProvider implements SensorEventListener
{
  private static final double EARTH_RADIUS_M = 6378137.0;
  private static final float STEP_METERS = 0.75f;
  private static final float MAX_SPEED_MPS = 55.0f; // ~198 km/h.
  private static final float START_ADVANCE_SPEED_MPS = 0.25f;
  private static final float POS_ACCEL_THRESHOLD = 0.30f;
  private static final float NEG_ACCEL_THRESHOLD = -0.50f;

  @NonNull private final SensorManager mSensorManager;
  @NonNull private final Handler mHandler = new Handler(Looper.getMainLooper());
  @NonNull private final Location mLocation;

  private Sensor mRotation;
  private Sensor mLinearAcceleration;
  private Sensor mAccelerometer;
  private Sensor mStep;

  private final float[] mRotationMatrix = new float[9];
  private final float[] mGravity = new float[3];
  private boolean mHaveRotation;
  private boolean mHaveGravity;

  private double mPhoneHeadingRad;
  private double mTravelHeadingRad;
  private float mSpeedMps;
  private long mLastTickNanos;
  private long mLastAccelNanos;
  private boolean mRunning;

  @Nullable private JunctionInfo[] mRoute;
  private int mRouteIndex;

  DeadReckoningProvider(@NonNull Context context, @NonNull Listener listener, @NonNull Location seed)
  {
    super(listener);
    mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    mLocation = new Location(seed);
    mSpeedMps = seed.hasSpeed() ? Math.max(0.0f, Math.min(MAX_SPEED_MPS, seed.getSpeed())) : 0.0f;
    mPhoneHeadingRad = Math.toRadians(seed.hasBearing() ? seed.getBearing() : 0.0f);
    mTravelHeadingRad = mPhoneHeadingRad;
  }

  /**
   * Supplies the already-built offline route. The current estimated location is
   * snapped to the nearest route sample, then all subsequent distance is moved
   * forward along the route rather than free-running by compass heading.
   */
  void setRoute(@Nullable JunctionInfo[] route)
  {
    if (route == null || route.length < 2)
    {
      mRoute = null;
      mRouteIndex = 0;
      return;
    }

    mRoute = route;
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
    updateRouteBearing();
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

    mLinearAcceleration = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    if (mLinearAcceleration == null)
      mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

    mStep = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);

    if (mRotation != null)
      mSensorManager.registerListener(this, mRotation, SensorManager.SENSOR_DELAY_GAME);
    if (mLinearAcceleration != null)
      mSensorManager.registerListener(this, mLinearAcceleration, SensorManager.SENSOR_DELAY_GAME);
    else if (mAccelerometer != null)
      mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
    if (mStep != null)
      mSensorManager.registerListener(this, mStep, SensorManager.SENSOR_DELAY_NORMAL);

    mLastTickNanos = SystemClock.elapsedRealtimeNanos();
    mLastAccelNanos = 0L;
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

      // Do NOT zero the speed just because current acceleration is ~0:
      // constant-speed motion has ~0 longitudinal acceleration too.
      if (mSpeedMps >= START_ADVANCE_SPEED_MPS && dt > 0.0)
        advance(mSpeedMps * dt);

      // Always emit every second. This keeps route following/camera updates alive
      // even if the estimated speed is temporarily zero.
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
      if (mRoute == null)
        mTravelHeadingRad = mPhoneHeadingRad;
      mHaveRotation = true;
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

    if (type == Sensor.TYPE_STEP_DETECTOR && mSpeedMps < 1.0f)
    {
      advance(STEP_METERS);
      emit(SystemClock.elapsedRealtimeNanos());
    }
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

    float forward;
    if (mHaveRotation)
    {
      // Approximate device -> earth transform. X=East, Y=North for our projection.
      final float east  = mRotationMatrix[0] * ax + mRotationMatrix[1] * ay + mRotationMatrix[2] * az;
      final float north = mRotationMatrix[3] * ax + mRotationMatrix[4] * ay + mRotationMatrix[5] * az;

      // When a route is active, project acceleration onto the route tangent
      // instead of the phone's physical orientation. The phone can be mounted
      // sideways while the car still follows the road.
      final double heading = mRoute != null ? mTravelHeadingRad : mPhoneHeadingRad;
      forward = (float)(east * Math.sin(heading) + north * Math.cos(heading));
    }
    else
    {
      final float horizontal = (float)Math.sqrt(ax * ax + ay * ay);
      forward = (horizontal >= POS_ACCEL_THRESHOLD && mSpeedMps < 1.0f) ? horizontal : 0.0f;
    }

    // Ignore the small sensor bias that previously bled the speed to zero after
    // only a few metres. Only clear acceleration/braking changes speed.
    if (forward > POS_ACCEL_THRESHOLD || forward < NEG_ACCEL_THRESHOLD)
    {
      forward = Math.max(-4.5f, Math.min(4.5f, forward));
      mSpeedMps += forward * (float)dt;
      mSpeedMps = Math.max(0.0f, Math.min(MAX_SPEED_MPS, mSpeedMps));
    }

    mLocation.setSpeed(mSpeedMps);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}

  private void advance(double meters)
  {
    if (meters <= 0.0)
      return;

    if (mRoute != null && mRoute.length >= 2)
    {
      advanceAlongRoute(meters);
      return;
    }

    double lat = Math.toRadians(mLocation.getLatitude());
    double lon = Math.toRadians(mLocation.getLongitude());
    double d = meters / EARTH_RADIUS_M;

    lat += d * Math.cos(mTravelHeadingRad);
    double cosLat = Math.max(0.01, Math.cos(lat));
    lon += d * Math.sin(mTravelHeadingRad) / cosLat;

    mLocation.setLatitude(Math.toDegrees(lat));
    mLocation.setLongitude(Math.toDegrees(lon));
    mLocation.setBearing(degreesBearing(mTravelHeadingRad));
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
        double lat = hereLat + (next.mLat - hereLat) * t;
        double lon = hereLon + (next.mLon - hereLon) * t;
        mTravelHeadingRad = bearingRad(hereLat, hereLon, next.mLat, next.mLon);
        mLocation.setLatitude(lat);
        mLocation.setLongitude(lon);
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
               Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2.0) * Math.sin(dl / 2.0);
    return 2.0 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
  }

  private static double bearingRad(double lat1, double lon1, double lat2, double lon2)
  {
    double p1 = Math.toRadians(lat1);
    double p2 = Math.toRadians(lat2);
    double dl = Math.toRadians(lon2 - lon1);
    double y = Math.sin(dl) * Math.cos(p2);
    double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
    return Math.atan2(y, x);
  }

  private static float degreesBearing(double rad)
  {
    return (float)((Math.toDegrees(rad) + 360.0) % 360.0);
  }

  private void emit(long elapsedNanos)
  {
    mLocation.setProvider("navshlyah_inertial");
    mLocation.setTime(System.currentTimeMillis());
    mLocation.setElapsedRealtimeNanos(elapsedNanos);
    mLocation.setBearing(degreesBearing(mTravelHeadingRad));
    mLocation.setSpeed(mSpeedMps);

    // Uncertainty grows, but stays bounded so the routing core can keep
    // map-matching the deliberately route-constrained estimate.
    float accuracy = mLocation.hasAccuracy() ? mLocation.getAccuracy() : 15.0f;
    mLocation.setAccuracy(Math.min(80.0f, accuracy + 0.35f));

    mListener.onLocationChanged(new Location(mLocation));
  }
}
