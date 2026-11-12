# 构建与校验工具

需要 Python 3.10+、JDK 17、Gradle 8.7 和 Android SDK（android-34、build-tools 34.0.0）。

脚本从自身位置识别仓库根目录。可通过以下环境变量指定工具和构建位置：

| 变量 | 作用 |
| --- | --- |
| MMBMH_PROJ | 仓库根目录，可选 |
| MMBMH_BUILD | 编译工作目录和工具链根目录 |
| MMBMH_JDK | JDK 17 根目录，可选 |
| MMBMH_KEYSTORE | 用于签名的 keystore 文件，可选 |

未单独指定工具位置时，构建目录包含 jdk17/jdk、android-sdk 和 gradle-dist/gradle-8.7。构建脚本将源码同步到编译工作目录，避免直接在源码目录生成产物。

| 命令 | 用途 |
| --- | --- |
| python tools/doctor.py | 检查工具链和签名证书 |
| python tools/_build_and_test.py | 同步源码、编译并执行单元测试 |
| python tools/_release_guard.py | 核对版本号和安装包文件名 |
| python tools/_pack.py | 构建、整理并签名 debug/release APK |
| python tools/_dexprobe2.py APK路径 | 检查安装包中的功能符号和版本 |
| python tools/_secret_sentinel.py | 扫描 Git 历史和待提交文本中的疑似凭据 |

签名文件不在仓库中。覆盖安装要求新旧安装包使用相同证书，不应使用新生成的密钥替代原签名。凭据扫描失败时不会判定为干净，必须先解决扫描错误。
