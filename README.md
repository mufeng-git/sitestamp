# SiteStamp — 现场水印相机

> 施工现场巡查、安全检查、隐患整改、施工进度记录的轻量级专业拍摄工具。
> 第一版 MVP：Android 原生（Kotlin + Jetpack Compose + CameraX + Room）。

## 功能概览

- **项目管理**：新建、编辑、删除、设置默认项目
- **模板管理**：项目 → 模板两级切换，模板可复制、排序、删除
- **现场字段编辑**：拍摄界面直接修改水印字段，实时预览
- **水印合成**：底部半透明信息块，自适应分辨率字号
- **GPS 定位**：经纬度 + 精度 + 地址（网络可用时解析，离线保留坐标）
- **照片保存**：原图 + 水印图双存，写入 EXIF 时间及 GPS

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 相机 | CameraX 1.4.1 |
| 数据库 | Room 2.6.1 |
| 图片处理 | Bitmap + Canvas |
| 图片保存 | MediaStore |
| EXIF | AndroidX ExifInterface |

## 目录结构

```
SiteStamp/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cscec/sitestamp/
│       │   ├── data/          # Room 实体与 DAO
│       │   │   ├── Project.kt
│       │   │   ├── Template.kt
│       │   │   ├── TemplateField.kt
│       │   │   ├── PhotoRecord.kt
│       │   │   ├── *Dao.kt
│       │   │   └── AppDatabase.kt
│       │   ├── core/          # 核心逻辑
│       │   │   ├── WatermarkRenderer.kt   # 水印合成
│       │   │   ├── LocationRepository.kt  # 定位与地址
│       │   │   └── PhotoRepository.kt     # 照片保存
│       │   └── ui/           # Compose UI
│       │       ├── MainActivity.kt
│       │       └── MainViewModel.kt
│       └── res/              # 资源文件
├── .github/workflows/
│   └── build-apk.yml         # GitHub Actions 自动打包
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/
    └── gradle-wrapper.properties
```

## 快速开始

### 本地构建

需要 JDK 17 + Android SDK 34 + Gradle 8.9：

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions 自动构建

推送代码到 `main` 分支后自动触发：

```bash
git init
git add -A
git commit -m "init: SiteStamp M1 scaffold"
# 推送到 GitHub 仓库后，Actions 自动构建并上传 APK artifact
```

在 GitHub Actions 页面可下载 `SiteStamp-Debug-APK`。

## M1 验收标准

1. ✅ 能够打开相机
2. ✅ 能够创建两套模板
3. ✅ 能够在拍摄界面直接切换模板
4. ✅ 能够保存带水印的照片

## 下一阶段

- M2：项目、模板、字段管理完整 CRUD
- M3：GPS 精度提示、地址解析降级、照片记录筛选
- M4：测试、修复、APK 打包发布
