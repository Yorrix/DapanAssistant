大盘助手校园服务 · 0.61.4

FastAPI / SQLite 后端。包含大工邮箱注册登录、二手信息、官网通知采集与摘要、公用 AI 流式对话和短语音额度服务。
账号与二手信息共用，支持 mail.dlut.edu.cn / dlut.edu.cn。公用文字与短语音共享每天1元、每周3元、全站每月30元的计量上限。

本地运行、配置模板和部署步骤见 ../docs/BUILD.md。
完整历史介绍保留于 ../docs/history/references/backend-README-original.txt；里面的旧版本状态不是当前部署状态。

关键文件
app.py：主服务与账号/二手/通知接口。
shared_ai.py：SSE转发、认证与额度预留/结算。
shared_speech.py：短语音WebSocket代理，不代理长时录音。
crawl_notices.py / sources.json：官网通知来源、抓取、摘要缓存。
conftest.py / test_*.py：隔离测试，不使用生产数据库，不发真实邮件。
.env.example：只有字段名与非敏感默认值，无密钥。
deploy/：服务器运维模板、备份/证书/配置和历史升级脚本。

公用AI请求经过开发者部署的服务器。账本不保存对话正文和音频，服务端模型密钥不下发客户端。上线运营需要维护密钥、额度、通知来源可用性和备份。
