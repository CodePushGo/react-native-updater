module.exports = {
  dependency: {
    platforms: {
      android: {
        packageImportPath:
          'import com.codepushgo.reactnativeupdater.ReactNativeUpdaterPackage;',
        packageInstance: 'new ReactNativeUpdaterPackage()',
      },
      ios: {},
    },
  },
};
