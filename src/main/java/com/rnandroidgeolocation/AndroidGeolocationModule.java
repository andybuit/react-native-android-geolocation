package com.rnandroidgeolocation;

import android.os.Handler;

import com.baidu.location.BDLocation;
import com.baidu.location.BDLocationListener;
import com.baidu.location.LocationClientOption;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.SystemClock;
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.annotation.Nullable;

/**
 * Native module that exposes Geolocation to JS.
 */
public class AndroidGeolocationModule extends ReactContextBaseJavaModule implements LifecycleEventListener {

  private @Nullable String mWatchedProvider;
  private static final int RCT_DEFAULT_LOCATION_ACCURACY = 100;
  private BaiduLocationService baiduLocationService;
  private static final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd hh:mm:ssZ");

  private final BDLocationListener mLocationListener = new BDLocationListener() {

    @Override
    public void onReceiveLocation(BDLocation location) {
      // TODO Auto-generated method stub
      if (null != location && location.getLocType() != BDLocation.TypeServerError) {
        getReactApplicationContext().getJSModule(RCTDeviceEventEmitter.class)
                .emit("geolocationDidChange", locationToMap(location));
      }
    }
  };

  public AndroidGeolocationModule(ReactApplicationContext reactContext) {
    super(reactContext);
//    buildBaiduApiClient();
  }

  private synchronized void buildBaiduApiClient() {
//    this.getReactApplicationContext().getCurrentActivity().runOnUiThread(
//      new Thread(
//            new Runnable() {
//              @Override
//              public void run() {
//                Looper.prepare();
        if(baiduLocationService==null)
                baiduLocationService = new BaiduLocationService(getReactApplicationContext());
//                Looper.loop();
//              }
//            }
//      ).start();

  }

  @Override
  public String getName() {
    return "BDLocationObserver";
  }

  private  LocationClientOption fromReactMap(ReadableMap map) {
    // precision might be dropped on timeout (double -> int conversion), but that's OK
    int timeout =
            map.hasKey("timeout") ? (int) map.getInt("timeout") : Integer.MAX_VALUE;
    int maximumAge =
            map.hasKey("maximumAge") ?(int)  map.getInt("maximumAge") : Integer.MAX_VALUE;
    boolean highAccuracy =
            map.hasKey("enableHighAccuracy") && map.getBoolean("enableHighAccuracy");
    int distanceFilter =
            map.hasKey("distanceFilter") ? (int) map.getInt("distanceFilter") : RCT_DEFAULT_LOCATION_ACCURACY;

    LocationClientOption option = baiduLocationService.getDefaultLocationClientOption();

    option.setTimeOut(timeout);
    option.setScanSpan(maximumAge);
    option.setOpenAutoNotifyMode(maximumAge, distanceFilter, LocationClientOption.LOC_SENSITIVITY_HIGHT);
    if(highAccuracy) option.setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy);
    else option.setLocationMode(LocationClientOption.LocationMode.Battery_Saving);



