# TimeKeeper

简易的安卓语音时间提示 App。按设定的分钟间隔，用语音播报当前系统时间（如"9点5分"、"12点整"），后台稳定运行，无需网络（离线 TTS）。

## 功能特性

- **定时语音报时**：可设定 1–240 分钟的播报间隔，默认 5 分钟
- **整点/非整点播报**：整点播报"X点整"，非整点播报"X点Y分"
- **报时倒计时**：开始报时后按钮上方显示倒计时（如 00:59 → 00:00），到 0 触发播报并循环
- **锁屏媒体控制**：采用 MediaSession + 媒体通知（与音乐播放器同机制），锁屏界面显示报时状态卡片，可直接点击"开始报时"/"停止报时"按钮
- **媒体级后台保活**：报时期间循环播放静音音频维持媒体活跃状态，避免系统进入 Doze/冻结后台导致锁屏后停止；另有前台服务（specialUse 类型防 Android 14+ 校验崩溃）+ 唤醒锁 + 电池优化白名单
- **音量智能压低**：播报时通过 Audio Focus 机制自动压低音乐等后台音频音量，播报结束自动恢复
- **离线 TTS**：使用系统自带 TextToSpeech，无需联网；语音包不匹配时自动回退，初始化失败会提示
- **极简界面**：主界面仅间隔输入 + 报时倒计时 + 开始/停止报时按钮

系统要求：Android 8.0（API 26）及以上。

## 工程结构

```
TimeKeeper/
├── .github/workflows/build_apk.yml   # GitHub Actions 手动编译 APK
├── settings.gradle.kts               # 仓库与模块声明
├── build.gradle.kts                  # AGP 8.5.2 / Kotlin 1.9.24
├── gradle.properties                 # AndroidX 开关、JVM 参数
└── app/
    ├── build.gradle.kts              # minSdk 26 / targetSdk 34
    └── src/main/
        ├── AndroidManifest.xml       # 权限与前台服务声明
        ├── java/com/timekeeper/app/
        │   ├── MainActivity.kt       # 主界面（间隔输入、开始/停止按钮）
        │   ├── TimeKeeperService.kt  # 前台服务（计时、播报、常驻通知）
        │   └── TtsManager.kt         # TTS 管理（离线合成、Audio Focus）
        └── res/                      # 布局、字符串、主题、图标
```

## 使用 GitHub Actions 手动编译 APK

无需在本地安装 Android Studio / Gradle / Android SDK，全部在云端完成。

### 第一步：创建 GitHub 仓库

1. 登录 GitHub，点击右上角 **+** → **New repository**
2. 仓库名填 `TimeKeeper`，选择 Public 或 Private 均可
3. 不要勾选 "Add a README file" 等初始化选项，点击 **Create repository**

### 第二步：推送代码到 GitHub

在 `D:\ai\TimeKeeper` 目录打开 Git Bash，执行：

```bash
git init
git add .
git commit -m "init: TimeKeeper v1.0.0"
git branch -M main
git remote add origin https://github.com/<你的用户名>/TimeKeeper.git
git push -u origin main
```

> 注意：必须推送到默认分支（main），workflow 文件只有在默认分支上，Actions 页面才会出现手动触发按钮。

### 第三步：手动触发编译

1. 打开 GitHub 仓库页面，点击顶部 **Actions** 页签
2. 左侧列表选择 **Build APK**
3. 右侧点击 **Run workflow** 按钮
4. 保持默认分支 `main` 不变，点击绿色 **Run workflow** 按钮开始编译

### 第四步：下载 APK

1. 在 Actions 运行列表中点击本次触发的那条运行记录
2. 等待编译完成（首次运行需下载依赖，约 5–10 分钟），出现绿色对勾 ✔
3. 页面下方 **Artifacts** 区域，点击下载 **timekeeper-apk**
4. 解压得到 `timekeeper_1.0.0_debug.apk`
5. 将 APK 传到手机（微信/数据线/网盘均可），安装时允许"安装未知来源应用"即可

### 重新编译（更新版本后）

修改代码 → 提交推送 → 重复"第三步"手动触发即可。如需修改版本号，编辑 `app/build.gradle.kts` 中的 `versionName`（格式 x.y.z）。

## App 使用说明

1. 安装并打开 TimeKeeper
2. 在输入框设定播报间隔分钟数（默认 5）
3. 点击 **开始报时**：立即播报一次当前时间，按钮上方出现倒计时（从设定间隔递减到 0，循环）；通知栏与锁屏出现媒体控制卡片
4. 点击 **停止报时**：暂停播报与倒计时（服务保留，通知与锁屏卡片保持显示"报时已暂停"）
5. 再次点击 **开始报时**（app 内或锁屏卡片上的按钮）即可恢复；报时间隔与上次一致
6. 要**彻底关闭**报时：从最近任务列表划掉 TimeKeeper（或系统设置中强行停止），通知与锁屏卡片随即消失
7. 首次启动会依次请求"通知权限"（Android 13+）和"忽略电池优化"弹窗，**两者都建议允许**——忽略电池优化是后台不被系统杀死的关键

> 锁屏控制说明：报时运行期间 app 会以媒体方式（MediaSession）向系统注册，因此锁屏界面显示报时状态与控制卡片，"停止报时"即暂停（可随时恢复）。暂停状态下不播放静音保活音频，以减少耗电。
>
> 后台稳定性提示：部分国产手机（小米/华为/OPPO/vivo 等）有激进的后台管控，除允许"忽略电池优化"外，还需在系统设置中给 TimeKeeper 开启 **自启动** 并将后台管控设为 **无限制/允许后台运行**，否则锁屏后服务可能被杀。
>
> 提示：若设备未安装中文 TTS 语音包（如部分原生系统），请到 系统设置 → 无障碍/语言 → 文字转语音（TTS）中安装中文语音数据。

## 本地构建（可选）

如后续本地装好了 Android Studio，直接用 Android Studio 打开本目录，`Build > Build APK(s)` 即可。