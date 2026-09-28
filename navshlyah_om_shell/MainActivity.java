package ua.navshlyah.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.MapView;
import app.organicmaps.sdk.OrganicMaps;

public final class MainActivity extends Activity {
  private static final int REQ=7;
  private OrganicMaps om;
  private MapView mapView;
  private TextView status;

  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    om=((NavApp)getApplication()).maps();

    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.WHITE);

    LinearLayout top=new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    top.setPadding(dp(12),dp(6),dp(8),dp(6));
    top.setBackgroundColor(Color.rgb(0,87,183));

    TextView title=new TextView(this);
    title.setText("НавШлях UA");
    title.setTextColor(Color.WHITE);
    title.setTextSize(20);
    title.setTypeface(null,1);
    top.addView(title,new LinearLayout.LayoutParams(0,dp(50),1));

    Button minus=new Button(this); minus.setText("−"); minus.setTextSize(22); minus.setOnClickListener(v->Map.zoomOut());
    Button plus=new Button(this); plus.setText("+"); plus.setTextSize(22); plus.setOnClickListener(v->Map.zoomIn());
    top.addView(minus,new LinearLayout.LayoutParams(dp(58),dp(48)));
    top.addView(plus,new LinearLayout.LayoutParams(dp(58),dp(48)));

    status=new TextView(this);
    status.setText("Київ і Київська область — офлайн. GPS використовується лише для стартової точки.");
    status.setTextColor(Color.DKGRAY);
    status.setTextSize(14);
    status.setPadding(dp(12),dp(6),dp(12),dp(6));

    mapView=new MapView(this);
    mapView.getMap().setLocationHelper(om.getLocationHelper());

    Button gps=new Button(this);
    gps.setText("Визначити стартову точку");
    gps.setOnClickListener(v->requestStartFix());

    root.addView(top);
    root.addView(status);
    root.addView(mapView,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(gps,new LinearLayout.LayoutParams(-1,dp(54)));
    setContentView(root);

    requestStartFix();
  }

  private int dp(int x){ return Math.round(x*getResources().getDisplayMetrics().density); }

  private void requestStartFix(){
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ);
      return;
    }
    status.setText("Визначаю стартову точку GPS… Після першого фіксу GPS буде вимкнено.");
    try { om.getLocationHelper().start(); }
    catch(Exception e){ status.setText("Не вдалося запустити GPS: "+e.getMessage()); }
  }

  @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
    super.onRequestPermissionsResult(r,p,g);
    if(r==REQ && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) requestStartFix();
    else status.setText("GPS не дозволено. Карта працює офлайн, стартову точку можна визначити пізніше.");
  }

  @Override protected void onStart(){ super.onStart(); mapView.getMap().onStart(); }
  @Override protected void onResume(){ super.onResume(); mapView.getMap().onResume(); }
  @Override protected void onPause(){ mapView.getMap().onPause(); super.onPause(); }
  @Override protected void onStop(){ mapView.getMap().onStop(); super.onStop(); }
}
