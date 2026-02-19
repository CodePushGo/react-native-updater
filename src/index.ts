/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {
  NativeEventEmitter,
  NativeModules,
  Platform,
  type EmitterSubscription,
} from "react-native";

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
  ListChannelsResult,
  GetLatestOptions,
  LatestVersion,
  ListOptions,
  MultiDelayConditions,
  OpenAppStoreOptions,
  PluginVersion,
  PluginListenerHandle,
  ResetOptions,
  ReactNativeUpdaterConfig,
  ReactNativeUpdaterPlugin,
  SetAppIdOptions,
  SetChannelOptions,
  SetCustomIdOptions,
  SetShakeChannelSelectorOptions,
  SetShakeMenuOptions,
  ShakeChannelSelectorEnabled,
  ShakeMenuEnabled,
  StatsUrl,
  UnsetChannelOptions,
  UpdateFailedEvent,
  UpdateUrl,
} from "./definitions";

const LINKING_ERROR =
  `The package '@codepush-go/react-native-updater' doesn't seem to be linked.\n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: "" }) +
  "- You rebuilt the app after installing the package\n" +
  "- You are not using Expo Go\n";

interface NativeReactNativeUpdaterModule {
  initialize(config: ReactNativeUpdaterConfig): Promise<string>;
  setUpdateUrl(url: string): Promise<void>;
  setStatsUrl(url: string): Promise<void>;
  setChannelUrl(url: string): Promise<void>;
  setCustomId(customId: string): Promise<void>;
  getBuiltinVersion(): Promise<string>;
  getDeviceId(): Promise<string>;
  getPluginVersion(): Promise<string>;
  isAutoUpdateEnabled(): Promise<boolean>;
  isAutoUpdateAvailable(): Promise<boolean>;
  unsetChannel(triggerAutoUpdate: boolean): Promise<void>;
  setChannel(channel: string, triggerAutoUpdate: boolean): Promise<ChannelRes>;
  getChannel(): Promise<GetChannelRes>;
  listChannels(): Promise<ListChannelsResult>;
  download(options: DownloadOptions): Promise<BundleInfo>;
  next(id: string): Promise<BundleInfo>;
  set(id: string): Promise<void>;
  delete(id: string): Promise<void>;
  setBundleError(id: string): Promise<BundleInfo | { bundle: BundleInfo }>;
  list(): Promise<BundleListResult>;
  getLatest(channel: string | null): Promise<LatestVersion>;
  reset(toLastSuccessful: boolean): Promise<void>;
  current(): Promise<CurrentBundleResult>;
  getNextBundle(): Promise<BundleInfo | null>;
  getFailedUpdate(): Promise<UpdateFailedEvent | { bundle: BundleInfo } | null>;
  setShakeMenu(enabled: boolean): Promise<void>;
  isShakeMenuEnabled(): Promise<boolean>;
  setShakeChannelSelector(enabled: boolean): Promise<void>;
  isShakeChannelSelectorEnabled(): Promise<boolean>;
  getAppId(): Promise<string>;
  setAppId(appId: string): Promise<void>;
  getAppUpdateInfo(country: string | null): Promise<AppUpdateInfo>;
  openAppStore(options: OpenAppStoreOptions | null): Promise<void>;
  performImmediateUpdate(): Promise<AppUpdateResult>;
  startFlexibleUpdate(): Promise<AppUpdateResult>;
  completeFlexibleUpdate(): Promise<void>;
  notifyAppReady(): Promise<void>;
  reload(): Promise<void>;
  compareVersions(versionA: string, versionB: string): Promise<number>;
  checkForUpdate(): Promise<string>;
  setMultiDelay(options: MultiDelayConditions): Promise<void>;
  cancelDelay(): Promise<void>;
  appMovedToForeground(): Promise<void>;
  appMovedToBackground(): Promise<void>;
}

const nativeUpdater: NativeReactNativeUpdaterModule | undefined =
  NativeModules.ReactNativeUpdater ?? NativeModules.RNReactNativeUpdater;

if (!nativeUpdater) {
  console.error(LINKING_ERROR);
}

const emitter = nativeUpdater
  ? new NativeEventEmitter(nativeUpdater as never)
  : null;
const activeSubscriptions = new Set<EmitterSubscription>();

const requireNative = (): NativeReactNativeUpdaterModule => {
  if (!nativeUpdater) {
    throw new Error(LINKING_ERROR);
  }
  return nativeUpdater;
};

const toListenerHandle = (
  subscription: EmitterSubscription,
): PluginListenerHandle => ({
  remove: async () => {
    if (activeSubscriptions.has(subscription)) {
      subscription.remove();
      activeSubscriptions.delete(subscription);
    }
  },
});

const ReactNativeUpdater: ReactNativeUpdaterPlugin = {
  initialize: (config: ReactNativeUpdaterConfig) =>
    requireNative().initialize(config),

  notifyAppReady: async (): Promise<AppReadyResult> => {
    await requireNative().notifyAppReady();
    const current = await ReactNativeUpdater.current();
    return { bundle: current.bundle };
  },

  setUpdateUrl: (options: UpdateUrl) =>
    requireNative().setUpdateUrl(options.url),

  setStatsUrl: (options: StatsUrl) => requireNative().setStatsUrl(options.url),

  setChannelUrl: (options: ChannelUrl) =>
    requireNative().setChannelUrl(options.url),

  download: (options: DownloadOptions) => requireNative().download(options),

  next: (options: BundleId) => requireNative().next(options.id),

  set: (options: BundleId) => requireNative().set(options.id),

  delete: (options: BundleId) => requireNative().delete(options.id),

  setBundleError: async (options: BundleId): Promise<BundleInfo> => {
    const result = await requireNative().setBundleError(options.id);
    return "bundle" in result ? result.bundle : result;
  },

  list: (_options?: ListOptions): Promise<BundleListResult> =>
    requireNative().list(),

  reset: (options?: ResetOptions) =>
    requireNative().reset(options?.toLastSuccessful ?? false),

  current: () => requireNative().current(),

  reload: () => requireNative().reload(),

  setCustomId: (options: SetCustomIdOptions) =>
    requireNative().setCustomId(options.customId),

  getLatest: (options?: GetLatestOptions) =>
    requireNative().getLatest(options?.channel ?? null),

  setChannel: (options: SetChannelOptions) =>
    requireNative().setChannel(
      options.channel,
      options.triggerAutoUpdate ?? false,
    ),

  unsetChannel: (options?: UnsetChannelOptions) =>
    requireNative().unsetChannel(options?.triggerAutoUpdate ?? false),

  getChannel: () => requireNative().getChannel(),

  listChannels: () => requireNative().listChannels(),

  getBuiltinVersion: async (): Promise<BuiltinVersion> => ({
    version: await requireNative().getBuiltinVersion(),
  }),

  getDeviceId: async (): Promise<DeviceId> => ({
    deviceId: await requireNative().getDeviceId(),
  }),

  getPluginVersion: async (): Promise<PluginVersion> => ({
    version: await requireNative().getPluginVersion(),
  }),

  isAutoUpdateEnabled: async (): Promise<AutoUpdateEnabled> => ({
    enabled: await requireNative().isAutoUpdateEnabled(),
  }),

  isAutoUpdateAvailable: async (): Promise<AutoUpdateAvailable> => ({
    available: await requireNative().isAutoUpdateAvailable(),
  }),

  getFailedUpdate: async (): Promise<UpdateFailedEvent | null> => {
    const result = await requireNative().getFailedUpdate();
    if (!result) {
      return null;
    }
    if ("bundle" in result) {
      return { bundle: result.bundle };
    }
    return result;
  },

  setShakeMenu: (options: SetShakeMenuOptions) =>
    requireNative().setShakeMenu(options.enabled),

  isShakeMenuEnabled: async (): Promise<ShakeMenuEnabled> => ({
    enabled: await requireNative().isShakeMenuEnabled(),
  }),

  setShakeChannelSelector: (options: SetShakeChannelSelectorOptions) =>
    requireNative().setShakeChannelSelector(options.enabled),

  isShakeChannelSelectorEnabled:
    async (): Promise<ShakeChannelSelectorEnabled> => ({
      enabled: await requireNative().isShakeChannelSelectorEnabled(),
    }),

  getAppId: async (): Promise<GetAppIdRes> => ({
    appId: await requireNative().getAppId(),
  }),

  setAppId: (options: SetAppIdOptions) =>
    requireNative().setAppId(options.appId),

  getAppUpdateInfo: (
    options?: GetAppUpdateInfoOptions,
  ): Promise<AppUpdateInfo> =>
    requireNative().getAppUpdateInfo(options?.country ?? null),

  openAppStore: (options?: OpenAppStoreOptions) =>
    requireNative().openAppStore(options ?? null),

  performImmediateUpdate: () => requireNative().performImmediateUpdate(),

  startFlexibleUpdate: () => requireNative().startFlexibleUpdate(),

  completeFlexibleUpdate: () => requireNative().completeFlexibleUpdate(),

  compareVersions: (options: { versionA: string; versionB: string }) =>
    requireNative().compareVersions(options.versionA, options.versionB),

  checkForUpdate: () => requireNative().checkForUpdate(),

  appMovedToForeground: () => requireNative().appMovedToForeground(),

  appMovedToBackground: () => requireNative().appMovedToBackground(),

  getNextBundle: () => requireNative().getNextBundle(),

  setMultiDelay: (options: MultiDelayConditions) =>
    requireNative().setMultiDelay(options),

  cancelDelay: () => requireNative().cancelDelay(),

  addListener: async (
    eventName: string,
    listenerFunc: (...args: any[]) => void,
  ): Promise<PluginListenerHandle> => {
    if (!emitter) {
      throw new Error(LINKING_ERROR);
    }
    const subscription = emitter.addListener(
      eventName,
      listenerFunc as (...args: unknown[]) => void,
    );
    activeSubscriptions.add(subscription);
    return toListenerHandle(subscription);
  },

  removeAllListeners: async () => {
    for (const subscription of activeSubscriptions) {
      subscription.remove();
    }
    activeSubscriptions.clear();
  },
};

export * from "./definitions";
export { ReactNativeUpdater };
