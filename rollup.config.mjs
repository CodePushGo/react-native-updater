export default {
  input: 'dist/esm/index.js',
  output: [
    {
      file: 'dist/index.js',
      format: 'cjs',
      sourcemap: true,
      inlineDynamicImports: true,
    },
    {
      file: 'dist/index.mjs',
      format: 'esm',
      sourcemap: true,
      inlineDynamicImports: true,
    },
  ],
  external: ['react', 'react-native'],
};
