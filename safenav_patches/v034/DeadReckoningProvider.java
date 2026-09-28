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

final class DeadReckoningProvider extends BaseLocationProvider implements SensorEventListener
{
  private static final double EARTH_RADIUS_M = 6378137.0;
  private static final float STEP_METERS = 0.75f;

  @NonNull private final SensorManager mSensorManager;
  @NonNull private final Handler mHandler = new Handler(Looper.getMainLooper());
  @NonNull private final Location mLocation;
  private Sensor mRotation;
  private Sensor mStep;
  private double mHeadingRad;
  private float mSpeedMps;
  private long mLastTickNanos;
  private boolean mRunning;

  DeadReckoningProvider(@NonNull Context context, @NonNull Listener listener, @NonNull Location seed)
  {
    super(listener);
    mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    mLocation = new Location(seed);
    mSpeedMps = seed.hasSpeed() ? Math.max(0.0f, seed.getSpeed()) : 0.0f;
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
    mStep = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
    if (mRotation != null)
      mSensorManager.registerListener(this, mRotation, SensorManager.SENSOR_DELAY_GAME);
    if (mStep != null)
      mSensorManager.registerListener(this, mStep, SensorManager.SENSOR_DELAY_NORMAL);
    mLastTickNanos = SystemClock.elapsedRealtimeNanos();
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
      if (mSpeedMps >= 2.0f && dt > 0.0)
      {
        advance(mSpeedMps * dt);
        emit(now);
      }
      mHandler.postDelayed(this, 1000);
    }
  };

  @Override
  public void onSensorChanged(SensorEvent event)
  {
    if (!mRunning)
      return;

    if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR ||
        event.sensor.getType() == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    {
      float[] rotation = new float[9];
      float[] orientation = new float[3];
      SensorManager.getRotationMatrixFromVector(rotation, event.values);
      SensorManager.getOrientation(rotation, orientation);
      mHeadingRad = orientation[0];
    }
    else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR && mSpeedMps < 2.0f)
    {
      advance(STEP_METERS);
      emit(SystemClock.elapsedRealtimeNanos());
    }
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
    if (mSpeedMps >= 2.0f)
      mLocation.setSpeed(mSpeedMps);

    float accuracy = mLocation.hasAccuracy() ? mLocation.getAccuracy() : 20.0f;
    mLocation.setAccuracy(Math.min(500.0f, accuracy + 1.0f));
  }

  private void emit(long elapsedNanos)
  {
    mLocation.setProvider("navshlyah_inertial");
    mLocation.setTime(System.currentTimeMillis());
    mLocation.setElapsedRealtimeNanos(elapsedNanos);
    mListener.onLocationChanged(new Location(mLocation));
  }
}
