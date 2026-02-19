/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.codepushgo.reactnativeupdater;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lightweight JSONObject wrapper with a non-throwing put() helper.
 */
public class JSObject extends JSONObject {

  public JSObject() {
    super();
  }

  public JSObject(String source) throws JSONException {
    super(source);
  }

  @Override
  public JSObject put(String name, Object value) {
    try {
      super.put(name, value);
    } catch (JSONException ignored) {
      // Keep behavior non-throwing for callback builders.
    }
    return this;
  }

  public JSObject put(String name, boolean value) {
    try {
      super.put(name, value);
    } catch (JSONException ignored) {
      // Keep behavior non-throwing for callback builders.
    }
    return this;
  }

  public JSObject put(String name, double value) {
    try {
      super.put(name, value);
    } catch (JSONException ignored) {
      // Keep behavior non-throwing for callback builders.
    }
    return this;
  }

  public JSObject put(String name, int value) {
    try {
      super.put(name, value);
    } catch (JSONException ignored) {
      // Keep behavior non-throwing for callback builders.
    }
    return this;
  }

  public JSObject put(String name, long value) {
    try {
      super.put(name, value);
    } catch (JSONException ignored) {
      // Keep behavior non-throwing for callback builders.
    }
    return this;
  }
}
