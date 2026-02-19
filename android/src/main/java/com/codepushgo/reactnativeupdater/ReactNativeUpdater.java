/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.codepushgo.reactnativeupdater;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import java.io.File;

/**
 * Host-app helper for resolving the active OTA JS bundle file path.
 *
 * MainApplication example:
 * return ReactNativeUpdater.getJSBundleFile(this.getApplicationContext());
 */
public final class ReactNativeUpdater {

  public static final String TAG = ReactNativeUpdaterCore.TAG;
  public static final String PREFS_NAME = "ReactNativeUpdaterPrefs_RN";
  public static final String ACTIVE_BUNDLE_PATH_KEY =
    "ReactNativeUpdaterPrefs_RN_activeBundlePath";
  private static final String BUILTIN_PATH = "public";

  private ReactNativeUpdater() {}

  @Nullable
  public static String getJSBundleFile(Context context) {
    SharedPreferences prefs = context.getSharedPreferences(
      PREFS_NAME,
      Context.MODE_PRIVATE
    );
    String activePath = prefs.getString(ACTIVE_BUNDLE_PATH_KEY, BUILTIN_PATH);

    if (
      activePath == null ||
      activePath.isEmpty() ||
      BUILTIN_PATH.equals(activePath)
    ) {
      return null;
    }

    File path = new File(activePath);
    if (!path.exists()) {
      return null;
    }

    if (path.isFile()) {
      return isBundleFile(path) ? path.getAbsolutePath() : null;
    }

    File preferredAndroidBundle = new File(path, "index.android.bundle");
    if (preferredAndroidBundle.exists() && preferredAndroidBundle.isFile()) {
      return preferredAndroidBundle.getAbsolutePath();
    }

    File preferredMainBundle = new File(path, "main.jsbundle");
    if (preferredMainBundle.exists() && preferredMainBundle.isFile()) {
      return preferredMainBundle.getAbsolutePath();
    }

    File discovered = findBundleFile(path);
    return discovered == null ? null : discovered.getAbsolutePath();
  }

  @Nullable
  private static File findBundleFile(File dir) {
    File[] entries = dir.listFiles();
    if (entries == null) {
      return null;
    }

    for (File entry : entries) {
      if (entry.isFile() && isBundleFile(entry)) {
        return entry;
      }
    }

    for (File entry : entries) {
      if (entry.isDirectory()) {
        File nested = findBundleFile(entry);
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  private static boolean isBundleFile(File file) {
    String name = file.getName();
    return name.endsWith(".bundle") || name.endsWith(".jsbundle");
  }
}
