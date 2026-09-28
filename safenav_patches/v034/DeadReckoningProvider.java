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
import androidx.annotation.RequiresPermission;

/**
 * Local-only dead reckoning used by NavShlyah UA after the one-shot GNSS fix.
 *
 * It emits one estimated position every second. For a vehicle it integrates
 * forward linear acceleration in the earth frame and preserves the last
 * estimated velocity between acceleration events. The Organic Maps routing
 * core then map-matches these fixes to the active route.
 *
 * This is intentionally not presented as GNSS-equivalent odometry: phone IMU
 * bias accumulates. The user can request another one-shot GNSS correction.
 */
final class DeadReckoningProvider extends BaseLocationProvider implements SensorEventListener
{
  private static final double EARTH_RADIUS_M = 6378137.0;
  private static final float STEP_METERS = 0.75f;
  private static final float MAX_SPEED_MPS = 55.0f; // ~198 km/h.
  private static final float ACCEL_DEADBAND = 0.10f;

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

  private double mHeadingRad;
  private float mSpeedMps;
  private long mLastTickNanos;
  private long mLastAccelNanos;
  private boolean mRunning;

  DeadReckoningProvider(@NonNull Context context, @NonNull Listener listener, @NonNull Location seed)
  {
    super(listener);
    mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    mLocation = new Location(seed);
    mSpeedMps = seed.hasSpeed() ? Math.max(0.0f, Math.min(MAX_SPEED_MPS, seed.getSpeed())) : 0.0f;
    mHeadingRad = Math.toRadians(seed.hasBearing() ? seed.getBearing() : 0.0f);
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

      // Vehicle mode. Keep velocity across constant-speed intervals; a phone
      // accelerometer cannot directly observe constant linear velocity.
      if (mSpeedMps >= 1.2f && dt > 0.0)
        advance(mSpeedMps * dt);

      // Always emit once a second so the routing core, marker and camera update
      // with the same cadence even during a short stop.
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
      mHeadingRad = orientation[0];
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
      final float alpha = 0.92f;
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

    if (type == Sensor.TYPE_STEP_DETECTOR && mSpeedMps < 1.2f)
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

    final double dt = Math.max(0.0, Math.min(0.25, (timestamp - mLastAccelNanos) / 1.0e9));
    mLastAccelNanos = timestamp;
    if (dt <= 0.0)
      return;

    float forward;
    if (mHaveRotation)
    {
      // Transform device acceleration into an approximate east/north/up frame.
      final float east  = mRotationMatrix[0] * ax + mRotationMatrix[1] * ay + mRotationMatrix[2] * az;
      final float north = mRotationMatrix[3] * ax + mRotationMatrix[4] * ay + mRotationMatrix[5] * az;
      forward = (float)(east * Math.sin(mHeadingRad) + north * Math.cos(mHeadingRad));
    }
    else
    {
      // Without orientation only use horizontal magnitude as a conservative
      // indication that motion has started.
      forward = (float)Math.sqrt(ax * ax + ay * ay);
      if (mSpeedMps > 0.5f)
        forward = 0.0f;
    }

    if (Math.abs(forward) < ACCEL_DEADBAND)
      forward = 0.0f;

    // Reject individual bumps and integrate only plausible vehicle acceleration.
    forward = Math.max(-4.5f, Math.min(4.5f, forward));
    mSpeedMps += forward * (float)dt;
    mSpeedMps = Math.max(0.0f, Math.min(MAX_SPEED_MPS, mSpeedMps));

    mLocation.setSpeed(mSpeedMps);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}

  private void advance(double meters)
  {
    if (meters <= 0.0)
      return;

    double lat = Math.toRadians(mLocation.getLatitude());
    double lon = Math.toRadians(mLocation.getLongitude());
    double d = meters / EARTH_RADIUS_M;

    lat += d * Math.cos(mHeadingRad);
    double cosLat = Math.max(0.01, Math.cos(lat));
    lon += d * Math.sin(mHeadingRad) / cosLat;

    mLocation.setLatitude(Math.toDegrees(lat));
    mLocation.setLongitude(Math.toDegrees(lon));
    mLocation.setBearing((float)((Math.toDegrees(mHeadingRad) + 360.0) % 360.0));
    mLocation.setSpeed(mSpeedMps);

    // Make growing IMU uncertainty explicit to the route matcher.
    float accuracy = mLocation.hasAccuracy() ? mLocation.getAccuracy() : 20.0f;
    mLocation.setAccuracy(Math.min(500.0f, accuracy + 0.8f));
  }

  private void emit(long elapsedNanos)
  {
    mLocation.setProvider("navshlyah_inertial");
    mLocation.setTime(System.currentTimeMillis());
    mLocation.setElapsedRealtimeNanos(elapsedNanos);
    mLocation.setBearing((float)((Math.toDegrees(mHeadingRad) + 360.0) % 360.0));
    mLocation.setSpeed(mSpeedMps);
    mListener.onLocationChanged(new Location(mLocation));
  }
}
