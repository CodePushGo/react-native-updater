/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import Foundation
import React
import UIKit
import Version

@objc(ReactNativeUpdater)
public class ReactNativeUpdaterModule: RCTEventEmitter {
    private static let updateUrlDefault = "https://plugin.capgo.app/updates"
    private static let statsUrlDefault = "https://plugin.capgo.app/stats"
    private static let channelUrlDefault = "https://plugin.capgo.app/channel_self"
    private static let delayConditionPreferences = "ReactNativeUpdaterDelayConditions"
    private static let customIdDefaultsKey = "ReactNativeUpdater.customId"
    private static let updateUrlDefaultsKey = "ReactNativeUpdater.updateUrl"
    private static let statsUrlDefaultsKey = "ReactNativeUpdater.statsUrl"
    private static let channelUrlDefaultsKey = "ReactNativeUpdater.channelUrl"
    private static let defaultChannelDefaultsKey = "ReactNativeUpdater.defaultChannel"
    private static let lastFailedBundleDefaultsKey = "ReactNativeUpdater.lastFailedBundle"

    private let pluginVersion = "7.0.50"
    private let implementation = ReactNativeUpdaterCore()

    private var updateUrl = ReactNativeUpdaterModule.updateUrlDefault
    private var statsUrl = ReactNativeUpdaterModule.statsUrlDefault
    private var channelUrl = ReactNativeUpdaterModule.channelUrlDefault
    private var configDefaultChannel = ""
    private var autoUpdate = false
    private var appReadyTimeout = 10000
    private var autoDeleteFailed = true
    private var autoDeletePrevious = true
    private var periodCheckDelay = 0
    private var allowModifyAppId = false
    private var allowManualBundleError = false
    private var persistCustomId = false
    private var persistModifyUrl = false
    private var allowSetDefaultChannel = true
    private var shakeMenuEnabled = false
    private var shakeChannelSelectorEnabled = false

    private var backgroundWork: DispatchWorkItem?
    private var appReadyCheck: DispatchWorkItem?
    private var periodicCheckTimer: Timer?
    private var taskRunning = false
    private var currentVersionNative: Version = "0.0.0"
    private var lastNotifiedStatPercent = 0

    private var initialized = false
    private var observersInstalled = false

    public override static func requiresMainQueueSetup() -> Bool {
        return false
    }

    public override func supportedEvents() -> [String]! {
        return [
            "download",
            "noNeedUpdate",
            "updateAvailable",
            "downloadComplete",
            "breakingAvailable",
            "majorAvailable",
            "updateFailed",
            "downloadFailed",
            "appReloaded",
            "appReady",
            "channelPrivate",
            "onFlexibleUpdateStateChange"
        ]
    }

    deinit {
        removeObservers()
        periodicCheckTimer?.invalidate()
    }

    @objc public static func getBundle() -> URL {
        let bundlePath = UserDefaults.standard.string(forKey: "serverBasePath") ?? ""
        if !bundlePath.isEmpty {
            let baseURL = URL(fileURLWithPath: bundlePath)

            let preferred = [
                baseURL.appendingPathComponent("index.ios.bundle"),
                baseURL.appendingPathComponent("main.jsbundle")
            ]
            for candidate in preferred where FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }

            if let discovered = findBundleFile(in: baseURL) {
                return discovered
            }
        }

