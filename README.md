# React Native Updater

OTA updater for React Native apps, powered by Capgo update endpoints.

## Install

```bash
bun add @codepush-go/react-native-updater
```

### iOS

```bash
cd ios
pod install
```

## Basic Usage

```ts
import { ReactNativeUpdater } from '@codepush-go/react-native-updater';

await ReactNativeUpdater.initialize({
  appId: 'com.example.app',
  autoUpdate: true,
  updateUrl: 'https://plugin.capgo.app/updates',
  statsUrl: 'https://plugin.capgo.app/stats',
  channelUrl: 'https://plugin.capgo.app/channel_self',
});

await ReactNativeUpdater.notifyAppReady();
```

## Manual Download + Activate

```ts
const bundle = await ReactNativeUpdater.download({
  url: 'https://example.com/update.zip',
  version: '1.2.3',
});

await ReactNativeUpdater.set({ id: bundle.id });
```

## Events

```ts
const handle = await ReactNativeUpdater.addListener('download', (event) => {
  console.log('progress', event.percent);
});

// later
await handle.remove();
```

## Native Host Integration

### Android (`MainApplication`)

```java
import com.codepushgo.reactnativeupdater.ReactNativeUpdater;

@Override
protected String getJSBundleFile() {
  String path = ReactNativeUpdater.getJSBundleFile(this.getApplicationContext());
  return path != null ? path : super.getJSBundleFile();
}
```

### iOS (`AppDelegate`)

```objc
#import "YourProject-Swift.h"

- (NSURL *)sourceURLForBridge:(RCTBridge *)bridge
{
  return [ReactNativeUpdater getBundle];
}
```

## API

Main methods:

- `initialize(config)`
- `notifyAppReady()`
- `download(options)`
- `set({ id })`
- `next({ id })`
- `checkForUpdate()`
- `getLatest(options?)`
- `current()`
- `list()`
- `reset(options?)`
- `addListener(eventName, callback)`

See `src/definitions.ts` for full types.
