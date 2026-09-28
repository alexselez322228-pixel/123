package ua.navshlyah.app;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements LocationListener {
    private static final int REQ_LOC=42;
    private TextView status;
    private NavMapView map;
    private Button fixButton;
    private RoadGraph graph;
    private RouteFollower follower;
    private InertialTracker inertial;
    private LocationManager lm;
    private final ExecutorService bg=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private boolean gotInitialFix=false;
    private double curLat=50.4501,curLon=30.5234;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14),dp(8),dp(8),dp(8));
        bar.setBackgroundColor(Color.rgb(0,87,183));

        TextView title=new TextView(this);
        title.setText("НавШлях UA");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null,1);
        bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));

        fixButton=new Button(this);
        fixButton.setText("Уточнити GPS");
        fixButton.setOnClickListener(v->requestOneFix());
        bar.addView(fixButton,new LinearLayout.LayoutParams(dp(135),dp(48)));

        status=new TextView(this);
        status.setText("Завантаження офлайн-карти…");
        status.setPadding(dp(12),dp(8),dp(12),dp(8));
        status.setTextSize(15);
        status.setTextColor(Color.DKGRAY);

        map=new NavMapView(this);
        map.setLongPressListener((lat,lon)->buildRoute(lat,lon));

        TextView hint=new TextView(this);
        hint.setText("Утримуйте палець на карті, щоб вибрати пункт призначення. GPS використовується лише для початкової точки або після натискання «Уточнити GPS».");
        hint.setPadding(dp(12),dp(8),dp(12),dp(10));
        hint.setTextSize(13);
        hint.setTextColor(Color.DKGRAY);

        root.addView(bar);
        root.addView(status);
        root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(hint);
        setContentView(root);

        lm=(LocationManager)getSystemService(LOCATION_SERVICE);
        inertial=new InertialTracker(this,(meters,heading,mode)->ui.post(()->advanceRoute(meters,mode)));

        bg.execute(()->{
            try{
                RoadGraph g=RoadGraph.load(this);
                ui.post(()->{
                    graph=g;
                    follower=new RouteFollower(g);
                    map.setGraph(g);
                    status.setText("Карта готова. Визначаю початкову точку GPS…");
                    requestOneFix();
                });
            }catch(Exception e){
                ui.post(()->status.setText("Помилка карти: "+e.getMessage()));
            }
        });
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private void requestOneFix(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOC);
            return;
        }
        gotInitialFix=false;
        status.setText("Одноразово визначаю початкову позицію GPS…");
        try{
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,500,0,this,Looper.getMainLooper());
            Location last=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if(last!=null && System.currentTimeMillis()-last.getTime()<120000) onLocationChanged(last);
        }catch(Exception e){status.setText("GPS недоступний: "+e.getMessage());}
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ_LOC && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) requestOneFix();
        else status.setText("Без одноразового GPS задайте старт пізніше через «Уточнити GPS».");
    }

    @Override public void onLocationChanged(Location l){
        if(gotInitialFix || l==null) return;
        gotInitialFix=true;
        try{lm.removeUpdates(this);}catch(Exception ignored){}
        curLat=l.getLatitude();curLon=l.getLongitude();
        map.setPosition(curLat,curLon,true);
        inertial.stop(); inertial.start();
        status.setText("Початкову точку зафіксовано. GPS вимкнено. Утримуйте карту для вибору пункту.");
        if(graph!=null && follower!=null){
            // A new one-shot fix re-anchors the current route to the nearest road.
            map.setPosition(curLat,curLon,true);
        }
    }

    private void buildRoute(double lat,double lon){
        if(graph==null){Toast.makeText(this,"Карта ще завантажується",Toast.LENGTH_SHORT).show();return;}
        final double sLat=curLat,sLon=curLon;
        status.setText("Будую маршрут офлайн…");
        bg.execute(()->{
            int s=graph.nearest(sLat,sLon), t=graph.nearest(lat,lon);
            int[] path=graph.route(s,t);
            ui.post(()->{
                if(path.length<2){status.setText("Не вдалося знайти маршрут у дорожньому графі.");return;}
                follower.setPath(path); map.setRoute(path);
                double km=0;
                for(int i=1;i<path.length;i++) km+=RoadGraph.distance(graph.lat(path[i-1]),graph.lon(path[i-1]),graph.lat(path[i]),graph.lon(path[i]));
                status.setText(String.format(java.util.Locale.forLanguageTag("uk"),"Маршрут %.1f км. GPS вимкнено — рух за локальними датчиками.",km/1000.0));
            });
        });
    }

    private void advanceRoute(double meters,String mode){
        if(follower==null || !follower.active()) return;
        double[] p=follower.advance(meters);
        if(p!=null){
            curLat=p[0];curLon=p[1];
            map.setPosition(curLat,curLon,true);
            status.setText(mode+" • GPS не використовується");
        }
    }

    @Override protected void onDestroy(){
        super.onDestroy();
        try{lm.removeUpdates(this);}catch(Exception ignored){}
        if(inertial!=null) inertial.stop();
        bg.shutdownNow();
    }

    @Override public void onProviderDisabled(String p){}
    @Override public void onProviderEnabled(String p){}
    @Override public void onStatusChanged(String p,int s,Bundle e){}
}
