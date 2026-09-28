from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path("om")

# Offline permissions.
for mf in root.glob("android/**/AndroidManifest.xml"):
    ms = mf.read_text(encoding="utf-8")
    original = ms
    ms = re.sub(
        r'\s*<uses-permission\s+android:name="android.permission.INTERNET"(?:\s+[^>]*)?/?>\s*',
        '\n', ms
    )
    if mf == root / "android/app/src/main/AndroidManifest.xml":
        ms = ms.replace('android:allowBackup="true"', 'android:allowBackup="false"')
        ms = ms.replace('android:backupInForeground="true"', 'android:backupInForeground="false"')
        ms = ms.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/logo"')
        m = re.search(r'<manifest\b[^>]*>', ms, flags=re.S)
        if not m:
            raise SystemExit("Could not locate opening <manifest> tag")
        removal = '\n  <uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>\n'
        ms = ms[:m.end()] + removal + ms[m.end():]
    if ms != original:
        mf.write_text(ms, encoding="utf-8")

main_manifest = root / "android/app/src/main/AndroidManifest.xml"
ET.parse(main_manifest)
main_text = main_manifest.read_text(encoding="utf-8")
if 'android.permission.ACCESS_NETWORK_STATE' not in main_text:
    raise SystemExit("ACCESS_NETWORK_STATE missing")

# Own package and permission verifier.
gradle = root / "android/app/build.gradle"
gs = gradle.read_text(encoding="utf-8")
gs = gs.replace("    \"name='android.permission.INTERNET'\",\n", "")
marker = "defaultConfig {"
p = gs.find(marker)
if p < 0:
    raise SystemExit("defaultConfig not found")
gs = gs[:p+len(marker)] + "\n    applicationId 'ua.navshlyah.offline'\n" + gs[p+len(marker):]
gradle.write_text(gs, encoding="utf-8")

# Brand.
for path in root.glob("android/app/src/main/res/values*/strings.xml"):
    txt = path.read_text(encoding="utf-8")
    txt = re.sub(r'(<string\s+name="app_name"[^>]*>).*?(</string>)',
                 r'\1НавШлях UA\2', txt, count=1, flags=re.S)
    path.write_text(txt, encoding="utf-8")

uk = root / "android/app/src/main/res/values-uk/strings.xml"
txt = uk.read_text(encoding="utf-8")
replacements = {
    "about_menu_title": "Про НавШлях UA",
    "about_headline": "Офлайн-навігатор для України",
    "about_proposition_1": "• Київ і Київська область уже всередині застосунку",
    "about_proposition_2": "• Карта і маршрут працюють без інтернету",
    "about_proposition_3": "• Початкова позиція визначається GPS, далі працює інерційний режим",
    "about_developed_by_enthusiasts": "НавШлях UA використовує відкриті картографічні дані OpenStreetMap та компоненти Organic Maps.",
}
for key, value in replacements.items():
    txt = re.sub(
        rf'(<string\s+name="{re.escape(key)}"[^>]*>).*?(</string>)',
        lambda m, v=value: m.group(1) + v + m.group(2),
        txt, count=1, flags=re.S
    )
# Rebrand other visible Ukrainian strings, while restoring legal attribution above.
txt = txt.replace("Organic Maps", "НавШлях UA")
txt = re.sub(
    r'(<string\s+name="about_developed_by_enthusiasts"[^>]*>).*?(</string>)',
    lambda m: m.group(1) + replacements["about_developed_by_enthusiasts"] + m.group(2),
    txt, count=1, flags=re.S
)
uk.write_text(txt, encoding="utf-8")

# Force Ukrainian locale.
app = root / "android/app/src/main/java/app/organicmaps/MwmApplication.java"
js = app.read_text(encoding="utf-8")
if "android.content.res.Configuration" not in js:
    js = js.replace("import android.content.Context;\n",
                    "import android.content.Context;\nimport android.content.res.Configuration;\n")
if "import java.util.Locale;" not in js:
    js = js.replace("import java.lang.ref.WeakReference;\n",
                    "import java.lang.ref.WeakReference;\nimport java.util.Locale;\n")
anchor = "public class MwmApplication extends Application implements Application.ActivityLifecycleCallbacks\n{\n"
method = """public class MwmApplication extends Application implements Application.ActivityLifecycleCallbacks
{
  @Override
  protected void attachBaseContext(Context base)
  {
    Locale locale = Locale.forLanguageTag("uk");
    Locale.setDefault(locale);
    Configuration config = new Configuration(base.getResources().getConfiguration());
    config.setLocale(locale);
    super.attachBaseContext(base.createConfigurationContext(config));
  }

"""
if anchor not in js:
    raise SystemExit("MwmApplication class anchor not found")
