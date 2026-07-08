# 伪装入口模块说明

## 功能概述

伪装入口通过工作资料桌面上的“游戏中心”入口保护隐藏空间。用户正常点击时会看到小米游戏中心网页；在左上角热区三连击后进入隐藏空间。

## 当前实现

### 桌面入口

- 显示名称：游戏中心
- Manifest 入口：`WorkProfileGameCenterAlias`
- 目标 Activity：`DisguiseActivity`
- 当前图标资源：`@drawable/ic_app_icon`

### 启动流程

1. 用户点击工作资料桌面的“游戏中心”。
2. 启动 `DisguiseActivity`。
3. 页面加载 `https://game.xiaomi.com`，并清理网页底部下载引导条。
4. 用户在左上角热区三连击。
5. 通过 `ACTION_OPEN_PRIVACY_SPACE` 打开隐藏空间；失败时回退到本资料内 `MainActivity`。

### 管理策略

- `configureCrossProfileEntry()` 会启用工作资料内的 `WorkProfileGameCenterAlias`。
- 主空间中的隐藏空间入口会被禁用，避免暴露真实入口。
- 隐藏空间应用图标的整理不再在进入首页时自动执行，改为设置中的“整理桌面残留图标”。

## 核心组件

- `DisguiseActivity`：显示伪装网页、处理三连击、跳转隐藏空间。
- `TripleTapHotZoneLayout`：左上角热区三连击识别。
- `WorkProfileManager`：配置跨资料入口、alias 显隐和工作资料策略。
