/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

export interface PluginListenerHandle {
  remove: () => Promise<void>;
}

export interface ReactNativeUpdaterConfig {
  appReadyTimeout?: number;
  responseTimeout?: number;
  autoDeleteFailed?: boolean;
  autoDeletePrevious?: boolean;
  autoUpdate?: boolean;
  resetWhenUpdate?: boolean;
  updateUrl?: string;
  channelUrl?: string;
  statsUrl?: string;
  publicKey?: string;
  version?: string;
  directUpdate?: boolean;
  autoSplashscreen?: boolean;
  autoSplashscreenLoader?: boolean;
  autoSplashscreenTimeout?: number;
  periodCheckDelay?: number;
  localS3?: boolean;
  localHost?: string;
  localWebHost?: string;
  localSupa?: string;
  localSupaAnon?: string;
  localApi?: string;
  localApiFiles?: string;
  allowModifyUrl?: boolean;
  allowModifyAppId?: boolean;
  allowManualBundleError?: boolean;
  persistCustomId?: boolean;
  persistModifyUrl?: boolean;
  allowSetDefaultChannel?: boolean;
  defaultChannel?: string;
  appId?: string;
  keepUrlPathAfterReload?: boolean;
  disableJSLogging?: boolean;
  osLogging?: boolean;
  shakeMenu?: boolean;
  allowShakeChannelSelector?: boolean;
}

export interface ReactNativeUpdaterPlugin {
  initialize(config: ReactNativeUpdaterConfig): Promise<string>;
  notifyAppReady(): Promise<AppReadyResult>;
  setUpdateUrl(options: UpdateUrl): Promise<void>;
  setStatsUrl(options: StatsUrl): Promise<void>;
  setChannelUrl(options: ChannelUrl): Promise<void>;
  download(options: DownloadOptions): Promise<BundleInfo>;
  next(options: BundleId): Promise<BundleInfo>;
  set(options: BundleId): Promise<void>;
  delete(options: BundleId): Promise<void>;
  setBundleError(options: BundleId): Promise<BundleInfo>;
  list(options?: ListOptions): Promise<BundleListResult>;
  reset(options?: ResetOptions): Promise<void>;
  current(): Promise<CurrentBundleResult>;
  reload(): Promise<void>;
  setMultiDelay(options: MultiDelayConditions): Promise<void>;
  cancelDelay(): Promise<void>;
  getLatest(options?: GetLatestOptions): Promise<LatestVersion>;
  setChannel(options: SetChannelOptions): Promise<ChannelRes>;
  unsetChannel(options?: UnsetChannelOptions): Promise<void>;
  getChannel(): Promise<GetChannelRes>;
  listChannels(): Promise<ListChannelsResult>;
  setCustomId(options: SetCustomIdOptions): Promise<void>;
  getBuiltinVersion(): Promise<BuiltinVersion>;
  getDeviceId(): Promise<DeviceId>;
  getPluginVersion(): Promise<PluginVersion>;
  isAutoUpdateEnabled(): Promise<AutoUpdateEnabled>;
  isAutoUpdateAvailable(): Promise<AutoUpdateAvailable>;
  getNextBundle(): Promise<BundleInfo | null>;
  getFailedUpdate(): Promise<UpdateFailedEvent | null>;
  setShakeMenu(options: SetShakeMenuOptions): Promise<void>;
  isShakeMenuEnabled(): Promise<ShakeMenuEnabled>;
  setShakeChannelSelector(
    options: SetShakeChannelSelectorOptions,
  ): Promise<void>;
  isShakeChannelSelectorEnabled(): Promise<ShakeChannelSelectorEnabled>;
  getAppId(): Promise<GetAppIdRes>;
  setAppId(options: SetAppIdOptions): Promise<void>;
  getAppUpdateInfo(options?: GetAppUpdateInfoOptions): Promise<AppUpdateInfo>;
  openAppStore(options?: OpenAppStoreOptions): Promise<void>;
  performImmediateUpdate(): Promise<AppUpdateResult>;
  startFlexibleUpdate(): Promise<AppUpdateResult>;
  completeFlexibleUpdate(): Promise<void>;
  compareVersions(options: {
    versionA: string;
    versionB: string;
  }): Promise<number>;
  checkForUpdate(): Promise<string>;
  appMovedToForeground(): Promise<void>;
  appMovedToBackground(): Promise<void>;
  removeAllListeners(): Promise<void>;

