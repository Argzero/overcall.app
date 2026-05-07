# OverCall — Solana dApp Store listing

Everything in this directory is consumed by `@solana-mobile/dapp-publishing`
to mint the publisher / app / release NFTs and submit a listing to the
Solana Mobile dApp Store.

## Layout

```
dapp-store/
├── config.yaml               # listing metadata + asset paths
├── README.md                 # this file
└── assets/
    ├── icon.svg              # source vector (shooting-star-coin)
    ├── icon.png              # 512×512 raster, dApp Store icon
    ├── icon-1024.png         # 1024×1024 for Solana Mobile portal
    ├── banner.svg
    ├── banner.png            # 1200×630 hero banner / feature graphic
    └── screenshot-*.png      # TODO — capture from device once Settings
                              # tab is shipped (Task 31).
```

The same icons are also wired into the Android app at
`android/app/src/main/res/mipmap-*/ic_launcher.png` so the home-screen
icon matches the listing.

## Re-rasterizing assets

```sh
brew install librsvg

# 1024 / 512 / 192 / 144 / 96 / 72 / 48 — Android + listing
rsvg-convert assets/icon.svg -w 1024 -h 1024 -o assets/icon-1024.png
rsvg-convert assets/icon.svg -w 512  -h 512  -o assets/icon.png
rsvg-convert assets/banner.svg -w 1200 -h 630 -o assets/banner.png

# Android launcher icon densities
ANDROID=../android/app/src/main/res
rsvg-convert assets/icon.svg -w 192 -h 192 -o $ANDROID/mipmap-xxxhdpi/ic_launcher.png
rsvg-convert assets/icon.svg -w 144 -h 144 -o $ANDROID/mipmap-xxhdpi/ic_launcher.png
rsvg-convert assets/icon.svg -w  96 -h  96 -o $ANDROID/mipmap-xhdpi/ic_launcher.png
rsvg-convert assets/icon.svg -w  72 -h  72 -o $ANDROID/mipmap-hdpi/ic_launcher.png
rsvg-convert assets/icon.svg -w  48 -h  48 -o $ANDROID/mipmap-mdpi/ic_launcher.png
```

## Publish flow (mainnet)

Pre-flight:
- A Solana wallet with ~0.5 SOL on **mainnet** (publisher + app + release
  NFTs each cost ~0.01–0.05 SOL in rent).
- Seeker-flavor signed release APK at
  `../android/app/build/outputs/apk/seeker/release/app-seeker-release.apk`.
- Privacy policy live at the URL in `config.yaml` (Solana Mobile resolves
  it during review).

Steps:

```sh
# from repo root
cd dapp-store

# One-time per publisher account
npx --yes @solana-mobile/dapp-publishing init
npx @solana-mobile/dapp-publishing create publisher

# One-time per app
npx @solana-mobile/dapp-publishing create app

# Each release
cd ../android && ./gradlew assembleSeekerRelease
cd ../dapp-store
npx @solana-mobile/dapp-publishing create release

# Submit for review
npx @solana-mobile/dapp-publishing publish submit
```

## What's still TODO before submitting

- [ ] Real screenshots from a Seeker device (Settings tab + bubble overlay)
- [ ] Privacy policy hosted at `https://overcall.app/privacy`
- [ ] Website at `https://overcall.app`
- [ ] Real domain — `overcall.app` is a placeholder
- [ ] `support@overcall.app` mailbox or alternative contact
- [ ] Signed release APK with a stable upload key (Play Store style)