    return option;
  }

  /**
   * Get the current position. This can return almost immediately if the location is cached or
   * request an update, which might take a while.
   *
   * @param options map containing optional arguments: timeout (millis), maximumAge (millis) and
   *        highAccuracy (boolean)
   */
  @ReactMethod
  public void getCurrentPosition(
      ReadableMap options,
      final Callback success,
      Callback error) {
    buildBaiduApiClient();
    LocationClientOption locationOptions = fromReactMap(options);

    baiduLocationService.setLocationOption(locationOptions);
//    baiduLocationService.start();
    BDLocation location = baiduLocationService.getLastKnownLocation();
      try {
          if (location != null &&
                  SystemClock.currentTimeMillis() - formatter.parse(location.getTime()+"+0800").getTime() < locationOptions.getScanSpan()) {
            success.invoke(locationToMap(location));
            return;
          }
      } catch (ParseException e) {
          e.printStackTrace();
      }
      new SingleUpdateRequest(this.baiduLocationService, locationOptions.getTimeOut(), success, error)
            .invoke();
  }

  /**
   * Start listening for location updates. These will be emitted via the
   * {@link RCTDeviceEventEmitter} as {@code geolocationDidChange} events.
   *
   * @param options map containing optional arguments: highAccuracy (boolean)
   */
  @ReactMethod
  public void startObserving(ReadableMap options) {
    buildBaiduApiClient();
    if(this.baiduLocationService.isStarted()) this.baiduLocationService.stop();
    LocationClientOption locationOptions = fromReactMap(options);

    baiduLocationService.setLocationOption(locationOptions);
    baiduLocationService.registerListener(mLocationListener);
    baiduLocationService.start();

  }

  /**
   * Stop listening for location updates.
   *
   * NB: this is not balanced with {@link #startObserving}: any number of calls to that method will
   * be canceled by just one call to this one.
   */
  @ReactMethod
  public void stopObserving() {
    baiduLocationService.unregisterListener(mLocationListener);
    baiduLocationService.stop();
  }

  private static WritableMap locationToMap(BDLocation location) {
    WritableMap map = Arguments.createMap();
    WritableMap coords = Arguments.createMap();
    coords.putDouble("latitude", location.getLatitude());
    coords.putDouble("longitude", location.getLongitude());
    coords.putDouble("altitude", location.getAltitude());
    coords.putDouble("accuracy", location.getRadius());
    coords.putDouble("heading", location.getDirection());
    coords.putDouble("speed", location.getSpeed());
    map.putMap("coords", coords);
    Date time = null;
    try {
      time = formatter.parse(location.getTime());
    } catch (ParseException e) {
      e.printStackTrace();
    }
    map.putDouble("timestamp", time!=null?time.getTime():0);
    return map;
  }

  private void emitError(String error) {
    getReactApplicationContext().getJSModule(RCTDeviceEventEmitter.class)
        .emit("geolocationError", error);
  }

  /**
   * Provides a clearer exception message than the default one.
   */
  private static void throwLocationPermissionMissing(SecurityException e) {
    throw new SecurityException(
      "Looks like the app doesn't have the permission to access location.\n" +
      "Add the following line to your app's AndroidManifest.xml:\n" +
      "<uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\" />", e);
  }

  private static class SingleUpdateRequest {

    private final Callback mSuccess;
    private final Callback mError;
    private final BaiduLocationService mbaiduLocationService;
    private final long mTimeout;
    private final Handler mHandler = new Handler();
    private final Runnable mTimeoutRunnable = new Runnable() {
      @Override
      public void run() {
        synchronized (SingleUpdateRequest.this) {
          if (!mTriggered) {
            mError.invoke("Location request timed out");
            mbaiduLocationService.unregisterListener(mLocationListener);
            mTriggered = true;
          }
        }
      }
    };
    private final BDLocationListener mLocationListener = new BDLocationListener() {
        @Override
        public void onReceiveLocation(BDLocation bdLocation) {
            synchronized (SingleUpdateRequest.this) {
                if (!mTriggered) {
                    mSuccess.invoke(locationToMap(bdLocation));
                    mHandler.removeCallbacks(mTimeoutRunnable);
                    mTriggered = true;
                    mbaiduLocationService.unregisterListener(mLocationListener);
                    mbaiduLocationService.stop();
                }
            }
        }

    };
    private boolean mTriggered;

    private SingleUpdateRequest(
            BaiduLocationService baiduLocationService,
        long timeout,
        Callback success,
        Callback error) {
        mbaiduLocationService = baiduLocationService;
      mTimeout = timeout;
      mSuccess = success;
      mError = error;
    }

    public void invoke() {
      mbaiduLocationService.registerListener(mLocationListener);
      mbaiduLocationService.start();
      mHandler.postDelayed(mTimeoutRunnable, mTimeout);
    }
  }

  @Override
  public void onHostResume() {

    baiduLocationService.start();
  }

  @Override
  public void onHostPause() {

    baiduLocationService.stop();
  }

  @Override
  public void onHostDestroy() {

    baiduLocationService.stop();
    baiduLocationService.unregisterListener(mLocationListener);
  }
}
