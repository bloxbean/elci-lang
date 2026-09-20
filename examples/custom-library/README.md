# Custom on-chain library

From the repository root:

```sh
mkdir -p examples/custom-library/build
jar --create --file examples/custom-library/build/pricing.jar -C examples/custom-library/java .
./gradlew installDist
build/install/elci/bin/elci eval examples/custom-library
```

The result is integer `42`. The native CLI accepts the same project and JAR:

```sh
build/native/elci eval examples/custom-library
```

The JAR intentionally contains source only. Elci reads Java sources under
`META-INF/plutus-sources/`, and Julc compiles the annotated library to PIR.
It does not load or execute JVM classes from the JAR. A normal compiled library
JAR works too, provided it bundles those source resources and all required
custom type/library dependency sources.

`Pricing.Quote` (or exposed `Quote`) is a nominal Java type. Its fields can be
read, and values are created by the Java factory `Pricing.quote`. A structurally
similar Elci record is not interchangeable with it.