        return Bundle.main.url(forResource: "main", withExtension: "jsbundle")!
    }

    private static func findBundleFile(in folder: URL) -> URL? {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: folder,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return nil
        }

        for file in files {
            if file.pathExtension == "bundle" || file.pathExtension == "jsbundle" {
                return file
            }
        }

        for file in files where file.isDirectory {
            if let nested = findBundleFile(in: file) {
                return nested
            }
        }

        return nil
    }

    @objc(initialize:resolver:rejecter:)
    public func initialize(
        _ config: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        let deviceID = (UserDefaults.standard.string(forKey: "appUUID") ?? UUID().uuidString).lowercased()
        UserDefaults.standard.set(deviceID, forKey: "appUUID")

        implementation.deviceID = deviceID
        implementation.PLUGIN_VERSION = pluginVersion
        implementation.notifyDownloadRaw = { [weak self] id, percent, ignore in
            self?.notifyDownload(id: id, percent: percent, ignoreMultipleOfTen: ignore)
        }

        allowModifyAppId = boolValue(config, key: "allowModifyAppId", defaultValue: false)
        allowManualBundleError = boolValue(config, key: "allowManualBundleError", defaultValue: false)
        persistCustomId = boolValue(config, key: "persistCustomId", defaultValue: false)
        persistModifyUrl = boolValue(config, key: "persistModifyUrl", defaultValue: false)
        allowSetDefaultChannel = boolValue(config, key: "allowSetDefaultChannel", defaultValue: true)
        shakeMenuEnabled = boolValue(config, key: "shakeMenu", defaultValue: false)
        shakeChannelSelectorEnabled = boolValue(config, key: "allowShakeChannelSelector", defaultValue: false)

        updateUrl = stringValue(config, key: "updateUrl", defaultValue: ReactNativeUpdaterModule.updateUrlDefault)
        statsUrl = stringValue(config, key: "statsUrl", defaultValue: ReactNativeUpdaterModule.statsUrlDefault)
        channelUrl = stringValue(config, key: "channelUrl", defaultValue: ReactNativeUpdaterModule.channelUrlDefault)
        configDefaultChannel = stringValue(config, key: "defaultChannel", defaultValue: "")

        if persistModifyUrl {
            updateUrl = UserDefaults.standard.string(forKey: ReactNativeUpdaterModule.updateUrlDefaultsKey) ?? updateUrl
            statsUrl = UserDefaults.standard.string(forKey: ReactNativeUpdaterModule.statsUrlDefaultsKey) ?? statsUrl
            channelUrl = UserDefaults.standard.string(forKey: ReactNativeUpdaterModule.channelUrlDefaultsKey) ?? channelUrl
            implementation.defaultChannel = UserDefaults.standard.string(forKey: ReactNativeUpdaterModule.defaultChannelDefaultsKey) ?? configDefaultChannel
        } else {
            implementation.defaultChannel = configDefaultChannel
        }

        autoUpdate = boolValue(config, key: "autoUpdate", defaultValue: false)
        autoDeleteFailed = boolValue(config, key: "autoDeleteFailed", defaultValue: true)
        autoDeletePrevious = boolValue(config, key: "autoDeletePrevious", defaultValue: true)
        appReadyTimeout = intValue(config, key: "appReadyTimeout", defaultValue: 10000)

        periodCheckDelay = intValue(config, key: "periodCheckDelay", defaultValue: 0)
        if periodCheckDelay > 0 && periodCheckDelay < 600 {
            periodCheckDelay = 600
        }

        implementation.timeout = Double(intValue(config, key: "responseTimeout", defaultValue: 20))
        implementation.statsUrl = statsUrl
        implementation.channelUrl = channelUrl
        implementation.publicKey = stringValue(config, key: "publicKey", defaultValue: "")
        implementation.directUpdate = boolValue(config, key: "directUpdate", defaultValue: false)

        if persistCustomId {
            implementation.customId = UserDefaults.standard.string(forKey: ReactNativeUpdaterModule.customIdDefaultsKey) ?? ""
        }

        let appId = stringValue(config, key: "appId", defaultValue: Bundle.main.bundleIdentifier ?? "")
        if appId.isEmpty {
            reject("INIT_ERROR", "appId is missing", nil)
            return
        }
        implementation.appId = appId

        let configuredVersion = stringValue(config, key: "version", defaultValue: Bundle.main.versionName ?? "0.0.0")
        implementation.versionBuild = configuredVersion
        do {
            currentVersionNative = try Version(configuredVersion)
        } catch {
            currentVersionNative = "0.0.0"
        }

        implementation.autoReset()

        installObserversIfNeeded()
        startPeriodicCheckIfNeeded()

        initialized = true
        appMovedToForegroundInternal()
        resolve("Plugin initialized")
    }

    @objc(setUpdateUrl:resolver:rejecter:)
    public func setUpdateUrl(
        _ url: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setUpdateUrl") else { return }
        updateUrl = url
        if persistModifyUrl {
            UserDefaults.standard.set(url, forKey: ReactNativeUpdaterModule.updateUrlDefaultsKey)
            UserDefaults.standard.synchronize()
        }
        resolve(nil)
    }

    @objc(setStatsUrl:resolver:rejecter:)
    public func setStatsUrl(
        _ url: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setStatsUrl") else { return }
        statsUrl = url
        implementation.statsUrl = url
        if persistModifyUrl {
            UserDefaults.standard.set(url, forKey: ReactNativeUpdaterModule.statsUrlDefaultsKey)
            UserDefaults.standard.synchronize()
        }
        resolve(nil)
    }

    @objc(setChannelUrl:resolver:rejecter:)
    public func setChannelUrl(
        _ url: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setChannelUrl") else { return }
        channelUrl = url
        implementation.channelUrl = url
        if persistModifyUrl {
            UserDefaults.standard.set(url, forKey: ReactNativeUpdaterModule.channelUrlDefaultsKey)
            UserDefaults.standard.synchronize()
        }
        resolve(nil)
    }

    @objc(getBuiltinVersion:rejecter:)
    public func getBuiltinVersion(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "getBuiltinVersion") else { return }
        resolve(implementation.versionBuild)
    }

    @objc(getDeviceId:rejecter:)
    public func getDeviceId(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "getDeviceId") else { return }
        resolve(implementation.deviceID)
    }

    @objc(getPluginVersion:rejecter:)
    public func getPluginVersion(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter _: @escaping RCTPromiseRejectBlock
    ) {
        resolve(pluginVersion)
    }

    @objc(setCustomId:resolver:rejecter:)
    public func setCustomId(
        _ customId: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setCustomId") else { return }
        implementation.customId = customId
        if persistCustomId {
            if customId.isEmpty {
                UserDefaults.standard.removeObject(forKey: ReactNativeUpdaterModule.customIdDefaultsKey)
            } else {
                UserDefaults.standard.set(customId, forKey: ReactNativeUpdaterModule.customIdDefaultsKey)
            }
            UserDefaults.standard.synchronize()
        }
        resolve(nil)
    }

    @objc(download:resolver:rejecter:)
    public func download(
        _ options: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "download") else { return }

        guard let urlString = options["url"] as? String, let url = URL(string: urlString) else {
            reject("DOWNLOAD_ERROR", "Download called without valid url", nil)
            return
        }

        guard let version = options["version"] as? String, !version.isEmpty else {
            reject("DOWNLOAD_ERROR", "Download called without version", nil)
            return
        }

        let sessionKey = options["sessionKey"] as? String ?? ""
        var checksum = options["checksum"] as? String ?? ""

        DispatchQueue.global(qos: .background).async {
            do {
                let bundle = try self.implementation.download(url: url, version: version, sessionKey: sessionKey)

                if !checksum.isEmpty || !self.implementation.publicKey.isEmpty {
                    checksum = try CryptoCipherV2.decryptChecksum(checksum: checksum, publicKey: self.implementation.publicKey)
                    if bundle.getChecksum() != checksum {
                        _ = self.implementation.delete(id: bundle.getId())
                        self.emit("downloadFailed", ["version": version])
                        reject("DOWNLOAD_ERROR", "Checksum mismatch", nil)
                        return
                    }
                }

                self.emit("updateAvailable", ["bundle": bundle.toJSON()])
                resolve(bundle.toJSON())
            } catch {
                self.emit("downloadFailed", ["version": version])
                self.implementation.sendStats(action: "download_fail", versionName: version)
                reject("DOWNLOAD_ERROR", error.localizedDescription, error)
            }
        }
    }

    @objc(reload:rejecter:)
    public func reload(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "reload") else { return }
        triggerReload(reason: "reload")
        resolve(nil)
    }

    @objc(next:resolver:rejecter:)
    public func next(
        _ id: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "next") else { return }
        if !implementation.setNextBundle(next: id) {
            reject("SET_NEXT_ERROR", "Set next version failed for id \(id)", nil)
            return
        }
        resolve(implementation.getBundleInfo(id: id).toJSON())
    }

    @objc(set:resolver:rejecter:)
    public func set(
        _ id: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "set") else { return }
        guard implementation.set(id: id) else {
            reject("SET_ERROR", "Set active bundle failed for id \(id)", nil)
            return
        }
        triggerReload(reason: "set")
        resolve(nil)
    }

    @objc(delete:resolver:rejecter:)
    public func delete(
        _ id: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "delete") else { return }
        if implementation.delete(id: id) {
            resolve(nil)
        } else {
            reject("DELETE_ERROR", "Delete failed for id \(id)", nil)
        }
    }

    @objc(setBundleError:resolver:rejecter:)
    public func setBundleError(
        _ id: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setBundleError") else { return }
        if !allowManualBundleError {
            reject("SET_BUNDLE_ERROR_FORBIDDEN", "setBundleError not allowed. Set allowManualBundleError to true in config.", nil)
            return
        }

        let bundle = implementation.getBundleInfo(id: id)
        if bundle.isUnknown() {
            reject("SET_BUNDLE_ERROR_UNKNOWN", "Bundle \(id) does not exist", nil)
            return
        }
        if bundle.isBuiltin() {
            reject("SET_BUNDLE_ERROR_BUILTIN", "Cannot set builtin bundle to error state", nil)
            return
        }

        implementation.setError(bundle: bundle)
        resolve(["bundle": implementation.getBundleInfo(id: id).toJSON()])
    }

    @objc(list:rejecter:)
    public func list(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "list") else { return }
        let bundles = implementation.list().map { $0.toJSON() }
        resolve(["bundles": bundles])
    }

    @objc(getLatest:resolver:rejecter:)
    public func getLatest(
        _ channel: NSString?,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "getLatest") else { return }
        guard let url = URL(string: updateUrl) else {
            reject("GET_LATEST_ERROR", "Invalid updateUrl", nil)
            return
        }

        DispatchQueue.global(qos: .background).async {
            let latest = self.implementation.getLatest(url: url, channel: channel as String?)
            resolve(latest.toDict())
        }
    }

    @objc(unsetChannel:resolver:rejecter:)
    public func unsetChannel(
        _ triggerAutoUpdate: Bool,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "unsetChannel") else { return }
        UserDefaults.standard.removeObject(forKey: ReactNativeUpdaterModule.defaultChannelDefaultsKey)
        UserDefaults.standard.synchronize()
        implementation.defaultChannel = configDefaultChannel

        if _isAutoUpdateEnabled() && triggerAutoUpdate {
            backgroundDownload()
        }

        resolve([
            "status": "ok",
            "message": "Channel override removed"
        ])
    }

    @objc(setChannel:triggerAutoUpdate:resolver:rejecter:)
    public func setChannel(
        _ channel: String,
        triggerAutoUpdate: Bool,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setChannel") else { return }
        if !allowSetDefaultChannel {
            reject("SETCHANNEL_FAILED", "setChannel is disabled by configuration", nil)
            return
        }

        let response = implementation.setChannel(channel: channel)
        if !response.error.isEmpty {
            if response.error.contains("cannot_update_via_private_channel") || response.error.contains("channel_self_set_not_allowed") {
                emit("channelPrivate", ["channel": channel, "message": response.error])
            }
            reject("SETCHANNEL_FAILED", response.error, nil)
            return
        }

        implementation.defaultChannel = channel
        UserDefaults.standard.set(channel, forKey: ReactNativeUpdaterModule.defaultChannelDefaultsKey)
        UserDefaults.standard.synchronize()

        if _isAutoUpdateEnabled() && triggerAutoUpdate {
            backgroundDownload()
        }
        resolve(response.toDict())
    }

    @objc(getChannel:rejecter:)
    public func getChannel(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "getChannel") else { return }
        let response = implementation.getChannel()
        resolve(response.toDict())
    }

    @objc(listChannels:rejecter:)
    public func listChannels(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "listChannels") else { return }
        let response = implementation.listChannels()
        if !response.error.isEmpty {
            reject("LISTCHANNELS_FAILED", response.error, nil)
            return
        }
        resolve(response.toDict())
    }

    @objc(reset:resolver:rejecter:)
    public func reset(
        _ toLastSuccessful: Bool,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "reset") else { return }
        guard _reset(toLastSuccessful: toLastSuccessful) else {
            reject("RESET_ERROR", "Reset failed", nil)
            return
        }
        triggerReload(reason: "reset")
        resolve(nil)
    }

    @objc(current:rejecter:)
    public func current(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "current") else { return }
        resolve([
            "bundle": implementation.getCurrentBundle().toJSON(),
            "native": currentVersionNative.description
        ])
    }

    @objc(notifyAppReady:rejecter:)
    public func notifyAppReady(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "notifyAppReady") else { return }
        appReadyCheck?.cancel()
        appReadyCheck = nil

        let bundle = implementation.getCurrentBundle()
        if !bundle.isBuiltin() {
            implementation.setSuccess(bundle: bundle, autoDeletePrevious: autoDeletePrevious)
        }
        sendReadyToJs(current: implementation.getCurrentBundle(), status: "ready")
        resolve(nil)
    }

    @objc(setMultiDelay:resolver:rejecter:)
    public func setMultiDelay(
        _ options: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setMultiDelay") else { return }
        guard let delayConditions = options["delayConditions"] as? [NSDictionary] else {
            reject("SET_DELAY_ERROR", "setMultiDelay called without delayConditions", nil)
            return
        }

        let normalized: [[String: String]] = delayConditions.compactMap { condition in
            guard let kind = condition["kind"] as? String else {
                return nil
            }
            let value = condition["value"] as? String ?? ""
            return ["kind": kind, "value": value]
        }

        do {
            let data = try JSONSerialization.data(withJSONObject: normalized)
            let json = String(data: data, encoding: .utf8) ?? "[]"
            UserDefaults.standard.set(json, forKey: ReactNativeUpdaterModule.delayConditionPreferences)
            UserDefaults.standard.synchronize()
            resolve(nil)
        } catch {
            reject("SET_DELAY_ERROR", error.localizedDescription, error)
        }
    }

    @objc(cancelDelay:rejecter:)
    public func cancelDelay(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "cancelDelay") else { return }
        _cancelDelay(source: "cancelDelay")
        resolve(nil)
    }

    @objc(isAutoUpdateEnabled:rejecter:)
    public func isAutoUpdateEnabled(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter _: @escaping RCTPromiseRejectBlock
    ) {
        resolve(["enabled": _isAutoUpdateEnabled()])
    }

    @objc(isAutoUpdateAvailable:rejecter:)
    public func isAutoUpdateAvailable(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter _: @escaping RCTPromiseRejectBlock
    ) {
        resolve(["available": !updateUrl.isEmpty])
    }

    @objc(compareVersions:versionB:resolver:rejecter:)
    public func compareVersions(
        _ versionA: String,
        versionB: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "compareVersions") else { return }
        do {
            let vA = try Version(versionA)
            let vB = try Version(versionB)
            if vA > vB {
                resolve(1)
            } else if vA < vB {
                resolve(-1)
            } else {
                resolve(0)
            }
        } catch {
            reject("COMPARE_VERSION_ERROR", error.localizedDescription, error)
        }
    }

    @objc(checkForUpdate:rejecter:)
    public func checkForUpdate(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "checkForUpdate") else { return }
        backgroundDownload()
        resolve("Check for update started")
    }

    @objc(appMovedToForeground:rejecter:)
    public func appMovedToForeground(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "appMovedToForeground") else { return }
        appMovedToForegroundInternal()
        resolve(nil)
    }

    @objc(appMovedToBackground:rejecter:)
    public func appMovedToBackground(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "appMovedToBackground") else { return }
        appMovedToBackgroundInternal()
        resolve(nil)
    }

    @objc(getNextBundle:rejecter:)
    public func getNextBundle(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "getNextBundle") else { return }
        guard let bundle = implementation.getNextBundle(), !bundle.isUnknown() else {
            resolve(nil)
            return
        }
        resolve(bundle.toJSON())
    }

    @objc(getFailedUpdate:rejecter:)
    public func getFailedUpdate(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "getFailedUpdate") else { return }
        guard let bundle = readLastFailedBundle(), !bundle.isUnknown() else {
            resolve(nil)
            return
        }
        persistLastFailedBundle(nil)
        resolve(["bundle": bundle.toJSON()])
    }

    @objc(setShakeMenu:resolver:rejecter:)
    public func setShakeMenu(
        _ enabled: Bool,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setShakeMenu") else { return }
        shakeMenuEnabled = enabled
        resolve(nil)
    }

    @objc(isShakeMenuEnabled:rejecter:)
    public func isShakeMenuEnabled(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter _: @escaping RCTPromiseRejectBlock
    ) {
        resolve(shakeMenuEnabled)
    }

    @objc(setShakeChannelSelector:resolver:rejecter:)
    public func setShakeChannelSelector(
        _ enabled: Bool,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setShakeChannelSelector") else { return }
        shakeChannelSelectorEnabled = enabled
        resolve(nil)
    }

    @objc(isShakeChannelSelectorEnabled:rejecter:)
    public func isShakeChannelSelectorEnabled(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter _: @escaping RCTPromiseRejectBlock
    ) {
        resolve(shakeChannelSelectorEnabled)
    }

    @objc(getAppId:rejecter:)
    public func getAppId(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "getAppId") else { return }
        resolve(implementation.appId)
    }

    @objc(setAppId:resolver:rejecter:)
    public func setAppId(
        _ appId: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "setAppId") else { return }
        if !allowModifyAppId {
            reject("SET_APP_ID_FORBIDDEN", "setAppId not allowed. Set allowModifyAppId in config to true.", nil)
            return
        }
        if appId.isEmpty {
            reject("SET_APP_ID_INVALID", "setAppId called without appId", nil)
            return
        }
        implementation.appId = appId
        resolve(nil)
    }

    @objc(getAppUpdateInfo:resolver:rejecter:)
    public func getAppUpdateInfo(
        _ country: NSString?,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "getAppUpdateInfo") else { return }
        let countryCode = (country as String?) ?? "US"
        let bundleId = implementation.appId

        DispatchQueue.global(qos: .background).async {
            let urlString = "https://itunes.apple.com/lookup?bundleId=\(bundleId)&country=\(countryCode)"
            guard let url = URL(string: urlString) else {
                reject("GET_APP_UPDATE_INFO_ERROR", "Invalid URL for App Store lookup", nil)
                return
            }

            URLSession.shared.dataTask(with: url) { data, _, error in
                if let error {
                    reject("GET_APP_UPDATE_INFO_ERROR", "App Store lookup failed: \(error.localizedDescription)", error)
                    return
                }

                guard let data else {
                    reject("GET_APP_UPDATE_INFO_ERROR", "No data received from App Store", nil)
                    return
                }

                do {
                    guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                          let resultCount = json["resultCount"] as? Int else {
                        reject("GET_APP_UPDATE_INFO_ERROR", "Invalid response from App Store", nil)
                        return
                    }

                    let currentVersionName = Bundle.main.versionName ?? "0.0.0"
                    let currentVersionCode = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"

                    var result: [String: Any] = [
                        "currentVersionName": currentVersionName,
                        "currentVersionCode": currentVersionCode,
                        "updateAvailability": 0
                    ]

                    if resultCount > 0,
                       let results = json["results"] as? [[String: Any]],
                       let appInfo = results.first {

                        let availableVersion = appInfo["version"] as? String
                        let releaseDate = appInfo["currentVersionReleaseDate"] as? String
                        let minimumOsVersion = appInfo["minimumOsVersion"] as? String

                        result["availableVersionName"] = availableVersion
                        result["availableVersionCode"] = availableVersion
                        result["availableVersionReleaseDate"] = releaseDate
                        result["minimumOsVersion"] = minimumOsVersion

                        if let availableVersion {
                            do {
                                let currentVer = try Version(currentVersionName)
                                let availableVer = try Version(availableVersion)
                                result["updateAvailability"] = availableVer > currentVer ? 2 : 1
                            } catch {
                                result["updateAvailability"] = availableVersion == currentVersionName ? 1 : 2
                            }
                        } else {
                            result["updateAvailability"] = 1
                        }

                        result["immediateUpdateAllowed"] = false
                        result["flexibleUpdateAllowed"] = false
                    } else {
                        result["updateAvailability"] = 1
                    }

                    resolve(result)
                } catch {
                    reject("GET_APP_UPDATE_INFO_ERROR", "Failed to parse App Store response: \(error.localizedDescription)", error)
                }
            }.resume()
        }
    }

    @objc(openAppStore:resolver:rejecter:)
    public func openAppStore(
        _ options: NSDictionary?,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard ensureInitialized(rejecter: reject, method: "openAppStore") else { return }
        if let appId = options?["appId"] as? String, !appId.isEmpty {
            guard let url = URL(string: "https://apps.apple.com/app/id\(appId)") else {
                reject("OPEN_APP_STORE_ERROR", "Invalid App Store URL", nil)
                return
            }
            DispatchQueue.main.async {
                UIApplication.shared.open(url) { success in
                    success ? resolve(nil) : reject("OPEN_APP_STORE_ERROR", "Failed to open App Store", nil)
                }
            }
            return
        }

        let bundleId = implementation.appId
        let lookupUrl = "https://itunes.apple.com/lookup?bundleId=\(bundleId)"
        DispatchQueue.global(qos: .background).async {
            guard let url = URL(string: lookupUrl) else {
                reject("OPEN_APP_STORE_ERROR", "Invalid lookup URL", nil)
                return
            }

            URLSession.shared.dataTask(with: url) { data, _, error in
                if let error {
                    reject("OPEN_APP_STORE_ERROR", "Failed to lookup app: \(error.localizedDescription)", error)
                    return
                }

                var storeURL: URL?
                if let data,
                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let results = json["results"] as? [[String: Any]],
                   let appInfo = results.first,
                   let trackId = appInfo["trackId"] as? Int {
                    storeURL = URL(string: "https://apps.apple.com/app/id\(trackId)")
                } else {
                    storeURL = URL(string: "https://apps.apple.com/app/\(bundleId)")
                }

                guard let finalURL = storeURL else {
                    reject("OPEN_APP_STORE_ERROR", "Failed to build App Store URL", nil)
                    return
                }

                DispatchQueue.main.async {
                    UIApplication.shared.open(finalURL) { success in
                        success ? resolve(nil) : reject("OPEN_APP_STORE_ERROR", "Failed to open App Store", nil)
                    }
                }
            }.resume()
        }
    }

    @objc(performImmediateUpdate:rejecter:)
    public func performImmediateUpdate(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        reject("NOT_SUPPORTED", "In-app updates are not supported on iOS. Use openAppStore() instead.", nil)
    }

    @objc(startFlexibleUpdate:rejecter:)
    public func startFlexibleUpdate(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        reject("NOT_SUPPORTED", "Flexible updates are not supported on iOS. Use openAppStore() instead.", nil)
    }

    @objc(completeFlexibleUpdate:rejecter:)
    public func completeFlexibleUpdate(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        reject("NOT_SUPPORTED", "Flexible updates are not supported on iOS.", nil)
    }

    private func _reset(toLastSuccessful: Bool) -> Bool {
        let fallback = implementation.getFallbackBundle()
        implementation.reset()

        if toLastSuccessful && !fallback.isBuiltin() {
            return implementation.set(bundle: fallback)
        }
        return true
    }

    private func triggerReload(reason: String) {
        NSLog("\(ReactNativeUpdaterCore.TAG) Triggering RN reload: \(reason)")
        emit("appReloaded", nil)

        DispatchQueue.main.async {
            if let bridge = self.bridge {
                bridge.reload()
            }
        }
    }

    private func notifyDownload(id: String, percent: Int, ignoreMultipleOfTen: Bool = false) {
        let bundle = implementation.getBundleInfo(id: id)
        emit("download", ["percent": percent, "bundle": bundle.toJSON()])

        if percent == 100 {
            emit("downloadComplete", ["bundle": bundle.toJSON()])
            implementation.sendStats(action: "download_complete", versionName: bundle.getVersionName())
            lastNotifiedStatPercent = 0
            return
        }

        let rounded = (percent / 10) * 10
        if ignoreMultipleOfTen || (rounded > lastNotifiedStatPercent && rounded % 10 == 0 && rounded > 0) {
            lastNotifiedStatPercent = rounded
            implementation.sendStats(action: "download_\(rounded)", versionName: bundle.getVersionName())
        }
    }

    private func checkAppReady() {
        appReadyCheck?.cancel()
        appReadyCheck = DispatchWorkItem { [weak self] in
            self?.checkRevert()
            self?.appReadyCheck = nil
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(appReadyTimeout), execute: appReadyCheck!)
    }

    private func checkRevert() {
        let current = implementation.getCurrentBundle()
        if current.isBuiltin() {
            return
        }

        if current.getStatus() != BundleStatus.SUCCESS.localizedString {
            emit("updateFailed", ["bundle": current.toJSON()])
            persistLastFailedBundle(current)
            implementation.sendStats(action: "update_fail", versionName: current.getVersionName())
            implementation.setError(bundle: current)
            _ = _reset(toLastSuccessful: true)
            if autoDeleteFailed {
                _ = implementation.delete(id: current.getId(), removeInfo: false)
            }
            triggerReload(reason: "revert_failed_bundle")
        }
    }

    private func backgroundDownload() {
        guard let url = URL(string: updateUrl) else {
            return
        }

        DispatchQueue.global(qos: .background).async {
            let response = self.implementation.getLatest(url: url, channel: nil)
            let current = self.implementation.getCurrentBundle()

            if response.major == true || response.breaking == true {
                self.emit("breakingAvailable", ["version": response.version])
                self.emit("majorAvailable", ["version": response.version])
            }

            if let error = response.error, !error.isEmpty {
                self.sendReadyToJs(current: current, status: response.message ?? error)
                return
            }

            if response.version == "builtin" {
                if self.implementation.directUpdate {
                    self.implementation.reset()
                    self.triggerReload(reason: "direct_update_builtin")
                } else {
                    _ = self.implementation.setNextBundle(next: BundleInfo.ID_BUILTIN)
                }
                self.sendReadyToJs(current: current, status: "builtin")
                return
            }

            let latestVersionName = response.version
            if latestVersionName.isEmpty || latestVersionName == current.getVersionName() {
                self.emit("noNeedUpdate", ["bundle": current.toJSON()])
                self.sendReadyToJs(current: current, status: "up_to_date")
                return
            }

            do {
                let sessionKey = response.sessionKey ?? ""
                let nextBundle: BundleInfo

                if let existing = self.implementation.getBundleInfoByVersionName(version: latestVersionName),
                   !existing.isDeleted(),
                   !existing.isErrorStatus() {
                    nextBundle = existing
                } else if let manifest = response.manifest {
                    nextBundle = try self.implementation.downloadManifest(
                        manifest: manifest,
                        version: latestVersionName,
                        sessionKey: sessionKey
                    )
                } else {
                    guard let updateFileURL = URL(string: response.url) else {
                        self.emit("downloadFailed", ["version": latestVersionName])
                        self.sendReadyToJs(current: current, status: "invalid_url")
                        return
                    }
                    nextBundle = try self.implementation.download(
                        url: updateFileURL,
                        version: latestVersionName,
                        sessionKey: sessionKey
                    )
                }

                let checksum = response.checksum
                if !checksum.isEmpty,
                   response.manifest == nil {
                    let decrypted = try CryptoCipherV2.decryptChecksum(
                        checksum: checksum,
                        publicKey: self.implementation.publicKey
                    )
                    if nextBundle.getChecksum() != decrypted {
                        _ = self.implementation.delete(id: nextBundle.getId())
                        self.emit("downloadFailed", ["version": latestVersionName])
                        self.sendReadyToJs(current: current, status: "checksum_failed")
                        return
                    }
                }

                if self.implementation.directUpdate {
                    _ = self.implementation.set(bundle: nextBundle)
                    self.triggerReload(reason: "direct_update")
                } else {
                    _ = self.implementation.setNextBundle(next: nextBundle.getId())
                    self.emit("updateAvailable", ["bundle": nextBundle.toJSON()])
                }

                self.sendReadyToJs(current: current, status: "update_available")
            } catch {
                self.emit("downloadFailed", ["version": latestVersionName])
                self.implementation.sendStats(action: "download_fail", versionName: latestVersionName)
                self.sendReadyToJs(current: current, status: "download_failed")
            }
        }
    }

    private func appMovedToForegroundInternal() {
        let current = implementation.getCurrentBundle()
        implementation.sendStats(action: "app_moved_to_foreground", versionName: current.getVersionName())

        if let backgroundWork = backgroundWork, taskRunning {
            backgroundWork.cancel()
            taskRunning = false
        }

        _checkCancelDelay(killed: false)

        if _isAutoUpdateEnabled() {
            backgroundDownload()
        } else {
            sendReadyToJs(current: current, status: "disabled")
        }

        checkAppReady()
    }

    private func appMovedToBackgroundInternal() {
        let current = implementation.getCurrentBundle()
        implementation.sendStats(action: "app_moved_to_background", versionName: current.getVersionName())

        let delayConditions = readDelayConditions()
        let backgroundCondition = delayConditions.first(where: { $0.getKind() == "background" })
        if let value = backgroundCondition?.getValue() {
            taskRunning = true
            let interval = (Double(value) ?? 0.0) / 1000.0
            backgroundWork?.cancel()
            let work = DispatchWorkItem { [weak self] in
                self?.taskRunning = false
                self?._checkCancelDelay(killed: false)
                self?.installNext()
            }
            backgroundWork = work
            DispatchQueue.global(qos: .background).asyncAfter(deadline: .now() + interval, execute: work)
        } else {
            _checkCancelDelay(killed: false)
            installNext()
        }
    }

    private func installNext() {
        if hasDelayConditions() {
            return
        }

        let current = implementation.getCurrentBundle()
        guard let next = implementation.getNextBundle() else {
            return
        }

        if next.isErrorStatus() || next.getVersionName() == current.getVersionName() {
            return
        }

        if implementation.set(bundle: next) {
            _ = implementation.setNextBundle(next: nil)
            triggerReload(reason: "install_next")
        }
    }

    private func _checkCancelDelay(killed: Bool) {
        let delayConditions = readDelayConditions()
        for condition in delayConditions {
            let kind = condition.getKind()
            let value = condition.getValue()

            switch kind {
            case "background":
                if !killed {
                    _cancelDelay(source: "background")
                }
            case "kill":
                if killed {
                    _cancelDelay(source: "kill")
                    installNext()
                }
            case "nativeVersion":
                if let value, !value.isEmpty {
                    do {
                        let minimumVersion = try Version(value)
                        if currentVersionNative >= minimumVersion {
                            _cancelDelay(source: "nativeVersion")
                        }
                    } catch {
                        _cancelDelay(source: "nativeVersionParse")
                    }
                } else {
                    _cancelDelay(source: "nativeVersionMissing")
                }
            case "date":
                if let value, !value.isEmpty {
                    let formatter = ISO8601DateFormatter()
                    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
                    if let expireDate = formatter.date(from: value), expireDate < Date() {
                        _cancelDelay(source: "dateExpired")
                    }
                } else {
                    _cancelDelay(source: "dateMissing")
                }
            default:
                break
            }
        }
    }

    private func _cancelDelay(source: String) {
        UserDefaults.standard.removeObject(forKey: ReactNativeUpdaterModule.delayConditionPreferences)
        UserDefaults.standard.synchronize()
        NSLog("\(ReactNativeUpdaterCore.TAG) delay Canceled from \(source)")
    }

    private func persistLastFailedBundle(_ bundle: BundleInfo?) {
        if let bundle {
            UserDefaults.standard.set(bundle.toString(), forKey: ReactNativeUpdaterModule.lastFailedBundleDefaultsKey)
        } else {
            UserDefaults.standard.removeObject(forKey: ReactNativeUpdaterModule.lastFailedBundleDefaultsKey)
        }
        UserDefaults.standard.synchronize()
    }

    private func readLastFailedBundle() -> BundleInfo? {
        guard let raw = UserDefaults.standard.string(forKey: ReactNativeUpdaterModule.lastFailedBundleDefaultsKey) else {
            return nil
        }
        return try? JSONDecoder().decode(BundleInfo.self, from: Data(raw.utf8))
    }

    private func sendReadyToJs(current: BundleInfo, status: String) {
        emit("appReady", ["bundle": current.toJSON(), "status": status])
    }

    private func _isAutoUpdateEnabled() -> Bool {
        return autoUpdate && !updateUrl.isEmpty
    }

    private func ensureInitialized(rejecter reject: RCTPromiseRejectBlock, method: String) -> Bool {
        if !initialized {
            reject("NOT_INITIALIZED", "\(method) called before initialize()", nil)
            return false
        }
        return true
    }

    private func hasDelayConditions() -> Bool {
        return !readDelayConditions().isEmpty
    }

    private func readDelayConditions() -> [DelayCondition] {
        guard let raw = UserDefaults.standard.string(forKey: ReactNativeUpdaterModule.delayConditionPreferences),
              let data = raw.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [[String: String]] else {
            return []
        }

        return json.compactMap { item in
            guard let kind = item["kind"] else {
                return nil
            }
            return DelayCondition(kind: kind, value: item["value"])
        }
    }

    private func installObserversIfNeeded() {
        guard !observersInstalled else { return }
        observersInstalled = true

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(onWillTerminate),
            name: UIApplication.willTerminateNotification,
            object: nil
        )
    }

    private func removeObservers() {
        guard observersInstalled else { return }
        NotificationCenter.default.removeObserver(self)
        observersInstalled = false
    }

    @objc private func onWillEnterForeground() {
        if initialized {
            appMovedToForegroundInternal()
        }
    }

    @objc private func onDidEnterBackground() {
        if initialized {
            appMovedToBackgroundInternal()
        }
    }

    @objc private func onWillTerminate() {
        if initialized {
            _checkCancelDelay(killed: true)
        }
    }

    private func startPeriodicCheckIfNeeded() {
        periodicCheckTimer?.invalidate()
        periodicCheckTimer = nil

        guard periodCheckDelay > 0 else {
            return
        }

        periodicCheckTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(periodCheckDelay), repeats: true) { [weak self] _ in
            guard let self = self, self._isAutoUpdateEnabled() else {
                return
            }
            self.backgroundDownload()
        }
    }

    private func stringValue(_ config: NSDictionary, key: String, defaultValue: String) -> String {
        return config[key] as? String ?? defaultValue
    }

    private func boolValue(_ config: NSDictionary, key: String, defaultValue: Bool) -> Bool {
        if let value = config[key] as? Bool {
            return value
        }
        if let value = config[key] as? NSNumber {
            return value.boolValue
        }
        return defaultValue
    }

    private func intValue(_ config: NSDictionary, key: String, defaultValue: Int) -> Int {
        if let value = config[key] as? Int {
            return value
        }
        if let value = config[key] as? NSNumber {
            return value.intValue
        }
        return defaultValue
    }

    private func emit(_ event: String, _ payload: [String: Any]?) {
        DispatchQueue.main.async {
            self.sendEvent(withName: event, body: payload)
        }
    }
}
