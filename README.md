# Privacy Space - Android 隐私空间管理工具

基于 Android Work Profile 的个人隐私空间管理应用。

## 项目信息

- **包名**: com.system.launcher.tools
- **最低 SDK**: Android 8.0 (API 26)
- **目标 SDK**: Android 14 (API 34)
- **语言**: Kotlin
- **架构**: MVVM + Repository

## 技术栈

- **依赖注入**: Hilt
- **导航**: Navigation Component
- **UI**: ViewBinding
- **生命周期**: Lifecycle (ViewModel + LiveData)
- **异步**: Coroutines

## 项目结构

```
app/src/main/java/com/system/launcher/tools/
├── PrivacySpaceApp.kt          # Application 类
├── MainActivity.kt              # 主 Activity
├── ui/
│   ├── disguise/               # 伪装界面
│   │   ├── DisguiseFragment.kt
│   │   └── DisguiseViewModel.kt
│   ├── home/                   # 隐私空间主界面
│   │   ├── HomeFragment.kt
│   │   └── HomeViewModel.kt
│   ├── apps/                   # 应用管理
│   │   ├── AppsFragment.kt
│   │   └── AppsViewModel.kt
│   ├── files/                  # 文件空间
│   │   ├── FilesFragment.kt
│   │   └── FilesViewModel.kt
│   └── settings/               # 设置
│       ├── SettingsFragment.kt
│       └── SettingsViewModel.kt
├── work/                       # Work Profile 相关逻辑
│   └── WorkProfileManager.kt
├── data/                       # Repository 层
│   └── repository/
│       └── WorkProfileRepository.kt
└── di/                         # 依赖注入模块
    └── AppModule.kt
```

## 构建项目

确保已安装：
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34

构建命令：
```bash
./gradlew assembleDebug
```

## 功能说明

这是项目框架的初始版本，包含：
- ✅ 完整的 MVVM 架构
- ✅ Hilt 依赖注入配置
- ✅ Navigation 导航框架
- ✅ 5 个页面的基础框架（空白占位）
- ✅ Work Profile 管理器基础类

## 下一步开发

1. 实现 Work Profile 创建和管理逻辑
2. 完善各个页面的 UI 和功能
3. 实现应用安装和管理
4. 实现文件空间管理
5. 添加安全验证机制

## 许可

个人项目
