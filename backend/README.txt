大盘助手校园服务 · 0.3.1

当前状态
2026-10-14 已部署到阿里云服务器：https://39.105.161.86。Android 0.3.1 默认连接此地址。
已验证可信 IP HTTPS、自动续期、通知每2小时采集、每日同机备份及隔离恢复。无需额外购买域名。
真实发信邮箱未配置，用户选择稍后配置；注册验证码暂不可用。官方通知和空集市浏览已可用。
演示账号和四件演示物品仅存在本机 demo-data，没有上传生产服务器。
不要把 demo-data、测试目录或演示账号复制到生产服务器。

接口与数据
app.py：FastAPI + SQLite；默认数据目录 backend/data，默认模式 production。
sources.json：9 个官方栏目，含莱斯特 4 个栏目。
crawl_notices.py：低频读取标题、发布日期、正文摘录和原链接；不下载附件。首批40条不是全部历史通知。
正文主要是图片/附件时明确提示读原文，不编造梗概。部分来源失败保留已有数据；结果包含逐源状态和错误。
secret.key 与 community.db、images 必须一起备份；密钥丢失会使现有会话及验证码失效。

本地运行（PowerShell；从项目根目录执行）
backend/.venv/Scripts/python.exe -m pip install -r backend/requirements.lock.txt
backend/.venv/Scripts/python.exe backend/crawl_notices.py --limit 6
backend/.venv/Scripts/python.exe backend/seed_demo.py
$env:DAPAN_DATA = (Resolve-Path backend/demo-data).Path
$env:DAPAN_MODE = 'demo'
backend/.venv/Scripts/python.exe -m uvicorn app:app --app-dir backend --host 127.0.0.1 --port 8765 --no-access-log

模拟器连接
adb -s emulator-5554 reverse tcp:8765 tcp:8765
Debug 包可以通过 localPreview intent 使用 http://127.0.0.1:8765。
生产客户端要求 HTTPS，Debug 明文例外仅有指定本地测试地址。
seed_demo.py 中的演示账号只用于本地 UI 测试，不向该地址发邮件。

测试
backend/.venv/Scripts/python.exe -m pytest backend/test_backend.py -q
验证码测试使用注入的本地邮件接收函数，不向真实邮箱发信。
缺少 SMTP 时 API 返回未配置提示，不返回“已发送”假成功。

部署说明（已执行；保留供后续维护）
1. 服务器创建专用 dapan 用户、/opt/dapan/backend、/opt/dapan/data、/opt/dapan/venv。
2. 上传源码和 requirements.lock.txt，在服务器虚拟环境安装依赖；不要上传 Windows 虚拟环境。
3. .env.example 私下填入 /etc/dapan.env；权限限制为管理员读取。SMTP 使用 SSL，默认为465端口。
4. ADMIN_TOKEN 使用随机强密钥，不能放进 App。仅通过 HTTPS 和受限管理端调用管理接口。
5. dapan-api.service 使用一个进程，监听127.0.0.1:8765。对外通过 HTTPS 反向代理；保留真实客户端 IP，仅信任本机代理。
6. 代理请求大小不超过3MiB，设置连接与请求频率限制；API端口不要直接公网开放。
7. 首次采集通知后安装 dapan-notices.service / dapan-notices.timer，每2小时更新。检查逐源采集状态。
8. 已配置数据库一致性备份（SQLite backup API）、图片及 secret.key 备份并完成隔离恢复；备份每日北京时间03:15附近执行，保留最近7份。备份仍在同一台服务器，不包含异地灾备。
9. 待配置发信邮箱后，再向自有大工邮箱验证投递、注册、登录、找回密码和发布流程。不要通过关闭邮箱验证来绕过。
10. 新版 App 已默认使用 HTTPS 地址；已有自定义地址的安装可在设置中修改。

运行维护
systemctl status dapan-api nginx
systemctl list-timers 'dapan-*'
systemctl restart dapan-api
systemctl start dapan-notices.service
systemctl start dapan-backup.service
python3 /opt/dapan/backend/deploy/verify_backup.py
API仅监听127.0.0.1:8765，管理接口在公网Nginx侧屏蔽；通过SSH管理，不把管理密钥写入App。
证书有效期较短，dapan-cert-renew.timer 每日检查两次，续期后重载Nginx；已完成dry-run。
IP变更时需更新证书、代理配置和App服务地址。日常关注systemctl失败状态、采集status和剩余磁盘空间。

权限与防滥用
仅精确接受 mail.dlut.edu.cn 和 dlut.edu.cn，不接受相似后缀或任意子域名；邮箱验证不代表在校学籍认证。
验证码10分钟有效，最多5次尝试，按邮箱和IP限频。登录按邮箱/IP限频；密码使用加盐 scrypt，服务端不存明文密码。
会话有效期14天，退出或重设密码后撤销。发布、修改和图片引用检查账号归属。
每天最多5条、最多20条有效信息，同名24小时防重复，30天过期。
只展示卖家主动填写的联系方式，不公开注册邮箱；售出、下架后隐藏联系方式。
图片限制大小与分辨率，重编码去除 EXIF。举报按账号和物品去重，管理员核查后下架，不按举报数量自动下架。
目前管理能力为接口，没有管理后台 UI；上线后仍需要人工处理举报与封禁、存储清理和运维。

管理 API
GET /admin/reports，POST /admin/listings/{id}/hide，请求头 X-Admin-Token。
不要在日志、截图或聊天中暴露真实密钥、验证码或用户私人资料。


2026-10-15 通知扩源与摘要升级（0.10）
登记24个栏目，覆盖校区官方目录的五个学院及教学、学工、团委、就业、图书馆、总务。空栏目保留状态。来源证据和本次验证见 source-verification.json。
公开 notices.json 只含通知元数据、AI短标题及摘要，不含抓取正文。notice-documents.json 仅在服务器数据目录保留用于摘要与重试；notice-summaries.json按正文哈希缓存。
/etc/dapan-notices.env 为 root 600 的独立环境文件，包含 DEEPSEEK_API_KEY、NOTICE_MODEL=deepseek-flash、NOTICE_AI_DAILY_LIMIT=160；不把管理员API密钥放入App。
配置助手通过SSH标准输入验证并写入密钥，成功后自动启动dapan-notices.service。原有每2小时采集timer继续使用。
未配置、请求失败或触达额度时保留 pending，客户端明确提示待生成；不以摘录冒充AI摘要。summaryStatus可检查ready/pending/error。
python crawl_notices.py --output /opt/dapan/data/notices.json --summary-only 可只处理未完成摘要；不要与systemd采集任务并行执行。
