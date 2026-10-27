# 极简记账

一个本地保存的 Android 支出记录应用。支持人民币、USDT、USDC，日期和可选时间及类别；不同币种不进行换算。金额按十进制文本保存。

使用 Android Studio 打开项目，安装 Android SDK 35 和 JDK 17，等待 Gradle 同步后运行。最低支持 Android 8.0（API 26）。数据仅保存在设备上，卸载应用会删除数据。

类别支持保存后选择。日期使用年月日循环滚轮，并按月份和闰年限制有效天数。

长按账单可编辑金额、币种、日期、可选时间、类别和说明，保存后更新原账单，取消则放弃本次编辑。列表不显示来源；原始来源仍保存于账单中并参与 CSV 导入导出。

应用图标采用深青色渐变背景、米白记事本和薄荷绿笔，支持 Android 自适应形状和 Android 13+ 主题图标。图形改编自 [Lucide notebook-pen](https://lucide.dev/icons/notebook-pen)，ISC 许可随应用打包在 [lucide-LICENSE.txt](./app/src/main/assets/lucide-LICENSE.txt)。

## CSV 导入和导出

首页右上角“导入”从系统文件选择器选取 CSV；先显示总笔数、日期范围、各币种金额以及将新增/跳过的笔数，确认后才写入。取消预览不会改变账单。所有记录通过校验后统一事务导入，有错误时不会导入部分记录；导入的类别也会保存供以后选择。

“导出”通过系统文件选择器保存全部账单为 UTF-8 BOM CSV，包括说明、来源和可选时间、类别。不需要申请存储权限。导出的文件可以直接再次导入；空账本不能导出。

CSV 表头如下，日期、金额、币种为必需列，其余列可以省略，列顺序可调整：

```csv
日期,时间,金额,币种,类别,说明,来源
2026-10-26,,18.33,人民币,日用品,沐浴露,支付宝
2026-10-26,12:30,2.5,USDT,,午餐,
```

- 英文表头也支持：`date,time,amount,currency,category,description,source`。
- 日期为 `YYYY-MM-DD`，时间为 `HH:mm` 或 `HH:mm:ss`，金额为大于零的十进制数。
- 人民币可写 `人民币`、`r`、`RMB`、`CNY`、`¥` 或 `￥`；另支持 `USDT`、`USDC`，各币种不换算。
- 正确处理逗号、双引号、换行和空值。文件须为 UTF-8（可带 BOM），最大 10 MB、50000 笔，单字段最多 2000 字符。
- 导入按日期、时间、金额、币种、类别、说明、来源及相同记录的笔数匹配。文件内的多笔相同支付会保留；重复导入同一文件不会增加已有笔数。不同描述或来源的记录不会自动合并。

## 手写账单清洗

[账单.csv](./账单.csv) 由 [账单.txt](./账单.txt) 清洗生成；规则、统计和待核对的重复项见 [账单清洗说明](./账单清洗说明.md)。运行 `python scripts/clean_bills.py` 可重新生成。清洗保留所有 153 笔，合计 5860.10 人民币；没有根据同日同额推断删除支出。

## 验证

`./gradlew build` 运行构建、CSV 单元测试和 lint。连接 Android 设备或启动官方模拟器后，运行 `./gradlew connectedDebugAndroidTest` 验证旧数据库升级、导入事务、重复支付和类别保存。

## Release 签名构建

Release 使用独立签名密钥。将 [release-signing.properties.example](./release-signing.properties.example) 复制为 `release-signing.properties` 并填写密钥位置、别名和密码，再执行 `./gradlew assembleRelease`；已签名安装包输出为 `app/build/outputs/apk/release/app-release.apk`。缺少有效签名配置时 release 构建会失败。

本机生成的密钥保存在 `.signing/release.jks`，凭据保存在 `release-signing.properties`，均已排除在 Git 之外。请将这两项一同安全备份；以后发布更新需使用相同签名密钥。Debug 和 release 的签名不同，不能直接覆盖安装。
