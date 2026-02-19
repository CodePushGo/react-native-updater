/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import type {
  AppUpdateInfo,
  AppUpdateResult,
  AppReadyResult,
  AutoUpdateAvailable,
  AutoUpdateEnabled,
  BuiltinVersion,
  BundleId,
  BundleInfo,
  BundleListResult,
  ChannelRes,
  ChannelUrl,
  CurrentBundleResult,
  DeviceId,
  DownloadOptions,
  GetAppIdRes,
  GetAppUpdateInfoOptions,
  GetChannelRes,
  GetLatestOptions,
  ListChannelsResult,
  LatestVersion,
  ListOptions,
  MultiDelayConditions,
  OpenAppStoreOptions,
  PluginListenerHandle,
  PluginVersion,
  ReactNativeUpdaterConfig,
  ReactNativeUpdaterPlugin,
  ResetOptions,
  SetAppIdOptions,
  SetChannelOptions,
  SetCustomIdOptions,
  SetShakeChannelSelectorOptions,
  SetShakeMenuOptions,
  ShakeChannelSelectorEnabled,
  ShakeMenuEnabled,
  UpdateFailedEvent,
  StatsUrl,
  UnsetChannelOptions,
  UpdateUrl,
} from "./definitions";

const BUNDLE_BUILTIN: BundleInfo = {
  status: "success",
  version: "",
  downloaded: "1970-01-01T00:00:00.000Z",
  id: "builtin",
  checksum: "",
};

const unsupported = (method: string): never => {
  throw new Error(
    `@codepush-go/react-native-updater: '${method}' is not supported on web.`,
  );
};

export class ReactNativeUpdaterWeb implements ReactNativeUpdaterPlugin {
  initialize(_config: ReactNativeUpdaterConfig): Promise<string> {
    return Promise.resolve("unsupported-web");
  }

  notifyAppReady(): Promise<AppReadyResult> {
    return Promise.resolve({ bundle: BUNDLE_BUILTIN });
  }

  setUpdateUrl(_options: UpdateUrl): Promise<void> {
    unsupported("setUpdateUrl");
  }

  setStatsUrl(_options: StatsUrl): Promise<void> {
    unsupported("setStatsUrl");
  }

  setChannelUrl(_options: ChannelUrl): Promise<void> {
    unsupported("setChannelUrl");
  }

  download(_options: DownloadOptions): Promise<BundleInfo> {
    return Promise.resolve(BUNDLE_BUILTIN);
  }

  next(_options: BundleId): Promise<BundleInfo> {
    return Promise.resolve(BUNDLE_BUILTIN);
  }

  set(_options: BundleId): Promise<void> {
    unsupported("set");
  }

  delete(_options: BundleId): Promise<void> {
    unsupported("delete");
  }

  setBundleError(_options: BundleId): Promise<BundleInfo> {
    unsupported("setBundleError");
  }

  list(_options?: ListOptions): Promise<BundleListResult> {
    return Promise.resolve({ bundles: [] });
  }

  reset(_options?: ResetOptions): Promise<void> {
    unsupported("reset");
  }

  current(): Promise<CurrentBundleResult> {
    return Promise.resolve({ bundle: BUNDLE_BUILTIN, native: "0.0.0" });
  }

  reload(): Promise<void> {
    unsupported("reload");
  }

  setMultiDelay(_options: MultiDelayConditions): Promise<void> {
    unsupported("setMultiDelay");
  }

  cancelDelay(): Promise<void> {
    unsupported("cancelDelay");
  }

  getLatest(_options?: GetLatestOptions): Promise<LatestVersion> {
    return Promise.resolve({ version: "0.0.0", message: "unsupported-web" });
  }

  setChannel(_options: SetChannelOptions): Promise<ChannelRes> {
    return Promise.resolve({ status: "error", error: "unsupported-web" });
  }

  unsetChannel(_options?: UnsetChannelOptions): Promise<void> {
    unsupported("unsetChannel");
  }

  getChannel(): Promise<GetChannelRes> {
    return Promise.resolve({ status: "error", error: "unsupported-web" });
  }

  listChannels(): Promise<ListChannelsResult> {
    return Promise.resolve({ channels: [] });
  }

  setCustomId(_options: SetCustomIdOptions): Promise<void> {
    unsupported("setCustomId");
  }

  getBuiltinVersion(): Promise<BuiltinVersion> {
    return Promise.resolve({ version: "0.0.0" });
  }

  getDeviceId(): Promise<DeviceId> {
    return Promise.resolve({ deviceId: "web" });
  }

  getPluginVersion(): Promise<PluginVersion> {
    return Promise.resolve({ version: "0.0.0" });
  }

  isAutoUpdateEnabled(): Promise<AutoUpdateEnabled> {
    return Promise.resolve({ enabled: false });
  }

  isAutoUpdateAvailable(): Promise<AutoUpdateAvailable> {
    return Promise.resolve({ available: false });
  }

  getNextBundle(): Promise<BundleInfo | null> {
    return Promise.resolve(null);
  }

  getFailedUpdate(): Promise<UpdateFailedEvent | null> {
    return Promise.resolve(null);
  }

  setShakeMenu(_options: SetShakeMenuOptions): Promise<void> {
    unsupported("setShakeMenu");
  }

  isShakeMenuEnabled(): Promise<ShakeMenuEnabled> {
    return Promise.resolve({ enabled: false });
  }

  setShakeChannelSelector(
    _options: SetShakeChannelSelectorOptions,
  ): Promise<void> {
    unsupported("setShakeChannelSelector");
  }

  isShakeChannelSelectorEnabled(): Promise<ShakeChannelSelectorEnabled> {
    return Promise.resolve({ enabled: false });
  }

  getAppId(): Promise<GetAppIdRes> {
    return Promise.resolve({ appId: "web" });
  }

  setAppId(_options: SetAppIdOptions): Promise<void> {
    unsupported("setAppId");
  }

  getAppUpdateInfo(_options?: GetAppUpdateInfoOptions): Promise<AppUpdateInfo> {
    unsupported("getAppUpdateInfo");
  }

  openAppStore(_options?: OpenAppStoreOptions): Promise<void> {
    unsupported("openAppStore");
  }

  performImmediateUpdate(): Promise<AppUpdateResult> {
    unsupported("performImmediateUpdate");
  }

  startFlexibleUpdate(): Promise<AppUpdateResult> {
    unsupported("startFlexibleUpdate");
  }

  completeFlexibleUpdate(): Promise<void> {
    unsupported("completeFlexibleUpdate");
  }

  compareVersions(_options: {
    versionA: string;
    versionB: string;
  }): Promise<number> {
    unsupported("compareVersions");
  }

  checkForUpdate(): Promise<string> {
    return Promise.resolve("unsupported-web");
  }

  appMovedToForeground(): Promise<void> {
    return Promise.resolve();
  }

  appMovedToBackground(): Promise<void> {
    return Promise.resolve();
  }

  addListener(
    _eventName: string,
    _listenerFunc: (...args: any[]) => void,
  ): Promise<PluginListenerHandle> {
    return Promise.resolve({
      remove: async () => {
        return;
      },
    });
  }

  removeAllListeners(): Promise<void> {
    return Promise.resolve();
  }
}
