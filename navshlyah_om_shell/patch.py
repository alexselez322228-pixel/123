from pathlib import Path
import shutil, re

root=Path("om")
repo=Path.cwd()

# Register custom app module.
settings=root/"android/settings.gradle"
s=settings.read_text(encoding="utf-8")
if "include ':navshlyah'" not in s:
    s += "\ninclude ':navshlyah'\n"
settings.write_text(s,encoding="utf-8")

mod=root/"android/navshlyah"
(mod/"src/main/java/ua/navshlyah/app").mkdir(parents=True,exist_ok=True)
(mod/"src/main/res/values").mkdir(parents=True,exist_ok=True)
(mod/"src/main/res/drawable").mkdir(parents=True,exist_ok=True)
(mod/"src/main/assets").mkdir(parents=True,exist_ok=True)
shutil.copy(repo/"navshlyah_om_shell/build.gradle", mod/"build.gradle")
shutil.copy(repo/"navshlyah_om_shell/AndroidManifest.xml", mod/"src/main/AndroidManifest.xml")
shutil.copy(repo/"navshlyah_om_shell/styles.xml", mod/"src/main/res/values/styles.xml")
shutil.copy(repo/"navshlyah_om_shell/ic_nav.xml", mod/"src/main/res/drawable/ic_nav.xml")
shutil.copy(repo/"navshlyah_om_shell/MainActivity.java", mod/"src/main/java/ua/navshlyah/app/MainActivity.java")
shutil.copy(repo/"navshlyah_om_shell/NavApp.java", mod/"src/main/java/ua/navshlyah/app/NavApp.java")

# SDK must not merge INTERNET into final APK.
sdk_manifest=root/"android/sdk/src/main/AndroidManifest.xml"
ms=sdk_manifest.read_text(encoding="utf-8")
ms=re.sub(r'\s*<uses-permission\s+android:name="android.permission.INTERNET"\s*/>\s*','\n',ms)
sdk_manifest.write_text(ms,encoding="utf-8")

# Expose MapView.getMap() to our own application shell.
mv=root/"android/sdk/src/main/java/app/organicmaps/sdk/MapView.java"
j=mv.read_text(encoding="utf-8")
j=j.replace("  Map getMap()\n", "  public Map getMap()\n")
mv.write_text(j,encoding="utf-8")

# Install local dead-reckoning provider and switch to it after first accepted GNSS fix.
dst=root/"android/sdk/src/main/java/app/organicmaps/sdk/location/DeadReckoningProvider.java"
shutil.copy(repo/"safenav_patches/v034/DeadReckoningProvider.java",dst)
helper=root/"android/sdk/src/main/java/app/organicmaps/sdk/location/LocationHelper.java"
hs=helper.read_text(encoding="utf-8")

# Add an explicit one-shot GNSS refresh API. It temporarily replaces the inertial
# provider, accepts one real fix, then the onLocationChanged patch below switches
# immediately back to DeadReckoningProvider.
hs=hs.replace(
    "  private boolean mActive;\n  private final Handler mHandler;",
    "  private boolean mActive;\n  private boolean mNavShlyahOneShotGps;\n  @Nullable private JunctionInfo[] mNavShlyahRoute;\n  private final Handler mHandler;",
    1)
hs=hs.replace(
    """    if (mSavedLocation != null)
    {
      if (!LocationUtils.isLocationBetterThanLast(location, mSavedLocation))""",
    """    if (!(mLocationProvider instanceof DeadReckoningProvider) &&
        !mNavShlyahOneShotGps && mSavedLocation != null)
    {
      if (!LocationUtils.isLocationBetterThanLast(location, mSavedLocation))""",
    1)
gps_method=r'''
  /**
   * NavShlyah UA: request exactly one fresh GNSS fix. After a valid fix is
   * delivered, onLocationChanged() switches back to the local inertial provider.
   */
  @SuppressLint("MissingPermission")
  public void requestOneShotGpsFix()
  {
    Logger.i(TAG, "NavShlyah: request one-shot GNSS fix");
    mNavShlyahOneShotGps = true;
    mLocationProvider.stop();
    unsubscribeFromGnssStatusUpdates();
    mLocationProvider = new AndroidNativeProvider(mContext, this);
    mInterval = 1000;
    mActive = true;
    mLocationProvider.start(mInterval);
    subscribeToGnssStatusUpdates();
    mHandler.removeCallbacks(mLocationTimeoutRunnable);
    mHandler.postDelayed(mLocationTimeoutRunnable, LOCATION_UPDATE_TIMEOUT_MS);
  }

  /**
   * Supplies the active offline route to the local dead-reckoning provider.
   * The provider snaps to this polyline and advances along it instead of
   * free-running by compass heading.
   */
  public void setDeadReckoningRoute(@Nullable JunctionInfo[] points)
  {
    mNavShlyahRoute = points;
    if (mLocationProvider instanceof DeadReckoningProvider)
      ((DeadReckoningProvider) mLocationProvider).setRoute(points);
  }

  public boolean isDeadReckoningOffRoute()
  {
    return mLocationProvider instanceof DeadReckoningProvider &&
           ((DeadReckoningProvider) mLocationProvider).isOffRoute();
  }

'''
anchor="  /**\n   * Restart the location with a new refresh interval if changed.\n   */"
if anchor not in hs:
    raise SystemExit("LocationHelper method anchor not found")
hs=hs.replace(anchor,gps_method+anchor,1)

old="""    mSavedLocation = location;
    mMyPosition = null;
    notifyLocationUpdated();
  }
"""
new="""    mSavedLocation = location;
    mMyPosition = null;
    notifyLocationUpdated();
    mNavShlyahOneShotGps = false;

    // NavShlyah UA: one real location fix, then local inertial tracking only.
    if (!(mLocationProvider instanceof DeadReckoningProvider))
    {
      Logger.i(TAG, "Initial location acquired; switching off GNSS and using local inertial tracking");
      final Location seed = new Location(mSavedLocation);
      mLocationProvider.stop();
      unsubscribeFromGnssStatusUpdates();
      mLocationProvider = new DeadReckoningProvider(mContext, this, seed);
      ((DeadReckoningProvider) mLocationProvider).setRoute(mNavShlyahRoute);
      mInterval = 1000;
      mLocationProvider.start(mInterval);
    }
  }
"""
if old not in hs:
    raise SystemExit("LocationHelper anchor not found")
helper.write_text(hs.replace(old,new,1),encoding="utf-8")

# Register bundled Kyiv map as local/resource-backed, preventing downloader UI/state.
cpp=root/"libs/platform/local_country_file_utils.cpp"
cs=cpp.read_text(encoding="utf-8")
anchor="  // Check for World and WorldCoasts in app bundle or in resources.\n"
idx=cs.find(anchor)
end=cs.find("\nvoid CleanupMapsDirectory(int64_t latestVersion)",idx)
close=cs.rfind("\n}",idx,end)
if idx<0 or end<0 or close<0:
    raise SystemExit("bundled map function not found")
block=r'''
  // NavShlyah UA: Kyiv Oblast map is bundled directly in APK assets.
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
cpp.write_text(cs[:close]+block+cs[close:],encoding="utf-8")
