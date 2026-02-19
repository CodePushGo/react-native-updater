/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.codepushgo.reactnativeupdater;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.jakewharton.processphoenix.ProcessPhoenix;
import io.github.g00fy2.versioncompare.Version;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public class ReactNativeUpdaterModule
  extends ReactContextBaseJavaModule
  implements LifecycleEventListener, ActivityEventListener {

  private static final String UPDATE_URL_DEFAULT =
    "https://plugin.capgo.app/updates";
  private static final String STATS_URL_DEFAULT =
    "https://plugin.capgo.app/stats";
  private static final String CHANNEL_URL_DEFAULT =
    "https://plugin.capgo.app/channel_self";
  private static final String DELAY_CONDITION_PREFERENCES =
    "ReactNativeUpdaterDelayConditions";
  private static final String CUSTOM_ID_PREF_KEY =
    "ReactNativeUpdater.customId";
  private static final String UPDATE_URL_PREF_KEY =
    "ReactNativeUpdater.updateUrl";
  private static final String STATS_URL_PREF_KEY =
    "ReactNativeUpdater.statsUrl";
  private static final String CHANNEL_URL_PREF_KEY =
    "ReactNativeUpdater.channelUrl";
  private static final String DEFAULT_CHANNEL_PREF_KEY =
    "ReactNativeUpdater.defaultChannel";
  private static final String LAST_FAILED_BUNDLE_PREF_KEY =
    "ReactNativeUpdater.lastFailedBundle";

  private static final String PLUGIN_VERSION = "7.0.50";
  private static final int APP_UPDATE_REQUEST_CODE = 9001;

  // AppUpdateAvailability enum values matching TypeScript definitions
  private static final int UPDATE_AVAILABILITY_UNKNOWN = 0;
  private static final int UPDATE_AVAILABILITY_NOT_AVAILABLE = 1;
  private static final int UPDATE_AVAILABILITY_AVAILABLE = 2;
  private static final int UPDATE_AVAILABILITY_IN_PROGRESS = 3;

  // AppUpdateResultCode enum values matching TypeScript definitions
  private static final int RESULT_OK = 0;
  private static final int RESULT_CANCELED = 1;
  private static final int RESULT_FAILED = 2;
  private static final int RESULT_NOT_AVAILABLE = 3;
  private static final int RESULT_NOT_ALLOWED = 4;
  private static final int RESULT_INFO_MISSING = 5;

  private final ReactApplicationContext reactContext;
  protected ReactNativeUpdaterCore implementation;

  private SharedPreferences prefs;
  private SharedPreferences.Editor editor;

  private int appReadyTimeout = 10000;
  private int periodCheckDelay = 0;
  private boolean autoDeleteFailed = true;
  private boolean autoDeletePrevious = true;
  private boolean autoUpdate = false;
  private String updateUrl = UPDATE_URL_DEFAULT;
  private String statsUrl = STATS_URL_DEFAULT;
  private String channelUrl = CHANNEL_URL_DEFAULT;
  private String configDefaultChannel = "";
  private boolean allowModifyAppId = false;
  private boolean allowManualBundleError = false;
  private boolean persistCustomId = false;
  private boolean persistModifyUrl = false;
  private boolean allowSetDefaultChannel = true;
  private boolean shakeMenuEnabled = false;
  private boolean shakeChannelSelectorEnabled = false;

  private Version currentVersionNative = new Version("0.0.0");

  private Thread backgroundTask;
  private Thread appReadyCheck;
  private boolean taskRunning = false;

  private int lastNotifiedStatPercent = 0;

  private boolean initialized = false;

  @Nullable
  private Promise pendingAppUpdatePromise;

  @Nullable
  private AppUpdateManager appUpdateManager;

  @Nullable
  private AppUpdateInfo cachedAppUpdateInfo;

  @Nullable
  private InstallStateUpdatedListener installStateUpdatedListener;

  ReactNativeUpdaterModule(ReactApplicationContext context) {
    super(context);
    this.reactContext = context;
    this.implementation = new ReactNativeUpdaterCore(context);
    setupPreferencesIfNeeded();
    context.addLifecycleEventListener(this);
    context.addActivityEventListener(this);
  }

  @Override
  public String getName() {
    return "ReactNativeUpdater";
  }

  @Override
  public void onHostResume() {
    if (!initialized) {
      return;
    }
    if (backgroundTask != null && taskRunning) {
      backgroundTask.interrupt();
    }
    appMovedToForegroundInternal();
  }

  @Override
  public void onHostPause() {
    if (!initialized) {
      return;
    }
    appMovedToBackgroundInternal();
  }

  @Override
  public void onHostDestroy() {
    if (!initialized) {
      return;
    }
    if (appReadyCheck != null) {
      appReadyCheck.interrupt();
      appReadyCheck = null;
    }
    if (installStateUpdatedListener != null && appUpdateManager != null) {
      try {
        appUpdateManager.unregisterListener(installStateUpdatedListener);
      } catch (Exception e) {
        Log.w(ReactNativeUpdaterCore.TAG, "Failed to unregister listener", e);
      }
      installStateUpdatedListener = null;
    }
    _checkCancelDelay(true);
  }

  @Override
  public void onNewIntent(Intent intent) {
    // no-op
  }

  @Override
  public void onActivityResult(
    Activity activity,
    int requestCode,
    int resultCode,
    Intent data
  ) {
    if (
      requestCode != APP_UPDATE_REQUEST_CODE || pendingAppUpdatePromise == null
    ) {
      return;
    }

    WritableMap result = Arguments.createMap();
    if (resultCode == Activity.RESULT_OK) {
      result.putInt("code", RESULT_OK);
    } else if (resultCode == Activity.RESULT_CANCELED) {
      result.putInt("code", RESULT_CANCELED);
    } else {
      result.putInt("code", RESULT_FAILED);
    }

    Promise promise = pendingAppUpdatePromise;
    pendingAppUpdatePromise = null;
    promise.resolve(result);
  }

  @ReactMethod
  public void initialize(ReadableMap config, Promise promise) {
    try {
      setupPreferencesIfNeeded();

      allowModifyAppId = config.hasKey("allowModifyAppId") &&
        !config.isNull("allowModifyAppId")
        ? config.getBoolean("allowModifyAppId")
        : false;
      allowManualBundleError = config.hasKey("allowManualBundleError") &&
        !config.isNull("allowManualBundleError")
        ? config.getBoolean("allowManualBundleError")
        : false;
      persistCustomId = config.hasKey("persistCustomId") &&
        !config.isNull("persistCustomId")
        ? config.getBoolean("persistCustomId")
        : false;
      persistModifyUrl = config.hasKey("persistModifyUrl") &&
        !config.isNull("persistModifyUrl")
        ? config.getBoolean("persistModifyUrl")
        : false;
      allowSetDefaultChannel = config.hasKey("allowSetDefaultChannel") &&
        !config.isNull("allowSetDefaultChannel")
        ? config.getBoolean("allowSetDefaultChannel")
        : true;
      shakeMenuEnabled = config.hasKey("shakeMenu") &&
        !config.isNull("shakeMenu")
        ? config.getBoolean("shakeMenu")
        : false;
      shakeChannelSelectorEnabled = config.hasKey(
          "allowShakeChannelSelector"
        ) &&
        !config.isNull("allowShakeChannelSelector")
        ? config.getBoolean("allowShakeChannelSelector")
        : false;

      updateUrl = config.hasKey("updateUrl") && !config.isNull("updateUrl")
        ? config.getString("updateUrl")
        : UPDATE_URL_DEFAULT;
      statsUrl = config.hasKey("statsUrl") && !config.isNull("statsUrl")
        ? config.getString("statsUrl")
        : STATS_URL_DEFAULT;
      channelUrl = config.hasKey("channelUrl") && !config.isNull("channelUrl")
        ? config.getString("channelUrl")
        : CHANNEL_URL_DEFAULT;
      configDefaultChannel = config.hasKey("defaultChannel") &&
        !config.isNull("defaultChannel")
        ? config.getString("defaultChannel")
        : "";

      if (persistModifyUrl) {
        updateUrl = prefs.getString(UPDATE_URL_PREF_KEY, updateUrl);
        statsUrl = prefs.getString(STATS_URL_PREF_KEY, statsUrl);
        channelUrl = prefs.getString(CHANNEL_URL_PREF_KEY, channelUrl);
        implementation.defaultChannel = prefs.getString(
          DEFAULT_CHANNEL_PREF_KEY,
          configDefaultChannel
        );
      } else {
        implementation.defaultChannel = configDefaultChannel;
      }

      autoUpdate = config.hasKey("autoUpdate") && !config.isNull("autoUpdate")
        ? config.getBoolean("autoUpdate")
        : false;
      autoDeleteFailed = config.hasKey("autoDeleteFailed") &&
        !config.isNull("autoDeleteFailed")
        ? config.getBoolean("autoDeleteFailed")
        : true;
      autoDeletePrevious = config.hasKey("autoDeletePrevious") &&
        !config.isNull("autoDeletePrevious")
        ? config.getBoolean("autoDeletePrevious")
        : true;
      appReadyTimeout = config.hasKey("appReadyTimeout") &&
        !config.isNull("appReadyTimeout")
        ? config.getInt("appReadyTimeout")
        : 10000;
      periodCheckDelay = config.hasKey("periodCheckDelay") &&
        !config.isNull("periodCheckDelay")
        ? config.getInt("periodCheckDelay")
        : 0;
      if (periodCheckDelay > 0 && periodCheckDelay < 600) {
        periodCheckDelay = 600;
      }

      implementation.appId = config.hasKey("appId") && !config.isNull("appId")
        ? config.getString("appId")
        : reactContext.getPackageName();
      implementation.publicKey = config.hasKey("publicKey") &&
        !config.isNull("publicKey")
        ? config.getString("publicKey")
        : "";
      implementation.statsUrl = statsUrl;
      implementation.channelUrl = channelUrl;
      implementation.defaultChannel = implementation.defaultChannel == null
        ? configDefaultChannel
        : implementation.defaultChannel;
      implementation.directUpdate = config.hasKey("directUpdate") &&
        !config.isNull("directUpdate")
        ? config.getBoolean("directUpdate")
        : false;
      implementation.timeout = config.hasKey("responseTimeout") &&
        !config.isNull("responseTimeout")
        ? config.getInt("responseTimeout") * 1000
        : 20000;
      implementation.PLUGIN_VERSION = PLUGIN_VERSION;

      if (persistCustomId) {
        implementation.customId = prefs.getString(CUSTOM_ID_PREF_KEY, "");
      }

      if (config.hasKey("version") && !config.isNull("version")) {
        implementation.versionBuild = config.getString("version");
      } else {
        setVersionInfoFromPackage();
      }

      if (
        implementation.versionBuild == null ||
        implementation.versionBuild.isEmpty()
      ) {
        implementation.versionBuild = "0.0.0";
      }

      try {
        currentVersionNative = new Version(implementation.versionBuild);
      } catch (Exception ignore) {
        currentVersionNative = new Version("0.0.0");
      }

      implementation.versionOs = Build.VERSION.RELEASE;
      implementation.autoReset();

      initialized = true;
      appMovedToForegroundInternal();
      promise.resolve("Plugin initialized");
    } catch (Exception e) {
      Log.e(ReactNativeUpdaterCore.TAG, "Initialization failed", e);
      promise.reject(
        "INIT_ERROR",
        "Initialization failed: " + e.getMessage(),
        e
      );
    }
  }

  @ReactMethod
  public void setUpdateUrl(String url, Promise promise) {
    if (!ensureInitialized(promise, "setUpdateUrl")) {
      return;
    }
    if (url == null) {
      promise.reject("SET_UPDATE_URL_ERROR", "setUpdateUrl called without url");
      return;
    }
    updateUrl = url;
    if (persistModifyUrl) {
      editor.putString(UPDATE_URL_PREF_KEY, url);
      editor.apply();
    }
    promise.resolve(null);
  }

  @ReactMethod
  public void setStatsUrl(String url, Promise promise) {
    if (!ensureInitialized(promise, "setStatsUrl")) {
      return;
    }
    if (url == null) {
      promise.reject("SET_STATS_URL_ERROR", "setStatsUrl called without url");
      return;
    }
    statsUrl = url;
    implementation.statsUrl = url;
    if (persistModifyUrl) {
      editor.putString(STATS_URL_PREF_KEY, url);
      editor.apply();
    }
    promise.resolve(null);
  }

  @ReactMethod
  public void setChannelUrl(String url, Promise promise) {
    if (!ensureInitialized(promise, "setChannelUrl")) {
      return;
    }
    if (url == null) {
      promise.reject(
        "SET_CHANNEL_URL_ERROR",
        "setChannelUrl called without url"
      );
      return;
    }
    channelUrl = url;
    implementation.channelUrl = url;
    if (persistModifyUrl) {
      editor.putString(CHANNEL_URL_PREF_KEY, url);
      editor.apply();
    }
    promise.resolve(null);
  }

  @ReactMethod
  public void getBuiltinVersion(Promise promise) {
    if (!ensureInitialized(promise, "getBuiltinVersion")) {
      return;
    }
    promise.resolve(implementation.versionBuild);
  }

  @ReactMethod
  public void getDeviceId(Promise promise) {
    if (!ensureInitialized(promise, "getDeviceId")) {
      return;
    }
    promise.resolve(implementation.deviceID);
  }

  @ReactMethod
  public void setCustomId(String customId, Promise promise) {
    if (!ensureInitialized(promise, "setCustomId")) {
      return;
    }
    if (customId == null) {
      promise.reject(
        "SET_CUSTOM_ID_ERROR",
        "setCustomId called without customId"
      );
      return;
    }
    implementation.customId = customId;
    if (persistCustomId) {
      if (customId.isEmpty()) {
        editor.remove(CUSTOM_ID_PREF_KEY);
      } else {
        editor.putString(CUSTOM_ID_PREF_KEY, customId);
      }
      editor.apply();
    }
    promise.resolve(null);
  }

  @ReactMethod
  public void getPluginVersion(Promise promise) {
    promise.resolve(PLUGIN_VERSION);
  }

  @ReactMethod
  public void unsetChannel(boolean triggerAutoUpdate, Promise promise) {
    if (!ensureInitialized(promise, "unsetChannel")) {
      return;
    }
    try {
      implementation.unsetChannel(
        editor,
        DEFAULT_CHANNEL_PREF_KEY,
        configDefaultChannel,
        result -> {
          if (result.has("error")) {
            promise.reject(
              result.optString("error", "UNSET_CHANNEL_FAILED"),
              result.optString("message", "Failed to unset channel")
            );
          } else {
            if (_isAutoUpdateEnabled() && triggerAutoUpdate) {
              backgroundDownload();
            }
            promise.resolve(convertJsonObjectToWritableMap(result));
          }
        }
      );
    } catch (Exception e) {
      promise.reject("UNSET_CHANNEL_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void setChannel(
    String channel,
    boolean triggerAutoUpdate,
    Promise promise
  ) {
    if (!ensureInitialized(promise, "setChannel")) {
      return;
    }
    if (channel == null) {
      promise.reject("SET_CHANNEL_ERROR", "setChannel called without channel");
      return;
    }
    try {
      implementation.setChannel(
        channel,
        editor,
        DEFAULT_CHANNEL_PREF_KEY,
        configDefaultChannel,
        allowSetDefaultChannel,
        result -> {
          if (result.has("error")) {
            String errorCode = result.optString("error", "SET_CHANNEL_FAILED");
            String errorMessage = result.optString(
              "message",
              "Failed to set channel"
            );
            if (
              errorCode.contains("cannot_update_via_private_channel") ||
              errorCode.contains("channel_self_set_not_allowed")
            ) {
              WritableMap payload = Arguments.createMap();
              payload.putString("channel", channel);
              payload.putString("message", errorMessage);
              sendEvent("channelPrivate", payload);
            }
            promise.reject(errorCode, errorMessage);
          } else {
            if (_isAutoUpdateEnabled() && triggerAutoUpdate) {
              backgroundDownload();
            }
            promise.resolve(convertJsonObjectToWritableMap(result));
          }
        }
      );
    } catch (Exception e) {
      promise.reject("SET_CHANNEL_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void getChannel(Promise promise) {
    if (!ensureInitialized(promise, "getChannel")) {
      return;
    }
    try {
      implementation.getChannel(result -> {
        if (result.has("error")) {
          promise.reject(
            result.optString("error", "GET_CHANNEL_FAILED"),
            result.optString("message", "Failed to get channel")
          );
        } else {
          promise.resolve(convertJsonObjectToWritableMap(result));
        }
      });
    } catch (Exception e) {
      promise.reject("GET_CHANNEL_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void listChannels(Promise promise) {
    if (!ensureInitialized(promise, "listChannels")) {
      return;
    }
    try {
      implementation.listChannels(result -> {
        if (result.has("error")) {
          promise.reject(
            result.optString("error", "LIST_CHANNELS_FAILED"),
            result.optString("message", "Failed to list channels")
          );
        } else {
          promise.resolve(convertJsonObjectToWritableMap(result));
        }
      });
    } catch (Exception e) {
      promise.reject("LIST_CHANNELS_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void download(ReadableMap options, Promise promise) {
    if (!ensureInitialized(promise, "download")) {
      return;
    }
    try {
      String url = options.hasKey("url") && !options.isNull("url")
        ? options.getString("url")
        : null;
      String version = options.hasKey("version") && !options.isNull("version")
        ? options.getString("version")
        : null;
      String sessionKey = options.hasKey("sessionKey") &&
        !options.isNull("sessionKey")
        ? options.getString("sessionKey")
        : "";
      String checksum = options.hasKey("checksum") &&
        !options.isNull("checksum")
        ? options.getString("checksum")
        : "";

      if (url == null || version == null) {
        promise.reject(
          "DOWNLOAD_SETUP_ERROR",
          "Download called without url or version"
        );
        return;
      }
      if (DownloadWorkerManager.isVersionDownloading(version)) {
        promise.reject(
          "DOWNLOAD_SETUP_ERROR",
          "Download already in progress for version: " + version
        );
        return;
      }

      String downloadId = implementation.downloadBackground(
        url,
        version,
        sessionKey,
        checksum,
        null
      );
      if (downloadId == null) {
        promise.reject(
          "DOWNLOAD_SETUP_ERROR",
          "Download already in progress for version: " + version
        );
        return;
      }

      observeDownload(downloadId, version);
      promise.resolve(
        convertBundleInfoToWritableMap(implementation.getBundleInfo(downloadId))
      );
    } catch (Exception e) {
      promise.reject("DOWNLOAD_SETUP_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void reload(Promise promise) {
    if (!ensureInitialized(promise, "reload")) {
      return;
    }
    try {
      triggerReload("js_request");
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("RELOAD_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void next(String id, Promise promise) {
    if (!ensureInitialized(promise, "next")) {
      return;
    }
    if (id == null) {
      promise.reject("SET_NEXT_FAILED", "Next called without id");
      return;
    }
    try {
      boolean success = implementation.setNextBundle(id);
      if (!success) {
        promise.reject(
          "SET_NEXT_FAILED",
          "Set next id failed. Bundle " + id + " does not exist."
        );
      } else {
        promise.resolve(
          convertBundleInfoToWritableMap(implementation.getBundleInfo(id))
        );
      }
    } catch (Exception e) {
      promise.reject("SET_NEXT_ERROR", "Could not set next id: " + id, e);
    }
  }

  @ReactMethod
  public void set(String id, Promise promise) {
    if (!ensureInitialized(promise, "set")) {
      return;
    }
    if (id == null) {
      promise.reject("SET_FAILED", "Set called without id");
      return;
    }
    try {
      boolean success = implementation.set(id);
      if (!success) {
        promise.reject(
          "SET_FAILED",
          "Update failed, id " + id + " does not exist or is invalid."
        );
      } else {
        triggerReload("set");
        promise.resolve(null);
      }
    } catch (Exception e) {
      promise.reject("SET_ERROR", "Could not set id " + id, e);
    }
  }

  @ReactMethod
  public void delete(String id, Promise promise) {
    if (!ensureInitialized(promise, "delete")) {
      return;
    }
    if (id == null) {
      promise.reject("DELETE_FAILED", "missing id");
      return;
    }
    try {
      boolean deleted = implementation.delete(id);
      if (deleted) {
        promise.resolve(null);
      } else {
        promise.reject(
          "DELETE_FAILED",
          "Delete failed, id " + id + " does not exist or cannot be deleted"
        );
      }
    } catch (Exception e) {
      promise.reject("DELETE_ERROR", "Could not delete id " + id, e);
    }
  }

  @ReactMethod
  public void setBundleError(String id, Promise promise) {
    if (!ensureInitialized(promise, "setBundleError")) {
      return;
    }
    if (!allowManualBundleError) {
      promise.reject(
        "SET_BUNDLE_ERROR_FORBIDDEN",
        "setBundleError not allowed. Set allowManualBundleError to true in config."
      );
      return;
    }
    if (id == null) {
      promise.reject(
        "SET_BUNDLE_ERROR_INVALID",
        "setBundleError called without id"
      );
      return;
    }

    try {
      BundleInfo bundle = implementation.getBundleInfo(id);
      if (bundle == null || bundle.isUnknown()) {
        promise.reject(
          "SET_BUNDLE_ERROR_UNKNOWN",
          "Bundle " + id + " does not exist"
        );
        return;
      }
      if (bundle.isBuiltin()) {
        promise.reject(
          "SET_BUNDLE_ERROR_BUILTIN",
          "Cannot set builtin bundle to error state"
        );
        return;
      }

      implementation.setError(bundle);
      WritableMap ret = Arguments.createMap();
      ret.putMap(
        "bundle",
        convertBundleInfoToWritableMap(implementation.getBundleInfo(id))
      );
      promise.resolve(ret);
    } catch (Exception e) {
      promise.reject(
        "SET_BUNDLE_ERROR_FAILED",
        "Could not set bundle error for id " + id,
        e
      );
    }
  }

  @ReactMethod
  public void list(Promise promise) {
    if (!ensureInitialized(promise, "list")) {
      return;
    }
    try {
      List<BundleInfo> bundles = implementation.list(false);
      WritableArray values = Arguments.createArray();
      for (BundleInfo bundle : bundles) {
        values.pushMap(convertBundleInfoToWritableMap(bundle));
      }
      WritableMap ret = Arguments.createMap();
      ret.putArray("bundles", values);
      promise.resolve(ret);
    } catch (Exception e) {
      promise.reject("LIST_ERROR", "Could not list bundles", e);
    }
  }

  @ReactMethod
  public void getLatest(String channel, Promise promise) {
    if (!ensureInitialized(promise, "getLatest")) {
      return;
    }
    try {
      implementation.getLatest(updateUrl, channel, result -> {
        if (result.has("error")) {
          promise.reject(
            result.optString("error", "GET_LATEST_FAILED"),
            result.optString("message", "Failed to get latest version")
          );
        } else {
          promise.resolve(convertJsonObjectToWritableMap(result));
        }
      });
    } catch (Exception e) {
      promise.reject("GET_LATEST_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void reset(Boolean toLastSuccessful, Promise promise) {
    if (!ensureInitialized(promise, "reset")) {
      return;
    }
    try {
      boolean success = _reset(toLastSuccessful != null && toLastSuccessful);
      if (!success) {
        promise.reject("RESET_ERROR", "Reset failed");
        return;
      }
      triggerReload("reset_requested");
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("RESET_ERROR", "Reset failed", e);
    }
  }

  @ReactMethod
  public void current(Promise promise) {
    if (!ensureInitialized(promise, "current")) {
      return;
    }
    try {
      WritableMap ret = Arguments.createMap();
      BundleInfo bundle = implementation.getCurrentBundle();
      ret.putMap("bundle", convertBundleInfoToWritableMap(bundle));
      ret.putString("native", currentVersionNative.getOriginalString());
      promise.resolve(ret);
    } catch (Exception e) {
      promise.reject("CURRENT_ERROR", "Could not get current bundle", e);
    }
  }

  @ReactMethod
  public void getNextBundle(Promise promise) {
    if (!ensureInitialized(promise, "getNextBundle")) {
      return;
    }
    try {
      BundleInfo bundle = implementation.getNextBundle();
      if (bundle == null || bundle.isUnknown()) {
        promise.resolve(null);
      } else {
        promise.resolve(convertBundleInfoToWritableMap(bundle));
      }
    } catch (Exception e) {
      promise.reject("GET_NEXT_ERROR", "Could not get next bundle", e);
    }
  }

  @ReactMethod
  public void getFailedUpdate(Promise promise) {
    if (!ensureInitialized(promise, "getFailedUpdate")) {
      return;
    }
    try {
      BundleInfo bundle = readLastFailedBundle();
      if (bundle == null || bundle.isUnknown()) {
        promise.resolve(null);
        return;
      }

      persistLastFailedBundle(null);
      WritableMap ret = Arguments.createMap();
      ret.putMap("bundle", convertBundleInfoToWritableMap(bundle));
      promise.resolve(ret);
    } catch (Exception e) {
      promise.reject(
        "GET_FAILED_UPDATE_ERROR",
        "Could not get failed update",
        e
      );
    }
  }

  @ReactMethod
  public void setShakeMenu(boolean enabled, Promise promise) {
    if (!ensureInitialized(promise, "setShakeMenu")) {
      return;
    }
    shakeMenuEnabled = enabled;
    promise.resolve(null);
  }

  @ReactMethod
  public void isShakeMenuEnabled(Promise promise) {
    promise.resolve(shakeMenuEnabled);
  }

  @ReactMethod
  public void setShakeChannelSelector(boolean enabled, Promise promise) {
    if (!ensureInitialized(promise, "setShakeChannelSelector")) {
      return;
    }
    shakeChannelSelectorEnabled = enabled;
    promise.resolve(null);
  }

  @ReactMethod
  public void isShakeChannelSelectorEnabled(Promise promise) {
    promise.resolve(shakeChannelSelectorEnabled);
  }

  @ReactMethod
  public void getAppId(Promise promise) {
    if (!ensureInitialized(promise, "getAppId")) {
      return;
    }
    promise.resolve(implementation.appId);
  }

  @ReactMethod
  public void setAppId(String appId, Promise promise) {
    if (!ensureInitialized(promise, "setAppId")) {
      return;
    }
    if (!allowModifyAppId) {
      promise.reject(
        "SET_APP_ID_FORBIDDEN",
        "setAppId not allowed. Set allowModifyAppId in config to true."
      );
      return;
    }
    if (appId == null || appId.isEmpty()) {
      promise.reject("SET_APP_ID_INVALID", "setAppId called without appId");
      return;
    }
    implementation.appId = appId;
    promise.resolve(null);
  }

  @ReactMethod
  public void notifyAppReady(Promise promise) {
    if (!ensureInitialized(promise, "notifyAppReady")) {
      return;
    }
    try {
      implementation.notifyAppReady();
      if (appReadyCheck != null) {
        appReadyCheck.interrupt();
        appReadyCheck = null;
      }
      BundleInfo current = implementation.getCurrentBundle();
      sendReadyToJs(current, "ready");
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("NOTIFY_ERROR", "Failed to commit app ready state.", e);
    }
  }

  @ReactMethod
  public void isAutoUpdateEnabled(Promise promise) {
    promise.resolve(_isAutoUpdateEnabled());
  }

  @ReactMethod
  public void isAutoUpdateAvailable(Promise promise) {
    promise.resolve(!_isEmpty(updateUrl));
  }

  @ReactMethod
  public void checkForUpdate(Promise promise) {
    if (!ensureInitialized(promise, "checkForUpdate")) {
      return;
    }
    if (_isEmpty(updateUrl)) {
      promise.reject("CHECK_UPDATE_ERROR", "updateUrl is empty");
      return;
    }
    backgroundDownload();
    promise.resolve("Check for update started");
  }

  @ReactMethod
  public void getAppUpdateInfo(String country, Promise promise) {
    if (!ensureInitialized(promise, "getAppUpdateInfo")) {
      return;
    }
    try {
      AppUpdateManager manager = getAppUpdateManager();
      Task<AppUpdateInfo> appUpdateInfoTask = manager.getAppUpdateInfo();

      appUpdateInfoTask
        .addOnSuccessListener(appUpdateInfo -> {
          cachedAppUpdateInfo = appUpdateInfo;
          WritableMap result = Arguments.createMap();

          try {
            PackageInfo pInfo = reactContext
              .getPackageManager()
              .getPackageInfo(reactContext.getPackageName(), 0);
            result.putString("currentVersionName", pInfo.versionName);
            result.putString(
              "currentVersionCode",
              String.valueOf(pInfo.versionCode)
            );
          } catch (PackageManager.NameNotFoundException e) {
            result.putString("currentVersionName", "0.0.0");
            result.putString("currentVersionCode", "0");
          }

          result.putInt(
            "updateAvailability",
            mapUpdateAvailability(appUpdateInfo.updateAvailability())
          );

          if (
            appUpdateInfo.updateAvailability() ==
            UpdateAvailability.UPDATE_AVAILABLE
          ) {
            result.putString(
              "availableVersionCode",
              String.valueOf(appUpdateInfo.availableVersionCode())
            );
            result.putString(
              "availableVersionName",
              String.valueOf(appUpdateInfo.availableVersionCode())
            );
            result.putInt("updatePriority", appUpdateInfo.updatePriority());
            result.putBoolean(
              "immediateUpdateAllowed",
              appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            );
            result.putBoolean(
              "flexibleUpdateAllowed",
              appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            );
            Integer stalenessDays = appUpdateInfo.clientVersionStalenessDays();
            if (stalenessDays != null) {
              result.putInt("clientVersionStalenessDays", stalenessDays);
            }
          } else {
            result.putBoolean("immediateUpdateAllowed", false);
            result.putBoolean("flexibleUpdateAllowed", false);
          }

          result.putInt("installStatus", appUpdateInfo.installStatus());
          promise.resolve(result);
        })
        .addOnFailureListener(e ->
          promise.reject(
            "GET_APP_UPDATE_INFO_ERROR",
            "Failed to get app update info: " + e.getMessage(),
            e
          )
        );
    } catch (Exception e) {
      promise.reject(
        "GET_APP_UPDATE_INFO_ERROR",
        "Error getting app update info: " + e.getMessage(),
        e
      );
    }
  }

  @ReactMethod
  public void openAppStore(ReadableMap options, Promise promise) {
    if (!ensureInitialized(promise, "openAppStore")) {
      return;
    }

    String packageName = reactContext.getPackageName();
    if (
      options != null &&
      options.hasKey("packageName") &&
      !options.isNull("packageName")
    ) {
      packageName = options.getString("packageName");
    }

    try {
      Intent intent = new Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=" + packageName)
      );
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      reactContext.startActivity(intent);
      promise.resolve(null);
    } catch (android.content.ActivityNotFoundException e) {
      try {
        Intent intent = new Intent(
          Intent.ACTION_VIEW,
          Uri.parse(
            "https://play.google.com/store/apps/details?id=" + packageName
          )
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        reactContext.startActivity(intent);
        promise.resolve(null);
      } catch (Exception ex) {
        promise.reject(
          "OPEN_APP_STORE_ERROR",
          "Failed to open Play Store: " + ex.getMessage(),
          ex
        );
      }
    }
  }

  @ReactMethod
  public void performImmediateUpdate(Promise promise) {
    if (!ensureInitialized(promise, "performImmediateUpdate")) {
      return;
    }
    if (cachedAppUpdateInfo == null) {
      WritableMap result = Arguments.createMap();
      result.putInt("code", RESULT_INFO_MISSING);
      promise.resolve(result);
      return;
    }
    if (
      cachedAppUpdateInfo.updateAvailability() !=
      UpdateAvailability.UPDATE_AVAILABLE
    ) {
      WritableMap result = Arguments.createMap();
      result.putInt("code", RESULT_NOT_AVAILABLE);
      promise.resolve(result);
      return;
    }
    if (!cachedAppUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
      WritableMap result = Arguments.createMap();
      result.putInt("code", RESULT_NOT_ALLOWED);
      promise.resolve(result);
      return;
    }

    Activity activity = getCurrentActivity();
    if (activity == null) {
      promise.reject("APP_UPDATE_ACTIVITY_ERROR", "Activity not available");
      return;
    }

    try {
      pendingAppUpdatePromise = promise;
      AppUpdateManager manager = getAppUpdateManager();
      manager.startUpdateFlowForResult(
        cachedAppUpdateInfo,
        activity,
        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
        APP_UPDATE_REQUEST_CODE
      );
    } catch (Exception e) {
      pendingAppUpdatePromise = null;
      WritableMap result = Arguments.createMap();
      result.putInt("code", RESULT_FAILED);
      promise.resolve(result);
    }
  }

  @ReactMethod
  public void startFlexibleUpdate(Promise promise) {
    if (!ensureInitialized(promise, "startFlexibleUpdate")) {
      return;
    }
    if (cachedAppUpdateInfo == null) {
      WritableMap result = Arguments.createMap();
      result.putInt("code", RESULT_INFO_MISSING);
      promise.resolve(result);
      return;
    }
    if (
      cachedAppUpdateInfo.updateAvailability() !=
      UpdateAvailability.UPDATE_AVAILABLE
    ) {
      WritableMap result = Arguments.createMap();
      result.putInt("code", RESULT_NOT_AVAILABLE);
      promise.resolve(result);
      return;
    }
    if (!cachedAppUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
      WritableMap result = Arguments.createMap();
      result.putInt("code", RESULT_NOT_ALLOWED);
      promise.resolve(result);
      return;
    }

    Activity activity = getCurrentActivity();
    if (activity == null) {
      promise.reject("APP_UPDATE_ACTIVITY_ERROR", "Activity not available");
      return;
    }

    try {
      AppUpdateManager manager = getAppUpdateManager();
      if (installStateUpdatedListener != null) {
        manager.unregisterListener(installStateUpdatedListener);
      }
      installStateUpdatedListener = state -> {
        WritableMap payload = Arguments.createMap();
        payload.putInt("installStatus", state.installStatus());
        if (state.installStatus() == InstallStatus.DOWNLOADING) {
          payload.putDouble(
            "bytesDownloaded",
            (double) state.bytesDownloaded()
          );
          payload.putDouble(
            "totalBytesToDownload",
            (double) state.totalBytesToDownload()
          );
        }
        sendEvent("onFlexibleUpdateStateChange", payload);
      };
      manager.registerListener(installStateUpdatedListener);

      pendingAppUpdatePromise = promise;
      manager.startUpdateFlowForResult(
        cachedAppUpdateInfo,
        activity,
        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
        APP_UPDATE_REQUEST_CODE
      );
    } catch (Exception e) {
      pendingAppUpdatePromise = null;
      WritableMap result = Arguments.createMap();
      result.putInt("code", RESULT_FAILED);
      promise.resolve(result);
    }
  }

  @ReactMethod
  public void completeFlexibleUpdate(Promise promise) {
    if (!ensureInitialized(promise, "completeFlexibleUpdate")) {
      return;
    }
    try {
      AppUpdateManager manager = getAppUpdateManager();
      manager
        .completeUpdate()
        .addOnSuccessListener(aVoid -> promise.resolve(null))
        .addOnFailureListener(e ->
          promise.reject(
            "COMPLETE_FLEXIBLE_UPDATE_ERROR",
            "Failed to complete flexible update: " + e.getMessage(),
            e
          )
        );
    } catch (Exception e) {
      promise.reject(
        "COMPLETE_FLEXIBLE_UPDATE_ERROR",
        "Error completing flexible update: " + e.getMessage(),
        e
      );
    }
  }

  @ReactMethod
  public void compareVersions(
    String versionA,
    String versionB,
    Promise promise
  ) {
    if (!ensureInitialized(promise, "compareVersions")) {
      return;
    }
    try {
      int result = implementation.compareVersions(versionA, versionB);
      promise.resolve(result);
    } catch (Exception e) {
      promise.reject("COMPARE_VERSION_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void setMultiDelay(ReadableMap options, Promise promise) {
    if (!ensureInitialized(promise, "setMultiDelay")) {
      return;
    }
    if (
      options == null ||
      !options.hasKey("delayConditions") ||
      options.isNull("delayConditions")
    ) {
      promise.reject(
        "SET_DELAY_ERROR",
        "setMultiDelay called without delayConditions"
      );
      return;
    }

    try {
      ReadableArray delayConditions = options.getArray("delayConditions");
      ArrayList<DelayCondition> conditions = new ArrayList<>();
      if (delayConditions != null) {
        for (int i = 0; i < delayConditions.size(); i++) {
          if (delayConditions.getType(i) != ReadableType.Map) {
            continue;
          }
          ReadableMap condition = delayConditions.getMap(i);
          if (
            condition == null ||
            !condition.hasKey("kind") ||
            condition.isNull("kind")
          ) {
            continue;
          }
          String kindValue = condition.getString("kind");
          String value = condition.hasKey("value") && !condition.isNull("value")
            ? condition.getString("value")
            : null;
          DelayUntilNext kind = DelayUntilNext.valueOf(kindValue);
          conditions.add(new DelayCondition(kind, value));
        }
      }

      String json = new Gson().toJson(conditions);
      editor.putString(DELAY_CONDITION_PREFERENCES, json);
      editor.commit();
      promise.resolve(null);
    } catch (Exception e) {
      promise.reject("SET_DELAY_ERROR", e.getMessage(), e);
    }
  }

  @ReactMethod
  public void cancelDelay(Promise promise) {
    if (!ensureInitialized(promise, "cancelDelay")) {
      return;
    }
    _cancelDelay("cancelDelay");
    promise.resolve(null);
  }

  @ReactMethod
  public void appMovedToForeground(Promise promise) {
    if (!ensureInitialized(promise, "appMovedToForeground")) {
      return;
    }
    appMovedToForegroundInternal();
    promise.resolve(null);
  }

  @ReactMethod
  public void appMovedToBackground(Promise promise) {
    if (!ensureInitialized(promise, "appMovedToBackground")) {
      return;
    }
    appMovedToBackgroundInternal();
    promise.resolve(null);
  }

  private void setupPreferencesIfNeeded() {
    if (prefs != null && editor != null) {
      return;
    }

    prefs = reactContext.getSharedPreferences(
      ReactNativeUpdater.PREFS_NAME,
      Context.MODE_PRIVATE
    );
    editor = prefs.edit();

    implementation.prefs = prefs;
    implementation.editor = editor;
    implementation.documentsDir = reactContext.getFilesDir();

    String existingDeviceId = prefs.getString("appUUID", null);
    if (_isEmpty(existingDeviceId)) {
      existingDeviceId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
      editor.putString("appUUID", existingDeviceId);
      editor.commit();
    }
    implementation.deviceID = existingDeviceId;

    if (!prefs.contains(ReactNativeUpdater.ACTIVE_BUNDLE_PATH_KEY)) {
      implementation.setCurrentBundlePath("public");
    }
  }

  private void setVersionInfoFromPackage() {
    try {
      PackageInfo pInfo = reactContext
        .getPackageManager()
        .getPackageInfo(reactContext.getPackageName(), 0);
      implementation.versionBuild = pInfo.versionName;
      implementation.versionCode = String.valueOf(pInfo.versionCode);
    } catch (PackageManager.NameNotFoundException e) {
      Log.e(ReactNativeUpdaterCore.TAG, "Failed to get package info", e);
      implementation.versionBuild = "0.0.0";
      implementation.versionCode = "0";
    }
  }

  private boolean ensureInitialized(Promise promise, String method) {
    if (!initialized) {
      promise.reject("NOT_INITIALIZED", method + " called before initialize()");
      return false;
    }
    return true;
  }

  private void sendEvent(String eventName, @Nullable WritableMap params) {
    if (!reactContext.hasActiveCatalystInstance()) {
      Log.w(
        ReactNativeUpdaterCore.TAG,
        "Catalyst instance not active. Cannot send event: " + eventName
      );
      return;
    }
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit(eventName, params);
  }

  private WritableMap convertBundleInfoToWritableMap(BundleInfo info) {
    if (info == null) {
      return Arguments.createMap();
    }
    WritableMap ret = Arguments.createMap();
    ret.putString("id", info.getId());
    ret.putString("version", info.getVersionName());
    ret.putString("downloaded", info.getDownloaded());
    ret.putString("checksum", info.getChecksum());
    ret.putString("status", info.getStatus().toString());
    return ret;
  }

  private WritableMap convertJsonObjectToWritableMap(JSONObject jsonObject) {
    WritableMap map = Arguments.createMap();
    if (jsonObject == null) {
      return map;
    }

    JSONArray names = jsonObject.names();
    if (names == null) {
      return map;
    }

    for (int i = 0; i < names.length(); i++) {
      String key = names.optString(i);
      Object value = jsonObject.opt(key);
      if (value == null || value == JSONObject.NULL) {
        map.putNull(key);
      } else if (value instanceof JSONObject) {
        map.putMap(key, convertJsonObjectToWritableMap((JSONObject) value));
      } else if (value instanceof JSONArray) {
        map.putArray(key, convertJsonArrayToWritableArray((JSONArray) value));
      } else if (value instanceof Boolean) {
        map.putBoolean(key, (Boolean) value);
      } else if (value instanceof Integer) {
        map.putInt(key, (Integer) value);
      } else if (value instanceof Long) {
        map.putDouble(key, ((Long) value).doubleValue());
      } else if (value instanceof Float) {
        map.putDouble(key, ((Float) value).doubleValue());
      } else if (value instanceof Double) {
        map.putDouble(key, (Double) value);
      } else {
        map.putString(key, String.valueOf(value));
      }
    }
    return map;
  }

  private WritableArray convertJsonArrayToWritableArray(JSONArray jsonArray) {
    WritableArray array = Arguments.createArray();
    if (jsonArray == null) {
      return array;
    }

    for (int i = 0; i < jsonArray.length(); i++) {
      Object value = jsonArray.opt(i);
      if (value == null || value == JSONObject.NULL) {
        array.pushNull();
      } else if (value instanceof JSONObject) {
        array.pushMap(convertJsonObjectToWritableMap((JSONObject) value));
      } else if (value instanceof JSONArray) {
        array.pushArray(convertJsonArrayToWritableArray((JSONArray) value));
      } else if (value instanceof Boolean) {
        array.pushBoolean((Boolean) value);
      } else if (value instanceof Integer) {
        array.pushInt((Integer) value);
      } else if (value instanceof Long) {
        array.pushDouble(((Long) value).doubleValue());
      } else if (value instanceof Float) {
        array.pushDouble(((Float) value).doubleValue());
      } else if (value instanceof Double) {
        array.pushDouble((Double) value);
      } else {
        array.pushString(String.valueOf(value));
      }
    }
    return array;
  }

  private void observeDownload(final String downloadId, final String version) {
    WorkManager wm = WorkManager.getInstance(reactContext);
    Observer<List<WorkInfo>> observer = new Observer<List<WorkInfo>>() {
      private boolean completed = false;

      @Override
      public void onChanged(List<WorkInfo> workInfos) {
        if (completed || workInfos == null || workInfos.isEmpty()) {
          return;
        }

        for (WorkInfo workInfo : workInfos) {
          Data progress = workInfo.getProgress();
          int percent = progress.getInt(DownloadService.PERCENT, -1);
          if (percent >= 0) {
            sendDownloadProgress(downloadId, percent);
          }

          if (!workInfo.getState().isFinished()) {
            continue;
          }

          completed = true;
          wm.getWorkInfosByTagLiveData(downloadId).removeObserver(this);
          DownloadWorkerManager.markVersionCompleted(version);

          if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
            Data output = workInfo.getOutputData();
            boolean isManifest = output.getBoolean(
              DownloadService.IS_MANIFEST,
              false
            );
            String dest = output.getString(DownloadService.FILEDEST);
            String downloadedVersion = output.getString(
              DownloadService.VERSION
            );
            String sessionKey = output.getString(DownloadService.SESSIONKEY);
            String checksum = output.getString(DownloadService.CHECKSUM);

            boolean directUpdateRequested = implementation.directUpdate;
            boolean finished = implementation.finishDownload(
              downloadId,
              dest,
              downloadedVersion,
              sessionKey,
              checksum,
              true,
              isManifest
            );

            if (finished) {
              BundleInfo bundle = implementation.getBundleInfo(downloadId);
              WritableMap payload = Arguments.createMap();
              payload.putMap("bundle", convertBundleInfoToWritableMap(bundle));
              sendEvent("updateAvailable", payload);
              sendEvent("downloadComplete", payload);
              implementation.sendStats(
                "download_complete",
                bundle.getVersionName()
              );

              if (directUpdateRequested) {
                boolean activated = implementation.set(bundle.getId());
                implementation.setNextBundle(null);
                if (activated) {
                  triggerReload("direct_update");
                }
              }
            } else {
              sendDownloadFailure(
                downloadedVersion != null ? downloadedVersion : version
              );
            }
          } else {
            sendDownloadFailure(version);
          }
        }
      }
    };

    wm.getWorkInfosByTagLiveData(downloadId).observeForever(observer);
  }

  private void sendDownloadProgress(String downloadId, int percent) {
    BundleInfo bundle = implementation.getBundleInfo(downloadId);
    WritableMap payload = Arguments.createMap();
    payload.putInt("percent", percent);
    payload.putMap("bundle", convertBundleInfoToWritableMap(bundle));
    sendEvent("download", payload);

    if (percent == 100) {
      return;
    }

    int rounded = (percent / 10) * 10;
    if (rounded > lastNotifiedStatPercent && rounded <= 90) {
      lastNotifiedStatPercent = rounded;
      implementation.sendStats("download_" + rounded, bundle.getVersionName());
    }
  }

  private void sendDownloadFailure(String version) {
    WritableMap payload = Arguments.createMap();
    payload.putString("version", version);
    sendEvent("downloadFailed", payload);
    implementation.sendStats("download_fail", version);
  }

  public void triggerReload(String reason) {
    Log.i(ReactNativeUpdaterCore.TAG, "Triggering RN reload: " + reason);
    sendEvent("appReloaded", null);

    Activity activity = getCurrentActivity();
    if (activity != null) {
      activity.runOnUiThread(() -> ProcessPhoenix.triggerRebirth(activity));
      return;
    }

    Intent launchIntent = reactContext
      .getPackageManager()
      .getLaunchIntentForPackage(reactContext.getPackageName());
    if (launchIntent != null) {
      launchIntent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
      );
      reactContext.startActivity(launchIntent);
    }
    Runtime.getRuntime().exit(0);
  }

  private boolean _reset(boolean toLastSuccessful) {
    BundleInfo fallback = implementation.getFallbackBundle();
    implementation.reset();

    if (toLastSuccessful && fallback != null && !fallback.isBuiltin()) {
      Log.i(ReactNativeUpdaterCore.TAG, "Resetting to fallback: " + fallback);
      return implementation.set(fallback.getId());
    }

    return true;
  }

  private boolean _isAutoUpdateEnabled() {
    return autoUpdate && !_isEmpty(updateUrl);
  }

  private void checkAppReady() {
    try {
      if (appReadyCheck != null) {
        appReadyCheck.interrupt();
      }

      appReadyCheck = startNewThread(() -> {
        try {
          Log.i(
            ReactNativeUpdaterCore.TAG,
            "Wait for " + appReadyTimeout + "ms, then check for notifyAppReady"
          );
          Thread.sleep(appReadyTimeout);
          checkRevert();
        } catch (InterruptedException ignored) {} finally {
          appReadyCheck = null;
        }
      });
    } catch (Exception e) {
      Log.e(ReactNativeUpdaterCore.TAG, "Failed to schedule appReady check", e);
    }
  }

  private void checkRevert() {
    BundleInfo current = implementation.getCurrentBundle();
    if (current.isBuiltin()) {
      return;
    }

    if (
      !BundleStatus.SUCCESS.toString().equals(current.getStatus().toString())
    ) {
      WritableMap payload = Arguments.createMap();
      payload.putMap("bundle", convertBundleInfoToWritableMap(current));
      sendEvent("updateFailed", payload);
      persistLastFailedBundle(current);

      implementation.sendStats("update_fail", current.getVersionName());
      implementation.setError(current);
      _reset(true);

      if (autoDeleteFailed && !current.isBuiltin()) {
        try {
          implementation.delete(current.getId(), false);
        } catch (Exception e) {
          Log.w(
            ReactNativeUpdaterCore.TAG,
            "Failed to auto-delete failed bundle " + current.getId(),
            e
          );
        }
      }
      triggerReload("revert_failed_bundle");
    }
  }

  private void appMovedToForegroundInternal() {
    BundleInfo current = implementation.getCurrentBundle();
    implementation.sendStats(
      "app_moved_to_foreground",
      current.getVersionName()
    );

    _checkCancelDelay(false);
    if (_isAutoUpdateEnabled()) {
      backgroundDownload();
    } else {
      sendReadyToJs(current, "disabled");
    }

    checkAppReady();
  }

  private void appMovedToBackgroundInternal() {
    BundleInfo current = implementation.getCurrentBundle();
    implementation.sendStats(
      "app_moved_to_background",
      current.getVersionName()
    );

    try {
      String delayPreferences = prefs.getString(
        DELAY_CONDITION_PREFERENCES,
        "[]"
      );
      Type type = new TypeToken<ArrayList<DelayCondition>>() {}.getType();
      ArrayList<DelayCondition> delayConditions = new Gson()
        .fromJson(delayPreferences, type);

      String backgroundDelay = null;
      if (delayConditions != null) {
        for (DelayCondition delayCondition : delayConditions) {
          if (delayCondition.getKind() == DelayUntilNext.background) {
            String value = delayCondition.getValue();
            backgroundDelay = (value == null || value.isEmpty()) ? "0" : value;
          }
        }
      }

      if (backgroundDelay != null) {
        taskRunning = true;
        long timeout = Long.parseLong(backgroundDelay);
        if (backgroundTask != null) {
          backgroundTask.interrupt();
        }
        backgroundTask = startNewThread(
          () -> {
            taskRunning = false;
            _checkCancelDelay(false);
            installNext();
          },
          timeout
        );
      } else {
        _checkCancelDelay(false);
        installNext();
      }
    } catch (Exception e) {
      Log.e(ReactNativeUpdaterCore.TAG, "Error during appMovedToBackground", e);
    }
  }

  private Thread startNewThread(final Runnable function, long waitTime) {
    Thread bgTask = new Thread(() -> {
      try {
        if (waitTime > 0) {
          Thread.sleep(waitTime);
        }
        function.run();
      } catch (InterruptedException ignored) {} catch (Exception e) {
        Log.e(ReactNativeUpdaterCore.TAG, "Background thread error", e);
      }
    });
    bgTask.start();
    return bgTask;
  }

  private Thread startNewThread(final Runnable function) {
    return startNewThread(function, 0);
  }

  private void backgroundDownload() {
    if (_isEmpty(updateUrl)) {
      return;
    }

    implementation.getLatest(updateUrl, null, result -> {
      BundleInfo current = implementation.getCurrentBundle();
      String latestVersionName = result.optString("version", "");

      if (
        (result.optBoolean("major", false) ||
          result.optBoolean("breaking", false)) &&
        !latestVersionName.isEmpty()
      ) {
        WritableMap majorPayload = Arguments.createMap();
        majorPayload.putString("version", latestVersionName);
        sendEvent("breakingAvailable", majorPayload);
        sendEvent("majorAvailable", majorPayload);
      }

      if (result.has("error")) {
        sendReadyToJs(current, result.optString("message", "error"));
        return;
      }

      if ("builtin".equals(latestVersionName)) {
        if (implementation.directUpdate) {
          implementation.reset();
          triggerReload("direct_update_builtin");
        } else {
          implementation.setNextBundle(BundleInfo.ID_BUILTIN);
        }
        sendReadyToJs(current, "builtin");
        return;
      }

      if (
        _isEmpty(latestVersionName) ||
        latestVersionName.equals(current.getVersionName())
      ) {
        WritableMap payload = Arguments.createMap();
        payload.putMap("bundle", convertBundleInfoToWritableMap(current));
        sendEvent("noNeedUpdate", payload);
        sendReadyToJs(current, "up_to_date");
        return;
      }

      BundleInfo existing = implementation.getBundleInfoByName(
        latestVersionName
      );
      if (
        existing != null && !existing.isDeleted() && !existing.isErrorStatus()
      ) {
        implementation.setNextBundle(existing.getId());
        WritableMap payload = Arguments.createMap();
        payload.putMap("bundle", convertBundleInfoToWritableMap(existing));
        sendEvent("updateAvailable", payload);
        sendReadyToJs(current, "already_downloaded");
        return;
      }

      if (existing != null && existing.isDeleted()) {
        try {
          implementation.delete(existing.getId(), true);
        } catch (Exception e) {
          Log.w(
            ReactNativeUpdaterCore.TAG,
            "Failed to remove deleted bundle " + existing.getId(),
            e
          );
        }
      }

      String downloadUrl = result.optString("url", "");
      String sessionKey = result.optString("sessionKey", "");
      String checksum = result.optString("checksum", "");
      JSONArray manifest = result.optJSONArray("manifest");

      if (_isEmpty(downloadUrl) && manifest == null) {
        sendDownloadFailure(latestVersionName);
        return;
      }

      String downloadId = implementation.downloadBackground(
        downloadUrl,
        latestVersionName,
        sessionKey,
        checksum,
        manifest
      );
      if (downloadId == null) {
        sendDownloadFailure(latestVersionName);
        return;
      }

      lastNotifiedStatPercent = 0;
      observeDownload(downloadId, latestVersionName);
      sendReadyToJs(current, "downloading");
    });
  }

  private void installNext() {
    if (hasDelayConditions()) {
      Log.i(
        ReactNativeUpdaterCore.TAG,
        "Update delayed until delay conditions are met"
      );
      return;
    }

    BundleInfo current = implementation.getCurrentBundle();
    BundleInfo next = implementation.getNextBundle();

    if (
      next == null ||
      next.isErrorStatus() ||
      next.getVersionName().equals(current.getVersionName())
    ) {
      return;
    }

    if (implementation.set(next.getId())) {
      implementation.setNextBundle(null);
      triggerReload("install_next");
    }
  }

  private boolean hasDelayConditions() {
    String delayPreferences = prefs.getString(
      DELAY_CONDITION_PREFERENCES,
      "[]"
    );
    Type type = new TypeToken<ArrayList<DelayCondition>>() {}.getType();
    ArrayList<DelayCondition> delayConditions = new Gson()
      .fromJson(delayPreferences, type);
    return delayConditions != null && !delayConditions.isEmpty();
  }

  private void _cancelDelay(String source) {
    editor.remove(DELAY_CONDITION_PREFERENCES);
    editor.commit();
    Log.i(ReactNativeUpdaterCore.TAG, "Delay canceled from " + source);
  }

  private void _checkCancelDelay(boolean killed) {
    String delayPreferences = prefs.getString(
      DELAY_CONDITION_PREFERENCES,
      "[]"
    );
    Type type = new TypeToken<ArrayList<DelayCondition>>() {}.getType();
    ArrayList<DelayCondition> delayConditions = new Gson()
      .fromJson(delayPreferences, type);
    if (delayConditions == null || delayConditions.isEmpty()) {
      return;
    }

    for (DelayCondition delayCondition : delayConditions) {
      DelayUntilNext kind = delayCondition.getKind();
      String value = delayCondition.getValue();
      if (kind == null) {
        continue;
      }
      switch (kind) {
        case background:
          if (!killed) {
            _cancelDelay("background");
          }
          break;
        case kill:
          if (killed) {
            _cancelDelay("kill");
            installNext();
          }
          break;
        case date:
          if (value == null || value.isEmpty()) {
            _cancelDelay("date_missing");
          }
          break;
        case nativeVersion:
          if (value == null || value.isEmpty()) {
            _cancelDelay("native_version_missing");
            break;
          }
          try {
            Version versionLimit = new Version(value);
            if (
              currentVersionNative.isHigherThan(versionLimit) ||
              currentVersionNative.isEqual(versionLimit)
            ) {
              _cancelDelay("native_version_reached");
            }
          } catch (Exception e) {
            _cancelDelay("native_version_parse_error");
          }
          break;
      }
    }
  }

  private AppUpdateManager getAppUpdateManager() {
    if (appUpdateManager == null) {
      appUpdateManager = AppUpdateManagerFactory.create(reactContext);
    }
    return appUpdateManager;
  }

  private int mapUpdateAvailability(int playStoreAvailability) {
    switch (playStoreAvailability) {
      case UpdateAvailability.UPDATE_AVAILABLE:
        return UPDATE_AVAILABILITY_AVAILABLE;
      case UpdateAvailability.UPDATE_NOT_AVAILABLE:
        return UPDATE_AVAILABILITY_NOT_AVAILABLE;
      case UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS:
        return UPDATE_AVAILABILITY_IN_PROGRESS;
      default:
        return UPDATE_AVAILABILITY_UNKNOWN;
    }
  }

  private void persistLastFailedBundle(@Nullable BundleInfo bundle) {
    if (bundle == null) {
      editor.remove(LAST_FAILED_BUNDLE_PREF_KEY);
      editor.apply();
      return;
    }
    editor.putString(LAST_FAILED_BUNDLE_PREF_KEY, bundle.toString());
    editor.apply();
  }

  @Nullable
  private BundleInfo readLastFailedBundle() {
    String raw = prefs.getString(LAST_FAILED_BUNDLE_PREF_KEY, null);
    if (_isEmpty(raw)) {
      return null;
    }

    try {
      return BundleInfo.fromJSON(raw);
    } catch (Exception e) {
      Log.w(
        ReactNativeUpdaterCore.TAG,
        "Failed to parse last failed bundle",
        e
      );
      return null;
    }
  }

  private void sendReadyToJs(BundleInfo current, String status) {
    WritableMap payload = Arguments.createMap();
    payload.putMap("bundle", convertBundleInfoToWritableMap(current));
    payload.putString("status", status);
    sendEvent("appReady", payload);
  }

  private static boolean _isEmpty(String value) {
    return value == null || value.isEmpty();
  }
}
