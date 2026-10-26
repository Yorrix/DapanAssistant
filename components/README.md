# 按需组件

扫描模型和离线词典是 App 内直接下载的数据，主 APK 不内置这两份数据。catalogue.json 记录文件大小及校验值，scripts/component-catalogue.py 可重新生成应用内的数据清单。当前两份数据放在 payloads/。

课表默认使用系统 WebView。加载失败时，用户可选择下载并安装独立兼容浏览器组件；主 APK 不捆绑 GeckoView 内核。组件源码在 timetable-component/，构建需要 Android SDK 37.2 与 Mozilla Maven 依赖。安装组件需要系统确认，与主应用通过签名权限交互。

提交包只提供一个主应用 APK。兼容浏览器成品按应用内固定地址下载；若自行更换签名或服务器，需要重新签署两个应用，并更新 TimetableComponentCatalog.kt 的下载地址、大小、SHA-256 与证书摘要。不能用自行签名的主应用搭配开发者签名的组件。

第三方许可证随源码及应用保留。新组件未完成华为实机验收，不能据此声称所有机型均兼容。
