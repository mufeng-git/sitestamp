# SiteStamp — 现场水印相机

> 施工现场巡查、安全检查、隐患整改、施工进度记录的轻量级专业拍摄工具。
> Android 原生（Kotlin + Jetpack Compose + CameraX + Room）。

## 已实现功能

- **项目管理**：新建、切换、删除；项目之间默认标记互斥，切换不再复制数据
- **模板管理**：项目内多模板，可新建（自定义字段）、删除、设为默认；拍摄界面一键切换；新建项目自动初始化日常巡查、隐患整改、施工进度、专项检查、通用模板
- **现场字段编辑**：拍摄界面直接修改水印字段，实时预览
- **字段快捷选择**：字段值为胶囊一键选择；内置施工区域、检查事项等预设，模板可自定义选项，也支持输入自定义值
- **拍摄界面**：沉浸式全屏取景（edge-to-edge）、三分构图参考线；快门按压缩放 + 白闪 + 震动 + 保存进度 + 结果提示；顶部切换项目、闪光灯与前后镜头，底部打开模板管理与照片记录
- **水印合成**：底部半透明信息块，按片高比例自适应字号；在原图分辨率上绘制，不先缩放
- **水印样式**：5 种版式可切换（三段式 / 深色卡 / 白卡 / 大时间 / 角标）；底色 6 色预设 + 透明度滑条、主色 5 色预设、文字色自动 / 白 / 深，时间、天气、地址、经纬度可分别开关
- **水印设置面板**：样式、颜色、logo 与显示项改动即时生效并写回当前模板；logo 可从相册导入或恢复内置；字段支持增删改、上下调序与自定义快捷选项
- **天气**：Open-Meteo 免 Key 实时天气（WMO 天气码转中文）；缓存 10 分钟、位移小于 3 km 不重查，取不到时静默留空，不阻塞拍照
- **GPS 定位**：经纬度 + 精度；地址解析（Geocoder，位移超过约 50 米才重新请求，避免配额耗尽）
- **照片保存**：原图与水印图双存系统相册；水印失败自动回退原图，不丢照片
- **EXIF**：写入拍摄时间（`DATETIME_ORIGINAL`）与 GPS 经纬度（度分秒有理数格式）
- **照片记录**：拍摄记录落 Room 表，可查看时间、项目、坐标与字段快照

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Kotlin 2.0.20 |
| UI | Jetpack Compose + Material 3 |
| 相机 | CameraX 1.4.1 |
| 数据库 | Room 2.6.1（KSP） |
| 图片处理 | Bitmap + Canvas |
| 图片保存 | MediaStore（API 29+）/ 公共 Pictures 目录 + MediaScanner（API 26-28） |
| EXIF | AndroidX ExifInterface |
| 构建 | AGP 8.5.2 / Gradle 8.9 / JDK 17 / compileSdk 34 / minSdk 26 |

## 目录结构

```
SiteStamp/
├── app/src/main/java/com/cscec/sitestamp/
│   ├── data/
│   │   ├── Project.kt / Template.kt / TemplateField.kt / PhotoRecord.kt   # Room 实体
│   │   ├── ProjectDao.kt / TemplateDao.kt / TemplateFieldDao.kt / PhotoRecordDao.kt   # DAO
│   │   ├── AppDatabase.kt                 # Room 数据库
│   │   ├── FieldPresets.kt                # 字段快捷选项预设
│   │   └── WatermarkStyle.kt              # 水印样式配置（颜色 / 透明度 / logo / 显示项）
│   ├── core/
│   │   ├── WatermarkRenderer.kt           # 水印合成（5 种版式）
│   │   ├── LocationRepository.kt          # 定位与地址解析
│   │   ├── PhotoRepository.kt             # 原图 / 水印图存盘与相册写入
│   │   └── WeatherRepository.kt           # Open-Meteo 天气（失败静默）
│   └── ui/
│       ├── MainActivity.kt                # 相机宿主、权限、镜头与沉浸式窗口
│       ├── MainViewModel.kt               # 状态与业务
│       ├── CaptureScreen.kt               # 拍摄界面、快门交互与底部面板
│       ├── WatermarkSettingsSheet.kt      # 水印设置面板
│       └── theme/Theme.kt                 # 深色主题
├── .github/workflows/build-apk.yml   # 推送即构建 Debug + Release APK
└── gradle/wrapper/                   # Gradle 8.9 wrapper
```

## 本地构建

需要 JDK 17 + Android SDK 34 + Gradle 8.9：

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 自动构建

推送到 `main` / `develop`，或对这两个分支开 PR，都会触发 `.github/workflows/build-apk.yml`：

- `Build Debug APK` → artifact `SiteStamp-Debug-APK`
- `Build Release APK` → artifact `SiteStamp-Release-APK`（未签名）

## 权限说明

| 权限 | 用途 |
|------|------|
| `CAMERA` | 拍摄 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 水印中的经纬度与地址 |
| `ACCESS_NETWORK_STATE` | Geocoder 网络反查地址 |
| `INTERNET` | 请求 Open-Meteo 天气接口 |
| `WRITE_EXTERNAL_STORAGE`（maxSdkVersion=28） | 仅 Android 9 及以下写入公共相册目录 |

## 版本

- **v1.6**（versionCode 7）：新增 82 个 JVM 单元测试并纳入 CI 的 `testDebugUnitTest`，修复 `yyyy-MM-dd` 格式的 EXIF 拍摄时间被错误写成当前系统时间的问题，配置 release 签名并在推送 `v*` tag 时自动构建签名 APK 发布到 GitHub Release
- **v1.5**（versionCode 6）：水印全可配置（5 种版式 / 底色 / 透明度 / 主色 / 文字色 / logo / 字段）+ 三段式工程水印与天气 + M3 界面交互重做（沉浸式取景、快门反馈、字段快捷选项）
- **v1.2**（versionCode 3）：补完 M1 闭环 + 模板管理 + 照片记录
- v1.1（versionCode 2）：修复拍照存不住、地址不解析、切项目复制数据、EXIF 未写等 7 项缺陷

## 已知限制

- 运行时行为需真机验证；CI 只证明编译通过，不覆盖相机、定位、相册写入等设备相关路径
- Release APK 未配置签名，仅供内部测试
- 地址解析依赖系统 Geocoder，无网络或国内机型可能返回空
