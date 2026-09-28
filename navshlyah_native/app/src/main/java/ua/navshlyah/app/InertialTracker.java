package ua.navshlyah.app;

import android.content.Context;
import android.hardware.*;
import android.os.*;

public final class InertialTracker implements SensorEventListener {
    public interface Listener { void onAdvance(double meters,double headingDeg,String mode); }
    private final SensorManager sm; private final Listener listener;
    private Sensor rot,step,lin;
    private double heading=0, speed=0;
    private long lastLin=0,lastTick=0;
    private final Handler h=new Handler(Looper.getMainLooper());
    private boolean running=false;

    public InertialTracker(Context c,Listener l){sm=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);listener=l;}

    public void start(){
        if(running)return; running=true;
        rot=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if(rot==null)rot=sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        step=sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        lin=sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if(rot!=null)sm.registerListener(this,rot,SensorManager.SENSOR_DELAY_GAME);
        if(step!=null)sm.registerListener(this,step,SensorManager.SENSOR_DELAY_NORMAL);
        if(lin!=null)sm.registerListener(this,lin,SensorManager.SENSOR_DELAY_GAME);
        lastTick=SystemClock.elapsedRealtimeNanos(); h.post(tick);
    }
    public void stop(){running=false;h.removeCallbacks(tick);sm.unregisterListener(this);}
    private final Runnable tick=new Runnable(){public void run(){
        if(!running)return;
        long now=SystemClock.elapsedRealtimeNanos();
        double dt=Math.min(1.5,Math.max(0,(now-lastTick)/1e9)); lastTick=now;
        speed*=Math.pow(0.96,dt);
        if(speed>1.5 && dt>0) listener.onAdvance(speed*dt,heading,"Інерційний режим");
        h.postDelayed(this,500);
    }};

    public void onSensorChanged(SensorEvent e){
        int t=e.sensor.getType();
        if(t==Sensor.TYPE_ROTATION_VECTOR||t==Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR){
            float[] r=new float[9],o=new float[3];
            SensorManager.getRotationMatrixFromVector(r,e.values);SensorManager.getOrientation(r,o);
            heading=(Math.toDegrees(o[0])+360)%360;
        } else if(t==Sensor.TYPE_STEP_DETECTOR){
            listener.onAdvance(0.75,heading,"Кроки + компас");
            speed=Math.min(speed,1.4);
        } else if(t==Sensor.TYPE_LINEAR_ACCELERATION){
            long now=e.timestamp;
            if(lastLin!=0){
                double dt=Math.min(0.2,(now-lastLin)/1e9);
                double mag=Math.sqrt(e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2]);
                double useful=Math.max(0,mag-0.12);
                if(useful>0.15) speed=Math.min(35.0,speed+useful*dt*0.35);
            }
            lastLin=now;
        }
    }
    public void onAccuracyChanged(Sensor s,int a){}
}
