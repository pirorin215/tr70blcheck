# TR70 Battery Monitor - 開発ドキュメント

## プロジェクト概要

TR70（Garmin Varia Radar）にBLE接続し、バッテリーレベルを表示するAndroidアプリ

## 目的

1. TR70のBLE仕様調査（Service UUID、Characteristic UUID、データ形式）
2. バッテリーレベルの取得と表示
3. 省電力での動作実現

## プロジェクト構成

```
/Users/yoshi/dev/Arduino/tr70blcheck/
├── app/
│   └── src/main/
│       ├── java/com/pirorin215/tr70batterymonitor/
│       │   ├── MainActivity.kt              # メインアクティビティ
│       │   ├── service/
│       │   │   ├── BleScanService.kt       # BLEスキャンサービス
│       │   │   └── BleScanServiceManager.kt # サービス通信
│       │   ├── data/
│       │   │   └── BleRepository.kt        # BLEリポジトリ
│       │   └── viewModel/
│       │       └── MainViewModel.kt        # ViewModel
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml       # メインレイアウト
│           └── values/                     # リソース
└── build.gradle.kts                        # ビルド設定
```

## 主要コンポーネント

### 1. BleScanService (ForegroundService)
- バックグラウンドでBLEデバイスをスキャン
- TR70を自動検出
- 現状：全デバイスをスキャンしてVaria/Garmin/Radarを含むデバイスを探す

### 2. BleRepository
- BLE接続管理（GATTクライアント）
- Service/Characteristic探索
- バッテリーレベル読み取り
- すべてのServiceとCharacteristicをログ出力

### 3. MainViewModel
- 接続状態管理
- UIステート管理
- ログ表示

### 4. MainActivity
- UI表示
- ボタン操作（スキャン開始、接続、切断）
- ログ表示

## BLE仕様調査（進行中）

### 現在の調査項目
1. ✅ BLEスキャン機能
2. ✅ Service探索機能
3. ✅ Characteristic探索機能
4. ✅ バッテリーレベル読み取り（標準Battery Service）
5. ⏳ TR70固有のService/Characteristic特定

### 次のステップ
1. TR70を実機でスキャン
2. Service UUIDとCharacteristic UUIDを特定
3. バッテリーデータの形式を確認

## 省電力設計（未実装）

以下の機能を実装予定：

1. **接続後即切断**
   - バッテリー取得完了したら即切断
   - 次回取得時に再接続

2. **スキャン間隔調整**
   - 画面ON: 1分間隔
   - 画面OFF: 10分間隔

3. **Service UUIDフィルター**
   - 特定したService UUIDのみをスキャン

## 参考プロジェクト

`/Users/yoshi/dev/Arduino/btclock/BTClockMob` の以下のファイルを参考：
- `service/BleScanService.kt`
- `viewModel/BleConnectionManager.kt`
- `data/BleRepository.kt`

## ビルドコマンド

```bash
cd /Users/yoshi/dev/Arduino/tr70blcheck
./gradlew assembleDebug
```

## インストールコマンド

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 開発中の注意点

1. まずはTR70のBLE情報を調査することが最優先
2. 標準Battery Service (0x180F) が使えるか確認
3. カスタムServiceの場合はUUIDを特定
4. バッテリーデータの読み取り形式を確認

## 将来の機能

- 省電力最適化
- バッテリー低下通知
- 過去のバッテリーレベル記録
- グラフ表示
