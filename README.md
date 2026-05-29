# TR70 Battery Monitor

TR70（Garmin Varia Radar）のバッテリーレベルをBLE経由で表示するAndroidアプリ

## 機能

- BLEデバイススキャン
- TR70（Varia Radar）の自動検出
- Service/Characteristicの調査・ログ表示
- バッテリーレベルの表示
- BLE接続管理

## 開発ステータス

### ✅ 完了
- [x] Androidプロジェクトのベース作成
- [x] BLEスキャン機能の実装
- [x] BLE接続と調査機能の実装
- [x] UI実装（バッテリー表示）

### ⏳ 未着手
- [ ] 省電力最適化
  - バッテリー取得完了後の即切断
  - スキャン間隔調整
  - Service UUIDフィルター追加

## ビルド方法

```bash
cd /Users/yoshi/dev/Arduino/tr70blcheck
./gradlew assembleDebug
```

## インストール方法

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. アプリを起動
2. 「スキャン開始」ボタンをタップ
3. TR70（Varia Radar）の電源をON
4. デバイスが見つかったら「接続」ボタンをタップ
5. ServiceとCharacteristicがログに表示されます
6. バッテリーレベルが表示されます

## TR70 BLE調査

このアプリは以下の情報を調査するために設計されました：

1. **Service UUID**
   - 標準Battery Service (0x180F)か？
   - カスタムGarmin Serviceか？

2. **Characteristic UUID**
   - Battery Level (0x2A19)か？
   - カスタムCharacteristicか？

3. **データ形式**
   - 百分率（0-100%）の1バイト値か？
   - その他の形式か？

調査結果はアプリ内のログで確認できます。

## 技術スタック

- Kotlin
- Android SDK (minSdk 26, targetSdk 35)
- Jetpack ViewModel
- Coroutines & Flow
- BLE (Bluetooth Low Energy)

## 参考プロジェクト

このプロジェクトは以下のプロジェクトを参考にしています：
- `/Users/yoshi/dev/Arduino/btclock/BTClockMob` - BLE実装パターン

## ライセンス

LICENSE ファイルを参照してください
