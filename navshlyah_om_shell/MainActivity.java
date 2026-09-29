package ua.navshlyah.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.*;

import java.util.Locale;

import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.MapView;
import app.organicmaps.sdk.OrganicMaps;
import app.organicmaps.sdk.PlacePageActivationListener;
import app.organicmaps.sdk.widget.placepage.PlacePageData;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.CarDirection;
import app.organicmaps.sdk.routing.JunctionInfo;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;

public final class MainActivity extends Activity implements RoutingController.Container, PlacePageActivationListener {
  private static final int REQ=7;

  private OrganicMaps om;
  private MapView mapView;
  private TextView status, navMain, navSub;
  private EditText latBox, lonBox;
  private Button buildButton, startButton, stopButton;
  private RoutingController routing;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private TextToSpeech tts;

  private int lastAnnouncedBucket=-1;
  private CarDirection lastDirection=CarDirection.NoTurn;
  private boolean autoRerouting;
  private boolean restartAfterReroute;
  private long lastRerouteMs;

  private final Runnable navTick=new Runnable(){
    @Override public void run(){
      updateNavigationUi();
      handler.postDelayed(this,1000);
    }
  };

  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    om=((NavApp)getApplication()).maps();
    routing=RoutingController.get();
    routing.attach(this);

    tts=new TextToSpeech(this, result -> {
      if(result==TextToSpeech.SUCCESS) tts.setLanguage(Locale.forLanguageTag("uk-UA"));
    });

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
    status.setText("Офлайн-карта готова. Торкніться місця на карті або введіть координати.");
    status.setTextColor(Color.DKGRAY);
    status.setTextSize(14);
    status.setPadding(dp(12),dp(6),dp(12),dp(6));

    LinearLayout routeBox=new LinearLayout(this);
    routeBox.setOrientation(LinearLayout.HORIZONTAL);
    routeBox.setPadding(dp(8),0,dp(8),dp(4));

    latBox=new EditText(this); latBox.setHint("Широта"); latBox.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    lonBox=new EditText(this); lonBox.setHint("Довгота"); lonBox.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    buildButton=new Button(this); buildButton.setText("Маршрут"); buildButton.setOnClickListener(v->buildRoute());

    routeBox.addView(latBox,new LinearLayout.LayoutParams(0,dp(52),1));
    routeBox.addView(lonBox,new LinearLayout.LayoutParams(0,dp(52),1));
    routeBox.addView(buildButton,new LinearLayout.LayoutParams(dp(110),dp(52)));

    LinearLayout navPanel=new LinearLayout(this);
    navPanel.setOrientation(LinearLayout.VERTICAL);
    navPanel.setPadding(dp(12),dp(8),dp(12),dp(8));
    navPanel.setBackgroundColor(Color.rgb(245,248,252));

    navMain=new TextView(this);
    navMain.setText("Маршрут ще не побудовано");
    navMain.setTextSize(20);
    navMain.setTextColor(Color.rgb(0,61,130));
    navMain.setTypeface(null,1);

    navSub=new TextView(this);
    navSub.setText("Торкніться точки на карті — маршрут побудується автоматично.");
    navSub.setTextSize(14);
    navSub.setTextColor(Color.DKGRAY);

    LinearLayout navButtons=new LinearLayout(this);
    navButtons.setOrientation(LinearLayout.HORIZONTAL);
    startButton=new Button(this); startButton.setText("СТАРТ"); startButton.setEnabled(false); startButton.setOnClickListener(v->startNavigation());
    stopButton=new Button(this); stopButton.setText("СТОП"); stopButton.setEnabled(false); stopButton.setOnClickListener(v->stopNavigation());
    navButtons.addView(startButton,new LinearLayout.LayoutParams(0,dp(50),1));
    navButtons.addView(stopButton,new LinearLayout.LayoutParams(0,dp(50),1));

    navPanel.addView(navMain);
    navPanel.addView(navSub);
    navPanel.addView(navButtons);

    mapView=new MapView(this);
    mapView.getMap().setLocationHelper(om.getLocationHelper());

