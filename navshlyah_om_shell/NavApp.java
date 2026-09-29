package ua.navshlyah.app;

import android.app.Application;
import app.organicmaps.sdk.OrganicMaps;

public final class NavApp extends Application {
  private OrganicMaps maps;
  @Override public void onCreate() {
    super.onCreate();
    maps = new OrganicMaps(getApplicationContext(), "fdroid", getPackageName(), 17, "1.7.0");
    try {
      maps.init(() -> {});
    } catch (Exception e) {
      throw new RuntimeException("Не вдалося ініціалізувати офлайн-карту", e);
    }
  }
  public OrganicMaps maps(){ return maps; }
}
