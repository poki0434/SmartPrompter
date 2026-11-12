# 智能提词器（SmartPrompter）安卓原生源码工程

一款专为主播、医生、短视频创作者打造的专业提词器 Android 原生应用。

## 🌟 核心功能特色
1. **蓝牙耳机物理/触控按键遥控**：
   * 基于 Android `MediaSessionCompat`，支持 AirPods、华为、小米、索尼等各类蓝牙耳机。
   * 单击/双击捏合耳机（播放/暂停）：随时**开始或暂停**提词器滚动。
   * 切歌按键（上一首/下一首）：微调滚动速度。
2. **音量大小直接调节滚动速度**：
   * 拦截物理音量按键与监听 `android.media.VOLUME_CHANGED_ACTION` 广播。
   * 调大音量：提词速度实时加快 (+5 px/s)。
   * 调小音量：提词速度实时减慢 (-5 px/s)。
   * 拦截系统弹出的音量遮挡框，保证提词视野干净。
3. **专业提词全特性**：
   * 毫秒级无级平滑垂直滚动（10 ~ 200 px/s）。
   * 水平镜像翻转（支持专业分光镜/反射式提词架）。
   * 视线对齐参考红线（确保眼神始终聚焦镜头）。
   * 沉浸式纯黑全屏模式，自动保持屏幕常亮防息屏。

---

## 🛠️ 如何编译生成 APK 文件？

### 方式一：Android Studio 一键生成（最推荐）
1. 解压本压缩包，打开 **Android Studio**；
2. 选择 **Open** 打开本工程根目录；
3. 等待 Gradle 同步完成后，点击顶部菜单栏：
   `Build` ➔ `Build Bundle(s) / APK(s)` ➔ `Build APK(s)`；
4. 编译完成后，点击右下角弹出的 **locate**，即可得到 `app-debug.apk`；
5. 将 `app-debug.apk` 传到手机微信/QQ/数据线安装即可！

### 方式二：命令行编译
在工程根目录下执行：
```bash
# Mac / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```
生成的 APK 路径为：`app/build/outputs/apk/debug/app-debug.apk`。

### 方式三：GitHub 云端自动编译（免装 Android Studio）
1. 将此工程上传到您的 GitHub 仓库；
2. 工程已内置 `.github/workflows/build-apk.yml`；
3. GitHub Actions 会自动在云端完成编译，并在 Actions 页面提供已签名的 APK 直接下载！
