{
  description = "home-launcher: minimal Android home screen";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # The Nix store is read-only, so Gradle can't download SDK parts itself.
      # Keep these in sync with compileSdk (app/build.gradle.kts) and the build-tools
      # version the Android Gradle plugin asks for.
      platformVersion = "35";
      buildToolsVersion = "34.0.0";

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ platformVersion ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      };
      androidSdk = androidComposition.androidsdk;
      sdkRoot = "${androidSdk}/libexec/android-sdk";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ androidSdk pkgs.jdk17 ];

        ANDROID_HOME = sdkRoot;
        ANDROID_SDK_ROOT = sdkRoot;
        JAVA_HOME = pkgs.jdk17.home;
        # Use the Nix-provided aapt2 instead of the generic one AGP downloads from Maven.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";

        shellHook = ''
          echo "Android SDK: $ANDROID_HOME"
        '';
      };
    };
}
