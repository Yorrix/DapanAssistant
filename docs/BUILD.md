# 构建与运行

## Android

本快照使用 Gradle Wrapper 9.6.0、Android Gradle Plugin 9.4.1、compileSdk/targetSdk 37。最低 Android 8.0（API 26）；提供 arm64-v8a，模拟器可切换 x86_64。

验证环境是 Windows、Android Studio 自带 JBR 25.0.3、Android SDK 37、Python 3.14.7。不要照搬原开发者的磁盘路径。

1. 在 Android Studio 中打开仓库根目录，安装所需 SDK，按提示同步依赖。首次同步需要网络。
2. 将 `JAVA_HOME` 指向 Android Studio 的 JDK，将 `ANDROID_HOME` 指向自己的 Android SDK。也可通过脚本参数传入。
3. 从根目录执行：

```powershell
./build-local.ps1 -Python python -RunTests
# 模拟器
./build-local.ps1 -Python python -Abi x86_64
```

脚本校验/生成组件摘要，并生成 debug APK 到 `release/`。临时构建目录默认在系统临时目录，可用 `-BuildRoot` 指定全英文路径；`-Offline` 只适合依赖已完整缓存时使用。

也可直接用 Gradle：

```powershell
python scripts/component-catalogue.py
./gradlew.bat :app:assembleRelease -PdapanAbi=arm64-v8a
./gradlew.bat :app:testDebugUnitTest
```

Release 构建默认输出未签名 APK，需用自己的签名密钥签名。参赛包里的成品 APK 沿用现有开发签名，签名私钥不随源码分发；自己构建的新签名不能覆盖此前不同签名的安装。

`vendor/sdk-0.7.0/maven` 是本地扫描 SDK 依赖。扫描 UI 和处理引擎由 `settings.gradle.kts` 指向的两个本地源码模块编译。不要删除 vendor、两个数据 payload 或模型原始文件。

## 后端本地运行

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r backend/requirements.lock.txt
$env:DAPAN_DATA = Join-Path $PWD '.local-data'
$env:DAPAN_MODE = 'demo'
./.venv/Scripts/python.exe -m uvicorn app:app --app-dir backend --host 127.0.0.1 --port 8765
```

配置模板见 `backend/.env.example`。应用不会自动加载这个文件；在进程环境中配置，生产服务由 systemd 的 `EnvironmentFile` 注入。没有 SMTP 时不能正常发送注册验证码；没有服务商密钥时不能使用相应在线 AI 服务，不会假装成功。

隔离测试：

```powershell
./.venv/Scripts/python.exe -m pytest backend -q
```

测试会使用临时数据库并阻止外部网络和真实邮件。生产数据库、会话密钥、上传图片、账户信息与账本不在本包内。

## 服务器部署

部署前修改 `backend/deploy/` 示例中的主机、证书与目录；创建专用服务用户。API 只监听本机 `127.0.0.1:8765`，由 HTTPS 反向代理对外服务。SSE 关闭代理缓冲，短语音 WebSocket 配置 Upgrade。

- 主服务：`backend/dapan-api.service`；环境配置置于服务器私有文件，限制访问权限。
- 官网通知：`backend/dapan-notices.service`、`.timer`；通知摘要密钥与限额在独立环境文件中配置。
- 数据组件：发布 `components/catalogue.json` 所指向的两个数据文件，保持地址与哈希一致。默认地址固定于 `ComponentCatalog.kt` 的生成脚本；更换服务器需同步更新并重新打包 App。
- 当前客户端默认服务器及证书固定配置见 `CommunityClient.kt` 及网络安全配置等实际代码。更换服务器时搜索原地址并更新相关配置，不能仅替换 Nginx 地址。
- `backend/deploy/deploy_shared_ai.py` 等历史升级脚本需要显式设置 `DAPAN_SSH_HOST`、`DAPAN_SSH_KEY`。这类脚本只适用于符合其检查条件的服务器，不是通用的一键初装器。

## 数据再生成与 AI 评估

```powershell
python scripts/build-vocabulary.py
python scripts/component-catalogue.py
python scripts/assistant-catalogue.py
```

在线模型评估是可选付费步骤，不属于默认构建/测试：

```powershell
# 在本机私密环境设置自己的 DEEPSEEK_API_KEY；不要写入源码或命令历史。
python scripts/ai-live-cases.py
python scripts/ai-live-eval.py --round manual --budget 1
```

评估脚本不再读取原开发服务器的密钥。预算按本地累计账本控制，不应删除账本来绕过预算。不要将真实同学的隐私资料作为测试数据。