js = js.replace(anchor, method, 1)
app.write_text(js, encoding="utf-8")

# Copy custom logo and dead-reckoning provider from this repo.
workspace = Path(__file__).resolve().parent
(root / "android/app/src/main/res/drawable/logo.xml").write_text(
    (workspace / "logo.xml").read_text(encoding="utf-8"), encoding="utf-8"
)
provider_dst = root / "android/sdk/src/main/java/app/organicmaps/sdk/location/DeadReckoningProvider.java"
provider_dst.write_text(
    (workspace / "DeadReckoningProvider.java").read_text(encoding="utf-8"), encoding="utf-8"
)

# Hide donation/social-promotional controls. Keep OSM/legal attribution.
about = root / "android/app/src/main/res/layout/about.xml"
ax = about.read_text(encoding="utf-8")
for vid in ["donate","support_us","news","rate","telegram","github","web","email",
            "matrix","mastodon","facebook","twitter","instagram"]:
    token = f'android:id="@+id/{vid}"'
    if token in ax:
        ax = ax.replace(token, token + '\n        android:visibility="gone"', 1)
about.write_text(ax, encoding="utf-8")

# Register bundled Kyiv map as resource-backed local map.
cpp = root / "libs/platform/local_country_file_utils.cpp"
cs = cpp.read_text(encoding="utf-8")
anchor = "  // Check for World and WorldCoasts in app bundle or in resources.\n"
idx = cs.find(anchor)
end_anchor = "\nvoid CleanupMapsDirectory(int64_t latestVersion)"
endp = cs.find(end_anchor, idx)
if idx < 0 or endp < 0:
    raise SystemExit("Bundled map patch anchor not found")
block_end = cs.rfind("\n}", idx, endp)
if block_end < 0:
    raise SystemExit("Bundled map function closing brace not found")
bundled = r'''
  // NavShlyah UA: Kyiv Oblast is bundled directly inside the APK.
  try
  {
    std::string const fileName("Ukraine_Kyiv Oblast");
    ModelReaderPtr reader(platform.GetReader(fileName + DATA_FILE_EXTENSION, "r"));
    LocalCountryFile bundledFile(std::string(), CountryFile(fileName), version::ReadVersionDate(reader));
    bundledFile.m_files[base::Underlying(MapFileType::Map)] = reader.Size();

    auto existing = std::find_if(localFiles.begin(), localFiles.end(),
                                 [&fileName](LocalCountryFile const & f)
                                 { return f.GetCountryName() == fileName; });
    if (existing == localFiles.end())
      localFiles.push_back(std::move(bundledFile));
    else if (bundledFile.GetVersion() > existing->GetVersion())
      *existing = std::move(bundledFile);
  }
  catch (RootException const & ex)
  {
    LOG(LWARNING, ("NavShlyah bundled Kyiv map missing:", ex.Msg()));
  }
'''
cs = cs[:block_end] + bundled + cs[block_end:]
cpp.write_text(cs, encoding="utf-8")

# Switch to local dead reckoning after first accepted real location fix.
helper = root / "android/sdk/src/main/java/app/organicmaps/sdk/location/LocationHelper.java"
hs = helper.read_text(encoding="utf-8")
old = """    mSavedLocation = location;
    mMyPosition = null;
    notifyLocationUpdated();
  }
"""
new = """    mSavedLocation = location;
    mMyPosition = null;
    notifyLocationUpdated();

    // NavShlyah UA privacy mode: after the first accepted real location fix,
    // switch off GNSS/network polling and continue with local phone sensors.
    if (!(mLocationProvider instanceof DeadReckoningProvider))
    {
      Logger.i(TAG, "Initial location acquired; switching to local inertial tracking");
      final Location seed = new Location(mSavedLocation);
      mLocationProvider.stop();
      unsubscribeFromGnssStatusUpdates();
      mLocationProvider = new DeadReckoningProvider(mContext, this, seed);
      mInterval = 1000;
      mLocationProvider.start(mInterval);
    }
  }
"""
if old not in hs:
    raise SystemExit("LocationHelper onLocationChanged anchor not found")
hs = hs.replace(old, new, 1)
helper.write_text(hs, encoding="utf-8")
