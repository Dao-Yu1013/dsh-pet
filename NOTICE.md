# 第三方组件与素材说明

MIT 只覆盖本仓库里作者自己写的那部分代码。下面这些各有各的许可，用之前请分别确认。

## Live2D

- `app/src/main/java/com/live2d/` 下的 Cubism Java 框架属于 Live2D Cubism Components，按 Live2D Open Software License 使用。
- **Cubism Core 不包含在本仓库里。** 它是 Live2D 的闭源组件，只能按 Live2D Proprietary Software License 使用，需要自己从官网下载。
- 如果年营收超过 1000 万日元，属于 Live2D 定义下的商业用户，必须另外取得 Cubism SDK Release License。
- 许可全文见 `app/src/main/assets/live2d/LICENSE.md`，或 Live2D 官网的 EULA 页面。

## 角色形象素材

`app/src/main/assets/webp/` 里的 107 张逐帧图，以及 `res/drawable/ic_launcher.png` 图标，由作者保留权利，**不在 MIT 范围内**。转载、二次分发或商用之前请先联系作者。

## 前端开源库

`app/src/main/assets/licenses/` 里放了每个库的许可原文：

- three.js（MIT）
- 3d-force-graph 1.80.0（MIT，打包的是压缩后的浏览器版本）
- mnemoverse-memory-viz（MIT，只借鉴了点云分布和配色）
- threejs-galaxy-shader（MIT，借鉴了旋臂布点）
- cosmic-nebula（MIT，借鉴了粒子预算和配色）
- starfield-animation（MIT，借鉴了星野运动和开场）
- dsh-video-skin（MIT，使用了 `ui/videos/dsh-video-skin-boot.mp4`）
- three-nebula（MIT）

`THIRD-PARTY-NOTICES.txt` 里有逐项的来源和改编说明。

## 构建工具

`app/libs/` 里带的 jar 不是本项目的代码：

- `r8-8.0.34.jar`：R8，Apache License 2.0。
- `ecj-3.37.0.jar`：Eclipse 编译器，Eclipse Public License。
- `android-4.1.1.4.jar`：Android API 16 编译桩，Apache License 2.0，只用于给手机端插件编译提供类型签名，不会打进插件。

对应的许可和 notice 文件放在同一目录。

## 没有放进仓库的东西

- 签名 keystore 和密码。
- Live2D Cubism Core 的 jar 和 so。
- 打包出来的 APK、R8 mapping。
- 任何 API Key、聊天记录、记忆数据。

## 外部程序

QQ 接入依赖 NapCat/OneBot，远端智能体依赖 AstrBot。它们都是独立项目，有自己的许可，本项目只是通过网络协议连接，不打包、不分发。
</content>
