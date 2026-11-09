# SiteStamp — 现场水印相机

> 施工现场巡查、安全检查、隐患整改、施工进度记录的轻量级专业拍摄工具。
> Android 原生（Kotlin + Jetpack Compose + CameraX + Room）。

## 已实现功能

- **项目管理**：新建、切换、删除；项目之间默认标记互斥，切换不再复制数据
- **模板管理**：项目内多模板，可新建（自定义字段）、删除、设为默认；拍摄界面一键切换
- **现场字段编辑**：拍摄界面直接修改水印字段，实时预览
- **水印合成**：底部半透明信息块，按片高比例自适应字号；在原图分辨率上绘制，不先缩放
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
│   ├── data/          # Room 实体与 DAO（Project / Template / TemplateField / PhotoRecord）
│   ├── core/          # WatermarkRenderer 水印合成 / LocationRepository 定位与地址 / PhotoRepository 存盘
│   └── ui/            # MainActivity（Compose UI）/ MainViewModel（状态与业务）
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
| `WRITE_EXTERNAL_STORAGE`（maxSdkVersion=28） | 仅 Android 9 及以下写入公共相册目录 |

## 版本

- **v1.2**（versionCode 3）：补完 M1 闭环 + 模板管理 + 照片记录
- v1.1（versionCode 2）：修复拍照存不住、地址不解析、切项目复制数据、EXIF 未写等 7 项缺陷

## 已知限制

- 运行时行为需真机验证；CI 只证明编译通过，不覆盖相机、定位、相册写入等设备相关路径
- Release APK 未配置签名，仅供内部测试
- 地址解析依赖系统 Geocoder，无网络或国内机型可能返回空