  addListener(
    eventName: "download",
    listenerFunc: (state: DownloadEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "noNeedUpdate",
    listenerFunc: (state: NoNeedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "updateAvailable",
    listenerFunc: (state: UpdateAvailableEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "downloadComplete",
    listenerFunc: (state: DownloadCompleteEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "breakingAvailable",
    listenerFunc: (state: BreakingAvailableEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "majorAvailable",
    listenerFunc: (state: MajorAvailableEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "updateFailed",
    listenerFunc: (state: UpdateFailedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "downloadFailed",
    listenerFunc: (state: DownloadFailedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "appReloaded",
    listenerFunc: () => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "appReady",
    listenerFunc: (state: AppReadyEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "channelPrivate",
    listenerFunc: (state: ChannelPrivateEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "onFlexibleUpdateStateChange",
    listenerFunc: (state: FlexibleUpdateState) => void,
  ): Promise<PluginListenerHandle>;
}

export type BundleStatus = "success" | "error" | "pending" | "downloading";

export type DelayUntilNext = "background" | "kill" | "nativeVersion" | "date";

export interface NoNeedEvent {
  bundle: BundleInfo;
}

export interface UpdateAvailableEvent {
  bundle: BundleInfo;
}

export interface ChannelRes {
  status: string;
  error?: string;
  message?: string;
}

export interface GetChannelRes {
  channel?: string;
  error?: string;
  message?: string;
  status?: string;
  allowSet?: boolean;
}

export interface ChannelInfo {
  id: string;
  name: string;
  public: boolean;
  allow_self_set: boolean;
}

export interface ListChannelsResult {
  channels: ChannelInfo[];
}

export interface DownloadEvent {
  percent: number;
  bundle: BundleInfo;
}

export interface MajorAvailableEvent {
  version: string;
}

export type BreakingAvailableEvent = MajorAvailableEvent;

export interface DownloadFailedEvent {
  version: string;
}

export interface DownloadCompleteEvent {
  bundle: BundleInfo;
}

export interface UpdateFailedEvent {
  bundle: BundleInfo;
}

export interface AppReadyEvent {
  bundle: BundleInfo;
  status: string;
}

export interface ChannelPrivateEvent {
  channel: string;
  message: string;
}

export interface ManifestEntry {
  file_name: string | null;
  file_hash: string | null;
  download_url: string | null;
}

export interface LatestVersion {
  version: string;
  checksum?: string;
  breaking?: boolean;
  major?: boolean;
  message?: string;
  sessionKey?: string;
  error?: string;
  old?: string;
  url?: string;
  data?: Record<string, string>;
  manifest?: ManifestEntry[];
}

export interface BundleInfo {
  id: string;
  version: string;
  downloaded: string;
  checksum: string;
  status: BundleStatus;
}

export interface SetChannelOptions {
  channel: string;
  triggerAutoUpdate?: boolean;
}

export interface UnsetChannelOptions {
  triggerAutoUpdate?: boolean;
}

export interface SetCustomIdOptions {
  customId: string;
}

export interface DelayCondition {
  kind: DelayUntilNext;
  value?: string;
}

export interface GetLatestOptions {
  channel?: string;
}

export interface AppReadyResult {
  bundle: BundleInfo;
}

export interface UpdateUrl {
  url: string;
}

export interface StatsUrl {
  url: string;
}

export interface ChannelUrl {
  url: string;
}

export interface DownloadOptions {
  url?: string;
  version: string;
  sessionKey?: string;
  checksum?: string;
}

export interface BundleId {
  id: string;
}

export interface BundleListResult {
  bundles: BundleInfo[];
}

export interface ResetOptions {
  toLastSuccessful: boolean;
}

export interface ListOptions {
  raw?: boolean;
}

export interface CurrentBundleResult {
  bundle: BundleInfo;
  native: string;
}

export interface MultiDelayConditions {
  delayConditions: DelayCondition[];
}

export interface BuiltinVersion {
  version: string;
}

export interface DeviceId {
  deviceId: string;
}

export interface PluginVersion {
  version: string;
}

export interface AutoUpdateEnabled {
  enabled: boolean;
}

export interface AutoUpdateAvailable {
  available: boolean;
}

export interface SetShakeMenuOptions {
  enabled: boolean;
}

export interface ShakeMenuEnabled {
  enabled: boolean;
}

export interface SetShakeChannelSelectorOptions {
  enabled: boolean;
}

export interface ShakeChannelSelectorEnabled {
  enabled: boolean;
}

export interface GetAppIdRes {
  appId: string;
}

export interface SetAppIdOptions {
  appId: string;
}

export interface GetAppUpdateInfoOptions {
  country?: string;
}

export interface AppUpdateInfo {
  currentVersionName: string;
  availableVersionName?: string;
  currentVersionCode: string;
  availableVersionCode?: string;
  availableVersionReleaseDate?: string;
  updateAvailability: AppUpdateAvailability;
  updatePriority?: number;
  immediateUpdateAllowed?: boolean;
  flexibleUpdateAllowed?: boolean;
  clientVersionStalenessDays?: number;
  installStatus?: FlexibleUpdateInstallStatus;
  minimumOsVersion?: string;
}

export interface OpenAppStoreOptions {
  packageName?: string;
  appId?: string;
}

export interface FlexibleUpdateState {
  installStatus: FlexibleUpdateInstallStatus;
  bytesDownloaded?: number;
  totalBytesToDownload?: number;
}

export interface AppUpdateResult {
  code: AppUpdateResultCode;
}

export enum AppUpdateAvailability {
  UNKNOWN = 0,
  UPDATE_NOT_AVAILABLE = 1,
  UPDATE_AVAILABLE = 2,
  UPDATE_IN_PROGRESS = 3,
}

export enum FlexibleUpdateInstallStatus {
  UNKNOWN = 0,
  PENDING = 1,
  DOWNLOADING = 2,
  INSTALLING = 3,
  INSTALLED = 4,
  FAILED = 5,
  CANCELED = 6,
  DOWNLOADED = 11,
}

export enum AppUpdateResultCode {
  OK = 0,
  CANCELED = 1,
  FAILED = 2,
  NOT_AVAILABLE = 3,
  NOT_ALLOWED = 4,
  INFO_MISSING = 5,
}