    Button gps=new Button(this);
    gps.setText("Разово уточнити GPS");
    gps.setOnClickListener(v->requestStartFix());

    root.addView(top);
    root.addView(status);
    root.addView(routeBox);
    root.addView(navPanel);
    root.addView(mapView,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(gps,new LinearLayout.LayoutParams(-1,dp(54)));
    setContentView(root);

    handler.post(navTick);
    requestStartFix();
  }

  private int dp(int x){ return Math.round(x*getResources().getDisplayMetrics().density); }

  private void requestStartFix(){
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ);
      return;
    }
    status.setText("Разово визначаю стартову точку GPS… після фіксації GNSS вимикається.");
    try { om.getLocationHelper().requestOneShotGpsFix(); }
    catch(Exception e){ status.setText("Не вдалося запустити GPS: "+e.getMessage()); }
  }

  private void buildRoute(){
    double lat,lon;
    try{
      lat=Double.parseDouble(latBox.getText().toString().trim().replace(',','.'));
      lon=Double.parseDouble(lonBox.getText().toString().trim().replace(',','.'));
    }catch(Exception e){
      status.setText("Введіть координати або просто торкніться пункту призначення на карті.");
      return;
    }
    buildRouteTo(lat,lon,"Пункт призначення");
  }

  private void buildRouteTo(double lat,double lon,String title){
    final MapObject start=om.getLocationHelper().getMyPosition();
    if(start==null){
      status.setText("Спочатку потрібна початкова точка. Натисніть «Разово уточнити GPS».");
      return;
    }
    if(lat<-90||lat>90||lon<-180||lon>180){
      status.setText("Некоректна точка призначення.");
      return;
    }

    latBox.setText(String.format(Locale.US,"%.6f",lat));
    lonBox.setText(String.format(Locale.US,"%.6f",lon));
    String resolved=(title==null||title.isEmpty()) ? Framework.nativeGetAddress(lat,lon) : title;
    if(resolved==null||resolved.isEmpty()) resolved="Точка на карті";
    MapObject finish=MapObject.createMapObject(MapObject.POI,resolved,"",lat,lon);

    status.setText("Будую автомобільний маршрут до вибраної точки офлайн…");
    navMain.setText("Побудова маршруту…");
    navSub.setText(resolved);
    startButton.setEnabled(false);
    routing.prepare(start,finish,Router.Vehicle);
    Framework.nativeDeactivateMapSelectionCircle(false);
  }

  private void startNavigation(){
    if(!routing.isBuilt()) return;
    try {
      JunctionInfo[] routePoints=Framework.nativeGetRouteJunctionPoints(3.0);
      om.getLocationHelper().setDeadReckoningRoute(routePoints);
    } catch(Throwable ignored) {}
    routing.start();
    lastAnnouncedBucket=-1;
    lastDirection=CarDirection.NoTurn;
    startButton.setEnabled(false);
    stopButton.setEnabled(true);
    status.setText("Навігація активна. Камера слідкує за рухом щосекунди; GNSS вимкнений до ручного уточнення.");
    speak("Навігацію розпочато");
  }

  private void stopNavigation(){
    routing.cancel();
    om.getLocationHelper().setDeadReckoningRoute(null);
    stopButton.setEnabled(false);
    startButton.setEnabled(false);
    navMain.setText("Навігацію завершено");
    navSub.setText("Побудуйте новий маршрут.");
    status.setText("Маршрут зупинено.");
  }

  private void updateNavigationUi(){
    if(routing.isNavigating() && !autoRerouting){
      try {
        if(om.getLocationHelper().isDeadReckoningOffRoute() &&
           SystemClock.elapsedRealtime()-lastRerouteMs>5000){
          rebuildAfterDeviation();
          return;
        }
      } catch(Throwable ignored) {}
    }

    if(!routing.isNavigating()) return;
    // Keep the camera locked to the locally estimated position and route.
    try { Framework.nativeFollowRoute(); } catch(Throwable ignored) {}
    RoutingInfo info;
    try { info=Framework.nativeGetRouteFollowingInfo(); }
    catch(Throwable t){ return; }
    if(info==null) return;

    String dist=info.distToTurn.toString(this);
    String dir=directionText(info.carDirection,info.exitNum);
    String street=(info.nextStreet==null||info.nextStreet.isEmpty()) ? "" : " • "+info.nextStreet;
    navMain.setText((dist.isEmpty()?"":dist+" • ")+dir);
    Location estimated=om.getLocationHelper().getSavedLocation();
    String speed="";
    if(estimated!=null && "navshlyah_inertial".equals(estimated.getProvider()))
    {
      if(estimated.getSpeed()<0.35f)
        speed=" • СТОЇМО";
      else
        speed=String.format(Locale.forLanguageTag("uk")," • ~%.0f км/год",estimated.getSpeed()*3.6f);
    }
    navSub.setText("До фінішу: "+info.distToTarget.toString(this)+" • "+formatTime(info.totalTimeInSeconds)+street+
                   String.format(Locale.forLanguageTag("uk")," • %.0f%%",info.completionPercent)+speed);

    announceIfNeeded(info);
  }

  private void rebuildAfterDeviation(){
    MapObject finish=routing.getEndPoint();
    Location here=om.getLocationHelper().getSavedLocation();
    if(finish==null || here==null) return;

    autoRerouting=true;
    restartAfterReroute=true;
    lastRerouteMs=SystemClock.elapsedRealtime();

    MapObject start=MapObject.createMapObject(
        MapObject.MY_POSITION,"","",here.getLatitude(),here.getLongitude());

    status.setText("З'їзд з маршруту — перебудовую маршрут офлайн…");
    navMain.setText("Перебудова маршруту…");
    navSub.setText("Нова траєкторія від поточної позиції");
    startButton.setEnabled(false);

    routing.prepare(start,finish,Router.Vehicle);
  }

  private void announceIfNeeded(RoutingInfo info){
    double m=info.distToTurn.mDistance;
    // mDistance is expressed in the units represented by the Distance object in the UI,
    // so normalize approximate threshold from its formatted units.
    double meters=m;
    switch(info.distToTurn.mUnits){
      case Kilometers: meters=m*1000.0; break;
      case Feet: meters=m*0.3048; break;
      case Miles: meters=m*1609.344; break;
      default: break;
    }

    int bucket;
    if(meters<=25) bucket=0;
    else if(meters<=60) bucket=1;
    else if(meters<=220) bucket=2;
    else if(meters<=520) bucket=3;
    else bucket=4;

    if(info.carDirection!=lastDirection || bucket<lastAnnouncedBucket || lastAnnouncedBucket<0){
      if(bucket<=3){
        String phrase=(bucket==0?"":("Через "+Math.max(10,(int)(Math.round(meters/10)*10))+" метрів, "))+
                      directionText(info.carDirection,info.exitNum).toLowerCase(Locale.forLanguageTag("uk"));
        speak(phrase);
      }
      lastDirection=info.carDirection;
      lastAnnouncedBucket=bucket;
    }
  }

  private String directionText(CarDirection d,int exit){
    switch(d){
      case GoStraight:
      case NoTurn:
      case StartAtEndOfStreet: return "Рухайтесь прямо";
      case TurnRight: return "Поверніть праворуч";
      case TurnSharpRight: return "Різко поверніть праворуч";
      case TurnSlightRight: return "Тримайтесь праворуч";
      case TurnLeft: return "Поверніть ліворуч";
      case TurnSharpLeft: return "Різко поверніть ліворуч";
      case TurnSlightLeft: return "Тримайтесь ліворуч";
      case UTurnLeft:
      case UTurnRight: return "Розверніться";
      case EnterRoundAbout: return "В'їдьте на круговий рух";
      case LeaveRoundAbout: return exit>0 ? "З'їдьте з кільця, з'їзд "+exit : "З'їдьте з кільця";
      case StayOnRoundAbout: return "Продовжуйте рух по кільцю";
      case ExitHighwayToLeft: return "З'їзд ліворуч";
      case ExitHighwayToRight: return "З'їзд праворуч";
      case ReachedYourDestination: return "Ви прибули";
      default: return "Продовжуйте маршрут";
    }
  }

  private String formatTime(int sec){
    int min=Math.max(0,sec/60);
    if(min<60) return min+" хв";
    return (min/60)+" год "+(min%60)+" хв";
  }

  private void speak(String text){
    if(tts!=null) tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"nav");
  }

  @Override public void onPlacePageActivated(PlacePageData data){
    if(routing.isNavigating()){
      status.setText("Навігація триває. Для нового пункту спочатку натисніть СТОП.");
      return;
    }
    if(!(data instanceof MapObject)) return;
    MapObject point=(MapObject)data;
    if(point.isMyPosition()) return;
    String title=point.getTitle();
    if(title==null||title.isEmpty()) title=point.getAddress();
    buildRouteTo(point.getLat(),point.getLon(),title);
  }

  @Override public void onPlacePageDeactivated(){}

  @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
    super.onRequestPermissionsResult(r,p,g);
    if(r==REQ && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) requestStartFix();
    else status.setText("Без дозволу на GPS автоматичну початкову точку визначити неможливо.");
  }

  @Override public void onStartRouteBuilding(){
    om.getLocationHelper().setDeadReckoningRoute(null);
    if(autoRerouting)
      status.setText("З'їзд з маршруту — перебудовую маршрут офлайн…");
    else
      status.setText("Маршрут обчислюється офлайн…");
  }
  @Override public void updateBuildProgress(int progress, Router router){ navMain.setText("Побудова маршруту: "+progress+"%"); }
  @Override public void onBuiltRoute(){
    try {
      JunctionInfo[] routePoints=Framework.nativeGetRouteJunctionPoints(3.0);
      om.getLocationHelper().setDeadReckoningRoute(routePoints);
    } catch(Throwable ignored) {}

    if(restartAfterReroute){
      restartAfterReroute=false;
      autoRerouting=false;
      routing.start();
      startButton.setEnabled(false);
      stopButton.setEnabled(true);
      navMain.setText("Маршрут перебудовано");
      navSub.setText("Продовжуйте рух за новим маршрутом.");
      status.setText("Новий маршрут побудовано офлайн. Навігація продовжується.");
      speak("Маршрут перебудовано");
      return;
    }

    navMain.setText("Маршрут готовий");
    navSub.setText("Натисніть СТАРТ для щосекундного ведення.");
    startButton.setEnabled(true);
    status.setText("Маршрут побудовано локально, без Інтернету.");
  }
  @Override public void onCommonBuildError(int code,String[] maps){
    autoRerouting=false;
    restartAfterReroute=false;
    navMain.setText("Помилка маршруту");
    navSub.setText("Код: "+code);
    status.setText("Не вдалося побудувати маршрут. Перевірте, що весь маршрут у межах вбудованої карти.");
  }
  @Override public void showRoutePlan(boolean show,Runnable completionListener){ if(completionListener!=null) completionListener.run(); }
  @Override public void showNavigation(boolean show){}
  @Override public void updateMenu(){}
  @Override public void onNavigationStarted(){}
  @Override public void onNavigationCancelled(){}

  @Override protected void onStart(){
    super.onStart();
    mapView.getMap().onStart();
    Framework.nativePlacePageActivationListener(this);
  }
  @Override protected void onResume(){ super.onResume(); mapView.getMap().onResume(); }
  @Override protected void onPause(){ mapView.getMap().onPause(); super.onPause(); }
  @Override protected void onStop(){
    try { Framework.nativeRemovePlacePageActivationListener(this); } catch(Throwable ignored) {}
    mapView.getMap().onStop();
    super.onStop();
  }

  @Override protected void onDestroy(){
    handler.removeCallbacks(navTick);
    routing.detach();
    if(tts!=null){ tts.stop(); tts.shutdown(); }
    super.onDestroy();
  }
}
