# A library written in Elci

`lib/Finance/Pricing.elm` exports `totalWithFee` and keeps `subtotal` private.
The application in `src/Main.elm` imports the library normally. Both directories
are listed in `elci.toml`; no Java module mapping or JAR is required.

From the repository root:

```sh
./gradlew installDist
build/install/elci/bin/elci eval examples/elci-library
```

The result is integer `105`. The native CLI can run the same project:

```sh
build/native/elci eval examples/elci-library
```

Share the `lib` directory as source (for example in a Git repository), then add
its path to the consuming project's `source-directories`. Module paths are
relative to that source root: `Finance.Pricing` must be `Finance/Pricing.elm`.
