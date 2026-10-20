# DeepSeek自然语言查单

发送“帮我看看商铺100的SF10001订单发货了吗？”，系统先从消息中识别查询参数，
再用当前登录买家的身份查询真实订单，返回数据库中的状态和金额。

## API Key填写位置

项目根目录已准备好 `.local/deepseek-secrets.yml`：

`D:\project\supportflow-rebuild\.local\deepseek-secrets.yml`

填入自己的Key，并开启功能：

```yaml
supportflow:
  ai:
    deepseek:
      enabled: true
      api-key: "这里替换成你的DeepSeek API Key"
```

此文件所在.local目录已被Git忽略，也不会打进jar。
公开模板config/deepseek-secrets.example.yml只有空占位符，可提交Git。
application-local.yml自动导入该本地文件；IDEA工作目录应为项目根目录，
程序参数保持 `--spring.profiles.active=local`，修改后重启应用。
Key不要贴到聊天、源码、日志或HTTP请求文件中。

未启用时，普通消息保持回显，固定查单和列表继续工作。
启用却未填Key时配置校验会阻止启动；Key无效、额度不足或供应商故障会在请求时返回统一503。
默认模型和超时在公开application.yml中配置；真实Key只需填写本地文件。

## 一次消息如何流动

以已认证买家200发送上述消息为例：

1. Spring Security认证账号并校验CSRF，ChatController取得可信身份和message。
2. ChatService先检查固定指令；自然语言消息交给OrderIntentRecognizer接口。
3. DeepSeekOrderIntentRecognizer通过HTTPS访问DeepSeek，发送系统规则和这条消息。
4. 模型应输出JSON，例如 `{"intent":"ORDER_LOOKUP","shopId":100,"orderNo":"SF10001"}`。
5. 适配器校验JSON结构、类型、范围和参数确实出现在原文中，转换成OrderIntent。
6. ChatService携带原来的买家身份，调用OrderQueryService.getOwnedOrder。
7. 订单服务检查ORDER_READ权限，仓储通过JDBC查询属于买家200的订单。
8. ChatService用真实订单生成固定格式文字；ChatController返回ChatResponse的JSON。

第3步通过网络访问外部模型，第7步通过JDBC访问数据库，其余主要是同一Java进程内的方法调用。
模型不会获得服务端认证身份、权限、数据库凭据或数据库订单结果。
用户自己写在message中的内容会发送给DeepSeek，使用时应避免粘贴密码等敏感信息。

## 类的职责

| 类或接口 | 调用方 | 输入 | 输出与作用 |
| --- | --- | --- | --- |
| OrderIntentRecognizer接口 | ChatService | 消息 | OrderIntent；约定意图识别能力 |
| DeepSeekOrderIntentRecognizer实现类 | ChatService | 消息、HTTP客户端和配置 | 调用官方接口，严格解析模型结果 |
| OrderIntent、OrderIntentType | 适配器创建，ChatService读取 | 意图、商铺ID、订单号 | 承载识别结果，没有身份或权限字段 |
| DeepSeekProperties | Spring配置绑定 | 开关、Key、模型名、超时 | 校验配置，toString隐藏Key |
| DeepSeekConfiguration | Spring启动流程 | 配置、ObjectMapper | 启用时创建HTTP适配器，关闭时返回OTHER识别器 |
| ModelUnavailableException | 适配器抛出，统一异常处理器捕获 | 不含供应商原始内容 | 映射503及枚举错误码MODEL_UNAVAILABLE |

ChatService负责组织业务流程；HTTP协议和供应商响应解析集中在适配器中。
订单查询复用现有服务和仓储，没有新增数据库表，也没有让模型生成SQL。

## 当前行为边界

- 支持单笔查单的自然语言表达，商铺ID使用原文阿拉伯数字，订单号保留原样。
- 缺商铺、缺订单号或两者都缺时，提示补充，不猜测字段或查询数据库。
- 补充时需要重新发送完整消息；本阶段没有跨消息记忆。
- “查订单”和“我的订单”开头的消息仍保留给原指令解析器，格式错误仍返回400。自然语言建议以“帮我…”开头。
- 模型识别为OTHER时回显原消息；本阶段没有开放式AI闲聊，也不让AI生成订单事实。
- 身份来自认证结果，消息或请求头中的伪造身份不能改变查询范围。
- 模型输出额外身份字段、无效JSON、截断结果或不在原文中的查询参数时失败，不执行查询。
- 连接和完整响应均有超时，响应体大小有上限，不自动重试。
- 网络错误、模型认证失败、限流及无效响应统一返回503和MODEL_UNAVAILABLE，不暴露供应商响应或Key。

## 自动验证与手动体验

自动验证：在项目根目录运行 `mvn -o verify`。
模型协议测试模拟HTTP响应；安全集成测试模拟意图识别并查询独立Testcontainers MySQL。
自动测试不消耗DeepSeek额度，不加载本地私密配置，也不连接日常业务库。

2026-10-20实际运行 `mvn -o verify`：84个单元测试、80个集成测试，
共164个测试，失败、错误、跳过均为0，BUILD SUCCESS。
相较之前114个测试，本次新增50个测试。

填写Key、启用并重启后，执行根目录chat.http中的自然语言示例。
先取得CSRF并保持cookie，再发送查询。SF10001只在测试库中保证存在；
实际本地库没有这笔订单或不属于登录买家时，返回404是正常结果。
真实DeepSeek连通性需要有效Key，模拟测试通过不代表真实Key已通过认证。

## 官方接口依据

接口为 `https://api.deepseek.com/chat/completions`，使用Bearer认证。
默认模型按当前官方文档配置为deepseek-flash，关闭thinking以进行简短参数提取。
采用json_object输出格式，同时在系统提示中明确要求JSON。

- [DeepSeek首次API调用](https://api-docs.deepseek.com/)
- [DeepSeek Chat Completions接口](https://api-docs.deepseek.com/api/create-chat-completion)
